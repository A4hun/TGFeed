package dev.tgfeed.core.feed

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import dev.tgfeed.core.td.FeedPage
import dev.tgfeed.core.td.FeedRequest
import dev.tgfeed.core.td.FloodCircuitBreaker
import dev.tgfeed.core.td.MockTdGateway
import dev.tgfeed.core.td.SearchRanker
import dev.tgfeed.core.td.SearchTextKit
import dev.tgfeed.core.ocr.CoverOcrApplyState
import dev.tgfeed.core.ocr.CoverOcrBackfillState
import dev.tgfeed.core.ocr.OcrEngine
import dev.tgfeed.core.ocr.OcrPolicy
import dev.tgfeed.core.ocr.decodeCoverOcrApplyState
import dev.tgfeed.core.ocr.decodeCoverOcrBackfillState
import dev.tgfeed.core.ocr.encodeCoverOcrApplyState
import dev.tgfeed.core.ocr.encodeCoverOcrBackfillState
import dev.tgfeed.core.ocr.encodeCoverOcrPilotReport
import dev.tgfeed.core.ocr.formatOcrBuckets
import dev.tgfeed.core.ocr.ocrLenBucket
import dev.tgfeed.core.ocr.parseOcrBuckets
import dev.tgfeed.core.td.SystemStormGate
import dev.tgfeed.core.td.ColdStartQuietGate
import dev.tgfeed.core.td.TdGateway
import dev.tgfeed.core.td.TdLibGateway
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 本地深度搜索索引（SQLite FTS5 磁盘索引版）：解决 TG 服务端搜索对老内容索引很浅的问题。
 *
 * ================= 架构（第六轮重构：内存倒排表 → SQLite FTS5 磁盘索引） =================
 * 前五轮在"内存倒排表"上打补丁（压缩 postings/落盘 bin/看门狗/单飞/重建节流），
 * 512MB 堆平板上依然 OOM/降级/等窗口/重建循环——病根是"索引必须全量进内存"。
 * 本轮推倒重来：**索引本体完全交给 SQLite 磁盘页管理，进程内存零常驻**。
 *
 * 存储（`storeDir/search.db`，android.database.sqlite.SQLiteDatabase 直用，无 Room）：
 *  - `items` 表：条目全字段（key 主键 + chatId/messageId/albumId/mediaType/coverFileId/
 *    caption/channelTitle/fileId/fileUniqueId/mimeType/durationSec/sizeBytes/width/height/
 *    dateEpochSec）。playUri/coverBytes 不落库——播放走 ensurePlayable 链现取，
 *    封面走 loadCoverBytesByMessage 消息反解（既有链路）。保留 rowid：fts 行以 rowid 回表。
 *  - `fts` 虚表：`USING fts5(tokens, content='', tokenize='unicode61')` contentless 模式；
 *    tokens = fold(caption+" "+channelTitle) 截断 [MAX_INDEX_TEXT_LEN] 后经
 *    [SearchTextKit.tokenize](forIndex=true) 去重后的空格连接串（CJK 单字+重叠 bigram +
 *    拉丁整词，与查询同口径）。contentless = 不存原文副本，只占词位字典+doclist。
 *  - `meta` 表：chatId 主键 + 爬取游标 cursor/完成态 exhausted + JSON 迁移完成标记 imported
 *    + 媒体清扫相位 mediaPass/相位内游标 sweepCursor（v4 起页级落盘，中断从断点续扫）
 *    + 浅爬加深标记 needDeepen + 头部刷新节流时间戳 lastHeadRefreshTs（v4 起）。
 *
 * 一次性迁移：首跑发现旧 per-channel JSON（`<chatId>.json`）即后台 IO 流式导入——
 * 每频道一批事务（解析 ≤[TAIL_MAX_ITEMS] 条瞬时占用），进度外露 [migrationProgress]；
 * **导入期间搜索可用**（WAL 读写不互斥，库里有多少搜多少）；每频道提交成功才删对应 JSON，
 * 全部完成后清掉残余 JSON 与旧 inverted.bin 死文件。崩溃可续导（meta.imported 判重 +
 * items INSERT OR IGNORE 幂等）。JSON 里的爬取断点（cursor/exhausted）同步迁入 meta，
 * 迁移完爬取从断点无缝续爬。
 *
 * 爬取写路径：每页一个事务——items/fts INSERT OR IGNORE（key 判重幂等，与旧
 * byKey.putIfAbsent 同口径，已存在条目不更新）+ meta 游标推进。每页即时落库，
 * 崩溃最多丢在飞的一页；不再有"全量 JSON 重写"的 O(n²) 落盘。
 * 爬取逻辑（fetchPageWithRetry/日历跳爬/头部刷新/尾部续爬/断点）原样保留，只换持久化目标。
 *
 * 查询 [search]：
 *  - 精确：查询 fold+tokenize(forIndex=false) 后 `MATCH '"t1" "t2" ...'`（词位短语 AND），
 *    回 items 表取全字段，再做段级连续 contains 复核（bigram 共现≠连续，与旧物化复核同口径）；
 *  - 模糊：词位 OR（含 [SearchTextKit.synonymVariants] 同义词组扩展）+ `bm25(fts)` 相关度排序；
 *  - 召回硬顶 [SEARCH_HARD_CAP]；相册展开双保险（"一条消息多种媒体都要能被搜到"）：
 *    albumId≠0 按 (chatId, albumId) 精确带出同组全部媒体；albumId=0（迁移存量，老 JSON
 *    没这字段）按 messageId ±[ALBUM_WINDOW_RADIUS] 邻近窗口近似带出（TG 相册成员
 *    messageId 天然连续）。空 caption 成员补同组/窗口内非空 caption，总量封顶 [EXPAND_HARD_CAP]；
 *  - 全磁盘操作，目标是 100 万条规模 <500ms；内存占用与索引规模无关。
 *
 * FTS5 可用性兜底：首跑 `PRAGMA compile_options` 探测（查不到再试建虚表实锤）；
 * 不可用（极少数厂商裁剪）则降级为 `items.tokens` 普通列 + `LIKE '% 词位 %'` 匹配
 * （同一 token 化口径，磁盘常驻同样不破内存）。两条路径同一 [search] 接口。
 *
 * 已删除的旧机制（内存倒排表全套）：SlimChannel/ChannelIndex/loadAll/每频道 JSON 落盘/
 * 物化 LRU/InvertedSnapshot/PostingWriter 压缩 postings/searchRefs/materialize/
 * inverted.bin 落盘/指纹新鲜度/后台重建节流窗口/堆门槛双闸/看门狗降级/建表单飞/
 * crawlSuppressed 建表爬取互斥/高频词位熔断。
 *
 * ================= 第七轮加固（搜索覆盖：实测 541 会话只爬 196、单频道 5000 截断、
 * 存量 albumId=0 相册展开失效） =================
 *  1. 全频道覆盖：根因在 FeedViewModel 调度侧（listSearchableChats 单次 GetChats 只返回
 *     TDLib 已加载前缀 + 只跑单轮），已改为常驻轮次爬取循环（枚举逐轮增长直至饱和，
 *     每轮全覆盖 + 维护轮头部刷新）——本类只提供 indexChannel 幂等续爬原语；
 *  2. 单频道深度：[TAIL_MAX_ITEMS] 5000 → 50000（磁盘索引时代内存约束消失）；
 *  3. 相册展开双保险：albumId=0 存量命中走 messageId 邻近窗口近似（见 [expandAlbums]）；
 *  4. 存量 albumId 低成本回填：爬取路径 key 冲突时顺手 UPDATE albumId/mediaType/coverFileId
 *     （不动 fts 行；迁移路径不回填，防 JSON 旧值回退网络新值）。
 *
 * ================= 第八轮提速（Premium 账号"不接受只能慢"） =================
 *  1. AIMD 自适应限速（[AdaptivePacer]，账号级共享）：替换旧固定 80ms/页——每页成功
 *     且出惩罚窗即 -20ms（下限 10ms）；flood/429 ×2（上限 2000ms）+ 5 分钟惩罚窗
 *     （窗内冻结，连续 5 分钟无 flood 才恢复回升）。真实速率天花板自动探出；
 *  2. 媒体清扫通道（[mediaSweep]）：SearchChatMessages + SearchMessagesFilter
 *     （视频→图片→音频三相位，游标与 GetChatHistory 同为 messageId 语义、互通），
 *     纯文字闲聊页整体跳过——文字占多数的频道页数少一个数量级。游标/相位断点
 *     落 meta（v2 库结构），中断续扫；同频道连灭 3 轮回退旧路兜底一轮（防系统性不可用）；
 *     代价说明：历史区的评论级联/文字批注关联不再全量扫（新内容仍由旧路头部刷新
 *     保真——清扫完成的频道后续轮次只跑头部刷新）；
 *  3. 并发度 AIMD（FeedViewModel 侧）：连续无 flood 一轮并发 +1（上限 6），出 flood 降回 3。
 *
 * ================= 第十轮提速（"六七天三百频道"实测投诉的回应） =================
 * 先讲诚实口径：网页版 TG 是"用户点到哪个频道才现拉哪个"的按需加载，咱们是
 * 全量后台爬历史建本地 FTS 索引——语义不同，注定不可能"几秒出全量"。但实测
 * 吞吐（≈45 频道/天）远低于参数应有的水平，本轮把可证的浪费逐个点掉：
 *  1. 页量翻倍（[PAGE_SIZE] 50→100，TDLib 单请求上限即 100）：页数减半 =
 *     pacer 间隔/逐页事务/限流暴露面全部近似减半，是零风险吞吐翻倍；
 *  2. 清扫页请求数 5→1：fetchAggregatedMedia 新增 expandCaptions=false，
 *     跳过空 caption 邻居窗口展开（每页最多 4 次 GetChatHistory 是 flood 的
 *     最大来源；页内相册共享 caption 免费保留，新内容保真由头部刷新承担）；
 *  3. 失败退避不再一刀切：旧路/清扫取页识别 flood 消息里的 "retry after N"
 *     精确退避（兜底 5s 起递增），短 flood 当场骑过去，不再丢整轮进度；
 *  4. pacer 冷启动 80→40ms：每页成功 -20ms 的加性提速下 2 页即探到下限，
 *     冷启动税减半（flood 红线不变：429 命中照旧 ×2 降速 + 5 分钟惩罚窗）。
 * 预期吞吐：干净网络下单车道每页 = 1 次 SearchChatMessages（RTT 主导，
 * ≈0.3~0.5s/100 条）→ 单频道车道 ≈200~300 条/s，3~6 车道并发下
 * 5000 条频道 ≈30~60s，300 频道全量 ≈0.5~1.5 小时前台爬取量级。
 * 剩余天花板在调度侧（轮间隔/并发度/进程存活，见 FeedViewModel.autoBuildDeepIndex）。
 *
 * ================= 第九轮补完（"搜不全"最后一环） =================
 *  1. 浅爬频道加深（[deepenLegacy]）：meta v3 新增 needDeepen 标记——触 [TAIL_MAX_ITEMS]
 *     条数顶（媒体清扫/旧路尾爬/日历跳爬三处）即置位；v2→v3 迁移时一次性识别旧 5000
 *     封顶时代数据（未到底 + 未进媒体清扫 + 条数 ≥[LEGACY_SHALLOW_ITEM_FLOOR]）。
 *     加深 = 旧路 GetChatHistory 从断点低速续爬（每轮 ≤[DEEPEN_MAX_PAGES] 页预算，
 *     AIMD 限速），补评论级联/文字批注/漏网媒体；到底清标记。爬取入口收尾处统一执行
 *     （媒体清扫完成路径与旧路径两路都覆盖）；
 *  2. 全局搜索网络腿双波段在 FeedViewModel 侧（30s 前台硬顶后剩余频道转后台低强度续搜，
 *     流式并入直至全频道扫完）——本类不涉及。
 *
 * ================= 第十一轮邪修提速（R46：同结果更少请求，净减 flood） =================
 * 原则：不拉并发、不加 rate，只靠"少发请求"提速。
 *  1. 时间分层采样（方案 c）：全新频道先探媒体总量（[TdLibGateway.probeMediaTotalCount]，
 *     2 次计数请求），超 [SAMPLE_MONSTER_MIN_ITEMS] 的怪兽频道不做全量翻页，改
 *     "按月锚点进历史"采样（[sampleChannelByMonthAnchors]：逐月 GetChatMessageByDate
 *     锚点 + 每活跃月三媒体类型各 ≤[SAMPLE_PAGES_PER_MONTH_PER_TYPE] 页）。
 *     （环境实证：GetChatMessageCalendar 在本 TDLib 构建上挂死不可用，
 *     GetChatMessageByDate/SearchChatMessages 计数是可靠替代原语。）
 *     采样频道 meta.sampled=1 落盘（DB v5），口径诚实——设置页能区分"全量/采样"覆盖；
 *  2. per-chatId 单飞闸（[indexingChats]）：轮循环/手动入口/搜索插队三路并发
 *     不再对同一频道双爬（方案 d 的前置）；
 *  3. 缓存白嫖（方案 b）：[harvestLocalCache] 走网关 onlyLocal=true 零网络变体，
 *     只采不推进游标（INSERT OR IGNORE 幂等天然兼容，本地空洞不污染
 *     "cursor 以新全覆盖"不变式）；
 *  4. 爬取泳道预算 + 熔断（§5 建议落地）：[crawlBudget] 收割器同款 FloodBudget
 *     独立车道（额度 FeedViewModel 每枚举拍从 AppPrefs.floodCrawlPerHour 刷新）；
 *     AIMD 退化为反应层（撞 429 才降，不再主动调速）；旧路 GetChatHistory 撞 429
 *     上报 R44 熔断表（方法族 "GetChatHistory"），熔断期整轮跳过。
 *
 * ================= 第十二轮智能分档（R163：关注×丰度双正交信号，四路径档位化） =================
 *  病根：全库一视同仁地爬——关注频道与死频道同权重吃带宽，库无界增长（R158 实测
 *  2.96GB/379 万行）。分档模型与判定（D 需求分/R 丰度分→T0..T3）
 *  唯一出处 [IndexTierPolicy]；本类只做**档位参数化执行**：
 *  1. meta v6 加 tier 列（-1=LEGACY 缺省——分档关时行为与分档前逐项一致，
 *     纪律①默认保守）；唯一写入口 setTier（B4 派工前评估落档）；
 *  2. 四条爬取路径按档位参数化：条数上限（T0 全量 50k/T1 20k/T2 2k）、
 *     头刷节流（T0 1h/其余 6h）、采样闸豁免（T0 怪兽也全爬）、加深闸（T1/T2
 *     触顶即终态，不靠加深绕上限）；
 *  3. 探针模式（M3 死亡循环击断）：T2/T3 全新频道首爬只跑 [IndexTierPolicy.probePageBudget]
 *     页（≈[TieringConfig.probeItems] 条）——低成本采样算丰度分 R，高分自动升 T1
 *     **无需关注**；预算截断是正常中断（SweepResult.BUDGET_CUT 三态，不进熔断
 *     计数不退旧路兜底）；
 *  4. 存储约束 evict（M7，修订令版）：磁盘水位线（分区剩余可用 < [TieringConfig.evictMinFreeBytes]，
 *     StatFs 零权限）∨ 用户索引库预算（[TieringConfig.evictIndexBudgetBytes]，设置项默认关）
 *     触发，从 T3>T2 最冷频道渐进整删（相册天然原子；fts 删除配方 = caption 回填
 *     同款现算词位同源删除），T0/T1/LEGACY 绝不淘汰；分档关永不 evict。**全库行数
 *     硬顶已废**（用户令：不要用死数字剪裁活偏好——体积=「关注了多少好频道」的
 *     涌现结果，只有真实约束才配当扳机）；兴趣衰退走自然降档：配额随档收紧即
 *     停增长，存量收缩由真实约束 evict 兜底（降档频道进 T2/T3 候选池；按配额
 *     逐行收缩=设计 §6.1 挂账未落码，头刷只增不删的漂移同由此兜底）。
 * ================= 第十三轮提速（R255：深索引构建速度大幅提升，用户令「手段不限」） =================
 * 基线实测（2026-10-19 23:49~23:53，16384/16416 双真库只读采样）：**16384 索引速度
 * = 0 条/小时**（四采样零增长）、16416 ≈ 240 条/h——病根不是慢，是**零产出空转**：
 *  1. 触顶即终态（W1）：条数触顶（INTERRUPTED 被当失败计→3 轮转旧路兜底烧预算
 *     零产出→枚举拍重新入队→无限空转）改为**完成态落盘出队**（6h 头刷接管保新帖）；
 *     页数触顶维持可续中断。配套 setTier 升档复活（cap 提升≠单向门）；
 *  2. 档位闸（W2）：分档关时**不读 meta.tier**（一律 LEGACY 参数）——历史残留档位行
 *     （B7 注入/evict 复位）的 T3 cap=0 秒退/T1 20000 提前封口泄漏就地解毒；
 *  3. 探针解锁（W3）：探针档条上限下限=探针页预算×页大小（T3 cap=0 曾让探针
 *     在首个 cap 检查即死——R163 探针链路首次真正可用）；
 *  4. 平滑配速（W5，[SmoothRateGate]）：爬取网络请求统一按 `预算窗口/maxOps`
 *     摊满整小时（6000/h→600ms/请求）——拆「突发耗尽→干涸 55min→队列空 FGS
 *     摘牌→进程冻结→滑窗恢复无人消费」死链（R158 ③实测）；**瞬时峰值比旧突发
 *     形态低一个数量级**（429 风险下降；AIMD/熔断/R208 风暴闸保留为反应层）；
 *  5. 预算默认 2000→6000（[AppPrefs.floodCrawlPerHour]）+ ingest 瘦身（W6：全冲突
 *     页跳过解析+词位计算延后）+ 每页两事务合一（W7：清扫游标并入页事务）+
 *     COUNT 缓存（W9：会话内 fresh 增量累计）。
 * 纯函数判定唯一出处 [CrawlThroughputPolicy]（单测锚定）；吞吐上限 = 预算上限本身。
 */
/**
 * R260 坏 SQL 修复的机检锚（纯函数，文件顶层=JVM 单测直测可达）：tag_items 多值
 * INSERT 的 SQL 形状——`VALUES (?,?,?),(?,?,?)…`（每元组自带括号组，组数=元组数）。
 * 旧内联写法把 N 元组拼进同一对括号 → `VALUES(?,?,?,?,?,?)`（SQLITE_ERROR
 * 「N×3 values for 3 columns」），≥2 标签的批静默整页丢失。
 */
/**
 * R262b 门房白名单（master 4018e40，回移植合流保留）：回填断点状态文件前缀判定——
 * caption_backfill_/tags_backfill_/album_caption_backfill（代数后缀 v8/v8r2/v9r2/未来
 * 换代与 .tmp 半写件天然覆盖）。与 R266 数字命名硬门双保险（见 legacyJsonFiles）。
 */
internal fun isBackfillStateFileName(name: String): Boolean =
    name.startsWith("caption_backfill_") ||
        name.startsWith("tags_backfill_") ||
        name.startsWith("album_caption_backfill") ||
        // R285 链式回填断点（链②相册共享+链④邻近批注的存量重算）
        name.startsWith("chain_caption_backfill") ||
        // R287：多设备索引合并断点（import_merge_v1.json）——门房数字硬门本就放行
        // （非数字命名），此处进白名单双保险（R262b 西西弗斯洞教训：断点被删=数小时合并归零）
        name.startsWith("import_merge_") ||
        // R289：自愈守卫状态（index_heal_guard.json[.tmp]）——损坏计数活在被重建的
        // search.db 之外；被门房删=只读回退史归零=活锁闸失效（西西弗斯洞同族教训）
        name.startsWith("index_heal_guard") ||
        // R292：封面 OCR 回填/并词位断点与试点报告（cover_ocr_backfill_v1/
        // cover_ocr_apply_v1/cover_ocr_pilot_report）——被删=试点进度归零重扫
        //（重烧封面下载量），与上列断点同族双保险
        name.startsWith("cover_ocr_")

/**
 * R262b C-① 修复在 v11 两段法时代的回归锚（纯常量，JVM 单测直测）：时段窗小时谓词
 * ——等值右值 **CAST(? AS INTEGER)**（字符串绑定下 '23' 是 TEXT、两侧皆无亲和性 →
 * 整数=文本比较恒假=时段窗恒空，R259c 机上双形态实证）。谓词两臂同施于
 * channelItemsTwoArm 的 items/refs 两路。
 */
internal const val HOUR_WINDOW_PREDICATE_SQL =
    " AND dateEpochSec>0 AND (dateEpochSec+?)/3600%24 = CAST(? AS INTEGER)"

internal fun tagItemsBulkInsertSql(tupleCount: Int): String {
    require(tupleCount >= 1) { "tupleCount must be >= 1" }
    // 表名直写（companion 私有常量类外不可见；tag_items 自 R236 起未改名——
    // 改它等于换库，无需间接层）
    return "INSERT OR IGNORE INTO tag_items(tag, rowid_ref, chatId) VALUES " +
        (1..tupleCount).joinToString(",") { "(?,?,?)" }
}

/**
 * R269 真机实锤修复的机检锚（纯函数，文件顶层=JVM 单测直测可达）：tagItems 的
 * JOIN 查询 SQL 形状——**必须含 `FROM items JOIN tag_items` 双表结构**。
 * 回归史：R236 修正刀（27dd5a8）治「chatId 列名歧义」时把
 * `SELECT $ITEM_COLS FROM tag_items JOIN items …` 改写为全限定列清单，**误把
 * FROM 子句一起删掉**——`SELECT items.* JOIN tag_items` 无 FROM 必炸
 * 「near "JOIN": syntax error」，被 runCatching 吞成空召回+一条 E 日志：
 * tagItems 自此全设备 100% 空召回，R222 时间轴/R234 传播带/R269 内容流三面
 * 同病（R236 自报的「毫秒级对照」是该修正**之前**的原形态实测；修正刀本身
 * 未重跑 EXPLAIN——教训：修 SQL 后必须真机重验）。列清单由调用方传
 *（ITEM_COLS_FROM_ITEMS 是 companion 私有派生，类外不可见）。
 */
internal fun tagItemsJoinSql(colsFromItems: String): String =
    "SELECT $colsFromItems FROM items JOIN tag_items ON items.rowid = tag_items.rowid_ref " +
        "WHERE tag_items.tag=? ORDER BY items.dateEpochSec DESC LIMIT ?"

/**
 * R313 tag 分代召回的 SQL 形状（纯函数，[tagItemsJoinSql] 同款 R269 机检锚——防
 * 27dd5a8 类误删/漂移）。与单路旧形的差异=**驱动侧锁死 + 代内日期区间**：
 *  - `tag_items CROSS JOIN items`：SQLite CROSS JOIN 禁优化器换序——锁死 tag 前缀
 *    扫驱动（tag_items PK (tag,rowid_ref) 范围扫 + items rowid 点查 + 代内临时排序）。
 *    **绝不 items 侧 INDEXED BY idx_items_date**（LIKE 路词位无索引只能 items 驱动；
 *    tag 侧若照抄，稀疏 tag（密度 ~1e-5）每代凑满 k 条要点查 ~10^5 行×8 代≈全表
 *    点查一遍，比现状劣化三个数量级——snap2 EXPLAIN/耗时实测详 r313_notes）；
 *  - 代内 `items.dateEpochSec >= ? AND < ?` + ORDER BY … DESC LIMIT ?（每代 k 条，
 *    并集 ≤ limit×oversample）。
 */
internal fun tagItemsSlicedJoinSql(colsFromItems: String): String =
    "SELECT $colsFromItems FROM tag_items CROSS JOIN items ON items.rowid = tag_items.rowid_ref " +
        "WHERE tag_items.tag=? AND items.dateEpochSec >= ? AND items.dateEpochSec < ? " +
        "ORDER BY items.dateEpochSec DESC LIMIT ?"

/**
 * R317 发布直方图点查件的 SQL 形状（纯函数，[tagItemsJoinSql] 同款 R269 机检锚——防
 * 27dd5a8 类 FROM/谓词误删漂移）。口径=[monthHistogram] 既有日粒度直方图的**频道版**
 * （`GROUP BY dateEpochSec/86400`；WHERE chatId=? 频道域点查——**有界成本非全库扫描**：
 * 终态候选 ≤[dev.tgfeed.core.tastemap.TasteAstroPhysics.TERMINAL_POOL_CAP] 颗）。
 * **INDEXED BY idx_items_chat_msg 锁驱动侧**（R313 tagItemsSlicedJoinSql 同款纪律：
 * snap2 EXPLAIN 实锤自然计划会误选 idx_items_album——441ms vs 强制 173ms/5 万条频道；
 * chatId 为索引前导列=WHERE 可下压，R290b「无 WHERE+非覆盖 INDEXED BY」雷形不踩；
 * 索引缺失窗（后台重建 pending）=SQLITE_ERROR 被 runCatching 吞成缺据 null=诚实降级）。
 * 双臂（items+repost_refs）各自聚合 Kotlin 侧合并=v11 D4 现状口径（每次转发各计一份）。
 * [refsTable] 由调用方传 companion 的 [DeepSearchIndex.REPOSTS_TABLE] 常量。
 */
internal fun chatHistogramDaySql(): String =
    "SELECT dateEpochSec/86400 AS day, COUNT(*) FROM items INDEXED BY idx_items_chat_msg " +
        "WHERE chatId=? AND dateEpochSec > 0 GROUP BY day"

/** 同上·repost_refs 臂（[chatHistogramDaySql] 的别名表镜像——形状锁同一机检锚管） */
internal fun chatHistogramDayRefsSql(refsTable: String): String =
    "SELECT dateEpochSec/86400 AS day, COUNT(*) FROM $refsTable INDEXED BY idx_refs_chat_msg " +
        "WHERE chatId=? AND dateEpochSec > 0 GROUP BY day"

/**
 * R323 发布直方图点查件·两窗构成档 SQL 形状（[chatHistogramDaySql] 同族 R269 机检
 * 锚——**有界点查非全库扫描**同锁：chatId 频道域点查+INDEXED BY 同索引；区别仅
 * GROUP BY 多一列 mediaType（自行两窗三票的原料——r315 J①「per-item dateSec+
 * mediaType 全量历史在库」）。双臂=items+repost_refs 各自聚合 Kotlin 侧合并
 * （v11 D4 现状口径：每次转发各计一份）。[refsTable] 由调用方传 companion 的
 * [DeepSearchIndex.REPOSTS_TABLE] 常量。
 */
internal fun chatHistogramMediaDaySql(): String =
    "SELECT dateEpochSec/86400 AS day, mediaType, COUNT(*) FROM items INDEXED BY idx_items_chat_msg " +
        "WHERE chatId=? AND dateEpochSec > 0 GROUP BY day, mediaType"

/** 同上·repost_refs 臂（[chatHistogramMediaDaySql] 的别名表镜像——形状锁同一锚管） */
internal fun chatHistogramMediaDayRefsSql(refsTable: String): String =
    "SELECT dateEpochSec/86400 AS day, mediaType, COUNT(*) FROM $refsTable INDEXED BY idx_refs_chat_msg " +
        "WHERE chatId=? AND dateEpochSec > 0 GROUP BY day, mediaType"

class DeepSearchIndex(
    // R287：internal（模块内 IndexImportMerge 复用——合并执行器必须落在同一
    // storeDir/主连接/writeLock 上；仅放宽可见性，签名零变化，公开 API 只加不改）
    internal val storeDir: File,
    /** 爬取泳道 flood 预算（R46，FeedViewModel 持有并每拍刷新额度上限）：
     *  清扫页/锚点请求按 1 次实记；旧路逻辑页（2~6 次 GetChatHistory + ≤3 次评论级联）
     *  按 [LEGACY_PAGE_COST] 估值记账。耗尽即整轮跳过（静默降级，窗口滑动自然恢复） */
    private val crawlBudget: FloodBudget,
) {

    /** per-chatId 单飞闸（R46 方案 d 前置）：同频道同时在爬最多一份，
     *  轮循环/手动入口/搜索插队三路派工撞车时后到者直接跳过（零成本幂等） */
    private val indexingChats = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    // ---------------- 数据库打开 / FTS5 探测 ----------------

    /** 数据库句柄（懒打开，openDb 幂等）；null = 尚未打开 */
    @Volatile
    private var db: SQLiteDatabase? = null

    /** DB 打开/建表/迁移启动的互斥锁（小临界区，绝不持锁做迁移本体） */
    private val dbLock = Any()

    /**
     * 写路径互斥锁：爬取落库与 JSON 迁移共用编译语句（SQLiteStatement 非线程安全），
     * 写事务串行化。读路径（search/channelItems/统计）不进此锁——WAL 下读写不互斥。
     * R287：internal（IndexImportMerge 合并在本锁内串行——主连接 busy_timeout=0，
     * 旁路连接写会与爬虫互撞 SQLITE_BUSY，合并绝不另开写连接）。
     */
    internal val writeLock = Any()

    /** FTS5 是否可用（openDb 时探测）；false = 走 items.tokens 列 LIKE 兜底路径。
     *  R287：internal（IndexImportMerge 按本形态决定新入行写 fts 虚表还是 tokens 列） */
    @Volatile
    internal var useFts5 = false

    /** bm25 排序是否可用（极端老 SQLite 在 contentless 表上 bm25 可能异常，首次失败后永久降级为时间倒序） */
    @Volatile
    private var bm25Ok = true

    // ---------------- R289 索引库损坏自愈（判定唯一出处 [IndexHealPolicy]，此处只做执行落形） ----------------

    /**
     * 只读回退态（防线③活锁闸）：true = openDb 走 OPEN_READONLY 直通（坏档读多少
     * 算多少），全部写入口由 [healWriteBlocked] 早退拦截、后台 Job 不点火。
     * 进入/退出只在 openDb 内发生（dbLock 内），读路径只见稳定快照。
     */
    @Volatile
    internal var healReadOnly = false
        private set

    /**
     * 轻损修复登记的大表缺失索引名（转后台单飞 [maybeStartHealRebuild] 重建；
     * 正常路径恒空=零行为变化）。openDb 同步段写入，后台 Job 建一个清一个。
     */
    private val healPendingIndexes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 轻损修复登记的 fts 重灌待办：重灌完成前 [useFts5] 保持 false（搜索走 LIKE 兜底） */
    @Volatile
    private var healFtsRebuildPending = false

    /**
     * R294 fts 重灌源（形态缝根治，判定 [IndexHealPolicy.ftsRefillSource]）：
     * true = items.tokens 列快速 SQL 直灌（LIKE 形态库）；false = 换源现算回灌
     * （FTS5 形态库从未建 tokens 列——旧码盲灌产空 fts+useFts5 置真=搜索静默全空，
     * r292 挂账/R294 收口）。在 healLightLocked/缝探测当下探测（ensureTokensColumn
     * 补列**之前**——补列后探测到的恒是在场空列，形态真值被污染）。
     */
    @Volatile
    private var healFtsRefillViaTokensSql = true

    /** 轻损修复后台重建单飞闸（防 openDb 重入重复点火） */
    @Volatile
    private var healRebuildStarted = false

    /** 只读回退写闸日志节流（每进程每分钟最多一行，防刷日志） */
    @Volatile
    private var lastReadOnlyWriteLogAt = 0L

    /**
     * R289 只读回退写闸（写入口统一早退口）：true=已拦（调用方立即早退）。
     * 判据 [healReadOnly] 单字段；日志节流防写入口热路径刷屏。
     */
    private fun healWriteBlocked(entry: String): Boolean {
        if (!healReadOnly) return false
        val now = System.currentTimeMillis()
        if (now - lastReadOnlyWriteLogAt >= 60_000L) {
            lastReadOnlyWriteLogAt = now
            Log.w(TAG, "R289 只读回退中：写入口 $entry 已拦（索引库连续损坏冷却期，读路径照常）")
        }
        return true
    }

    /**
     * AIMD 自适应限速器（第八轮提速；账号级共享——flood wait 是账号级事件，
     * 全部并发爬取协程（含媒体清扫/旧路 GetChatHistory/日历锚点）共用同一状态）：
     *  - 每页成功且处于惩罚窗外：加性提速（间隔 -[PACER_ADDITIVE_MS]ms，下限 [PACER_MIN_MS]）；
     *  - flood/429（含媒体清扫游标踏步判定的网关吞错）：乘性降速（间隔 ×2，上限
     *    [PACER_MAX_MS]）+ 开 [PACER_QUIET_MS]（5 分钟）惩罚窗——窗内冻结不提速，
     *    连续 5 分钟无 flood 后才恢复加性回升；
     *  Premium 账号的真实速率天花板由此自动探出（干净即升、撞 flood 即翻倍降）。
     *  当前间隔随爬取进度日志顺带输出（见各 Log.d 的"间隔="字段）。
     */
    private class AdaptivePacer {
        private val lock = Any()
        private var intervalMs = PACER_START_MS
        private var lastFloodAt = 0L
        private var floods = 0

        /** 当前页间隔（毫秒） */
        fun current(): Long = synchronized(lock) { intervalMs }

        /** flood 事件总数（VM 侧并发度 AIMD 判定"本轮有无 flood"用） */
        fun floodCount(): Int = synchronized(lock) { floods }

        /** 一页成功：惩罚窗外加性提速（-20ms/页，下限 10ms） */
        fun onPageSuccess() {
            synchronized(lock) {
                if (System.currentTimeMillis() - lastFloodAt >= PACER_QUIET_MS && intervalMs > PACER_MIN_MS) {
                    intervalMs = maxOf(PACER_MIN_MS, intervalMs - PACER_ADDITIVE_MS)
                }
            }
        }

        /** flood/429：乘性降速（×2，上限 2000ms）+ 开 5 分钟惩罚窗 */
        fun onFlood() {
            synchronized(lock) {
                floods++
                intervalMs = minOf(PACER_MAX_MS, intervalMs * 2)
                lastFloodAt = System.currentTimeMillis()
            }
        }
    }

    /** 全局限速器实例（账号级；本类单例随 VM 存活） */
    private val pacer = AdaptivePacer()

    /**
     * R255 W5 平滑配速闸（账号级全局——与 [pacer] 同层级的第二限速器，作用不同：
     * pacer 是撞 429 后的反应层，本闸是**预算的平滑消费层**）。
     * 爬取网络请求（清扫页/旧路页/锚点/探针/采样）发出前统一领一个发送槽位，
     * 槽位间隔 = [crawlRateIntervalMs]（窗口时长/每小时预算上限）。
     *
     * 动机（拆死链）：旧形态 8 车道突发 ~16 页/s → 预算分钟级耗尽 → 干涸 55min →
     * 队列空 FGS 摘牌 → 进程缓存冻结（R158 ③实测）→ 预算滑窗恢复无人消费——
     * 突发额度实际只用一个头，其余小时全浪费。配速把同一预算摊满整小时持续消费
     * （吞吐=预算上限本身；突发消失，FGS 常驻在飞，进程不再进冻结态）。
     *
     * 安全口径（429 风险**下降**而非上升）：瞬时请求率从 ~16 页/s 降到
     * 预算/3600s（6000/h→1.67 页/s）——峰值低于现状突发一个数量级；AIMD pacer
     * （撞 429 ×2 降速+5min 惩罚窗）/熔断表/R208 风暴闸全部保留为反应层，
     * 本闸只做平滑不做降级。并发车道在闸上排队互斥领槽（synchronized 段内
     * 只做时刻算术，无 IO 无锁链风险——锁图：rateGate.lock 自持有自释放，
     * 不与 writeLock/dbLock 交叉）。
     */
    private class SmoothRateGate {
        private val lock = Any()
        private var nextSlotAtMs = 0L

        /** 领一个发送槽位：槽位间隔已到立即返回，否则挂起等到本槽位时刻 */
        suspend fun await(intervalMs: Long) {
            while (true) {
                val waitMs = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    if (now >= nextSlotAtMs) {
                        nextSlotAtMs = now + intervalMs.coerceAtLeast(1L)
                        0L
                    } else {
                        nextSlotAtMs - now
                    }
                }
                if (waitMs <= 0L) return
                delay(waitMs)
            }
        }
    }

    /** R255 W5 配速闸实例（账号级，随本类单例存活） */
    private val rateGate = SmoothRateGate()

    /**
     * 当前配速间隔（毫秒）：爬取预算窗口 [CRAWL_BUDGET_WINDOW_MS] /
     * [FloodBudget.maxOpsPerHour]（枚举拍每 5min 从 AppPrefs 刷新额度，配速随
     * 设置免重启生效；唯一实现处 [CrawlThroughputPolicy.rateIntervalMs]）。
     * ops≤0（哑元实例/读不到）→ Long.MAX_VALUE=不配速（防御：永不因配速卡死）。
     */
    private fun crawlRateIntervalMs(): Long =
        CrawlThroughputPolicy.rateIntervalMs(CRAWL_BUDGET_WINDOW_MS, crawlBudget.maxOpsPerHour)

    /** flood 事件总数（爬取开始至今）：FeedViewModel 并发度 AIMD 的判定依据 */
    fun floodEventCount(): Int = pacer.floodCount()

    /** 媒体清扫连续失败计数（chatId → 连灭轮数；达 [SWEEP_FALLBACK_AFTER_FAILS] 本轮退回旧路兜底） */
    private val sweepFailCount = HashMap<Long, Int>()

    /** 迁移协程域（一次性 JSON→SQLite 导入在后台 IO 跑，不阻塞任何调用方） */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 迁移是否已启动过（防 openDb 重入重复点火） */
    @Volatile
    private var migrationStarted = false

    /**
     * JSON→SQLite 一次性迁移进度（已处理文件数, 总文件数），null = 未在迁移/已完成。
     * 导入期间搜索照常可用（库里有多少搜多少）。
     */
    private val _migrationProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val migrationProgress: StateFlow<Pair<Int, Int>?> = _migrationProgress.asStateFlow()

    /**
     * 旧"倒排表建表进度"公开口（SearchScreen 在用，签名不能动）：倒排表机制已删除，
     * 现映射为 JSON→SQLite 迁移进度——语义同为"索引不全，结果可能偏少"，迁移完自动消失。
     */
    val invertedBuildProgress: StateFlow<Pair<Int, Int>?> = _migrationProgress.asStateFlow()

    /** R202 时间索引后台构建单飞闸（防 openDb 每次重入重复点火） */
    @Volatile
    private var dateIndexBuildStarted = false

    // ---------------- R266 v11：内容指纹索引 + 转发合并批迁 ----------------

    /** v11 内容指纹索引后台构建单飞闸（防 openDb 重入重复点火） */
    @Volatile
    private var fuidIndexBuildStarted = false

    /**
     * v11 内容指纹索引（idx_items_fuid）就绪态：true = 写入路径查重归并
     * （insertItemLocked 转学收编）与 FirstSeenLedger 毫秒级溯源可用。
     * false 期间写入路径**零行为变化**（同 fuid 转发照旧各占一行——存量形态，
     * 待索引建成+批迁 Job 收编；就绪前绝不按 fuid 查库——那是全表扫红线）。
     */
    private val _fuidIndexReady = MutableStateFlow(false)
    val fuidIndexReady: StateFlow<Boolean> = _fuidIndexReady.asStateFlow()

    /** v11 合并批迁 Job 单飞闸（防 openDb 重入/索引建成回调重复点火） */
    @Volatile
    private var mergeBackfillStarted = false

    /**
     * v11 合并批迁进度（已收编行数, 已处理组数），null = 未在跑/已完成。
     * 进度外露供诊断/设置页观察（合并是数天级后台错峰工程，进度诚实可见）。
     */
    private val _mergeProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val mergeProgress: StateFlow<Pair<Int, Int>?> = _mergeProgress.asStateFlow()

    /** v11 写入路径归并是否激活（指纹索引就绪才允许按 fuid 查库——否则是全表扫红线） */
    private val mergeActive: Boolean get() = _fuidIndexReady.value

    // ---------------- R287：多设备索引合并导入（设计/裁决口径见 IndexImportMerge/IndexMergePolicy） ----------------

    /** 合并导入 Job 单飞闸（防 openDb 重入/索引建成回调/设置页手动三路重复点火） */
    @Volatile
    private var importMergeStarted = false

    /**
     * 合并导入进度（人话行，null=无在跑合并）：设置页导入完成提示的观察口。
     * 合并全程后台批迁（批 500/1s 错峰/R208 风暴闸让路），中断自动续跑（断点
     * import_merge_v1.json 每批原子落盘）。
     */
    private val _importMergeProgress = MutableStateFlow<String?>(null)
    val importMergeProgress: StateFlow<String?> = _importMergeProgress.asStateFlow()

    /**
     * 合并导入点火（两处入口：openDb 尾 + 指纹索引建成回调；三闸全过才跑）：
     *  1. 状态文件在（BackupManager 解包完成落了 import_merge_v1.json）；
     *  2. 指纹索引就绪（分类③内容指纹点查缺索引=全表扫红线，等链式回调）；
     *  3. 单飞闸。
     */
    private fun maybeStartImportMerge(d: SQLiteDatabase) {
        // R287 探针（点火链路定位；低频点——openDb 首启/指纹索引回调/设置页手动三处）
        Log.d(TAG, "R287 点火探测：state=${File(storeDir, IndexImportMerge.STATE_FILE_NAME).isFile} started=$importMergeStarted")
        if (!File(storeDir, IndexImportMerge.STATE_FILE_NAME).isFile) return
        if (!fuidIndexReadyCached(d)) {
            Log.i(TAG, "R287 合并导入已挂起：等指纹索引建成后自动开跑")
            return
        }
        synchronized(dbLock) {
            if (importMergeStarted) return
            importMergeStarted = true
        }
        ioScope.launch {
            Log.d(TAG, "R287 合并协程起跑")
            IndexImportMerge(this@DeepSearchIndex) { msg -> _importMergeProgress.value = msg }.run(d)
        }
    }

    /**
     * 设置页合并导入触发口（BackupManager 解包就绪后调用）：
     * 返回 true=有挂起合并已接单（在跑/排队）；false=无挂起合并（调用方按异常路径提示）。
     */
    fun requestImportMerge(): Boolean {
        if (!File(storeDir, IndexImportMerge.STATE_FILE_NAME).isFile) return false
        if (healWriteBlocked("requestImportMerge")) return false // R289 只读回退闸（合并是写通道）
        return runCatching {
            maybeStartImportMerge(openDb())
            true
        }.getOrDefault(false)
    }

    /**
     * refs 表存在探测（sqlite_master，µs 级）：金矿只读连接（[openGoldDb]）可能先于
     * openDb 迁移打开库文件（升级窗口期文件仍是 v10，无 repost_refs）——goldDb 路径
     * 的 refs 臂一律先探后发，缺席=存量形态直通（items 臂语义与 v10 前逐字节一致）。
     * 主连接（openDb）路径无需探测（迁移已在 openDb 内先行）。
     */
    private fun hasRepostsTable(d: SQLiteDatabase): Boolean =
        d.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(REPOSTS_TABLE),
        ).use { it.moveToFirst() }

    // ---------------- R228 caption 结构化（parsed_title/item_tags） ----------------

    /**
     * v8 结构化列就绪（items.parsed_title/item_tags 已存在）。
     * openDb 同步幂等加列后置真；后台单飞 [maybeStartParsedColsNet] 兜底复核。
     * false 期间 ingest 不写解析字段、读路径不取新列（存量口径零变化）。
     */
    @Volatile
    private var parsedColsReady = false

    /** v8 存量回填 Job 单飞闸（防 openDb 重入重复点火） */
    @Volatile
    private var legacyBackfillStarted = false

    /** R285 链式回填 Job 单飞闸（防 openDb 重入重复点火） */
    @Volatile
    private var chainBackfillStarted = false

    /** R292 封面 OCR 回填 Job 单飞闸（FeedOcrPart 接线点火，不走 openDb 自燃） */
    @Volatile
    private var coverOcrBackfillStarted = false

    /** R292 词位并入（apply）Job 单飞闸（同上） */
    @Volatile
    private var coverOcrApplyStarted = false

    /** v8 结构化列 UPDATE 编译语句（writeLock 内复用；SQLiteStatement 非线程安全） */
    private var stmtParsedUpsert: SQLiteStatement? = null

    // ---------------- R236 v9：tags/tag_items 聚合层 ----------------

    /** v9 存量聚合 Job 单飞闸（防 openDb 重入重复点火） */
    @Volatile
    private var tagsBackfillStarted = false

    /** R236 tags/tag_items 编译语句缓存（writeLock 内复用；SQLiteStatement 非线程安全） */
    private var stmtTagsInsert: SQLiteStatement? = null
    private var stmtTagItemsDelete: SQLiteStatement? = null
    private var stmtTagsPrune: SQLiteStatement? = null
    /** R255 W11：批量 delta（+=N/-=M 带极值 CASE）编译语句（writeLock 内复用） */
    private var stmtTagsDeltaN: SQLiteStatement? = null
    private var stmtTagsRemoveN: SQLiteStatement? = null

    /**
     * R202 时间索引（idx_items_date）就绪态：true = 索引已存在、时间窗族查询可用。
     * 后台构建完成/任一连接探测到已存在时置 true；UI 观察本流在建完后自动补刷
     * （R183 金矿「建完自动补刷」同款）。false 期间 [timeWindowItems]/[dateBounds]/
     * [monthHistogram] 一律返回空（调用方空态承接——绝不允许退化成全表扫）。
     */
    private val _dateIndexReady = MutableStateFlow(false)
    val dateIndexReady: StateFlow<Boolean> = _dateIndexReady.asStateFlow()

    /**
     * 打开数据库（幂等，任意线程可调用；建表/FTS5 探测/迁移启动只在首次执行）。
     * 打开失败抛异常（调用方自行 runCatching；下次调用重试）。
     *
     * R289 损坏自愈编排（判定真值全在 [IndexHealPolicy]，本函数只做执行落形）：
     *  1. 只读直通：守卫状态在只读冷却窗内 → OPEN_READONLY 打开即返（不检测/不建表/
     *     不迁移/不点火——连续损坏达顶后的防活锁回退，坏档读多少算多少）；
     *  2. 有界检测：打开+WAL 后跑 [probeIntegrityFast] 毫秒级抽样探针（6GB 库全量
     *     quick_check 是分钟级长扫，绝不进首开路径）；
     *  3. 自愈分级：探针/真损坏签名 SQLite 异常进 catch——L0/L1/L2 层（模式/
     *     meta/items 主表）=重损（备份坏档→重建空库→meta 打捞→爬虫自然回填），
     *     L3/L4 层（索引/衍生表）=轻损（重建受损对象）；**每次打开最多自愈一次**
     *     （healAttempts 环回硬顶 1，修复后再炸按原契约 close+throw——防修复本身
     *     构成循环）。R289b：一切异常先过 [IndexHealPolicy.classifyDbError] 文案
     *     分流——计划/用法错误（no query solution 等）绝非损坏，原契约上抛零自愈；
     *  4. 用户数据红线：自愈操作域只限 deepindex/search.db 三件套与 corrupt 备份件
     *     （[IndexHealPolicy.isHealScopePath] 锚定）——收藏/历史（store/）、设置
     *     （prefs/）、登录态（tdlib/）、R254 成品缓存（galaxy/）一律不碰。
     */
    private fun openDb(): SQLiteDatabase {
        db?.let { return it }
        synchronized(dbLock) {
            db?.let { return it }
            storeDir.mkdirs()
            val healDbFile = File(storeDir, DB_NAME)
            val healNowMs = System.currentTimeMillis()
            var healGuard = readHealGuard()
            if (IndexHealPolicy.shouldEnterReadOnly(healGuard, healNowMs) && healDbFile.exists()) {
                val ro = SQLiteDatabase.openDatabase(healDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                healReadOnly = true
                Log.w(TAG, "R289 索引库只读回退中：连续损坏 ${healGuard.consecutiveCorruptions} 次——坏档只读直通、写路径全闸（冷却窗内不再重建）")
                db = ro
                return ro
            }
            healReadOnly = false
            // String 路径版 openOrCreateDatabase（File 版重载要 API 27+，本项目 minSdk 26）
            var d = SQLiteDatabase.openOrCreateDatabase(healDbFile.absolutePath, null)
            var healSalvageFrom: File? = null // 重损修复后置：坏档备份件（打捞 meta 用）
            var healAttempts = 0 // 每次打开最多自愈一次（环回硬顶，防修复构成循环）
            while (true) {
                try {
                    // WAL：迁移/爬取写库期间搜索读不阻塞（"导入期间搜索可用"的前提）；
                    // journal_mode 返回结果行，必须走 rawQuery 而不是 execSQL
                    d.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
                    d.execSQL("PRAGMA synchronous=NORMAL") // WAL 下的安全档位（省每事务 fsync）
                    // R289 ①有界损坏检测：毫秒级抽样探针，命中即抛 HealProbeException
                    // 进 catch 自愈编排（轻/重分级真值=[IndexHealPolicy.classify]）
                    probeIntegrityFast(d)?.let { throw HealProbeException(it) }
                    // 库结构版本迁移：v1 → v2 补 meta.mediaPass 列（媒体清扫相位持久化）；
                    // v2 → v3 补 meta.needDeepen 列（浅爬频道加深标记）；
                    // v3 → v4 补 meta.sweepCursor/lastHeadRefreshTs 列（R45：相位内游标落盘 + 头部刷新节流）。
                    // 新库 CREATE_META_SQL 已含全部列；旧库按版本差走 ALTER 补列
                    val schemaVersion = d.rawQuery("PRAGMA user_version", null).use { c ->
                        if (c.moveToFirst()) c.getInt(0) else 0
                    }
                    d.execSQL(CREATE_ITEMS_SQL)
                    createIndexUnlessPending(d, CREATE_ITEMS_CHAT_INDEX_SQL, "idx_items_chat_msg")
                    createIndexUnlessPending(d, CREATE_ITEMS_ALBUM_INDEX_SQL, "idx_items_album")
                    d.execSQL(CREATE_META_SQL)
                    // R236 v9：tags 聚合表 + tag_items 倒排表（CREATE IF NOT EXISTS 幂等，
                    // 空表=元数据级瞬时，v2~v6 同款安全档；存量聚合走 [maybeStartTagsBackfill]）
                    d.execSQL(CREATE_TAGS_SQL)
                    createIndexUnlessPending(d, CREATE_TAGS_TAG_INDEX_SQL, TAGS_TAG_INDEX)
                    d.execSQL(CREATE_TAG_ITEMS_SQL)
                    createIndexUnlessPending(d, CREATE_TAG_ITEMS_CHAT_INDEX_SQL, TAG_ITEMS_CHAT_INDEX)
                    createIndexUnlessPending(d, CREATE_TAG_ITEMS_ROWID_INDEX_SQL, TAG_ITEMS_ROWID_INDEX)
                    // R266 v11：repost_refs 别名表+四索引+posts_view 兼容视图（CREATE IF NOT
                    // EXISTS 幂等，空表+空表索引=元数据级瞬时，v9 同款先例；idx_items_fuid
                    // 是分钟级大索引绝不进此同步路径——版本号落位后转后台单飞，见 v11 分支）
                    d.execSQL(CREATE_REPOSTS_SQL)
                    createIndexUnlessPending(d, CREATE_REFS_CHAT_INDEX_SQL, REFS_CHAT_INDEX)
                    createIndexUnlessPending(d, CREATE_REFS_ALBUM_INDEX_SQL, REFS_ALBUM_INDEX)
                    createIndexUnlessPending(d, CREATE_REFS_FUID_INDEX_SQL, REFS_FUID_INDEX)
                    createIndexUnlessPending(d, CREATE_REFS_DATE_INDEX_SQL, REFS_DATE_INDEX)
                    d.execSQL(CREATE_POSTS_VIEW_SQL)
                    // R292 v14：item_ocr 封面 OCR 独立表（CREATE IF NOT EXISTS 幂等，
                    // 空表+索引=元数据级瞬时，v9/v11 同款先例；识别文本绝不混 items.caption——
                    // 引擎换代整表重建不触主表）
                    d.execSQL(CREATE_ITEM_OCR_SQL)
                    createIndexUnlessPending(d, CREATE_ITEM_OCR_CHAT_INDEX_SQL, ITEM_OCR_CHAT_INDEX)
                    createIndexUnlessPending(d, CREATE_ITEM_OCR_APPLIED_INDEX_SQL, ITEM_OCR_APPLIED_INDEX)
                    if (schemaVersion in 1 until DB_VERSION) {
                        if (schemaVersion < 2) {
                            // R293：裸 ALTER 全族换 [ensureMetaColumn] 幂等守卫（R286 挂账
                            // 收口——降级重入防 duplicate column 变砖，v2~v6/v10 六处同刀）
                            ensureMetaColumn(d, "mediaPass", "INTEGER NOT NULL DEFAULT -1")
                        }
                        if (schemaVersion < 3) {
                            ensureMetaColumn(d, "needDeepen", "INTEGER NOT NULL DEFAULT 0")
                            // 旧 5000 封顶时代的浅爬频道一次性识别：未到底 + 从未进媒体清扫
                            // + 条数接近旧封顶（≥[LEGACY_SHALLOW_ITEM_FLOOR]，留一页误差）→ 标记待加深
                            d.execSQL(
                                "UPDATE meta SET needDeepen=1 WHERE exhausted=0 AND mediaPass=-1 " +
                                    "AND (SELECT COUNT(*) FROM items WHERE items.chatId = meta.chatId) >= $LEGACY_SHALLOW_ITEM_FLOOR",
                            )
                            val marked = d.rawQuery("SELECT COUNT(*) FROM meta WHERE needDeepen=1", null).use { c ->
                                if (c.moveToFirst()) c.getInt(0) else 0
                            }
                            Log.d(TAG, "旧 5000 封顶浅爬频道识别：标记 $marked 个待加深")
                        }
                        if (schemaVersion < 4) {
                            // R45：sweepCursor（清扫相位内游标，页级落盘断点续扫）与
                            // lastHeadRefreshTs（头部刷新 6h 节流时间戳）。
                            // 缺省 0 = 相位起点/从未刷过——老数据语义恰等于旧行为，天然兼容
                            ensureMetaColumn(d, "sweepCursor", "INTEGER NOT NULL DEFAULT 0")
                            ensureMetaColumn(d, "lastHeadRefreshTs", "INTEGER NOT NULL DEFAULT 0")
                        }
                        if (schemaVersion < 5) {
                            // R46：sampled（时间分层采样标记，怪兽频道按月锚点采样而非全量翻页）。
                            // 缺省 0 = 全量口径——存量频道语义不变，天然兼容
                            ensureMetaColumn(d, "sampled", "INTEGER NOT NULL DEFAULT 0")
                        }
                        if (schemaVersion < 6) {
                            // R163 智能分档：tier（-1=LEGACY 分档关/未分类，0..3=T0..T3）。
                            // 缺省 -1 = 存量频道全落 LEGACY 档——分档开启前爬取参数
                            // 与分档前完全一致（纪律①行为零变化）；分档开启后 B4 逐频道
                            // 评估 setTier 迁移到真实档位
                            ensureMetaColumn(d, "tier", "INTEGER NOT NULL DEFAULT -1")
                        }
                        if (schemaVersion < 7) {
                            // R202 时间维度钥匙：idx_items_date(dateEpochSec, chatId)。
                            // 与 v2~v6 的 ALTER 加列（瞬时）本质不同——CREATE INDEX 在
                            // 百万行真库上是分钟级长事务，**绝不在此同步执行**（openDb 被
                            // search/金矿/爬取/设置页多方触发，同步建 = 首开分钟级卡死，
                            // 首开秒级红线直接死）。此处只落版本号；索引本体走
                            // [maybeStartDateIndexBuild] 后台单飞（探测-建-幂等可续建，
                            // 见其 KDoc）。建到一半进程死亡：CREATE INDEX 单事务回滚无
                            // 半成品，下次 openDb 探 idx 缺失自动重启建（user_version 已
                            // 是 7 不影响重入路径）。
                            Log.d(TAG, "v7 时间索引：版本号落位，索引本体转后台单飞构建")
                        }
                        if (schemaVersion < 8) {
                            // R228 caption 结构化：items 加 parsed_title/item_tags 两列。
                            // 迁移策略裁定（对任务书「后台单飞」的工程修正，详见 r228_notes.md）：
                            // ALTER ADD COLUMN 带 NULL 缺省是**元数据级瞬时操作**（v2~v6 的
                            // 同步加列同款，与行数无关——R202 v7 后台单飞针对的是分钟级
                            // CREATE INDEX 长事务，对本迁移不适用）。若转后台，openDb 返回到
                            // ALTER 完成之间存在「18 列查询打 16 列表」窗口（search 报错清空/
                            // 爬取页事务回滚）——同步加列是唯一无窗口方案；失败由 openDb 既有
                            // 失败契约兜底（close + throw + 下次调用重试），逐列探测-补列幂等
                            // （半途死亡重入不炸 duplicate column）。R202 式后台单飞骨架仍保留
                            // （[maybeStartParsedColsNet]：就绪探测/修复路径，列已在时零成本直通）。
                            ensureParsedCols(d)
                            Log.d(TAG, "v8 caption 结构化：items + parsed_title/item_tags（瞬时加列）")
                        }
                        if (schemaVersion < 9) {
                            // R236 v2 tags 表：tags（chat+tag 聚合计数账本，R233 行星/尘埃带
                            // 排序源）+ tag_items（tag→items.rowid 元素级倒排，R222 时间轴/
                            // R234 尘埃带毫秒级召回源）。建表已在上文无条件幂等执行（空表
                            // 瞬时）；**存量聚合转 [maybeStartTagsBackfill] 后台 Job**
                            // （item_tags 已解析行→delta 入账，批 500 错峰断点续传）；
                            // v9 之后的解析事件（R228 回填 Job 继续跑/新爬 ingest）经
                            // writeParsedRowLocked delta 实时聚合，无需本 Job 重扫。
                            // 加速层不是替代：item_tags 列与 R228 回填 Job 零触碰。
                            Log.d(TAG, "v9 tags 表：tags/tag_items 就绪（存量聚合转后台 Job）")
                        }
                        if (schemaVersion < 10) {
                            // R262b 死档标记：meta 加 dead 列（瞬时加列，v2~v6 同款同步路径）。
                            // 一次性分诊（判定口径唯一出处 DeadChannelPolicy，SQL 是其落形）：
                            //  ①标死：exhausted=1 + mediaPass>=3（全相位清扫到底）+ 零条目
                            //    + 非采样 → dead=1（真·无媒体死档，快照实锤 101 个）；
                            //  ②复活：exhausted=1 + mediaPass<3（相位未走完=提前终态嫌疑）
                            //    + 低产<100 → 清 exhausted 回未到底队列重爬自证（快照实锤
                            //    3 个；≥100 条的 86 个相位未走完者是旧线性爬合法完成态，
                            //    不动——r262_notes.md 挂账声明）。
                            ensureMetaColumn(d, "dead", "INTEGER NOT NULL DEFAULT 0")
                            d.execSQL(
                                "UPDATE meta SET dead=1 WHERE exhausted=1 AND mediaPass>=3 AND sampled=0 " +
                                    "AND (SELECT COUNT(*) FROM items WHERE items.chatId = meta.chatId) = 0",
                            )
                            val markedDead = d.rawQuery("SELECT changes()", null).use { c ->
                                if (c.moveToFirst()) c.getInt(0) else 0
                            }
                            d.execSQL(
                                "UPDATE meta SET exhausted=0 WHERE exhausted=1 AND mediaPass<3 " +
                                    "AND (SELECT COUNT(*) FROM items WHERE items.chatId = meta.chatId) < 100",
                            )
                            val revived = d.rawQuery("SELECT changes()", null).use { c ->
                                if (c.moveToFirst()) c.getInt(0) else 0
                            }
                            Log.d(TAG, "v10 死档分诊：标死 $markedDead 个（7d 探活），复活重爬 $revived 个（提前终态嫌疑）")
                        }
                        if (schemaVersion < 11) {
                            // R266 v11 存储层合并：repost_refs/posts_view 已在上文无条件幂等
                            // 建（空表瞬时，零数据迁移）；idx_items_fuid 与存量收编全转后台
                            // （[maybeStartFuidIndexBuild]→[maybeStartMergeBackfill] 链式点火）。
                            // 裸 VACUUM 实施红线：items 是非 IPK 表，VACUUM 重排 rowid 会团灭
                            // tag_items/refs.canonRowid 对齐——任何压实只许显式保 rowid 重建。
                            Log.d(TAG, "v11 转发合并：repost_refs/posts_view 就绪，指纹索引与存量收编转后台单飞")
                        }
                        if (schemaVersion < 12) {
                            // R284 时效感知：meta 加 ttlSec 列（messageAutoDeleteTime 秒，
                            // 0=不自毁）。瞬时加列（v2~v6/v10 同款同步路径，与行数无关）；
                            // 存量行缺省 0=无时效语义=现状锚。真值由目录枚举 noteChatTtls
                            // 顺手落（零新增请求），随 5min 枚举拍自然铺齐。
                            // R286 补幂等守卫（R290 V1 实锤的降级重入雷：v13 库被 v12 码
                            // 开过会把 user_version 写回 12，再进新包时裸 ALTER 炸
                            // duplicate column=openDb 永久抛异常变砖——v8 ensureParsedCols
                            // 同款逐列探测-补列）。
                            ensureMetaColumn(d, "ttlSec", "INTEGER NOT NULL DEFAULT 0")
                            Log.d(TAG, "v12 时效感知：meta + ttlSec（自毁 TTL，枚举拍顺手落真值）")
                        }
                        if (schemaVersion < 13) {
                            // R286 活跃新鲜度：meta 加 lastMsgSec 列（服务器
                            // Chat.lastMessage.date 秒，0=未知）。瞬时加列同款同步路径；
                            // 存量行缺省 0=未知=活跃度闸直通现状锚。真值由目录枚举
                            // noteChatLastMessages 顺手落（只 UPDATE 已有行不建行——
                            // 防全量枚举频道灌成 untouched 污染缺口档，R284 建行教训）。
                            // 幂等守卫同 v12（降级重入不炸 duplicate column）。
                            ensureMetaColumn(d, "lastMsgSec", "INTEGER NOT NULL DEFAULT 0")
                            Log.d(TAG, "v13 活跃新鲜度：meta + lastMsgSec（服务器最新帖秒，枚举拍顺手落）")
                        }
                        if (schemaVersion < 14) {
                            // R292 封面 OCR 试点（T1a）：item_ocr 独立表已在上文无条件
                            // 幂等建（空表+索引=元数据级瞬时，v9/v11 同款先例）——OCR 文本
                            // 绝不混 items.caption（引擎换代可整表重建，主表零触碰）。
                            // 存量回填/并词位全转后台 Job（maybeStartCoverOcr* 族，
                            // 由 FeedOcrPart 接线点火，不在 openDb 自燃——引擎/网关/窗口
                            // 闸原料不在本类手里）。
                            Log.d(TAG, "v14 封面 OCR：item_ocr 表就绪（识别文本独立表，回填/并词位转后台 Job）")
                        }
                        Log.d(TAG, "索引库结构迁移：v$schemaVersion → v$DB_VERSION")
                    }
                    ensureParsedCols(d) // 新库 CREATE 已含列；旧库 v8 已加过——幂等探测置就绪位
                    // R292：OCR 已并词位旗标探测（applied=1 存在即置位——精确路复核的
                    // OCR 补丁臂开关；LIMIT 1 点查瞬时，试点期恒空=false=零开销直通）
                    ocrAppliedSeen = d.rawQuery(
                        "SELECT 1 FROM $ITEM_OCR_TABLE WHERE applied=1 LIMIT 1", null,
                    ).use { c -> c.moveToFirst() }
                    val fts5Capable = detectFts5(d)
                    useFts5 = fts5Capable && !healFtsRebuildPending // R289：fts 轻损重灌期间保持 LIKE 兜底
                    // R294 形态缝根治①：fts 轻损修复 DROP 后环回到此——重灌待办在也必须
                    // 把空表建回来（旧码只在 useFts5 建=后台重灌打「no such table」空靶，
                    // 待办卡死到进程死亡，下次打开空 fts+useFts5 置真=搜索静默全空定型）
                    if (useFts5 || healFtsRebuildPending) {
                        d.execSQL(CREATE_FTS_SQL)
                    }
                    if (!useFts5) {
                        // 重灌窗口期写入路径走 LIKE 分支要绑 tokens 列（FTS5 形态库也得补——
                        // 缺列=爬取写入炸）；重灌完成后该列闲置无害（老 SQLite 不可 DROP 列）
                        ensureTokensColumn(d)
                        Log.w(
                            TAG,
                            if (fts5Capable) {
                                "R289/R294 fts 重灌待办在途：搜索暂走 items.tokens 列 LIKE 兜底（重灌完成自动切回 FTS5）"
                            } else {
                                "本机 SQLite 未编译 FTS5，深度搜索降级为 items.tokens 列 LIKE 匹配（磁盘常驻，不破内存）"
                            },
                        )
                    }
                    // R294 形态缝根治②（r292 挂账收口）：fts 空表缝探测——fts5 可用 ∧
                    // items 非空 ∧ fts 空=重灌待办（轻损修复后进程死亡丢内存待办/旧版缝
                    // 遗留/LIKE 库迁到 FTS5 设备形态升级，三族同缝一并收口；判定
                    // [IndexHealPolicy.ftsRefillNeeded]）。MAX(rowid)+LIMIT 1 双点查
                    // 毫秒级；全新库（items 空）天然直通零误报
                    if (fts5Capable && !healFtsRebuildPending) {
                        val itemsMax = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                        }
                        val ftsEmpty = d.rawQuery("SELECT rowid FROM fts LIMIT 1", null).use { c -> !c.moveToFirst() }
                        if (IndexHealPolicy.ftsRefillNeeded(fts5Capable, true, itemsMax > 0, ftsEmpty)) {
                            healFtsRebuildPending = true
                            healFtsRefillViaTokensSql = hasTokensColumn(d) && hasNonEmptyTokens(d)
                            useFts5 = false // 重灌完成前保持 LIKE 兜底（绝不空 fts 静默全空）
                            ensureTokensColumn(d)
                            Log.w(
                                TAG,
                                "R294 fts 空表缝实锤：items=$itemsMax 行但 fts 空——登记后台重灌（源=" +
                                    (if (healFtsRefillViaTokensSql) "tokens 列直灌" else "换源现算") +
                                    "），完成前搜索走 LIKE 兜底",
                            )
                        }
                    }
                    d.execSQL("PRAGMA user_version=$DB_VERSION")
                    // R289 健康打点：探针+迁移全过——健康衰减窗外清连续计数/只读史
                    // （硬件恢复后第一次健康打开即自动解除只读回退）
                    healGuard = IndexHealPolicy.onHealthy(healGuard, healNowMs)
                    writeHealGuard(healGuard)
                    break // 建表/迁移/探针全过=健康出环
                } catch (e: Throwable) {
                    // ---------------- R289 自愈编排（判定真值 [IndexHealPolicy]；每层最多到此一次） ----------------
                    val healProbe = (e as? HealProbeException)?.probe
                    // R289b 错误分流（R290b V7 误报实锤铁律）：探针损坏命中 或 异常文案带
                    // 真损坏签名（malformed/corrupt/not a database）才进自愈；计划/用法错误
                    // （no query solution/no such index/duplicate column 等）与一切其他异常
                    // =原契约 close+throw——健康对象零 DROP 红线，计划错误绝不触发自愈
                    val damageSigned = (e as? android.database.SQLException)
                        ?.let { IndexHealPolicy.classifyDbError(it.message) == IndexHealPolicy.DbErrorClass.DAMAGE } == true
                    if (healProbe == null && !damageSigned) {
                        runCatching { d.close() }
                        throw e // 非损坏异常（OOM/计划错误等）不自愈——原契约上抛
                    }
                    if (healAttempts >= 1) {
                        runCatching { d.close() }
                        throw e // 每次打开最多自愈一次：修复后再炸=原契约上抛（防修复循环）
                    }
                    healAttempts++
                    val stage = healProbe?.stage ?: IndexHealPolicy.ProbeStage.SCHEMA
                    val damage = IndexHealPolicy.classify(stage)
                    healGuard = IndexHealPolicy.onCorruption(healGuard, healNowMs)
                    writeHealGuard(healGuard)
                    Log.e(
                        TAG,
                        "R289 索引库损坏实锤：层=$stage 对象=${healProbe?.culprit ?: "-"} 级=$damage " +
                            "连续=${healGuard.consecutiveCorruptions} 累计=${healGuard.totalCorruptions}",
                        e,
                    )
                    when (IndexHealPolicy.decide(damage, healGuard, healNowMs)) {
                        IndexHealPolicy.HealAction.READ_ONLY -> {
                            runCatching { d.close() }
                            val ro = SQLiteDatabase.openDatabase(
                                healDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                            )
                            healReadOnly = true
                            Log.w(TAG, "R289 连续损坏达顶转只读回退（${IndexHealPolicy.READONLY_COOLDOWN_MS / 3_600_000}h 冷却窗内不再重建）")
                            db = ro
                            return ro
                        }
                        IndexHealPolicy.HealAction.REBUILD_OBJECTS -> {
                            try {
                                healLightLocked(d, stage, healProbe?.culprit)
                            } catch (healErr: Throwable) {
                                // 修复本身失败（坏索引 DROP 不动等）——计数已记，本次放弃；
                                // 下次打开再试，连续达顶自然转只读（防线③收口，绝不死磕）
                                runCatching { d.close() }
                                throw healErr
                            }
                            healGuard = IndexHealPolicy.onHealed(healGuard, healNowMs)
                            writeHealGuard(healGuard)
                            // 环回重跑建表/迁移（修复落盘后幂等直出；大表索引登记转后台）
                        }
                        IndexHealPolicy.HealAction.HEAVY_REBUILD -> {
                            runCatching { d.close() }
                            healSalvageFrom = backupCorruptDb(healDbFile, healNowMs)
                            // 全新空库（openOrCreateDatabase 直出）；环回后 CREATE 族+迁移链出 v13 结构
                            d = SQLiteDatabase.openOrCreateDatabase(healDbFile.absolutePath, null)
                            healGuard = IndexHealPolicy.onHealed(healGuard, healNowMs)
                            writeHealGuard(healGuard)
                        }
                        IndexHealPolicy.HealAction.NONE -> throw e // 不可达（damage=NONE 不进编排）
                    }
                }
            }
            db = d
            // R289 重损修复收尾：meta 打捞（新库 schema 已就绪；能捞多少捞多少，
            // 爬取进度字段复位缺省让爬虫自然回填——打捞列白名单唯一出处
            // [IndexHealPolicy.SALVAGE_META_COLUMNS]）。用户数据红线：打捞只读坏档
            // meta 表，收藏/历史/设置/成品缓存一概不在操作域（isHealScopePath 锚定）
            healSalvageFrom?.let { salvageMetaFromCorruptDb(it, d) }
            maybeStartMigration(d) // 一次性 JSON→SQLite 迁移（后台 IO；期间搜索可用）
            maybeStartDateIndexBuild(d) // R202：时间索引后台单飞（空库瞬间/大库分钟级，绝不挡本次 open）
            maybeStartParsedColsNet(d) // R228：结构化列就绪复核/修复（后台单飞骨架；列已在零成本直通）
            maybeStartLegacyBackfill(d) // R228：存量 caption 解析回填（用户追加需求，错峰后台 Job）
            maybeStartTagsBackfill(d) // R236：存量 item_tags→tags/tag_items 聚合（v9，错峰后台 Job）
            maybeStartAlbumCaptionBackfill(d) // R238：存量相册成员 caption 列共享回填（错峰后台 Job）
            maybeStartChainBackfill(d) // R285：存量链式回填（链②相册共享+链④邻近批注重算，错峰后台 Job）
            maybeStartFuidIndexBuild(d) // R266：内容指纹索引后台单飞（建成后链式点火存量合并批迁）
            maybeStartImportMerge(d) // R287：多设备索引合并（有挂起合并包才跑；指纹索引未就绪则等链式回调）
            maybeStartHealRebuild(d) // R289：轻损修复登记的大表索引/fts 重灌（后台单飞，绝不挡本次 open）
            return d
        }
    }

    // ---------------- R289 索引库损坏自愈：探针/修复/打捞/守卫 IO（判定真值全在 [IndexHealPolicy]） ----------------

    /** 探针命中结果（病灶层+受损对象名；culprit=null=整层未定名） */
    private class HealProbe(val stage: IndexHealPolicy.ProbeStage, val culprit: String?)

    /** 探针命中异常（纯控制流——把病灶从 try 体送进 catch 自愈编排） */
    private class HealProbeException(val probe: HealProbe) : android.database.SQLException("R289 探针命中损坏")

    /**
     * 有界损坏探针（openDb 同步路径，毫秒级硬预算；判定/分级真值 [IndexHealPolicy]）。
     * 分层推进、任一层**真损坏签名**异常即返回病灶；全部通过=null（健康）。
     * 成本上界与库大小无关（点查穿透 B-tree，不是扫描）：L0/L1 微秒级；
     * L2 = MAX(rowid) + [IndexHealPolicy.ITEMS_SAMPLE_POINTS] 个均布 rowid 点查
     * （≈65 页读）；L3 每索引 ≤[IndexHealPolicy.INDEX_PROBE_PER_INDEX] 发 INDEXED BY
     * 穿刺；L4 每衍生表 MAX+4 采样点查。**全量 quick_check/integrity_check 绝不进
     * 首开路径**（6GB 库分钟级长扫=首开秒级红线）。
     * 全新库（无任何表）各层以 sqlite_master 对象清单存在为前提，天然直通不误报。
     * R289b 误报根治（R290b V7 实锤）：每层异常一律过 [probeLayer] 文案分流——
     * 计划/用法错误（no query solution/no such index 等）≠损坏，记日志跳过本层；
     * 只有 malformed/corrupt/not a database 真损坏签名才落病灶进自愈。
     */
    private fun probeIntegrityFast(d: SQLiteDatabase): HealProbe? {
        // L0 模式层：一次查询同时拿全量对象清单（schema 行数十余条，瞬时），供各层存在性闸
        val objects = HashSet<String>()
        probeLayer(IndexHealPolicy.ProbeStage.SCHEMA, null) {
            d.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','index')", null).use { c ->
                while (c.moveToNext()) objects += c.getString(0)
            }
        }?.let { return it }
        // L1 meta 主表（数百行全扫廉价；顺手取 chatId 样本供 L3 索引穿刺）
        val chatIds = ArrayList<Long>(8)
        if ("meta" in objects) {
            probeLayer(IndexHealPolicy.ProbeStage.META, "meta") {
                d.rawQuery("SELECT chatId FROM meta LIMIT 8", null).use { c ->
                    while (c.moveToNext()) chatIds += c.getLong(0)
                }
            }?.let { return it }
        }
        // L2 items 表体：MAX(rowid) 最右叶瞬时 + 均布采样页点查
        if ("items" in objects) {
            probeLayer(IndexHealPolicy.ProbeStage.ITEMS_BODY, "items") {
                val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                }
                for (r in IndexHealPolicy.sampleRowids(maxRowid)) {
                    d.rawQuery("SELECT rowid FROM items WHERE rowid >= ? LIMIT 1", arrayOf(r.toString())).use { it.moveToFirst() }
                }
            }?.let { return it }
        }
        // L3 二级索引穿刺（INDEXED BY 强走索引页——索引坏但表体好的典型场景；
        // SQL 唯一生成口 [IndexHealPolicy.indexProbePlans]=覆盖列 rowid+WHERE 前导列
        // 安全形状，R289b 前「无 WHERE+非覆盖」形状在 Android SQLite 3.32.2 恒抛
        // no query solution=健康索引误报根源）
        for ((indexName, sql) in IndexHealPolicy.indexProbePlans(INDEX_PROBE_SPECS, chatIds)) {
            if (indexName !in objects) continue // 缺席=后台单飞会建，跳过（缺失≠损坏）
            probeLayer(IndexHealPolicy.ProbeStage.SECONDARY_INDEX, indexName) {
                d.rawQuery(sql, null).use { it.moveToFirst() }
            }?.let { return it }
        }
        // L4 衍生层表体：tags/tag_items（可重聚）/repost_refs（可重建空表）——全轻损
        for (table in listOf("tags", "tag_items", REPOSTS_TABLE)) {
            if (table !in objects) continue
            probeLayer(IndexHealPolicy.ProbeStage.DERIVED, table) {
                val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM $table", null).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                }
                for (r in IndexHealPolicy.sampleRowids(maxRowid, 4)) {
                    d.rawQuery("SELECT rowid FROM $table WHERE rowid >= ? LIMIT 1", arrayOf(r.toString())).use { it.moveToFirst() }
                }
            }?.let { return it }
        }
        // L4 fts 虚表（contentless：LIMIT 1 穿一页；缺席=LIKE 设备/未建，跳过）
        if ("fts" in objects) {
            probeLayer(IndexHealPolicy.ProbeStage.DERIVED, "fts") {
                d.rawQuery("SELECT rowid FROM fts LIMIT 1", null).use { it.moveToFirst() }
            }?.let { return it }
        }
        return null
    }

    /**
     * 探针层执行统一口（R289b 错误分流唯一落形）：块内 SQLite 异常按
     * [IndexHealPolicy.classifyDbError] 分流——真损坏签名 → 病灶（进自愈编排）；
     * 计划/用法/未知错误 → 记日志**跳过本层不计损坏**（健康对象零 DROP 红线；
     * 漏检代价=维持 R289 前「靠祈祷」现状，远低于误报代价）。
     */
    private inline fun probeLayer(
        stage: IndexHealPolicy.ProbeStage,
        culprit: String?,
        block: () -> Unit,
    ): HealProbe? {
        try {
            block()
            return null
        } catch (e: android.database.SQLException) {
            if (IndexHealPolicy.classifyDbError(e.message) == IndexHealPolicy.DbErrorClass.DAMAGE) {
                return HealProbe(stage, culprit)
            }
            Log.w(TAG, "R289 探针层 $stage/${culprit ?: "-"} 遇非损坏错误（计划/用法类），跳过不计损坏：${e.message}")
            return null
        }
    }

    /**
     * 索引建/跳闸（openDb 建表段唯一口）：正常路径=CREATE IF NOT EXISTS 瞬时幂等
     * （健康库索引全在）。索引**缺失**且表行数上界（MAX(rowid) 瞬时估计）超
     * [IndexHealPolicy.SYNC_INDEX_BUILD_MAX_ROWS] → 同步建=分钟级长事务（首开秒级
     * 红线，R202 v7/R266 v11 同款纪律）→ 登记 [healPendingIndexes] 转后台单飞
     * [maybeStartHealRebuild]。本闸同时兜「轻损修复后进程死亡」的重启续建
     * （内存登记丢失，下次 openDb 探测缺失重登记——幂等无窗口）。
     */
    private fun createIndexUnlessPending(d: SQLiteDatabase, ddl: String, indexName: String) {
        if (indexName in healPendingIndexes) return
        if (!indexExistsByName(d, indexName) &&
            tableRowsUpperBound(d, tableForHealedIndex(indexName)) > IndexHealPolicy.SYNC_INDEX_BUILD_MAX_ROWS
        ) {
            healPendingIndexes += indexName
            Log.w(TAG, "R289 索引 $indexName 缺失且表行数超同步红线——转后台单飞重建（不堵首开）")
            return
        }
        d.execSQL(ddl)
    }

    /** sqlite_master 索引存在探测（µs 级） */
    private fun indexExistsByName(d: SQLiteDatabase, indexName: String): Boolean =
        d.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", arrayOf(indexName)).use { it.moveToFirst() }

    /** 表行数上界（MAX(rowid) 最右叶瞬时；含洞高估——闸口径保守方向正确） */
    private fun tableRowsUpperBound(d: SQLiteDatabase, table: String): Long =
        runCatching {
            d.rawQuery("SELECT MAX(rowid) FROM $table", null).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            }
        }.getOrDefault(0L)

    /** 索引名→所属表（行数上界探测用；键集=反射锚锁定清单） */
    private fun tableForHealedIndex(indexName: String): String = when {
        indexName.startsWith("idx_tag_items") -> "tag_items"
        indexName.startsWith("idx_tags_") -> "tags"
        indexName.startsWith("idx_refs_") -> REPOSTS_TABLE
        else -> "items" // idx_items_* 与未名防御
    }

    /**
     * 坏对象删除（轻损修复专用）：先正常 DROP；坏页会让 DROP 的页遍历炸（L1 彩排
     * 实锤：索引根页清零后 DROP INDEX 抛 malformed）→ 退 `PRAGMA writable_schema`
     * 删 schema 行（不遍历页——残留页成孤儿无害，体积由未来显式保 rowid 压实干线
     * 收口，R266 挂账同族）。sqlKeyword=DROP 语句关键字（TABLE/INDEX），
     * type=sqlite_master 的 type 值（table/index 小写）。
     */
    private fun dropBadObjectLocked(d: SQLiteDatabase, sqlKeyword: String, type: String, name: String) {
        try {
            d.execSQL("DROP $sqlKeyword IF EXISTS $name")
        } catch (e: android.database.SQLException) {
            d.execSQL("PRAGMA writable_schema=ON")
            d.compileStatement("DELETE FROM sqlite_master WHERE type=? AND name=?").apply {
                bindString(1, type)
                bindString(2, name)
            }.use { it.executeUpdateDelete() }
            // R294 F1 彩排实锤：虚表强删只删主行——FTS5 影子表（fts_data/fts_idx/…）
            // schema 行残留会让环回 CREATE VIRTUAL TABLE 撞「already exists」必炸
            // （修复失败→计数涨→三坏只读的事故链起点）。影子行连带强删（清单真值
            // [IndexHealPolicy.FTS5_SHADOW_SUFFIXES]；影子表数据页残留=孤儿页，
            // 体积由 R294 P3 压实收口——双挂账同根同源）
            if (type == "table") {
                for (suffix in IndexHealPolicy.FTS5_SHADOW_SUFFIXES) {
                    d.compileStatement("DELETE FROM sqlite_master WHERE type='table' AND name=?").apply {
                        bindString(1, name + suffix)
                    }.use { it.executeUpdateDelete() }
                }
            }
            d.execSQL("PRAGMA writable_schema=OFF")
            // bump schema cookie 强制全连接重读 schema（L1 彩排实锤：不 bump 时本连接
            // 缓存旧 schema，后续 CREATE INDEX IF NOT EXISTS 误判对象仍在=跳过真建，
            // 查询计划仍引用已删对象的坏页——修复假成功）
            val sv = d.rawQuery("PRAGMA schema_version", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            d.execSQL("PRAGMA schema_version=${sv + 1}")
            Log.w(TAG, "R289 坏对象 $name 正常 DROP 不动——writable_schema 删 schema 行收口（页残留孤儿无害，压实收口）")
        }
    }

    /**
     * 轻损修复（openDb catch 编排内调用；判定真值 [IndexHealPolicy.classify]=轻损）。
     * 只做元数据级瞬时操作（DROP 受损对象+登记），对象重建由环回建表段/后台单飞
     * 收口——**绝不**在此跑大表 CREATE INDEX/全量重灌（首开秒级红线）。
     */
    private fun healLightLocked(d: SQLiteDatabase, stage: IndexHealPolicy.ProbeStage, culprit: String?) {
        when (culprit) {
            "fts" -> {
                // fts 虚表坏：DROP（环回建表段 CREATE 空表）；contentless 无 rebuild
                // 原语——后台分批重灌；重灌完成前 useFts5=false（搜索自动走 LIKE 兜底，
                // 读路径零新分支）
                dropBadObjectLocked(d, "TABLE", "table", "fts")
                healFtsRebuildPending = true
                // R294 形态缝根治③：重灌源此刻定（环回 ensureTokensColumn 补列**之前**
                // 探测才是形态真值——补列后恒探到在场空列，FTS5 形态被污染成「快速 SQL
                // 灌 0 行」）；tokens 缺席/全空=换源现算（[IndexHealPolicy.ftsRefillSource]）
                healFtsRefillViaTokensSql = hasTokensColumn(d) && hasNonEmptyTokens(d)
                Log.w(
                    TAG,
                    "R289 轻损修复：fts 坏已 DROP 待重建空表，词位重灌转后台（源=" +
                        (if (healFtsRefillViaTokensSql) "tokens 列直灌" else "换源现算") +
                        "，期间搜索走 LIKE 兜底）",
                )
            }
            "tags", "tag_items" -> {
                // 衍生聚合表坏：DROP（环回建表段 CREATE 空表）+删聚合断点 → 存量聚合
                // Job 全量重扫自愈（delta 幂等，R236 v9r2 通道零改动复用）
                dropBadObjectLocked(d, "TABLE", "table", culprit)
                runCatching { File(storeDir, "tags_backfill_v9r2.json").delete() }
                runCatching { File(storeDir, "tags_backfill_v9r2.json.tmp").delete() }
                Log.w(TAG, "R289 轻损修复：衍生表 $culprit 坏已 DROP 待重建，tags 聚合断点已删=后台全量重扫")
            }
            REPOSTS_TABLE -> {
                // refs 表坏：DROP（环回建表段 CREATE 空表+四索引）。损失如实记录：
                // 被收编转发的发布侧副本索引丢失——canonical 行在 items 主表无恙，
                // 增量转发随爬取/头刷自然回补
                dropBadObjectLocked(d, "TABLE", "table", REPOSTS_TABLE)
                Log.w(TAG, "R289 轻损修复：repost_refs 坏已 DROP 待重建空表（副本索引损失；canonical 无恙，增量随爬取回补）")
            }
            else -> {
                // 二级索引坏：DROP（元数据级）→ 环回建表段 [createIndexUnlessPending]
                // 按行数闸决定同步瞬时建/后台单飞；date/fuid 两大索引由其既有专属
                // 单飞（探测缺失自动重建）收口，无需登记
                if (stage == IndexHealPolicy.ProbeStage.SECONDARY_INDEX && culprit != null) {
                    dropBadObjectLocked(d, "INDEX", "index", culprit)
                    Log.w(TAG, "R289 轻损修复：索引 $culprit 已 DROP，重建随建表段行数闸收口")
                } else {
                    Log.w(TAG, "R289 轻损修复：病灶层=$stage 无具名对象，直通建表段幂等自愈")
                }
            }
        }
    }

    /**
     * 重损修复第一步：坏档备份（三件套同改——WAL/SHM 残留会被当新库日志重放，
     * BackupManager 同口径）。改名失败=上抛（**绝不删除坏档**——不丢可救数据，
     * 留档即人工抢救通道）。只留最新 [IndexHealPolicy.MAX_CORRUPT_BACKUPS] 份
     * （GB 级体积红线，备份堆积会挤爆磁盘制造二次事故）；作用域自查双保险
     * （[IndexHealPolicy.isHealScopePath]——用户数据绝不在操作域）。
     */
    private fun backupCorruptDb(dbFile: File, nowMs: Long): File {
        val backup = File(storeDir, IndexHealPolicy.corruptBackupName(DB_NAME, nowMs))
        for (suffix in listOf("", "-wal", "-shm")) {
            val src = File(storeDir, DB_NAME + suffix)
            if (src.exists() && !src.renameTo(File(storeDir, backup.name + suffix))) {
                throw android.database.SQLException("R289 坏档备份改名失败：${src.name}（不丢可救数据——绝不删除原档）")
            }
        }
        val leftovers = storeDir.listFiles { f ->
            f.isFile && f.name.startsWith("$DB_NAME.corrupt-") && IndexHealPolicy.isHealScopePath(f.name)
        }.orEmpty().groupBy { it.name.removeSuffix("-wal").removeSuffix("-shm") }
        leftovers.keys.sortedDescending().drop(IndexHealPolicy.MAX_CORRUPT_BACKUPS).forEach { stem ->
            leftovers[stem]?.forEach { runCatching { it.delete() } }
        }
        Log.e(TAG, "R289 重损修复：坏档已备份为 ${backup.name}（保留最新 ${IndexHealPolicy.MAX_CORRUPT_BACKUPS} 份），重建空库+meta 打捞+爬虫自然回填")
        return backup
    }

    /**
     * 重损修复第二步：meta 打捞（坏档只读连接逐行读，能救多少救多少——中游异常
     * 保留已读行）。打捞列白名单 [IndexHealPolicy.SALVAGE_META_COLUMNS]：花名册
     * （chatId）+档位（tier）+调度提示（ttlSec/lastMsgSec）+覆盖口径（sampled）；
     * **爬取进度字段全部复位缺省**（cursor/exhausted/mediaPass/sweepCursor/imported/
     * dead/needDeepen）——空库挂 exhausted=1=永不再爬的假死，必须复位让爬虫自然
     * 回填。列级存在性探测（v9 库无 ttlSec/lastMsgSec/dead——ensureMetaColumn 同款
     * 缺席跳过纪律）。用户数据红线：只读坏档 meta 一表，他表他库一概不碰。
     */
    private fun salvageMetaFromCorruptDb(corruptFile: File, newDb: SQLiteDatabase) {
        var salvaged = 0
        runCatching {
            val bad = SQLiteDatabase.openDatabase(corruptFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val cols = HashSet<String>()
                bad.rawQuery("PRAGMA table_info(meta)", null).use { c ->
                    while (c.moveToNext()) cols += c.getString(1)
                }
                val wanted = IndexHealPolicy.SALVAGE_META_COLUMNS.filter { it in cols }
                if ("chatId" !in wanted) {
                    Log.w(TAG, "R289 meta 打捞：坏档 meta 无 chatId 列=无可救（新库从零自然爬）")
                    return
                }
                val rows = ArrayList<LongArray>()
                try {
                    bad.rawQuery("SELECT ${wanted.joinToString(",")} FROM meta", null).use { c ->
                        while (c.moveToNext()) {
                            rows += LongArray(wanted.size) { i -> if (c.isNull(i)) 0L else c.getLong(i) }
                        }
                    }
                } catch (e: android.database.SQLException) {
                    Log.w(TAG, "R289 meta 打捞中途断（坏档页损坏）：已读 ${rows.size} 行保留（能救多少救多少）")
                }
                synchronized(writeLock) {
                    newDb.beginTransaction()
                    try {
                        val sets = wanted.filter { it != "chatId" }
                        val sql = buildString {
                            append("INSERT OR IGNORE INTO meta(chatId")
                            if (sets.isNotEmpty()) append(",").append(sets.joinToString(","))
                            append(") VALUES(?")
                            repeat(sets.size) { append(",?") }
                            append(")")
                        }
                        val stmt = newDb.compileStatement(sql)
                        for (row in rows) {
                            stmt.clearBindings()
                            stmt.bindLong(1, row[wanted.indexOf("chatId")])
                            sets.forEachIndexed { i, col -> stmt.bindLong(i + 2, row[wanted.indexOf(col)]) }
                            stmt.executeInsert()
                        }
                        newDb.setTransactionSuccessful()
                    } finally {
                        newDb.endTransaction()
                    }
                }
                salvaged = rows.size
            } finally {
                runCatching { bad.close() }
            }
        }.onFailure { Log.w(TAG, "R289 meta 打捞失败（坏档不可读=零打捞，新库从零自然爬）", it) }
        Log.e(TAG, "R289 重损修复落盘：meta 打捞 $salvaged 行（花名册+档位/调度提示保留；爬取进度复位=爬虫自然回填）")
    }

    /** 自愈守卫状态文件（deepindex 目录——计数必须活在被重建的 search.db 之外） */
    private fun healGuardFile(): File = File(storeDir, HEAL_GUARD_FILE_NAME)

    /** 守卫状态读（正则抠字段，readBackfillState 同款；文件缺失/损坏=缺省零值直通） */
    private fun readHealGuard(): IndexHealPolicy.GuardState = runCatching {
        val f = healGuardFile()
        if (!f.isFile) return IndexHealPolicy.GuardState()
        val txt = f.readText()
        fun num(key: String): Long = Regex("\"$key\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L
        IndexHealPolicy.GuardState(
            consecutiveCorruptions = num("consecutiveCorruptions").toInt(),
            totalCorruptions = num("totalCorruptions").toInt(),
            lastCorruptionAtMs = num("lastCorruptionAtMs"),
            lastHealAtMs = num("lastHealAtMs"),
            lastHealthyAtMs = num("lastHealthyAtMs"),
            readOnlyUntilMs = num("readOnlyUntilMs"),
        )
    }.getOrDefault(IndexHealPolicy.GuardState())

    /** 守卫状态原子写（tmp+rename，R262b 33cf977 LMK 实锤同款——防半写文件毒化） */
    private fun writeHealGuard(s: IndexHealPolicy.GuardState) {
        runCatching {
            val tmp = File(storeDir, HEAL_GUARD_FILE_NAME + ".tmp")
            tmp.writeText(
                "{\"consecutiveCorruptions\":${s.consecutiveCorruptions}," +
                    "\"totalCorruptions\":${s.totalCorruptions}," +
                    "\"lastCorruptionAtMs\":${s.lastCorruptionAtMs}," +
                    "\"lastHealAtMs\":${s.lastHealAtMs}," +
                    "\"lastHealthyAtMs\":${s.lastHealthyAtMs}," +
                    "\"readOnlyUntilMs\":${s.readOnlyUntilMs}}",
            )
            if (!tmp.renameTo(healGuardFile())) {
                runCatching { healGuardFile().delete() }
                runCatching { tmp.renameTo(healGuardFile()) }
            }
        }
    }

    /** R289 轻损修复后台重建单飞点火（openDb 尾部；有待办才点，正常路径零成本直通） */
    private fun maybeStartHealRebuild(d: SQLiteDatabase) {
        if (healPendingIndexes.isEmpty() && !healFtsRebuildPending) return
        if (healRebuildStarted) return
        synchronized(dbLock) {
            if (healRebuildStarted) return
            healRebuildStarted = true
        }
        ioScope.launch { runHealRebuild(d) }
    }

    /**
     * 轻损修复后台重建本体（writeLock 串行，与爬虫/回填错峰同族纪律）：
     *  1. 登记索引逐个 CREATE（单条单事务原子——半途死亡无半成品，下次 openDb
     *     [createIndexUnlessPending] 探测缺失重登记，幂等可续建）；
     *  2. fts 重灌（R294 形态缝根治=双臂，源判定 [IndexHealPolicy.ftsRefillSource]）：
     *     - TOKENS_SQL（LIKE 形态库）：`INSERT INTO fts(rowid,tokens) SELECT …` 分批
     *       直灌（批 [HEAL_FTS_REBUILD_BATCH] 行+批间隔错峰，回填族同口径）；
     *     - RECOMPUTE（FTS5 形态库——items 无 tokens 列，旧码盲灌产空 fts=搜索静默
     *       全空，r292 挂账）：批读 (rowid,caption,channelTitle) → insert 同源配方
     *       [indexTokensOf] 现算词位（OCR 后缀被 ocrAppliedSeen 闸挡=试点期零成本）
     *       → 同事务批插 fts——读+算+插全程持 writeLock（防批间 caption 回填改词
     *       位原料，contentless 删除逐字节一致约束不被竞态污染）；
     *     完成前 useFts5=false=搜索走 LIKE 兜底，完成置回 true 切回 FTS5。
     *     失败不上抛（本进程保持兜底，下次修复重灌）。
     */
    private suspend fun runHealRebuild(d: SQLiteDatabase) {
        while (true) {
            val idx = healPendingIndexes.firstOrNull() ?: break
            val ddl = INDEX_DDL_BY_NAME[idx]
            if (ddl == null) {
                healPendingIndexes -= idx
                continue
            }
            try {
                synchronized(writeLock) { d.execSQL(ddl) }
                Log.w(TAG, "R289 后台重建索引 $idx 建成")
            } catch (e: Throwable) {
                Log.e(TAG, "R289 后台重建索引 $idx 失败（本进程不重试，下次 openDb 探测缺失重登记）", e)
            }
            healPendingIndexes -= idx
        }
        if (healFtsRebuildPending) {
            var lastRowid = 0L
            var filled = 0L
            try {
                if (healFtsRefillViaTokensSql) {
                    // 臂①tokens 列直灌（LIKE 形态库——列随写入填充真词位）
                    while (true) {
                        val n = synchronized(writeLock) {
                            d.execSQL(
                                "INSERT INTO fts(rowid, tokens) SELECT rowid, tokens FROM items " +
                                    "WHERE rowid > ? AND tokens != '' ORDER BY rowid LIMIT ?",
                                arrayOf(lastRowid, HEAL_FTS_REBUILD_BATCH),
                            )
                            val cnt = d.rawQuery("SELECT changes()", null).use { c ->
                                if (c.moveToFirst()) c.getLong(0) else 0L
                            }
                            d.rawQuery("SELECT MAX(rowid) FROM fts", null).use { c ->
                                if (c.moveToFirst() && !c.isNull(0)) lastRowid = c.getLong(0)
                            }
                            cnt
                        }
                        if (n <= 0L) break
                        filled += n
                        delay(HEAL_REBUILD_BATCH_GAP_MS) // 错峰（R228 回填族同口径）
                    }
                } else {
                    // 臂②换源现算（FTS5 形态库——r292 形态缝根治）：insert 同源配方现算
                    while (true) {
                        val n = synchronized(writeLock) {
                            val batch = ArrayList<Triple<Long, String, String>>(HEAL_FTS_REBUILD_BATCH)
                            d.rawQuery(
                                "SELECT rowid, caption, channelTitle FROM items " +
                                    "WHERE rowid > ? ORDER BY rowid LIMIT ?",
                                arrayOf(lastRowid.toString(), HEAL_FTS_REBUILD_BATCH.toString()),
                            ).use { c ->
                                while (c.moveToNext()) {
                                    batch += Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
                                }
                            }
                            if (batch.isEmpty()) {
                                0L
                            } else {
                                d.beginTransaction()
                                try {
                                    val st = stmtInsertFts
                                        ?: d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
                                            .also { stmtInsertFts = it }
                                    for ((r, caption, title) in batch) {
                                        // OCR 已并词位的行同源带上（applied=1 全局闸挡=试点期零查询成本）
                                        val ocr = if (ocrAppliedSeen) ocrAppliedTextLocked(d, r) else null
                                        st.clearBindings()
                                        st.bindLong(1, r)
                                        st.bindString(2, indexTokensOf(caption, title, ocr))
                                        st.executeInsert()
                                    }
                                    d.setTransactionSuccessful()
                                } finally {
                                    d.endTransaction()
                                }
                                lastRowid = batch.last().first
                                batch.size.toLong()
                            }
                        }
                        if (n <= 0L) break
                        filled += n
                        delay(HEAL_REBUILD_BATCH_GAP_MS)
                    }
                }
                healFtsRebuildPending = false
                useFts5 = true
                Log.w(TAG, "R289/R294 fts 重灌完成：$filled 行词位回灌（源=${if (healFtsRefillViaTokensSql) "tokens 列" else "现算"}），搜索切回 FTS5")
            } catch (e: Throwable) {
                Log.e(TAG, "R289 fts 重灌失败（已灌 $filled 行；本进程保持 LIKE 兜底，下次修复重灌）", e)
            }
        }
        healRebuildStarted = false // 单飞闸复位（后续新待办允许再点火）
    }

    /**
     * FTS5 可用性探测：首选 `PRAGMA compile_options` 直查（任务口径）；
     * 查不到再试建临时虚表实锤（双保险，防个别厂商编译选项串缺漏但模块实际可加载）。
     */
    private fun detectFts5(d: SQLiteDatabase): Boolean {
        runCatching {
            d.rawQuery("PRAGMA compile_options", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0)?.uppercase()?.contains("FTS5") == true) return true
                }
            }
        }
        return runCatching {
            d.execSQL("CREATE VIRTUAL TABLE temp.tgfeed_fts5_probe USING fts5(x)")
            d.execSQL("DROP TABLE temp.tgfeed_fts5_probe")
            true
        }.getOrDefault(false)
    }

    /**
     * items.tokens 列在场探测（FTS5 形态库从未建此列——R294 重灌源判定的形态真值
     * 探测口；[ensureTokensColumn] 同用）。**调用时机纪律**：判形态必须在
     * ensureTokensColumn 补列之前（补列后恒在场，真值被污染）。
     */
    private fun hasTokensColumn(d: SQLiteDatabase): Boolean =
        d.rawQuery("PRAGMA table_info(items)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == "tokens") { found = true; break }
            found
        }

    /**
     * tokens 列有真词位内容探测（`tokens != ''` LIMIT 1 点查；**前置**=列在场，
     * 否则 SQL 炸——调用方先过 [hasTokensColumn] 短路）。列在但全空=旧版缝补建的
     * 空列，快速 SQL 灌只会产空 fts——形态判定按缺席论（换源现算）。
     */
    private fun hasNonEmptyTokens(d: SQLiteDatabase): Boolean =
        d.rawQuery("SELECT 1 FROM items WHERE tokens != '' LIMIT 1", null).use { it.moveToFirst() }

    /** LIKE 兜底路径：items 表补 tokens 普通列（幂等；FTS5 路径不建此列，省一份词位串存储） */
    private fun ensureTokensColumn(d: SQLiteDatabase) {
        if (!hasTokensColumn(d)) d.execSQL("ALTER TABLE items ADD COLUMN tokens TEXT NOT NULL DEFAULT ''")
    }

    // ---------------- R294 P3 物理压实（显式保 rowid 重建换装；策略真值 [IndexCompactPolicy]） ----------------

    /** 压实单飞闸（用户触发路径防重入） */
    private val compactionRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 压实预估（设置页确认弹窗口径）：(自由页字节, 库总字节, auto_vacuum=FULL?)；
     * 库未建/探测失败=null。三个 PRAGMA 点查瞬时；判定 [IndexCompactPolicy.shouldCompact]。
     *
     * R294 彩排实锤的设备形态：Android 建库默认 **auto_vacuum=FULL**（snap2 快照实锤）
     * ——整页删除随每次 commit 自动收编截断，freelist 常态 ≈0。故自由页闸只作
     * 「建议」措辞；压实的残余价值=**页内密度重整**（散布删除留下的半空页，
     * auto_vacuum 收不动）+**孤儿页回收**（writable_schema 强删残留页不在 freelist，
     * auto_vacuum 永远够不着——R289 挂账②的唯一回收口）。
     */
    fun compactionEstimate(): Triple<Long, Long, Boolean>? {
        if (!File(storeDir, DB_NAME).exists()) return null
        return runCatching {
            val d = openDb()
            fun pragmaLong(name: String): Long =
                d.rawQuery("PRAGMA $name", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val pageSize = pragmaLong("page_size")
            Triple(
                pragmaLong("freelist_count") * pageSize,
                pragmaLong("page_count") * pageSize,
                pragmaLong("auto_vacuum") == 1L,
            )
        }.getOrNull()
    }

    /**
     * P3 物理压实（设置页用户触发；R266 设计 §4.1/§6.3 + R289 孤儿页挂账收口）。
     * **显式保 rowid 重建换装**（裸 VACUUM 是成文红线：items 是非 IPK 表，VACUUM
     * 可重排 rowid → tag_items.rowid_ref/refs.canonRowid/fts 回表对齐全灭——本函数
     * 是唯一合法压实路径，clearAll 的 VACUUM 仅限全清场景）：
     *  1. 闸：只读回退/单飞/自由页收益（[IndexCompactPolicy.shouldCompact]）/磁盘
     *     余量（enoughDisk）——不值得压/压不下=返回原因串，零副作用；
     *  2. **writeLock 全程持锁**（拷贝期写冻结=行集一致性前提；读路径 WAL 不堵。
     *     用户确认的维护操作，弹窗已明示「压实期间索引写入暂停、搜索短时降级」）；
     *  3. wal_checkpoint(TRUNCATE) 把 WAL 归并进主文件（ATTACH 侧读到完整内容）；
     *  4. 暂存库（journal=OFF 一次性件）重放 schema：**先建表**（CREATE_*_SQL 常量
     *     原样复用=零第二份 schema）→ ATTACH 主库为 src → 逐表
     *     [IndexCompactPolicy.copySql] 显式保 rowid 拷贝 → **后建全部索引**+视图
     *     （批量拷贝不养索引，索引一次成型）+fts 空表（FTS5 形态；contentless 虚表
     *     不可拷——换装后由 openDb 缝探测登记后台重灌收口，R294 任务②同引擎）；
     *  5. 换装前对账：逐表 COUNT/SUM(rowid)/MAX(rowid) 与 src 相等 + tag_items/refs/
     *     item_ocr 悬空计数与 src 同值——任一不符=删暂存中止（主库零触碰）；
     *  6. 换装（dbLock 内）：关主库句柄 → search.db 三件套改名 precompact → 暂存
     *     改名 search.db → openDb 重开全链（探针+迁移直通）——重开失败自动回滚换装；
     *  7. 成功收尾：删 precompact 备份（压实本意=回收磁盘，行集已对账不留旧档）。
     * 在飞读者说明（如实声明）：换装瞬间旧句柄上的在读查询吃句柄关闭异常（全仓读
     * 路径 runCatching 兜底=瞬时空结果，下次调用即新库）——维护窗口语义。
     * 合并批迁/导入合并断点（rowid 游标）天然兼容：逐行保 rowid=游标语义零漂移。
     */
    suspend fun compactIndexDbPreservingRowids(onProgress: (String) -> Unit): String {
        if (healWriteBlocked("compactIndexDb")) return "索引库只读回退中（连续损坏冷却期），压实已跳过"
        if (!compactionRunning.compareAndSet(false, true)) return "压实已在进行中"
        try {
            val d = openDb()
            return synchronized(writeLock) { compactLocked(d, onProgress) }
        } finally {
            compactionRunning.set(false)
        }
    }

    /** 压实本体（调用方持 writeLock）；返回报告串（脱敏：只出计数/字节），失败=原因串 */
    private fun compactLocked(d: SQLiteDatabase, onProgress: (String) -> Unit): String {
        fun pragmaLong(name: String): Long =
            d.rawQuery("PRAGMA $name", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val pageSize = pragmaLong("page_size")
        val totalBytes = pragmaLong("page_count") * pageSize
        val freeBytes = pragmaLong("freelist_count") * pageSize
        if (!IndexCompactPolicy.shouldCompact(freeBytes, totalBytes)) {
            return "无需压实：可回收自由页 ${freeBytes / 1_048_576}MB / 库 ${totalBytes / 1_048_576}MB" +
                "（阈值 ${IndexCompactPolicy.MIN_COMPACT_FREE_BYTES / 1_048_576}MB 且占比 ≥10%）"
        }
        val liveBytes = totalBytes - freeBytes
        val usable = File(storeDir, ".").usableSpace
        if (!IndexCompactPolicy.enoughDisk(usable, liveBytes)) {
            return "磁盘余量不足：压实瞬态需约 ${(liveBytes * 1.1).toLong() / 1_048_576}MB 可用，当前 ${usable / 1_048_576}MB——已跳过"
        }
        onProgress("压实：归并日志…")
        d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        val dbFile = File(storeDir, DB_NAME)
        val staging = File(storeDir, IndexCompactPolicy.COMPACT_STAGING_NAME)
        deleteCompactArtifacts(staging) // 上次半途残留（一次性件，成败都清）
        var s: SQLiteDatabase? = null
        val plan: List<IndexCompactPolicy.TableCopyPlan>
        try {
            s = SQLiteDatabase.openOrCreateDatabase(staging.absolutePath, null)
            // 一次性件：不要日志不要同步（半途死亡=整件丢弃重建；R287 暂存库同族纪律）
            s.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
            s.execSQL("PRAGMA synchronous=OFF")
            // 先建表（CREATE_*_SQL 常量原样复用——schema 唯一出处，零第二份）
            for (ddl in listOf(
                CREATE_ITEMS_SQL, CREATE_META_SQL, CREATE_TAGS_SQL,
                CREATE_TAG_ITEMS_SQL, CREATE_REPOSTS_SQL, CREATE_ITEM_OCR_SQL,
            )) {
                s.execSQL(ddl)
            }
            s.execSQL("ATTACH DATABASE '${dbFile.absolutePath.replace("'", "''")}' AS src")
            // 源形态探测（决定 items 是否带 tokens 列/老库缺表直通——hasRepostsTable 同族先例）
            val srcHasTokens = attachedHasColumn(s, "src", "items", "tokens")
            val srcHasReposts = attachedTableExists(s, "src", REPOSTS_TABLE)
            val srcHasOcr = attachedTableExists(s, "src", ITEM_OCR_TABLE)
            // LIKE 形态：tokens 列随 items 行整列拷走（ensureTokensColumn 同口径补列）；
            // FTS5 形态：建空 fts（contentless 不可拷——换装后缝探测登记后台重灌收口）
            if (srcHasTokens) s.execSQL("ALTER TABLE items ADD COLUMN tokens TEXT NOT NULL DEFAULT ''")
            if (useFts5) s.execSQL(CREATE_FTS_SQL)
            // 逐表显式保 rowid 拷贝
            plan = IndexCompactPolicy.copyPlan(srcHasTokens, srcHasReposts, srcHasOcr)
            for (p in plan) {
                onProgress("压实：拷贝 ${p.table}…")
                s.execSQL(IndexCompactPolicy.copySql(p))
            }
            // 后建全部索引+视图（批量拷贝不养索引，一次成型；大索引分钟级=持锁窗口
            // 的大头，弹窗已明示；date/fuid 两专属单飞探测到已存在自然直通）
            onProgress("压实：重建索引…")
            for (ddl in INDEX_DDL_BY_NAME.values) s.execSQL(ddl)
            s.execSQL(CREATE_ITEMS_DATE_INDEX_SQL)
            s.execSQL(CREATE_ITEMS_FUID_INDEX_SQL)
            s.execSQL(CREATE_ITEM_OCR_CHAT_INDEX_SQL)
            s.execSQL(CREATE_ITEM_OCR_APPLIED_INDEX_SQL)
            s.execSQL(CREATE_POSTS_VIEW_SQL)
            s.execSQL("PRAGMA user_version=$DB_VERSION")
            // 换装前对账（任一不符=中止，主库零触碰）
            onProgress("压实：对账…")
            val mismatch = StringBuilder()
            for (p in plan) {
                val srcSum = readChecksum(s, IndexCompactPolicy.tableChecksumSql(p.table, "src."))
                val newSum = readChecksum(s, IndexCompactPolicy.tableChecksumSql(p.table))
                if (srcSum != newSum) mismatch.append("${p.table}($srcSum≠$newSum);")
            }
            val danglingChecks = ArrayList<Pair<String, String>>()
            danglingChecks += "tag_items" to "rowid_ref"
            if (srcHasOcr) danglingChecks += ITEM_OCR_TABLE to "rowid"
            if (srcHasReposts) danglingChecks += REPOSTS_TABLE to "canonRowid"
            for ((table, refCol) in danglingChecks) {
                val srcD = readCount(s, IndexCompactPolicy.danglingCountSql(table, refCol, "src."))
                val newD = readCount(s, IndexCompactPolicy.danglingCountSql(table, refCol))
                if (srcD != newD) mismatch.append("$table.$refCol 悬空($srcD≠$newD);")
            }
            if (mismatch.isNotEmpty()) {
                s.execSQL("DETACH DATABASE src")
                s.close(); s = null
                deleteCompactArtifacts(staging)
                Log.e(TAG, "R294 压实中止：换装前对账不符——$mismatch（主库零触碰）")
                return "压实中止：对账不符（$mismatch）——主库零触碰"
            }
            s.execSQL("DETACH DATABASE src")
            s.close(); s = null
        } catch (e: Throwable) {
            runCatching { s?.close() }
            deleteCompactArtifacts(staging)
            Log.e(TAG, "R294 压实失败（暂存已清，主库零触碰）", e)
            return "压实失败（主库零触碰）：${e.message}"
        }
        // 换装（dbLock 内：关句柄→改名→重开探针；失败自动回滚）
        onProgress("压实：换装…")
        swapCompactedDbLocked(dbFile, staging, File(storeDir, IndexCompactPolicy.PRECOMPACT_BACKUP_NAME))
            ?.let { return it }
        val afterBytes = File(storeDir, DB_NAME).length()
        // FTS5 形态：换装后缝探测已登记词位后台重灌（RECOMPUTE 臂）——窗口明示
        val refillNote = if (healFtsRebuildPending) "；fts 词位后台重灌中（完成前搜索走 LIKE 兜底）" else ""
        val report = "压实完成：${totalBytes / 1_048_576}MB → ${afterBytes / 1_048_576}MB" +
            "（回收 ${(totalBytes - afterBytes) / 1_048_576}MB；${plan.size} 表行集对账全过）$refillNote"
        Log.w(TAG, "R294 $report")
        return report
    }

    /** 压实换装（dbLock 内原子段）：旧库→precompact 备份、暂存→主库、重开验证、失败回滚。null=成功 */
    private fun swapCompactedDbLocked(dbFile: File, staging: File, backup: File): String? {
        synchronized(dbLock) {
            // 关旧句柄（在飞读查询吃关闭异常=维护窗口语义，全仓读路径 runCatching 兜底）
            runCatching { db?.close() }
            db = null
            // 清旧备份残留
            for (suffix in arrayOf("", "-wal", "-shm")) {
                runCatching { File(storeDir, backup.name + suffix).delete() }
            }
            // 旧库三件套改名备份（WAL 已 TRUNCATE 归并，-wal/-shm 可能已不在场——在场才改）
            for (suffix in arrayOf("", "-wal", "-shm")) {
                val f = File(storeDir, DB_NAME + suffix)
                if (f.exists() && !f.renameTo(File(storeDir, backup.name + suffix))) {
                    rollbackCompactSwap(backup)
                    return "压实换装失败：旧库改名备份失败（已回滚，主库原样）"
                }
            }
            // 暂存改名主库（journal=OFF 无 -wal/-shm 旁件）
            if (!staging.renameTo(dbFile)) {
                rollbackCompactSwap(backup)
                return "压实换装失败：新库改名失败（已回滚旧库）"
            }
            // 重开验证（openDb 全链：有界探针+建表段幂等直通+缝探测/后台 Job 点火）
            try {
                openDb()
            } catch (e: Throwable) {
                Log.e(TAG, "R294 压实换装后重开失败——回滚旧库", e)
                runCatching { db?.close() }
                db = null
                for (suffix in arrayOf("", "-wal", "-shm")) {
                    runCatching { File(storeDir, DB_NAME + suffix).delete() }
                }
                rollbackCompactSwap(backup)
                runCatching { openDb() } // 旧库重开（尽力恢复服务）
                return "压实换装后重开失败（已回滚旧库）：${e.message}"
            }
            // 成功：删备份（压实本意=回收磁盘；行集对账已过闸，旧档不留）
            for (suffix in arrayOf("", "-wal", "-shm")) {
                runCatching { File(storeDir, backup.name + suffix).delete() }
            }
            return null
        }
    }

    /** 压实换装回滚：precompact 备份三件套改回 search.db（尽力而为，逐件 runCatching） */
    private fun rollbackCompactSwap(backup: File) {
        for (suffix in arrayOf("", "-wal", "-shm")) {
            val b = File(storeDir, backup.name + suffix)
            if (b.exists()) runCatching { b.renameTo(File(storeDir, DB_NAME + suffix)) }
        }
    }

    /** 压实暂存件清理（一次性件：成败都清；作用域=[IndexCompactPolicy.isCompactScopePath] 锚内） */
    private fun deleteCompactArtifacts(staging: File) {
        for (suffix in arrayOf("", "-wal", "-shm")) {
            runCatching { File(storeDir, staging.name + suffix).delete() }
        }
    }

    /** ATTACH 侧表在场探测（老库缺表直通——hasRepostsTable 同族先例） */
    private fun attachedTableExists(s: SQLiteDatabase, schema: String, table: String): Boolean =
        s.rawQuery(
            "SELECT 1 FROM $schema.sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    /** ATTACH 侧列在场探测（PRAGMA 支持 schema 前缀；LIKE/FTS5 形态分流原料） */
    private fun attachedHasColumn(s: SQLiteDatabase, schema: String, table: String, column: String): Boolean =
        s.rawQuery("PRAGMA $schema.table_info($table)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == column) { found = true; break }
            found
        }

    /** 对账读数：行集指纹三元组（COUNT/SUM(rowid)/MAX(rowid)） */
    private fun readChecksum(s: SQLiteDatabase, sql: String): Triple<Long, Long, Long> =
        s.rawQuery(sql, null).use { c ->
            if (c.moveToFirst()) Triple(c.getLong(0), c.getLong(1), c.getLong(2)) else Triple(0L, 0L, 0L)
        }

    /** 对账读数：悬空计数单值 */
    private fun readCount(s: SQLiteDatabase, sql: String): Long =
        s.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    // ---------------- 索引状态可见性（设置页/频道页在用，签名冻结） ----------------

    /** 已索引条数（供 UI 显示"已索引 N 条"）；DB COUNT 查询（chatId 索引命中），失败返回 0 */
    fun indexedCount(chatId: Long): Int =
        runCatching { countItems(openDb(), chatId) }.getOrDefault(0)

    /** 全局索引统计：（已建频道数, 总条目数）。供索引状态可见性（设置页）；失败返回 (0,0)。
     *  R266 v11 D6 诚实口径：总条目=items+refs 双表相加（含转发），与爬取上限
     *  TAIL_MAX_ITEMS 的频道发布口径一致 */
    fun totalStats(): Pair<Int, Int> = runCatching {
        val d = openDb()
        val chans = d.rawQuery("SELECT COUNT(*) FROM meta", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val items = d.rawQuery("SELECT COUNT(*) FROM items", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val refs = repostCount(d)
        chans to (items + refs)
    }.getOrDefault(0 to 0)

    /** v11 已收编转发条数（D6 设置页「含转发 M」诚实副标数据源；失败返回 0） */
    fun repostCount(): Int = runCatching { repostCount(openDb()) }.getOrDefault(0)

    private fun repostCount(d: SQLiteDatabase): Int =
        d.rawQuery("SELECT COUNT(*) FROM $REPOSTS_TABLE", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * 已爬取频道 id 全集（meta 表全部 chatId key；主键全表扫，毫秒级轻查询，失败返回空集）。
     *
     * 用途：发现页"已加入频道"硬过滤的第三集合。listChannels 的 GetChats 只返回
     * 已加载前缀（约 200 个），并非全量已加入；深度索引爬取覆盖的频道才是
     * "全量已加入"的更好近似——爬取完成前该集合单调增长、越来越严
     * （尽力而为的最严口径：未爬到的已加入频道仍可能漏过滤，反之不会误伤未加入频道）。
     */
    fun indexedChannelIds(): Set<Long> = runCatching {
        val d = openDb()
        d.rawQuery("SELECT chatId FROM meta", null).use { c ->
            val out = HashSet<Long>(c.count)
            while (c.moveToNext()) out += c.getLong(0)
            out
        }
    }.getOrDefault(emptySet())

    // ---------------- R163 智能分档：档位读写（meta.tier v6，档位评估在 B4 调度侧） ----------------

    /**
     * 写频道档位（R163 B3；B4 派工前评估落档的**唯一写入口**，幂等可重入）：
     * 行不存在（M1 场景——订阅了从未爬过的频道）按"碰过但没爬过"建行
     * （cursor=0/exhausted=0/未进清扫/未采样——与本地缓存白嫖建行同形态，
     * [untouchedChannelIds] 识别口径不受影响）；行已存在只改 tier，
     * 绝不动爬取断点（cursor/mediaPass/sweepCursor 断点语义不掺水）。
     *
     * R255 W1 配套·升档复活：触顶终态（exhausted=1，R255 触顶即出队）的频道在
     * 新档条数上限 > 现有条数时清 exhausted 回「未到底」队列（完成态≠爬取断点——
     * 复活只影响调度入队口径，断点原样续爬；cap 会随档位变化，终态不能是单向门。
     * 判定唯一出处 [CrawlThroughputPolicy.shouldReviveExhausted]；cap 取缺省
     * TieringConfig——B4 接线即缺省配置，口径一致）。真到底频道误复活 → sweep
     * 相位已全到底 → 一轮零请求 DONE 即刻回终态，复活成本一次派工，诚实可接受。
     */
    fun setTier(chatId: Long, tier: IndexTierPolicy.IndexTier) {
        if (healWriteBlocked("setTier")) return // R289 只读回退闸
        runCatching {
            val d = openDb()
            synchronized(writeLock) {
                d.beginTransaction()
                try {
                    // R255：复活判定原料先行读取（旧完成态；旧档位值仅日志用，UPDATE 覆盖式幂等）
                    var wasExhausted = false
                    var oldTier = Int.MIN_VALUE
                    d.rawQuery("SELECT tier, exhausted FROM meta WHERE chatId=?", arrayOf(chatId.toString())).use { c ->
                        if (c.moveToFirst()) {
                            oldTier = c.getInt(0)
                            wasExhausted = c.getInt(1) != 0
                        }
                    }
                    val ins = stmtMetaIgnore
                        ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                            .also { stmtMetaIgnore = it }
                    ins.clearBindings()
                    ins.bindLong(1, chatId)
                    ins.bindLong(2, 0L)
                    ins.bindLong(3, 0L)
                    ins.bindLong(4, 0L)
                    ins.executeInsert()
                    val upd = stmtMetaUpdateTier
                        ?: d.compileStatement("UPDATE meta SET tier=? WHERE chatId=?")
                            .also { stmtMetaUpdateTier = it }
                    upd.clearBindings()
                    upd.bindLong(1, tier.persisted.toLong())
                    upd.bindLong(2, chatId)
                    upd.executeUpdateDelete()
                    // R255 W1 配套：升档复活（B4 分档评估实时调用同款口径）
                    if (CrawlThroughputPolicy.shouldReviveExhausted(
                            wasExhausted,
                            countItems(d, chatId),
                            IndexTierPolicy.tierCap(tier, IndexTierPolicy.TieringConfig()),
                        )
                    ) {
                        val rev = stmtMetaRevive
                            ?: d.compileStatement("UPDATE meta SET exhausted=0, dead=0 WHERE chatId=?")
                                .also { stmtMetaRevive = it }
                        rev.clearBindings()
                        rev.bindLong(1, chatId)
                        rev.executeUpdateDelete()
                        Log.d(
                            TAG,
                            "R255 升档复活 chatId=$chatId $oldTier→${tier.persisted}" +
                                "（条数 < 新档上限，清完成态回未到底队列续爬）",
                        )
                    }
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }
        }.onFailure { Log.w(TAG, "档位写入失败（幂等，下轮重试）：chatId=$chatId ${it.message}") }
    }

    /** 读单频道档位（行缺失/读失败 = LEGACY——分档前口径，爬取参数与现状一致） */
    fun readTier(chatId: Long): IndexTierPolicy.IndexTier = runCatching {
        val d = openDb()
        d.rawQuery("SELECT tier FROM meta WHERE chatId=?", arrayOf(chatId.toString())).use { c ->
            if (c.moveToFirst()) IndexTierPolicy.IndexTier.fromPersisted(c.getInt(0))
            else IndexTierPolicy.IndexTier.LEGACY
        }
    }.getOrDefault(IndexTierPolicy.IndexTier.LEGACY)

    /** 全部已显式落档频道的档位表（B4 枚举拍批量读；LEGACY 行不在表=未分类，按 LEGACY 处理） */
    fun tiers(): Map<Long, IndexTierPolicy.IndexTier> = runCatching {
        val d = openDb()
        d.rawQuery("SELECT chatId, tier FROM meta WHERE tier != -1", null).use { c ->
            val out = HashMap<Long, IndexTierPolicy.IndexTier>()
            while (c.moveToNext()) out[c.getLong(0)] = IndexTierPolicy.IndexTier.fromPersisted(c.getInt(1))
            out
        }
    }.getOrDefault(emptyMap())

    // ---------------- R284 时效感知：自毁 TTL 落库（meta.ttlSec v12） ----------------

    /**
     * 频道自毁 TTL 批量落库（R284；调用方=FeedIndexPart 枚举拍，每 5min 一批）。
     * 真值来源=目录枚举 listSearchableChats 的 GetChat 响应现成字段
     * （Chat.messageAutoDeleteTime，javap 实证本 AAR 在场）——**零新增请求**。
     *
     * 写纪律（setTier 同款先例收口）：
     *  - 值变化才写（UPDATE 带 ttlSec<>? 守卫），返回变化条数供枚举拍日志；
     *  - ttl>0 且行不存在时按「碰过但没爬过」建行（断点全零——[untouchedChannelIds]
     *    识别口径不受影响，仍按未爬过档=prio 0，恰是自毁群想要的抢爬语义）；
     *  - ttl<=0 不建行（与列缺省同义，不为零信息膨胀 meta）；已有行的 TTL 撤销
     *    （>0→0，群关掉自毁）照常落——撤销即解除时效提权；
     *  - 绝不动爬取断点（cursor/mediaPass/sweepCursor/tier/dead 零触碰）。
     *
     * 语句局部编译（非热路径——每枚举拍一批数百行单事务，毫秒级；不入 stmt 缓存族）。
     */
    fun noteChatTtls(ttls: Map<Long, Int>): Int = runCatching {
        if (ttls.isEmpty()) return 0
        if (healWriteBlocked("noteChatTtls")) return 0 // R289 只读回退闸
        val d = openDb()
        var changed = 0
        synchronized(writeLock) {
            val ins = d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
            val upd = d.compileStatement("UPDATE meta SET ttlSec=? WHERE chatId=? AND ttlSec<>?")
            try {
                d.beginTransaction()
                try {
                    for ((chatId, rawTtl) in ttls) {
                        val ttl = rawTtl.coerceAtLeast(0) // 负值防御（TDLib 口径 0=不自毁）
                        if (ttl > 0) {
                            ins.clearBindings()
                            ins.bindLong(1, chatId)
                            ins.bindLong(2, 0L)
                            ins.bindLong(3, 0L)
                            ins.bindLong(4, 0L)
                            ins.executeInsert()
                        }
                        upd.clearBindings()
                        upd.bindLong(1, ttl.toLong())
                        upd.bindLong(2, chatId)
                        upd.bindLong(3, ttl.toLong())
                        changed += upd.executeUpdateDelete()
                    }
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            } finally {
                runCatching { ins.close() }
                runCatching { upd.close() }
            }
        }
        changed
    }.getOrDefault(0)

    /** 自毁频道（ttlSec>0）的 TTL 表（调度排序/诊断用；主键全表扫毫秒级，失败返回空表） */
    fun chatTtls(): Map<Long, Int> = runCatching {
        val d = openDb()
        d.rawQuery("SELECT chatId, ttlSec FROM meta WHERE ttlSec > 0", null).use { c ->
            val out = HashMap<Long, Int>(c.count)
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
            out
        }
    }.getOrDefault(emptyMap())

    // ---------------- R286 活跃新鲜度：服务器最新帖秒落库（meta.lastMsgSec v13） ----------------

    /**
     * 频道服务器最新帖时刻批量落库（R286；调用方=FeedIndexPart 枚举拍，与
     * [noteChatTtls] 同拍）。真值来源=目录枚举 listSearchableChats 的 GetChat
     * 响应现成字段（Chat.lastMessage.date，R284 起 ChannelBrief 已携带）——
     * **零新增请求**。
     *
     * 写纪律（与 noteChatTtls 同款收口，一处关键差异）：
     *  - 值变化才写（UPDATE 带 lastMsgSec<>? 守卫），返回变化条数供枚举拍日志；
     *  - **只 UPDATE 已有行，绝不建行**——lastMsgSec 对几乎所有枚举频道都非零，
     *    建行会把全量枚举频道灌成「碰过没爬过」（untouched 口径=缺口档 0），
     *    频道宇宙瞬间膨胀数倍（R284「ttl>0 才建行」教训的正面规避）；新频道的
     *    lastMsgSec 在其 meta 行被爬取/白嫖建立后的下一拍自然补齐（≤5min 迟滞，
     *    首次爬取本就不走头刷节流，零语义损失）；
     *  - 绝不动爬取断点（cursor/mediaPass/sweepCursor/tier/dead/ttlSec 零触碰）。
     */
    fun noteChatLastMessages(lastMsg: Map<Long, Long>): Int = runCatching {
        if (lastMsg.isEmpty()) return 0
        if (healWriteBlocked("noteChatLastMessages")) return 0 // R289 只读回退闸
        val d = openDb()
        var changed = 0
        synchronized(writeLock) {
            val upd = d.compileStatement("UPDATE meta SET lastMsgSec=? WHERE chatId=? AND lastMsgSec<>?")
            try {
                d.beginTransaction()
                try {
                    for ((chatId, rawSec) in lastMsg) {
                        val sec = rawSec.coerceAtLeast(0L) // 负值防御（0=未知）
                        upd.clearBindings()
                        upd.bindLong(1, sec)
                        upd.bindLong(2, chatId)
                        upd.bindLong(3, sec)
                        changed += upd.executeUpdateDelete()
                    }
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            } finally {
                runCatching { upd.close() }
            }
        }
        changed
    }.getOrDefault(0)

    /**
     * R286 索引新鲜度聚合诊断（枚举拍日志/设置页观测用；纯读，失败返回 null）：
     * 逐已到底频道算应有头刷间隔（tier→dead→ttl→活跃度四刀同一公式链）+
     * 相关子查询取索引头（idx_items_chat_msg 点查 µs 级/频道，数百行毫秒级），
     * 按 [CrawlThroughputPolicy.isFreshnessLagging] 判滞后。
     * [active] = 服务器最新帖 ≤24h 的频道数；[lagging] = 滞后告警线之上频道数；
     * [worstLagSec] = 最大（服务器最新帖−索引头）gap 秒。**请在 IO 线程调用**。
     */
    data class FreshnessStats(val tracked: Int, val active: Int, val lagging: Int, val worstLagSec: Long)

    fun freshnessStats(nowMs: Long, tiering: IndexTierPolicy.TieringConfig): FreshnessStats? = runCatching {
        val d = openDb()
        val nowSec = nowMs / 1_000L
        var tracked = 0
        var active = 0
        var lagging = 0
        var worst = 0L
        d.rawQuery(
            "SELECT m.tier, m.dead, m.ttlSec, m.lastMsgSec, " +
                "(SELECT i.dateEpochSec FROM items i WHERE i.chatId = m.chatId " +
                "ORDER BY i.messageId DESC LIMIT 1) FROM meta m WHERE m.exhausted=1",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                tracked++
                val tier = CrawlThroughputPolicy.effectiveTier(c.getInt(0), tiering.enabled)
                val base = DeadChannelPolicy.headRefreshIntervalMs(
                    c.getInt(1) != 0, IndexTierPolicy.headRefreshIntervalMs(tier, tiering),
                )
                val intervalMs = CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(
                    CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(base, c.getInt(2)),
                    c.getLong(3), nowSec,
                )
                val lastMsgSec = c.getLong(3)
                val headSec = if (c.isNull(4)) 0L else c.getLong(4)
                if (lastMsgSec > 0L && nowSec - lastMsgSec <= CrawlThroughputPolicy.ACTIVITY_ACTIVE_WINDOW_SEC) active++
                if (lastMsgSec > 0L && headSec > 0L) {
                    val gap = lastMsgSec - headSec
                    if (gap > worst) worst = gap
                    // 间隔 Long.MAX_VALUE（T3 永不头刷）折算秒防御溢出
                    val expectedSec = if (intervalMs == Long.MAX_VALUE) Long.MAX_VALUE else intervalMs / 1_000L
                    if (CrawlThroughputPolicy.isFreshnessLagging(headSec, lastMsgSec, expectedSec)) lagging++
                }
            }
        }
        FreshnessStats(tracked, active, lagging, worst)
    }.getOrNull()

    /**
     * 未爬到底的频道集合（meta 行存在且 exhausted=0；含浅爬待加深的）。
     * 供自动索引调度排序：尾部续爬优先于纯头部刷新（未到底的先爬，已到底的排后）。
     * 主键全表扫，毫秒级轻查询，失败返回空集。
     */
    fun unfinishedChannelIds(): Set<Long> = runCatching {
        val d = openDb()
        d.rawQuery("SELECT chatId FROM meta WHERE exhausted=0", null).use { c ->
            val out = HashSet<Long>(c.count)
            while (c.moveToNext()) out += c.getLong(0)
            out
        }
    }.getOrDefault(emptySet())

    /**
     * 头部刷新到期的已到底频道集合（R45 节流配套，调度侧入队预筛）：
     * 未到期的已到底频道根本不入爬取队列，连"派工→进 indexChannel→被节流跳过"
     * 的空转都省掉。lastHeadRefreshTs=0（从未刷过）视为到期。失败返回空集
     * （宁可漏一轮，不放大请求）。
     *
     * R163 B3 档位化：分档开启时头刷节流按档位差异化（T0 1h / T1·T2 6h /
     * LEGACY·T3 现状 6h——见 [IndexTierPolicy.headRefreshIntervalMs]）；分档关
     * （[tiering] 缺省）与原单一阈值逐项相等（纪律①——逐行判定在 ttlSec=0
     * 全覆盖时与原 SQL 同集合，effectiveTier(LEGACY) 参数=现状锚）。
     *
     * R262b 死档降频：dead=1 频道（无媒体死档）头刷间隔降为 7 天探活
     * （[DeadChannelPolicy.DEAD_PROBE_INTERVAL_MS]）——101 个死档从「每天 4 次
     * 空刷」降为「每周 1 次探活」，探到新鲜由 persistPage 的 fresh>0 钩自动复活。
     *
     * R284 时效覆盖闸（与分档正交的独立加速维度）：自毁频道（ttlSec>0）一轮覆盖
     * 周期 ≤ TTL/[CrawlThroughputPolicy.TTL_COVERAGE_DIVISOR]（7 天自毁→3.5 天
     * 一覆盖，别真等到第七天；与档位/死档基准取 min，钳制唯一出处
     * [CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs]；ttlSec=0 直通=现状锚）。
     * R284 起两分档分支统一逐行评估（meta 行数百级，枚举拍 5min 一次，毫秒级）。
     *
     * R286 活跃新鲜度闸（第四刀 min 钳，与 tier/dead/ttl 正交）：服务器最新帖
     * 距今 ≤1h 热→30min / ≤24h 活跃→2h（钳制唯一出处
     * [CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs]；lastMsgSec=0
     * 直通=现状锚）——治「头刷间隔长的频道新内容进索引慢」。
     */
    fun headRefreshDueChannelIds(
        nowMs: Long,
        tiering: IndexTierPolicy.TieringConfig = IndexTierPolicy.TieringConfig(),
    ): Set<Long> = runCatching {
        val d = openDb()
        d.rawQuery("SELECT chatId, tier, lastHeadRefreshTs, dead, ttlSec, lastMsgSec FROM meta WHERE exhausted=1", null).use { c ->
            val nowSec = nowMs / 1_000L
            val out = HashSet<Long>(c.count)
            while (c.moveToNext()) {
                // R255 W2 同款档位闸：分档关恒 LEGACY（不读落库档位防泄漏）
                val tier = CrawlThroughputPolicy.effectiveTier(c.getInt(1), tiering.enabled)
                val dead = c.getInt(3) != 0
                val base = DeadChannelPolicy.headRefreshIntervalMs(
                    dead, IndexTierPolicy.headRefreshIntervalMs(tier, tiering),
                )
                // R284：时效覆盖闸钳制（ttlSec=0 直通）
                val ttlClamped = CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(base, c.getInt(4))
                // R286：活跃新鲜度闸再钳一刀（lastMsgSec=0 直通）——两闸同公式零漂移
                val interval = CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(
                    ttlClamped, c.getLong(5), nowSec,
                )
                if (nowMs - c.getLong(2) >= interval) out += c.getLong(0)
            }
            out
        }
    }.getOrDefault(emptySet())

    /**
     * "碰过但还没真正爬过"的频道集合（R46 方案 b 配套）：本地缓存白嫖可能先于正式
     * 爬取建出 meta 行（cursor=0 / exhausted=0 / 未进清扫相位 / 非采样）——调度侧
     * 须把这类行当"未爬过"档（缺口优先 0 档），否则白嫖建行反而把频道从"未爬过"
     * 降档成"未到底"，用户浏览过的频道反而爬得更晚。失败返回空集。
     */
    fun untouchedChannelIds(): Set<Long> = runCatching {
        val d = openDb()
        d.rawQuery(
            "SELECT chatId FROM meta WHERE cursor=0 AND exhausted=0 AND mediaPass=-1 AND sampled=0",
            null,
        ).use { c ->
            val out = HashSet<Long>(c.count)
            while (c.moveToNext()) out += c.getLong(0)
            out
        }
    }.getOrDefault(emptySet())

    /**
     * 采样覆盖频道数（R46 口径诚实配套）：设置页"已索引 N 个（含按月采样 S 个）"展示，
     * 用户能区分全量/采样覆盖。失败返回 0。
     */
    fun sampledChannelCount(): Int = runCatching {
        val d = openDb()
        d.rawQuery("SELECT COUNT(*) FROM meta WHERE sampled=1", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.getOrDefault(0)

    /**
     * 单频道 items 聚合统计（R163 B4 丰度分原料；只读 SQL，IO 协程毫秒级）：
     * [spanSec] = 最老-最新条目发布秒差（密度分母）；[avgCaptionLen] = **非空**
     * caption 的平均长度（空 caption 不拖低信息量口径）；[sampleCaptions] = 最新
     * [captionSampleLimit] 条非空 caption（剧集规整率 parseEpisodeMark 样本——
     * 最新优先：分档关心的是当前产出质量不是三年前的）。
     * 返回 null = 该频道零条目（未探针——probed=false 语义由调用方折算）。
     */
    data class ChannelIndexStats(
        val items: Int,
        val spanSec: Long,
        val videoShare: Double,
        val captionNonEmptyRate: Double,
        val avgCaptionLen: Double,
        val sampleCaptions: List<String>,
    )

    /** 单频道 items 聚合（R163 B4；mediaType=0 = 视频——与 FeedPrefetchPart 预载过滤同口径） */
    fun channelIndexStats(chatId: Long, captionSampleLimit: Int = RICHNESS_CAPTION_SAMPLE): ChannelIndexStats? =
        runCatching {
            val d = openDb()
            // R266 v11 双臂聚合（D6 含转发口径）：items 臂 + refs 臂（caption 覆盖列
            // NULL 的经 LEFT JOIN canonical 补——丰度口径与原状一致）；COUNT/AVG 按
            // 行数加权合并，span 取两臂 MIN/MAX 极值。
            var itemsN = 0
            var minD = Long.MAX_VALUE
            var maxD = Long.MIN_VALUE
            var videoSum = 0.0
            var capRateSum = 0.0
            var capLenSum = 0.0
            d.rawQuery(
                "SELECT COUNT(*), MIN(dateEpochSec), MAX(dateEpochSec), " +
                    "IFNULL(SUM(CASE WHEN mediaType=0 THEN 1.0 ELSE 0.0 END), 0.0), " +
                    "IFNULL(SUM(CASE WHEN caption != '' THEN 1.0 ELSE 0.0 END), 0.0), " +
                    "IFNULL(SUM(CASE WHEN caption != '' THEN LENGTH(caption) END), 0.0) " +
                    "FROM items WHERE chatId=?",
                arrayOf(chatId.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    itemsN = c.getInt(0)
                    if (itemsN > 0) {
                        minD = minOf(minD, c.getLong(1))
                        maxD = maxOf(maxD, c.getLong(2))
                    }
                    videoSum = c.getDouble(3)
                    capRateSum = c.getDouble(4)
                    capLenSum = c.getDouble(5)
                }
            }
            d.rawQuery(
                "SELECT COUNT(*), MIN(r.dateEpochSec), MAX(r.dateEpochSec), " +
                    "IFNULL(SUM(CASE WHEN r.mediaType=0 THEN 1.0 ELSE 0.0 END), 0.0), " +
                    "IFNULL(SUM(CASE WHEN IFNULL(r.caption, i.caption) != '' THEN 1.0 ELSE 0.0 END), 0.0), " +
                    "IFNULL(SUM(CASE WHEN IFNULL(r.caption, i.caption) != '' THEN LENGTH(IFNULL(r.caption, i.caption)) END), 0.0) " +
                    "FROM $REPOSTS_TABLE r LEFT JOIN items i ON i.rowid = r.canonRowid WHERE r.chatId=?",
                arrayOf(chatId.toString()),
            ).use { c ->
                if (c.moveToFirst()) {
                    val refsN = c.getInt(0)
                    if (refsN > 0) {
                        minD = minOf(minD, c.getLong(1))
                        maxD = maxOf(maxD, c.getLong(2))
                        videoSum += c.getDouble(3)
                        capRateSum += c.getDouble(4)
                        capLenSum += c.getDouble(5)
                        itemsN += refsN
                    }
                }
            }
            if (itemsN <= 0) return@runCatching null
            // caption 样本双臂（各取 LIMIT 后按 messageId DESC 归并截样本上限——
            // refs 臂 caption=覆盖列 ?: canonical caption）
            val capRows = ArrayList<Pair<Long, String>>(captionSampleLimit.coerceAtMost(64) * 2)
            d.rawQuery(
                "SELECT messageId, caption FROM items WHERE chatId=? AND caption != '' " +
                    "ORDER BY messageId DESC LIMIT ?",
                arrayOf(chatId.toString(), captionSampleLimit.toString()),
            ).use { c ->
                while (c.moveToNext()) capRows += c.getLong(0) to (c.getString(1) ?: "")
            }
            d.rawQuery(
                "SELECT r.messageId, IFNULL(r.caption, i.caption) FROM $REPOSTS_TABLE r " +
                    "LEFT JOIN items i ON i.rowid = r.canonRowid " +
                    "WHERE r.chatId=? AND IFNULL(r.caption, i.caption) != '' ORDER BY r.messageId DESC LIMIT ?",
                arrayOf(chatId.toString(), captionSampleLimit.toString()),
            ).use { c ->
                while (c.moveToNext()) capRows += c.getLong(0) to (c.getString(1) ?: "")
            }
            val capMerged = capRows.sortedByDescending { it.first }
                .take(captionSampleLimit)
                .map { it.second }
            ChannelIndexStats(
                itemsN,
                if (maxD >= minD) maxD - minD else 0L,
                videoSum / itemsN,
                capRateSum / itemsN,
                if (capRateSum > 0) capLenSum / capRateSum else 0.0,
                capMerged,
            )
        }.getOrNull()

    /**
     * 读某频道全部已索引条目（messageId 新→旧序），供频道主页首帧直出
     * （FeedViewModel.loadChannelIndexedItems）。DB 查询（chatId 索引命中），
     * **请在 IO 线程调用**；索引未建返回空表。
     * R266 v11：两段法（items 臂 + refs 臂归并，D3 频道页照常显示转发条目）——
     * refs 行按 canonRowid 批量补内容侧合成，caption 覆盖列忠实保留（D2）。
     */
    fun channelItems(chatId: Long): List<VideoItem> {
        val d = openDb()
        return channelItemsTwoArm(d, chatId, Int.MAX_VALUE, 0)
    }

    /**
     * R212：读某频道索引条目的一页（messageId 新→旧序，**LIMIT+OFFSET 有界**）——
     * 频道主页无限滚动的深索引分页供给。
     *
     * 性能口径（任务书「有界查询」）：WHERE chatId 命中 idx_items_chat_msg
     * (chatId, messageId DESC)——先定位频道区间再倒序走，**回表行数恒 ≤ [limit]**，
     * 与频道总量无关（channelHeadRows 同款纪律：OFFSET 只在索引区间内跳，不做全表扫）。
     * 怪兽频道（数万条）逐页供给与小型频道全量直出的耗时同数量级。
     * 配套总数走 [indexedCount]（COUNT 在索引区间上聚合，毫秒级）。
     * **请在 IO 线程调用**；索引未建/参数越界返回空表。
     */
    fun channelItemsPage(chatId: Long, limit: Int, offset: Int): List<VideoItem> {
        if (limit <= 0 || offset < 0) return emptyList()
        val d = openDb()
        // R266 v11 两段法：两臂各自索引有界（LIMIT offset+limit）→ 归并 → refs 合成
        return channelItemsTwoArm(d, chatId, limit, offset)
    }

    /**
     * R253①：读某频道「本地钟点小时」的索引条目一页（messageId 新→旧序，LIMIT+OFFSET
     * 有界）——星图信息卡 24h 热力条点选后的「时段代表作」供给（点 22 时格=取 22:00~
     * 23:00 发布的条目）。
     *
     * 小时口径与信息卡热力条直方图同一式：`(dateEpochSec+tzOffsetSec)/3600%24`——tz 偏移
     * 秒由调用侧传入（与直方图装载同一出处，**所见即所得**：热力格 N 条=同一谓词同序取数）。
     * 性能口径同 [channelItemsPage]：WHERE chatId 命中 idx_items_chat_msg 前缀（messageId
     * 倒序走），小时谓词逐行判定（回表读 dateEpochSec）；峰时段命中密集=索引序早停，
     * 稀小时段=频道索引区间一趟（与既有频道域 GROUP BY 聚合同款成本类，R183「绝不
     * 全表扫」不破——恒 ≤ 频道自身区间）。
     * **请在 IO 线程调用**；hour 域外（0..23）/参数越界返回空表。
     */
    fun channelItemsByHourPage(
        chatId: Long,
        hour0to23: Int,
        tzOffsetSec: Int,
        limit: Int,
        offset: Int,
    ): List<VideoItem> {
        // 24=ChannelEraPolicy.HOURS_PER_DAY 口径（core.feed 不引 core.tastemap 依赖，注释锚定）
        if (limit <= 0 || offset < 0 || hour0to23 !in 0 until 24) return emptyList()
        val d = openDb()
        // R266 v11 两段法：小时谓词两臂同施（refs.dateEpochSec=本频道发布时刻，
        // 与直方图「所见即所得」口径同式——refs 臂命中 idx_refs_chat_msg 前缀逐行判定）。
        // R262b C-① 收编（本函数已被两段法重写，修复一并落形）：等值右值 CAST 数值化
        // ——字符串绑定下 '23' 是 TEXT、两侧皆无亲和性 → 整数=文本比较恒假=时段窗恒空
        return channelItemsTwoArm(
            d, chatId, limit, offset,
            extraPredicate = HOUR_WINDOW_PREDICATE_SQL,
            extraArgs = listOf(tzOffsetSec.toString(), hour0to23.toString()),
        )
    }

    // ---------------- R236 v9 tags 表查询（只加不改；全部索引导航毫秒级，零全表扫） ----------------

    /**
     * 查询 tag 的折叠归一键（去 # 前缀 + [SearchTextKit.fold]——与 R228 searchHashtag
     * 比对、CaptionParser 提取去重同一把尺子，三方同源）。空键=无意义查询。
     */
    private fun tagKeyOf(tag: String): String {
        val f = SearchTextKit.fold(tag.trim().trimStart('#', '＃'))
        return f.takeIf { it.isNotEmpty() } ?: ""
    }

    /**
     * R236：该 tag 的频道账本（chatId → 条数），按条数降序（chatId 升序破平——
     * 确定性序）。R234 尘埃带/传播频道的毫秒级数据源（idx_tags_tag 前缀导航，
     * 与全网 tag 基数无关）。**请在 IO 线程调用**。
     */
    fun tagChannels(tag: String): List<Pair<Long, Int>> {
        val key = tagKeyOf(tag)
        if (key.isEmpty()) return emptyList()
        return runCatching {
            val d = openDb()
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = ArrayList<Pair<Long, Int>>(32)
            d.rawQuery(
                "SELECT chatId, itemCount FROM $TAGS_TABLE WHERE tag=? ORDER BY itemCount DESC, chatId ASC",
                arrayOf(key),
            ).use { c ->
                while (c.moveToNext()) out += c.getLong(0) to c.getInt(1)
            }
            Log.d(TAG, "R236 tagChannels tag=#$key 频道=${out.size} ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            out
        }.getOrElse {
            Log.e(TAG, "R236 tagChannels 查询失败 tag=$tag", it)
            emptyList()
        }
    }

    /**
     * R236：该 tag 的条目（全字段 [VideoItem]），发布时间倒序，上限 [limit]。
     * **无 FTS5 设备 hashtag 检索 3-8 分钟→毫秒级的根治件**：tag_items 倒排
     * （PK tag 前缀扫）JOIN items（rowid 回表 O(log n)），零全表扫零词位候选——
     * R228 已知缺口（超长 caption 截断位外的 tag/混合相册空 caption 成员）顺带
     * 根治（item_tags 元素即索引行，不依赖 tokens 词位）。
     * 覆盖口径：item_tags 已解析行（R228 回填进行中时未解析行暂不在召回——
     * 随 R228 回填推进收敛到全集；旧路 [search] 的 caption LIKE 兜底仍在，本表
     * 是加速层不是替代）。相册语义：R228 落库时相册级 tag 已逐成员落位，无需
     * 相册展开（与 search() 的展开口径差异=本 API 只返回真带该 tag 的成员）。
     * **请在 IO 线程调用**。
     */
    fun tagItems(tag: String, limit: Int = SEARCH_HARD_CAP): List<VideoItem> {
        if (limit <= 0) return emptyList()
        val key = tagKeyOf(tag)
        if (key.isEmpty()) return emptyList()
        return runCatching {
            val d = openDb()
            val t0 = android.os.SystemClock.elapsedRealtime()
            // 列名全限定（tag_items 也有 chatId 列——裸列名在 JOIN 下歧义报错）；
            // R269：SQL 形状收口纯函数 [tagItemsJoinSql]（27dd5a8 误删 FROM 的回归锚）
            val out = tagItemsRecall(d, key, limit)
            Log.d(
                TAG,
                "R236 tagItems tag=#$key 召回=${out.size}条 ${android.os.SystemClock.elapsedRealtime() - t0}ms" +
                    "（LIKE 旧路对照分钟级）",
            )
            out
        }.getOrElse {
            Log.e(TAG, "R236 tagItems 查询失败 tag=$tag", it)
            emptyList()
        }
    }

    /**
     * tag_items 倒排召回本体（R293 从 [tagItems] 抽出供 searchHashtag 换轨复用——
     * 单一 SQL 源 [tagItemsJoinSql] 不私抄）。调用方自备连接与异常兜底。
     * 元素级精确：item_tags 写路径已逐成员落位，召回免复核。
     */
    private fun tagItemsRecall(d: SQLiteDatabase, key: String, limit: Int): List<VideoItem> {
        val out = ArrayList<VideoItem>(minOf(limit, 64))
        d.rawQuery(
            tagItemsJoinSql(ITEM_COLS_FROM_ITEMS),
            arrayOf(key, limit.toString()),
        ).use { c -> while (c.moveToNext()) out += rowToItem(c) }
        return out
    }

    /** R313 词频闸原料：tag 行数精确计数（tag_items PK 前缀范围计数，索引内完成毫秒级） */
    private fun tagRowCount(d: SQLiteDatabase, key: String): Long =
        d.rawQuery("SELECT COUNT(*) FROM tag_items WHERE tag=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /**
     * R313 tag 倒排召回唯一决策口（词频闸同形 R293 [likeRecall]——「老内容进不了
     * 候选集」在 tag 侧的根治件，消费方=[tagItemsByRelevance]（R2 兴趣路）与
     * [searchHashtag] 倒排主路（顺手清 R293 挂账③：超宽 tag 新 2000 偏置））：
     *  - 闸下（时间索引未就绪 / 行数 ≤ [RecallSlicePolicy.GATE_FACTOR]×limit——截断浅，
     *    最新 limit 条已覆盖 ≥1/GATE_FACTOR 命中）→ [tagItemsRecall] 单路旧形直通
     *    （现状零回归）；
     *  - 闸上（宽 tag，截断率 ≥75%）→ [tagItemsSlicedRecall] 时间分代：老年代条目
     *    进候选集，下游 BM25 重排才有米下锅。
     * tag 侧闸用**精确行数**（tag_items PK 前缀计数——比 LIKE 路的 df 采样估计更准，
     * 零新增成本）。分代异常 runCatching 回落单路旧形（R293 同款防御）。
     * **时间序契约消费方（[tagItems]：R222 时间轴/R234）不走本口**——时间序是契约。
     */
    private fun tagRecall(d: SQLiteDatabase, key: String, limit: Int): List<VideoItem> {
        if (limit <= 0) return emptyList()
        if (!dateIndexReadyCached(d)) return tagItemsRecall(d, key, limit)
        val count = runCatching { tagRowCount(d, key) }
            .getOrElse { return tagItemsRecall(d, key, limit) }
        if (!RecallSlicePolicy.shouldStratify(count, limit)) {
            return tagItemsRecall(d, key, limit)
        }
        return runCatching { tagItemsSlicedRecall(d, key, limit) }
            .onFailure { Log.w(TAG, "R313 tag 分代召回异常，回落单路旧形", it) }
            .getOrElse { tagItemsRecall(d, key, limit) }
    }

    /**
     * tag 分代落形（闸上）：全库时间量程（[itemsDateRange]，索引 O(1)）等分
     * [RECALL_SLICE_BUCKETS] 代，每代 [tagItemsSlicedJoinSql]（tag_items CROSS JOIN
     * items 锁 tag 侧驱动 + 代内日期区间 + 代内时间倒序 LIMIT
     * k=⌈limit×[RECALL_SLICE_OVERSAMPLE]/B⌉）。
     * 成本红线：驱动侧=tag PK 前缀扫（count 行，闸已实测），总访问 ≤ B×count 次
     * rowid 点查 ≪ 45s 级 LIKE 全表扫（snap2 实测：3.2 万行宽 tag 8 代 0.55s PC，
     * 旧形 32 条全挤 7 天内 → 分代并集横跨 10.7 年量程，详 r313_notes）。
     * 代间 key 去重合并（区间互斥本不撞，防御性同 R293）。
     */
    private fun tagItemsSlicedRecall(d: SQLiteDatabase, key: String, limit: Int): List<VideoItem> {
        val range = itemsDateRange(d) ?: return tagItemsRecall(d, key, limit)
        val slices = RecallSlicePolicy.slices(
            range.first, range.second, RECALL_SLICE_BUCKETS, limit, RECALL_SLICE_OVERSAMPLE,
        )
        if (slices.isEmpty()) return tagItemsRecall(d, key, limit)
        val merged = LinkedHashMap<String, VideoItem>(limit * 2)
        val t0 = android.os.SystemClock.elapsedRealtime()
        for (s in slices) {
            d.rawQuery(
                tagItemsSlicedJoinSql(ITEM_COLS_FROM_ITEMS),
                arrayOf(key, s.fromSec.toString(), s.toSecExclusive.toString(), s.limit.toString()),
            ).use { c -> while (c.moveToNext()) { val it = rowToItem(c); merged.putIfAbsent(it.key, it) } }
        }
        Log.d(
            TAG,
            "R313 tag 分代召回：tag=#$key 代=${slices.size} 召回=${merged.size} " +
                "耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms（单路旧形上限 $limit 条）",
        )
        return merged.values.toList()
    }

    /**
     * R288：[tagItems] 的相关度序变体（**只加不改**——tagItems 时间序契约原样保留，
     * R222 时间轴/R234 尘埃带/TagContentFeedSource 消费方零影响）。
     * tag_items 倒排毫秒级召回（**R313 起走 [tagRecall] 词频闸**：宽 tag 时间分代，
     * 老年代条目也进候选集——R307b 实锤的近端截断「推荐全是新的」根治）→
     * [rescoreByRelevance] BM25 轻量重排：tag 词位在正文反复出现的聚焦 caption 压过
     * 长清单体顺带提及；统计不可用时时间序直通。适用方=想要「这个 tag 最有代表性的
     * 内容」的消费场景；想要时间序的继续调 [tagItems]。**请在 IO 线程调用**。
     */
    fun tagItemsByRelevance(tag: String, limit: Int = SEARCH_HARD_CAP): List<VideoItem> {
        if (limit <= 0) return emptyList()
        val key = tagKeyOf(tag)
        if (key.isEmpty()) return emptyList()
        return runCatching {
            val d = openDb()
            val items = tagRecall(d, key, limit)
            if (items.size <= 1) return items
            runCatching {
                rescoreByRelevance(d, items, key, queryTokens(listOf(key), withSynonyms = false))
            }.getOrDefault(items) // 重排失败=召回原序直通（不丢结果）
        }.getOrElse {
            Log.e(TAG, "R298 tagItemsByRelevance 查询失败 tag=$tag", it)
            emptyList()
        }
    }

    /**
     * R236：频道 top tags（display 首见原形 → 全频道历史条数），按条数降序
     * （tag 升序破平——确定性序）。R233 行星层排序源（PK chatId 前缀导航毫秒级，
     * 治「近端 240 样本偏差」挂账）；关联弧指纹/创世纪 K tag 统计的账本口径
     * （消费方 fold(display) 得归一键——fold 幂等，与 tags.tag 同键）。
     * **请在 IO 线程调用**。
     */
    fun topTags(chatId: Long, limit: Int): List<Pair<String, Int>> {
        if (limit <= 0) return emptyList()
        return runCatching {
            val d = openDb()
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = ArrayList<Pair<String, Int>>(minOf(limit, 32))
            d.rawQuery(
                "SELECT display, itemCount FROM $TAGS_TABLE WHERE chatId=? ORDER BY itemCount DESC, tag ASC LIMIT ?",
                arrayOf(chatId.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) out += (c.getString(0) ?: "") to c.getInt(1)
            }
            Log.d(TAG, "R236 topTags chatId=$chatId tags=${out.size} ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            out
        }.getOrElse {
            Log.e(TAG, "R236 topTags 查询失败 chatId=$chatId", it)
            emptyList()
        }
    }

    /**
     * R303c P1：全频道 tag 指纹账本批量口（**只加不改**、只读、零网络——涌现布局
     * 场 Jaccard 引力对的真源）。tags 表全量扫（chatId, tag, itemCount——聚合账本
     * 行量=频道数×均几十 tag，毫秒~几十毫秒级一次性装配成本，非逐频道点查的
     * N 次往返）。tag 列=fold 归一键（R236 口径——与指纹消费方 TasteTagPlanets.
     * fingerprintOf 的 fold(display) 同键域，Jaccard 集合语义一致）；调用方按
     * 池内 chatId 过滤。空表/回填窗口期=空 Map 诚实降档（互链/距离两路仍可成对）。
     * **请在 IO 线程调用**。
     */
    fun chatTagLedger(): Map<Long, Map<String, Int>> {
        return runCatching {
            val d = openDb()
            val t0 = android.os.SystemClock.elapsedRealtime()
            val out = HashMap<Long, HashMap<String, Int>>(1024)
            d.rawQuery("SELECT chatId, tag, itemCount FROM $TAGS_TABLE", null).use { c ->
                while (c.moveToNext()) {
                    val tag = c.getString(1) ?: continue
                    if (tag.isEmpty()) continue
                    out.getOrPut(c.getLong(0)) { HashMap(32) }[tag] = c.getInt(2)
                }
            }
            val keys = out.values.sumOf { it.size }
            Log.d(TAG, "R303c chatTagLedger 频道=${out.size} 指纹键=$keys ${android.os.SystemClock.elapsedRealtime() - t0}ms")
            out
        }.getOrElse {
            Log.e(TAG, "R303c chatTagLedger 查询失败（降档=空账本）", it)
            emptyMap()
        }
    }

    /**
     * R267：tag_items 覆盖度粗判（**只读、O(1) 文件级**——两个回填断点 JSON 的 done 位）：
     * caption 解析回填（caption_backfill_v8r2，item_tags 列填充）或 tags 聚合回填
     * （tags_backfill_v9r2，item_tags→tags/tag_items）任一在途 = 覆盖不足，hashtag
     * 消费方（R222 时间轴/研究器）应走 LIKE 兜底而非空白——导入存量库/断点被门房
     * 误杀（R266 前）的库正是此态。断点文件缺失=从未跑过=同样覆盖不足。
     * done=true 后空结果=真空（不再兜全表扫）。**请在 IO 线程调用**。
     */
    fun tagCoverageLikelyIncomplete(): Boolean {
        val cap = readBackfillState()
        val tags = readTagsBackfillState()
        return cap?.done != true || tags?.done != true
    }

    /** R293：覆盖粗判的进程内节流缓存（TTL [TAG_COVERAGE_CACHE_TTL_MS]） */
    @Volatile
    private var tagCoverageIncompleteCache: Pair<Boolean, Long>? = null

    /**
     * [tagCoverageLikelyIncomplete] 的节流包装（R293 searchHashtag 兜底闸用）：
     * 每次搜索都读两个断点 JSON 虽小但无意义——done 位是分钟级漂移，秒级搜索
     * 路径吃 TTL 缓存足够；闸翻转最坏滞后一个 TTL（在途期多兜一次旧路=现状成本，
     * 收敛后晚退役一分钟=零风险方向）。
     */
    private fun tagCoverageLikelyIncompleteThrottled(): Boolean {
        val now = System.currentTimeMillis()
        tagCoverageIncompleteCache?.let { if (now - it.second < TAG_COVERAGE_CACHE_TTL_MS) return it.first }
        val v = tagCoverageLikelyIncomplete()
        tagCoverageIncompleteCache = v to now
        return v
    }

    /**
     * R298：按 key 批量取条目的 item_tags（原形标签，无 # 前缀；key 是主键，
     * IN 分块点查毫秒级）。供真推荐引擎冷启动先验（点赞条目的 tag 反查）——
     * 只读、零网络。未索引/未解析/已解析无标签的 key 一律缺席（调用方按无 tag
     * 处理；「未解析」与「无标签」在此口径下不区分——先验是低档加分，宁缺毋滥）。
     * **请在 IO 线程调用**。
     */
    fun tagsOfKeys(keys: Collection<String>): Map<String, List<String>> {
        if (keys.isEmpty()) return emptyMap()
        return runCatching {
            val d = openDb()
            val out = HashMap<String, List<String>>(keys.size)
            val list = keys.toList()
            var i = 0
            while (i < list.size) {
                val chunk = list.subList(i, minOf(i + TIME_WINDOW_IN_CHUNK, list.size))
                val marks = chunk.joinToString(",") { "?" }
                d.rawQuery(
                    "SELECT key, item_tags FROM items WHERE key IN ($marks)",
                    chunk.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        val tags = parseTagsJson(c.getString(1))
                        if (!tags.isNullOrEmpty()) out[c.getString(0)] = tags
                    }
                }
                i += chunk.size
            }
            out
        }.getOrElse {
            Log.e(TAG, "R298 tagsOfKeys 查询失败（${keys.size} keys）", it)
            emptyMap()
        }
    }

    // ---------------- R183 发现页金矿查询（只读；性能纪律：全部索引友好，零全表扫） ----------------

    /**
     * 金矿专用只读连接（R183 真机性能修正）：
     * 主连接（openDb）被爬取写入事务串行占用——金矿快照构建（rollup + 453 频道 head
     * 采样）在主连接上排队等爬虫的每页事务，实测 172s；WAL 模式下**独立只读连接**
     * 读自己的快照不与写事务互斥（主连接常开保证 -shm/-wal 存活），并行读完首建
     * 回到秒级。懒建 + @Synchronized 互斥建；查询失败回落主连接口径（runCatching 已兜）。
     */
    @Volatile
    private var goldDb: SQLiteDatabase? = null

    private val goldDbLock = Any()

    private fun openGoldDb(): SQLiteDatabase {
        goldDb?.let { return it }
        synchronized(goldDbLock) {
            goldDb?.let { return it }
            val d = SQLiteDatabase.openDatabase(
                File(storeDir, DB_NAME).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            goldDb = d
            return d
        }
    }

    /**
     * 头部采样行（R183 发现页金矿原料：每频道最新 [limit] 条的发布时间/媒体类型/caption/标题）。
     *
     * 形状刻意走 `WHERE chatId=? ORDER BY messageId DESC LIMIT ?`——idx_items_chat_msg
     * (chatId, messageId DESC) 先定位频道区间再倒序走，回表行数 **有界**（恒 ≤ limit，
     * 与频道总量无关）；**绝不用 dateEpochSec 过滤**（该列不在任何索引里，WHERE 日期条件
     * 会把全频道行回表扫一遍——LIKE 全表扫的同族病）。近 7 天窗口、模板风暴、caption
     * 话术样本、丰度折算全部由调用方在 Kotlin 侧对这批有界行计算。
     *
     * title 取自最新一行 channelTitle（**候选频道标题免网络**——互链嗅探时代裸 ID 候选
     * 要发 fetchVideos 拉标题，金矿候选直接从索引行拿）。行序 = messageId 倒序（新→旧）。
     * 索引未建（hasIndex 假）返回空表——绝不为金矿查询触发建库（openDb 会连带建表/
     * FTS5 探测/迁移扫描，不该被发现页刷新触发）。**请在 IO 线程调用**。
     */
    data class ChannelHeadRow(
        val dateEpochSec: Long,
        val mediaType: Int,
        val caption: String,
        val channelTitle: String,
    )

    /** [channelHeadRows] 的每频道采样上限（R183 快照构建口径：256 行覆盖活跃频道近月产出） */
    fun channelHeadRows(chatId: Long, limit: Int): List<ChannelHeadRow> {
        if (limit <= 0 || !File(storeDir, DB_NAME).exists()) return emptyList()
        return runCatching {
            val d = openGoldDb() // 金矿专用只读连接：不与爬取写事务串行（见 openGoldDb KDoc）
            // R266 v11 两段法：items 臂+refs 臂各按 messageId DESC 取 LIMIT → 归并取前
            // limit（D3 转发条目仍是频道发布事实）→ refs 行覆盖列 NULL 的按 canonRowid
            // 批量补 canonical caption（caption 样本需 canonical 补，设计 §3.4）
            mergeHeadRows(d, chatId, limit)
        }.getOrDefault(emptyList())
    }

    /**
     * v11 头部采样两臂归并（channelHeadRows 真身）：items 臂 + refs 臂各取
     * messageId DESC 前 limit → 归并截 limit → refs 行 caption=覆盖列 ?:
     * canonical caption（批量 IN 补，有界回表）。
     */
    private fun mergeHeadRows(d: SQLiteDatabase, chatId: Long, limit: Int): List<ChannelHeadRow> {
        // captionOverride 三态必须保真：NULL=与 canonical 同（待补）；空串=转发者
        // 清空文案（D2 忠实保留，**绝不补**）；非空=独立文案
        data class HeadRaw(val messageId: Long, val row: ChannelHeadRow, val canonRowid: Long, val overrideIsNull: Boolean)
        val raws = ArrayList<HeadRaw>(limit)
        d.rawQuery(
            "SELECT messageId, dateEpochSec, mediaType, caption, channelTitle FROM items " +
                "WHERE chatId=? ORDER BY messageId DESC LIMIT ?",
            arrayOf(chatId.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                raws += HeadRaw(
                    c.getLong(0),
                    ChannelHeadRow(c.getLong(1), c.getInt(2), c.getString(3) ?: "", c.getString(4) ?: ""),
                    -1L, false,
                )
            }
        }
        // refs 臂（升级窗口期 goldDb 下表可能缺席——探测直通存量形态）
        if (hasRepostsTable(d)) {
            d.rawQuery(
                "SELECT messageId, dateEpochSec, mediaType, caption, channelTitle, canonRowid FROM $REPOSTS_TABLE " +
                    "WHERE chatId=? ORDER BY messageId DESC LIMIT ?",
                arrayOf(chatId.toString(), limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    raws += HeadRaw(
                        c.getLong(0),
                        ChannelHeadRow(c.getLong(1), c.getInt(2), if (c.isNull(3)) "" else c.getString(3) ?: "", c.getString(4) ?: ""),
                        c.getLong(5), c.isNull(3),
                    )
                }
            }
        }
        if (raws.none { it.canonRowid > 0L }) return raws.map { it.row } // 无收编行=存量直通（items 臂本已 DESC）
        raws.sortByDescending { it.messageId }
        val win = raws.take(limit)
        // 覆盖列 NULL（=与 canonical 同文案）的行：批量补 canonical caption
        val needCanon = win.filter { it.canonRowid > 0L && it.overrideIsNull }
            .map { it.canonRowid }.distinct()
        val canonCap = HashMap<Long, String>(needCanon.size * 2)
        for (chunk in needCanon.chunked(TIME_WINDOW_IN_CHUNK)) {
            d.rawQuery(
                "SELECT rowid, caption FROM items WHERE rowid IN (" + chunk.joinToString(",") { "?" } + ")",
                chunk.map { it.toString() }.toTypedArray(),
            ).use { c -> while (c.moveToNext()) canonCap[c.getLong(0)] = c.getString(1) ?: "" }
        }
        return win.map { r ->
            if (r.canonRowid > 0L && r.overrideIsNull) {
                r.row.copy(caption = canonCap[r.canonRowid] ?: "")
            } else {
                r.row
            }
        }
    }

    /**
     * 全频道条数清单（R183 金矿宇宙 + 存货规模）：`GROUP BY chatId` 的 COUNT(*)——
     * 聊天 id 与行号全在 idx_items_chat_msg 覆盖索引里，SQLite 走纯索引扫描聚合，
     * **零回表**（百万行冷缓存秒级，R155/R158 抽样两段法同款覆盖索引口径）。
     * 走金矿只读连接（与爬取写事务并行，见 [openGoldDb]）；索引未建返回空表
     * （hasIndex 前置，同 [channelHeadRows] 的防误建口径）。**请在 IO 线程调用**。
     */
    fun channelCountRollup(): Map<Long, Int> {
        if (!File(storeDir, DB_NAME).exists()) return emptyMap()
        return runCatching {
            val d = openGoldDb()
            val out = HashMap<Long, Int>(256)
            // R266 v11 双臂（D6 含转发口径）：两臂各走自身 chatId 前缀索引聚合，
            // Kotlin 侧相加合并——免 posts_view 全表物化（老 SQLite 谓词不压 UNION ALL）
            d.rawQuery("SELECT chatId, COUNT(*) FROM items GROUP BY chatId", null).use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
            }
            if (hasRepostsTable(d)) { // goldDb 升级窗口期直通存量形态
                d.rawQuery("SELECT chatId, COUNT(*) FROM $REPOSTS_TABLE GROUP BY chatId", null).use { c ->
                    while (c.moveToNext()) out.merge(c.getLong(0), c.getInt(1), Int::plus)
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    // ---------------- R202 时间维度：时间窗查询原语（TL-1 时光机/TL-4 事件考古/DS-1 此刻共享） ----------------

    /**
     * 时间窗查询参数（R202 共享原语——**接口公告：R203/R204 的时间查询统一走
     * [timeWindowItems] 这里，不要再开新的日期口径**）。
     *
     * @param fromSec 窗口起点（含，Unix 秒；与 [toSec] 构成闭区间）
     * @param toSec 窗口终点（含，Unix 秒）
     * @param channelIds null = 全网全部已索引频道（时光机/事件考古默认）；
     *   非空 = 频道过滤集（订阅版/单频道切片）；空集 = 无目标频道（直接空结果）
     * @param limit 采样上限（有界红线：内部钳到 [TIME_WINDOW_MAX_LIMIT]，
     *   绝不无界取全窗）
     */
    data class TimeWindowQuery(
        val fromSec: Long,
        val toSec: Long,
        val channelIds: Set<Long>? = null,
        val limit: Int,
    )

    /**
     * 时间区间 × 频道的**有界**横截面查询（时间维度族的地基原语）。
     *
     * 形状：`WHERE dateEpochSec >= ? AND <= ? [AND chatId IN(…)] ORDER BY
     * dateEpochSec DESC LIMIT ?`——idx_items_date(dateEpochSec, chatId) 索引导航：
     * 从 toSec 起沿索引倒序走、回表行数恒 ≤ limit（**有界**，与窗口内总量无关，
     * R183 禁令「绝不全表扫」的正解）；频道过滤在索引内判 chatId（第二列覆盖，
     * 非目标频道行零回表直接跳过）。dateEpochSec=0 防御性排除（正常爬取条目恒>0）。
     *
     * 频道集 > [TIME_WINDOW_IN_CHUNK] 时分块多查合并（每块独立 LIMIT、按日期归并
     * 取前 limit——块间边界近似，采样语义可接受；块上限=老 SQLite 999 占位符上限
     * 留余量，与 sampleRandom 的 SAMPLE_MAX_CHANNELS 同口径）。
     *
     * 前置门（三纪律，R183 金矿同款）：库未建（hasIndex 纯文件 stat）→ 空表；
     * **时间索引未就绪（[dateIndexReadyCached] 探 sqlite_master）→ 空表**（绝不
     * 退化成无索引全表扫——首开秒级红线；调用方用 [dateIndexReady] 流做「构建中」
     * 诚实空态+就绪补刷）；查询异常 runCatching 静默空表。**请在 IO 线程调用**。
     *
     * 返回条目 playUri=null（点播放走 ensurePlayable 链现取）、coverBytes=null
     * （封面走消息反解链），与 [search]/[sampleRandom] 同口径；行序 = dateEpochSec
     * 新→旧。
     */
    fun timeWindowItems(query: TimeWindowQuery): List<VideoItem> {
        val limit = query.limit.coerceIn(1, TimeWindowPolicy.QUERY_LIMIT_MAX)
        val from = maxOf(query.fromSec, 1L) // dateEpochSec=0 防御性排除
        val to = query.toSec
        if (to < from) return emptyList()
        if (query.channelIds != null && query.channelIds.isEmpty()) return emptyList()
        if (!File(storeDir, DB_NAME).exists()) return emptyList()
        return runCatching {
            val d = openGoldDb()
            if (!dateIndexReadyCached(d)) {
                Log.d(TAG, "时间窗查询：时间索引未就绪（后台构建中），本轮空手返回")
                return emptyList()
            }
            val chans = query.channelIds
            when {
                chans == null -> queryWindow(d, from, to, null, limit)
                chans.size <= TIME_WINDOW_IN_CHUNK -> queryWindow(d, from, to, chans, limit)
                else -> {
                    // 大频道集：分块各查 limit，日期归并取前 limit（块间边界近似口径见 KDoc）
                    val merged = ArrayList<VideoItem>(limit)
                    for (chunk in chans.chunked(TIME_WINDOW_IN_CHUNK)) {
                        merged += queryWindow(d, from, to, chunk.toSet(), limit)
                    }
                    merged.sortedByDescending { it.dateEpochSec }.take(limit)
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 单窗查询（无频道过滤或单块频道集）：索引倒序导航 + LIMIT 有界回表。
     * **INDEXED BY idx_items_date 锁死访问路径**（host 预演实锤：无此锁时规划器
     * 对 `chatId IN` 形态会选 idx_items_album(chatId=?) 走整频道扫描 + TEMP
     * B-TREE 排序——频道过滤窗变相全表扫，R183 禁令红线；锁死后频道过滤在
     * 时间索引内覆盖判定，非目标行零回表丢弃，访问量恒=窗内索引条目数）。
     * 调用前置 [dateIndexReadyCached] 门保证索引必在场（INDEXED BY 缺索引即报错，
     * 门在报错前就空手返回——不兼容路径不存在）。
     */
    private fun queryWindow(
        d: SQLiteDatabase,
        fromSec: Long,
        toSec: Long,
        chans: Set<Long>?,
        limit: Int,
    ): List<VideoItem> {
        val sql = buildString {
            append("SELECT ").append(ITEM_COLS).append(" FROM items INDEXED BY ")
            append(DATE_INDEX_NAME)
            append(" WHERE dateEpochSec >= ? AND dateEpochSec <= ?")
            if (chans != null) {
                append(" AND chatId IN(").append(chans.joinToString(",") { "?" }).append(")")
            }
            append(" ORDER BY dateEpochSec DESC LIMIT ").append(limit)
        }
        val args = buildList {
            add(fromSec.toString())
            add(toSec.toString())
            if (chans != null) chans.forEach { add(it.toString()) }
        }
        val out = ArrayList<VideoItem>(limit)
        d.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) out += rowToItem(c)
        }
        // R266 v11 D4 现状口径（每次转发各计一份）：refs 臂同窗同限（INDEXED BY
        // idx_refs_date 同款锁死访问路径），两臂按 dateEpochSec DESC 归并取前 limit，
        // refs 行按 canonRowid 批量补内容侧合成（两段法）。goldDb 升级窗口期 refs
        // 表可能缺席（v10 文件）——探测缺席=存量形态直通
        if (!hasRepostsTable(d)) return out
        val rSql = buildString {
            append("SELECT ").append(REFS_PUB_COLS).append(" FROM $REPOSTS_TABLE INDEXED BY ")
            append(REFS_DATE_INDEX)
            append(" WHERE dateEpochSec >= ? AND dateEpochSec <= ?")
            if (chans != null) {
                append(" AND chatId IN(").append(chans.joinToString(",") { "?" }).append(")")
            }
            append(" ORDER BY dateEpochSec DESC LIMIT ").append(limit)
        }
        val refsArm = ArrayList<RefsPubRow>(limit)
        d.rawQuery(rSql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) refsArm += cursorToRefsRow(c)
        }
        if (refsArm.isEmpty()) return out
        val synthesized = synthesizeRefItems(d, refsArm)
        val merged = ArrayList<VideoItem>(limit)
        var i = 0
        var j = 0
        while (merged.size < limit && (i < out.size || j < refsArm.size)) {
            val it = out.getOrNull(i)
            val rf = refsArm.getOrNull(j)
            if (rf == null) {
                merged += it!!
                i++
            } else if (it == null) {
                synthesized[rf.key]?.let { merged += it }
                j++
            } else if (it.dateEpochSec >= rf.dateEpochSec.toInt()) {
                merged += it
                i++
            } else {
                synthesized[rf.key]?.let { merged += it }
                j++
            }
        }
        return merged
    }

    /**
     * 索引时间量程（R202 时光机滑杆的 min/max）：`MIN/MAX(dateEpochSec)`（>0 口径）。
     * 索引就绪后两端 O(1)（idx_items_date 起止条目直达）；未建库/索引未就绪返回
     * null（调用方回落「近 2 年」默认量程）。**请在 IO 线程调用**。
     */
    fun dateBounds(): Pair<Long, Long>? {
        if (!File(storeDir, DB_NAME).exists()) return null
        return runCatching {
            val d = openGoldDb()
            if (!dateIndexReadyCached(d)) return null
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            d.rawQuery(
                "SELECT MIN(dateEpochSec), MAX(dateEpochSec) FROM items WHERE dateEpochSec > 0",
                null,
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                    lo = minOf(lo, c.getLong(0))
                    hi = maxOf(hi, c.getLong(1))
                }
            }
            // R266 v11 D4 现状口径：refs 臂同求两端（idx_refs_date 起止 O(1) 直达；
            // goldDb 升级窗口期表缺席直通）
            if (hasRepostsTable(d)) {
                d.rawQuery(
                    "SELECT MIN(dateEpochSec), MAX(dateEpochSec) FROM $REPOSTS_TABLE WHERE dateEpochSec > 0",
                    null,
                ).use { c ->
                    if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                        lo = minOf(lo, c.getLong(0))
                        hi = maxOf(hi, c.getLong(1))
                    }
                }
            }
            if (lo == Long.MAX_VALUE) null else lo to hi
        }.getOrNull()
    }

    /**
     * 日粒度发布密度直方图（R202 滑杆刻度糖 / 时光机「哪个年代有货」预览）：
     * `GROUP BY dateEpochSec/86400`——索引序扫描聚合，百万行级秒内（后台预热
     * 调用，不挡用户路径）。返回 (dayIndex, count) 升序，dayIndex×86400=桶起点秒。
     * 索引未就绪返回空表（滑杆回落均匀刻度）。**请在 IO 线程调用**。
     */
    fun monthHistogram(): List<Pair<Int, Int>> {
        if (!File(storeDir, DB_NAME).exists()) return emptyList()
        return runCatching {
            val d = openGoldDb()
            if (!dateIndexReadyCached(d)) return emptyList()
            // R266 v11 D4 现状口径（每次转发各计一份）：两臂各自索引序 GROUP BY
            // （免 posts_view 物化），Kotlin 侧按日桶相加合并
            val byDay = HashMap<Int, Int>(1024)
            d.rawQuery(
                "SELECT dateEpochSec/86400 AS day, COUNT(*) FROM items WHERE dateEpochSec > 0 GROUP BY day",
                null,
            ).use { c ->
                while (c.moveToNext()) byDay.merge(c.getInt(0), c.getInt(1), Int::plus)
            }
            if (hasRepostsTable(d)) { // goldDb 升级窗口期表缺席直通
                d.rawQuery(
                    "SELECT dateEpochSec/86400 AS day, COUNT(*) FROM $REPOSTS_TABLE WHERE dateEpochSec > 0 GROUP BY day",
                    null,
                ).use { c ->
                    while (c.moveToNext()) byDay.merge(c.getInt(0), c.getInt(1), Int::plus)
                }
            }
            byDay.entries.sortedBy { it.key }.map { it.key to it.value }
        }.getOrDefault(emptyList())
    }

    /**
     * R317 发布直方图点查结果（S3 件——终态宣判判据族 [TasteAstroPhysics] 的证据
     * 源，「一查询三消费」：爆发峰/骤停档（终态宣判）+两窗构成差（R321 自行）+
     * 后续任何需要历史证据的概念都接此件不另开炉灶）：
     *  - [dayHistogram]=(dayIndex, count) 升序（dayIndex×86400=桶起点秒；日粒度=
     *    [monthHistogram] 既有直方图口径的频道版）；双臂合并（items+repost_refs，
     *    v11 D4 现状口径：每次转发各计一份）；
     *  - [firstDaySec]/[lastDaySec]=首/末发布日起点秒（**日粒度精度 ±1 天**——对
     *    判据族 60/90 天门零影响）；[items]=有效日期条数合计（≠体量条数——体量
     *    走 channelItemsCount 全量口径含 0 值日期行）。
     * 判据族消费方：TasteAstroPhysics.publishHistogramFeatures(dayHistogram, nowSec)。
     */
    data class ChatPublishHistogram(
        val dayHistogram: List<Pair<Int, Int>>,
        val firstDaySec: Long,
        val lastDaySec: Long,
        val items: Int,
    )

    /**
     * 单频道发布直方图点查（R317 S3——**有界点查非全库扫描**：chatId 点查经
     * INDEXED BY idx_items_chat_msg 锁频道域索引；调用方先按体量门预筛终态候选
     * ≤[dev.tgfeed.core.tastemap.TasteAstroPhysics.TERMINAL_POOL_CAP] 颗。**成本实
     * 测**（snap2 6GB 快照，5 万条级频道）：单频道 173~441ms——成本 ∝ 频道条数，
     * 装配期预算口径与降级方案详见 r317_notes 实测账）。null=零有效日期行（未索引、
     * 未发布、索引重建 pending 窗——runCatching 诚实降级）=缺据，判据族不宣判
     * 绝不冒充终态。SQL 形状=[chatHistogramDaySql]/[chatHistogramDayRefsSql] 单一
     * 出处（R269 机检锚）。**请在 IO 线程调用**。
     */
    fun chatPublishHistogram(chatId: Long): ChatPublishHistogram? {
        if (!File(storeDir, DB_NAME).exists()) return null
        return runCatching {
            val d = openGoldDb()
            val byDay = HashMap<Int, Int>(256)
            d.rawQuery(chatHistogramDaySql(), arrayOf(chatId.toString())).use { c ->
                while (c.moveToNext()) byDay.merge(c.getInt(0), c.getInt(1), Int::plus)
            }
            if (hasRepostsTable(d)) { // goldDb 升级窗口期表缺席直通（monthHistogram 同口径）
                d.rawQuery(
                    chatHistogramDayRefsSql(REPOSTS_TABLE),
                    arrayOf(chatId.toString()),
                ).use { c ->
                    while (c.moveToNext()) byDay.merge(c.getInt(0), c.getInt(1), Int::plus)
                }
            }
            if (byDay.isEmpty()) return@runCatching null
            val sorted = byDay.entries.sortedWith(compareBy<Map.Entry<Int, Int>> { it.key }.thenBy { it.value })
            var items = 0
            for ((_, cnt) in sorted) items += cnt
            ChatPublishHistogram(
                dayHistogram = sorted.map { it.key to it.value },
                firstDaySec = sorted.first().key * 86_400L,
                lastDaySec = sorted.last().key * 86_400L,
                items = items,
            )
        }.getOrNull()
    }

    /**
     * R323 单频道发布直方图·**两窗构成档**点查（S3 直方图点查件的两窗构成消费
     * ——r315 J① 自行「漂移候选」的原料：[day, mediaType, count] 三元组升序表；
     * mediaType 0=视频/1=图文/2=音频（[ChatPublishHistogram] 日粒度口径的构成分桶
     * 版）。调用方=漂移池（≤[dev.tgfeed.core.tastemap.ProperMotionFx.DRIFT_POOL_CAP]
     * 颗——BIRTH_POOL 同款有界池姿势，成本 ∝ 频道条数同 [chatPublishHistogram]
     * 账）；消费方=[dev.tgfeed.core.tastemap.ProperMotionFx.windowVotes]（两窗
     * 归桶）→[ProperMotionFx.driftOf]（漂移宣判）。null=零有效日期行（未索引/
     * 未发布/索引重建 pending 窗——runCatching 诚实降级）=缺据，不判漂移绝不
     * 冒充。SQL 形状=[chatHistogramMediaDaySql]/[chatHistogramMediaDayRefsSql]
     * 单一出处（R269 机检锚族）。**请在 IO 线程调用**。
     */
    fun chatPublishMediaHistogram(chatId: Long): List<Triple<Int, Int, Int>>? {
        if (!File(storeDir, DB_NAME).exists()) return null
        return runCatching {
            val d = openGoldDb()
            // (day×8+mediaType) 复合键 → 计数（同日同型两臂各自 COUNT 合并——Int::plus）
            val byDayMedia = HashMap<Int, Int>(256)
            fun absorb(day: Int, mediaType: Int, count: Int) {
                byDayMedia.merge(day * 8 + mediaType, count, Int::plus)
            }
            d.rawQuery(chatHistogramMediaDaySql(), arrayOf(chatId.toString())).use { c ->
                while (c.moveToNext()) absorb(c.getInt(0), c.getInt(1), c.getInt(2))
            }
            if (hasRepostsTable(d)) { // goldDb 升级窗口期表缺席直通（双臂同口径）
                d.rawQuery(
                    chatHistogramMediaDayRefsSql(REPOSTS_TABLE),
                    arrayOf(chatId.toString()),
                ).use { c ->
                    while (c.moveToNext()) absorb(c.getInt(0), c.getInt(1), c.getInt(2))
                }
            }
            if (byDayMedia.isEmpty()) return@runCatching null
            // 升序稳定输出（day 序、mediaType 序破平——确定性：同库同表同序）；
            // mediaType 域外行不入票（防御——现实只有 0/1/2，未知型不编造构成）
            byDayMedia.entries
                .map { (k, cnt) -> Triple(k / 8, k % 8, cnt) }
                .filter { it.second in 0..2 }
                .sortedWith(compareBy<Triple<Int, Int, Int>> { it.first }.thenBy { it.second })
        }.getOrNull()
    }

    // ---------------- R283 编年史⑥互联卷数据底座（只读口·零写零新索引） ----------------
    //
    // 同指纹（fileUniqueId）跨频道内容证据的三只读口：频道对时间线/频道对聚合/频道
    // 伙伴榜。数据面=v11 双臂现成结构（items=canonical 锚 / repost_refs=收编行，
    // canonRowid→items.rowid 点查、fuid→refs fuid 索引点查、refs.chatId→REFS_CHAT_
    // INDEX 导航）——零 ALTER 零新索引零写操作（共享层改动已在 r283_notes 广播）。
    // 方向语义：canonical 侧=源头（本地证据下的发布先后，FirstSeenLedger 同款覆盖
    // 诚实口径——不冒充全 TG 首发判定）；两侧皆 refs=共现无方向。
    // 逐臂行数 cap [INTERLINK_ARM_ROW_CAP] 防巨型频道失控；命中 cap 置 truncated
    // （诚实降级标记，不谎报全集）。**请在 IO 线程调用**。

    /**
     * 频道对互联内容时间线（「频道 A 与频道 B 的互联史」卷面原料）：同指纹内容在
     * 两频道间的发布/收编证据，按互联开始时刻升序。三臂并集经 [InterlinkMerge]
     * 去重排序截断；索引未建/refs 表缺席（升级窗口期）返回空表——诚实降级不报错。
     */
    fun interlinkSharedTimeline(chatIdA: Long, chatIdB: Long, limit: Int = INTERLINK_TIMELINE_LIMIT): List<InterlinkEvidence> {
        if (chatIdA == chatIdB || limit <= 0 || !File(storeDir, DB_NAME).exists()) return emptyList()
        return runCatching {
            val d = openGoldDb()
            if (!hasRepostsTable(d)) return emptyList()
            InterlinkMerge.timeline(collectInterlinkPairRows(d, chatIdA, chatIdB).first, limit)
        }.getOrDefault(emptyList())
    }

    /**
     * 频道对互联聚合（双星判定的时间分布维度喂料）：方向计数（a→b / b→a / 共现）+
     * 首末互联日期。取数与时间线同臂同 cap（truncated 透传）。
     */
    fun interlinkPairSummary(chatIdA: Long, chatIdB: Long): InterlinkPairSummary {
        if (chatIdA == chatIdB || !File(storeDir, DB_NAME).exists()) {
            return InterlinkPairSummary(0, 0, 0, 0L, 0L, truncated = false)
        }
        return runCatching {
            val d = openGoldDb()
            if (!hasRepostsTable(d)) return InterlinkPairSummary(0, 0, 0, 0L, 0L, truncated = false)
            val (rows, truncated) = collectInterlinkPairRows(d, chatIdA, chatIdB)
            InterlinkMerge.summarize(chatIdA, chatIdB, rows, truncated)
        }.getOrDefault(InterlinkPairSummary(0, 0, 0, 0L, 0L, truncated = false))
    }

    /**
     * 频道级内容伙伴榜（「频道 A 的互联史」的伙伴清单原料）：相对 [chatId] 的全部
     * 同指纹证据按对方频道归组——对方是源（本频道收编）/本频道是源（对方收编）/
     * 共现三类计数+首末日期，total 降序截断。
     */
    fun interlinkPartnersOf(chatId: Long, limit: Int = INTERLINK_PARTNER_LIMIT): List<InterlinkPartner> {
        if (limit <= 0 || !File(storeDir, DB_NAME).exists()) return emptyList()
        return runCatching {
            val d = openGoldDb()
            if (!hasRepostsTable(d)) return emptyList()
            val rows = ArrayList<InterlinkEvidence>(256)
            // 臂U（对方是源）：本频道 refs 行 → canonRowid 点查 canonical 所在频道
            d.rawQuery(
                "SELECT i.chatId, i.messageId, i.dateEpochSec, r.messageId, r.dateEpochSec, r.mediaType " +
                    "FROM $REPOSTS_TABLE r JOIN items i ON r.canonRowid = i.rowid " +
                    "WHERE r.chatId=? LIMIT ?",
                arrayOf(chatId.toString(), INTERLINK_ARM_ROW_CAP.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += InterlinkEvidence(
                        sourceChatId = c.getLong(0), sourceMessageId = c.getLong(1), sourceDateSec = c.getLong(2),
                        repostChatId = chatId, repostMessageId = c.getLong(3), repostDateSec = c.getLong(4),
                        mediaType = c.getInt(5), coOccur = false,
                    )
                }
            }
            // 臂D（本频道是源）：本频道 canonical 行 fuid → refs 收编方（他频道）
            d.rawQuery(
                "SELECT r.chatId, r.messageId, r.dateEpochSec, i.messageId, i.dateEpochSec, i.mediaType " +
                    "FROM items i JOIN $REPOSTS_TABLE r ON r.fuid = i.fileUniqueId " +
                    "WHERE i.chatId=? AND r.chatId != ? AND i.fileUniqueId != '' LIMIT ?",
                arrayOf(chatId.toString(), chatId.toString(), INTERLINK_ARM_ROW_CAP.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += InterlinkEvidence(
                        sourceChatId = chatId, sourceMessageId = c.getLong(3), sourceDateSec = c.getLong(4),
                        repostChatId = c.getLong(0), repostMessageId = c.getLong(1), repostDateSec = c.getLong(2),
                        mediaType = c.getInt(5), coOccur = false,
                    )
                }
            }
            // 臂C（共现）：本频道 refs 行 fuid → 他频道 refs 行（同收编第三方）
            d.rawQuery(
                "SELECT r2.chatId, r2.messageId, r2.dateEpochSec, r1.messageId, r1.dateEpochSec, r1.mediaType " +
                    "FROM $REPOSTS_TABLE r1 JOIN $REPOSTS_TABLE r2 ON r1.fuid = r2.fuid " +
                    "WHERE r1.chatId=? AND r2.chatId != ? LIMIT ?",
                arrayOf(chatId.toString(), chatId.toString(), INTERLINK_ARM_ROW_CAP.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += InterlinkEvidence(
                        sourceChatId = chatId, sourceMessageId = c.getLong(3), sourceDateSec = c.getLong(4),
                        repostChatId = c.getLong(0), repostMessageId = c.getLong(1), repostDateSec = c.getLong(2),
                        mediaType = c.getInt(5), coOccur = true,
                    )
                }
            }
            InterlinkMerge.partners(chatId, rows, limit)
        }.getOrDefault(emptyList())
    }

    /**
     * 频道对三臂取行（interlinkSharedTimeline/interlinkPairSummary 共用）：返回
     * (原始证据行, 是否触 cap)。臂1=方向边（refs→canon，双向一次查）；臂2=共现
     * （refs∩refs）；臂0=残双 canonical（fuid 索引就绪门 [_fuidIndexReady]——未就绪
     * 诚实跳过：v11 合并完成的库此臂恒空，升级窗口期漏收属可接受降级）。
     */
    private fun collectInterlinkPairRows(d: SQLiteDatabase, a: Long, b: Long): Pair<List<InterlinkEvidence>, Boolean> {
        val rows = ArrayList<InterlinkEvidence>(128)
        var truncated = false
        // 臂1（方向边，双向）：refs(A∪B) → canon 落对方频道
        run {
            val hits = d.rawQuery(
                "SELECT i.chatId, i.messageId, i.dateEpochSec, r.chatId, r.messageId, r.dateEpochSec, r.mediaType " +
                    "FROM $REPOSTS_TABLE r JOIN items i ON r.canonRowid = i.rowid " +
                    "WHERE (r.chatId=? AND i.chatId=?) OR (r.chatId=? AND i.chatId=?) LIMIT ?",
                arrayOf(a.toString(), b.toString(), b.toString(), a.toString(), INTERLINK_ARM_ROW_CAP.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    rows += InterlinkEvidence(
                        sourceChatId = c.getLong(0), sourceMessageId = c.getLong(1), sourceDateSec = c.getLong(2),
                        repostChatId = c.getLong(3), repostMessageId = c.getLong(4), repostDateSec = c.getLong(5),
                        mediaType = c.getInt(6), coOccur = false,
                    )
                }
                c.count
            }
            if (hits >= INTERLINK_ARM_ROW_CAP) truncated = true
        }
        // 臂2（共现）：refs(A) ∩ refs(B) 同指纹（归一：source=较小 chatId 保去重键稳定）
        run {
            val hits = d.rawQuery(
                "SELECT r1.messageId, r1.dateEpochSec, r2.messageId, r2.dateEpochSec, r1.mediaType " +
                    "FROM $REPOSTS_TABLE r1 JOIN $REPOSTS_TABLE r2 ON r1.fuid = r2.fuid " +
                    "WHERE r1.chatId=? AND r2.chatId=? LIMIT ?",
                arrayOf(a.toString(), b.toString(), INTERLINK_ARM_ROW_CAP.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val m1 = c.getLong(0); val d1 = c.getLong(1); val m2 = c.getLong(2); val d2 = c.getLong(3)
                    rows += if (a <= b) {
                        InterlinkEvidence(a, m1, d1, b, m2, d2, c.getInt(4), coOccur = true)
                    } else {
                        InterlinkEvidence(b, m2, d2, a, m1, d1, c.getInt(4), coOccur = true)
                    }
                }
                c.count
            }
            if (hits >= INTERLINK_ARM_ROW_CAP) truncated = true
        }
        // 臂0（残双 canonical，fuid 索引就绪门）：方向按发布日期先后（早者为源）
        if (_fuidIndexReady.value) {
            val hits = d.rawQuery(
                "SELECT i1.messageId, i1.dateEpochSec, i2.messageId, i2.dateEpochSec, i1.mediaType " +
                    "FROM items i1 JOIN items i2 ON i1.fileUniqueId = i2.fileUniqueId " +
                    "WHERE i1.chatId=? AND i2.chatId=? AND i1.fileUniqueId != '' LIMIT ?",
                arrayOf(a.toString(), b.toString(), INTERLINK_ARM_ROW_CAP.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    val m1 = c.getLong(0); val d1 = c.getLong(1); val m2 = c.getLong(2); val d2 = c.getLong(3)
                    rows += if (d1 <= d2) {
                        InterlinkEvidence(a, m1, d1, b, m2, d2, c.getInt(4), coOccur = false)
                    } else {
                        InterlinkEvidence(b, m2, d2, a, m1, d1, c.getInt(4), coOccur = false)
                    }
                }
                c.count
            }
            if (hits >= INTERLINK_ARM_ROW_CAP) truncated = true
        }
        return rows to truncated
    }

    // ---------------- 历史回溯随机抽样（FeedViewModel"老内容挖掘"注入用） ----------------

    /**
     * 索引库是否已建立（纯文件 stat，不开库不建库）：Feed 历史回溯注入的前置闸——
     * 库文件不存在 = 从未建过索引，调用方静默跳过；绝不为抽样去创建空库
     * （openDb 附带建表/FTS5 探测/迁移扫描，不该被刷 Feed 的旁路触发）。
     */
    fun hasIndex(): Boolean = File(storeDir, DB_NAME).exists()

    /**
     * 从已索引全量历史随机抽样（Feed"历史回溯"注入：资源发布频道动辄成千上万条历史，
     * Feed 只刷得到最新一页——老内容的矿在索引里，按随机抽样挖出来）。
     *
     * R158 两段抽样（修 2.0GB 库上的 26s 冷抽样）：旧单条 SQL
     * `SELECT 全字段 WHERE chatId IN(…) AND key NOT IN(…) ORDER BY RANDOM() LIMIT n`
     * 把 RANDOM() 排序建在"回表读全字段"的结果集上——每条命中行都要读 items 表体
     * （key/caption 等 16 列），冷页缓存下百万行级回表 = 十秒级（R155 体检实锤
     * 首拉被拖 26s 的就是它）。新两段：
     *  - ①抽样段只取 rowid：`SELECT rowid FROM items WHERE chatId IN(…) ORDER BY
     *    RANDOM() LIMIT want`——chatId+rowid 全在 idx_items_chat_msg 覆盖索引里，
     *    零回表（索引体积是表体的几十分之一，冷启动也秒级），RANDOM() 只在索引
     *    条目上求值；
     *  - ②取数段按抽中的 rowid 主键探测回表取全字段（≤ 数百次 B 树定位）；
     *  - key 排除挪到 Kotlin 结果过滤（调用方 FeedNewFacePart/FeedNavPart 本就做
     *    全量 key 复查兜底，语义不变：排除键只管"当前列表不重复"，**不含已看排除**
     *    ——已看剔除由调用方在抽样结果上双轨复查）；抽样量按排除键规模过采
     *    （`limit + min(排除键数, limit×8)`）补偿被过滤掉的份额，欠补不报错。
     *  - 结果在 Kotlin 侧 shuffle：②的 IN 返回按 rowid 序，shuffle 回随机序
     *    （保持旧 `ORDER BY RANDOM()` 的"返回列表本身乱序"口径）。
     *
     *  - [channelIds] 超 [SAMPLE_MAX_CHANNELS] 随机抽子集：防 SQL 占位符上限
     *    （老设备 999），顺带打散频道来源；
     *  - 返回条目 playUri=null（点播放走 ensurePlayable 链现取）、coverBytes=null
     *    （封面走 loadCoverBytesByMessage 消息反解），与 [channelItems]/[search] 同口径；
     *  - 静默兜底：库不存在/查询异常一律记日志返回空表（增益路径，绝不影响正常刷）。
     */
    fun sampleRandom(channelIds: List<Long>, excludeKeys: Set<String>, limit: Int): List<VideoItem> {
        if (channelIds.isEmpty() || limit <= 0) return emptyList()
        if (!File(storeDir, DB_NAME).exists()) return emptyList() // 库未建：不开库不建库
        return runCatching {
            val startedAt = System.currentTimeMillis()
            val d = openDb()
            val chans = if (channelIds.size > SAMPLE_MAX_CHANNELS) {
                channelIds.shuffled().take(SAMPLE_MAX_CHANNELS)
            } else {
                channelIds
            }
            // ①抽样段：只取 rowid，走覆盖索引零回表；过采补偿 Kotlin 侧排除键过滤
            val slack = excludeKeys.size.coerceAtMost(limit * 8)
            val want = limit + slack
            val sqlSample = buildString {
                append("SELECT rowid FROM items WHERE chatId IN(")
                append(chans.joinToString(",") { "?" })
                // LIMIT 直接内联：Int 无注入风险，且避开老 SQLite 对 LIMIT 绑定参数的挑剔
                append(") ORDER BY RANDOM() LIMIT ").append(want)
            }
            // INTEGER 列亲和度会把数字串转回整数（与既有查询 arrayOf(chatId.toString()) 同口径）
            val rowIds = ArrayList<Long>(want)
            d.rawQuery(sqlSample, chans.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) rowIds += c.getLong(0)
            }
            if (rowIds.isEmpty()) return emptyList()
            // ②取数段：主键探测回表取全字段（rowid 定位，≤ want 次 B 树查找）
            val out = ArrayList<VideoItem>(rowIds.size)
            val sqlFetch = buildString {
                append("SELECT ").append(ITEM_COLS).append(" FROM items WHERE rowid IN(")
                append(rowIds.joinToString(",") { "?" })
                append(")")
            }
            d.rawQuery(sqlFetch, rowIds.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) out += rowToItem(c)
            }
            // 排除键 Kotlin 复查（原 SQL NOT IN 语义的承接，调用方复查双保险不变）+ 取满 limit
            val result = out.filter { it.key !in excludeKeys }.take(limit).toMutableList()
            result.shuffle() // ②按 rowid 序返回，shuffle 回乱序（旧口径）
            android.util.Log.d(
                TAG,
                "随机抽样两段：${chans.size} 频道 → rowid ${rowIds.size} → 命中 ${result.size}，" +
                    "耗时 ${System.currentTimeMillis() - startedAt}ms",
            )
            result
        }.getOrElse {
            Log.w(TAG, "历史回溯随机抽样失败（静默跳过）", it)
            emptyList()
        }
    }

    // ---------------- 索引管理（设置页"清空重建"面板） ----------------

    /**
     * 清空全部索引：items/fts/meta 三表全清 + VACUUM 回收磁盘页。
     * 不 DROP 表——schema 与编译语句缓存（stmtInsert* 等）继续可用，清完即可无缝重爬；
     * 清空后 meta 游标没了，所有频道回到"未建"态：下次 indexChannel 一律按首次建索引
     * 全量重爬（走日历跳爬路径）。旧内存倒排表机制已删除，无 inverted/物化缓存需要复位。
     * 线程安全：writeLock 串行化（与爬取/迁移写互斥；VACUUM 不允许在事务内，在锁内事务外做）。
     * 与在飞爬取并发安全：在飞页随后 INSERT OR IGNORE 落库，即重建的第一批数据。
     * LIKE 兜底路径（无 FTS5）只清 items/meta。
     */
    fun clearAll() {
        if (healWriteBlocked("clearAll")) return // R289 只读回退闸（只读期绝不动库文件）
        val d = openDb()
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                d.execSQL("DELETE FROM items")
                if (useFts5) d.execSQL("DELETE FROM fts")
                d.execSQL("DELETE FROM meta")
                d.execSQL("DELETE FROM $REPOSTS_TABLE") // R266 v11：别名表随全清（key 全域互斥语义复位）
                // R292：item_ocr 随全清（rowid 对齐生死线——不清则重爬新行的 rowid 撞上
                // 旧 OCR 行=张冠李戴错配）。同类扫描立规：tags/tag_items 同病（rowid_ref
                // 悬空错配未来新行）——原实现漏清，本次同刀（新爬经 ingest 解析+delta
                // 聚合自然重建 tags 面，存量回填断点无须复位）
                d.execSQL("DELETE FROM $ITEM_OCR_TABLE")
                d.execSQL("DELETE FROM $TAG_ITEMS_TABLE")
                d.execSQL("DELETE FROM $TAGS_TABLE")
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            // R292：OCR 断点/报告随全清复位（重爬后回填 Job 从头重识别；全清=用户
            // 显式动作，重跑成本已含在「重建」语义内）。ocrAppliedSeen 复位（精确路
            // 复核补丁臂回试点前零开销态）
            ocrAppliedSeen = false
            runCatching { coverOcrBackfillStateFile().delete() }
            runCatching { File(storeDir, "$COVER_OCR_BACKFILL_STATE_FILE.tmp").delete() }
            runCatching { coverOcrApplyStateFile().delete() }
            runCatching { File(storeDir, "$COVER_OCR_APPLY_STATE_FILE.tmp").delete() }
            runCatching { File(storeDir, COVER_OCR_PILOT_REPORT_FILE).delete() }
            // VACUUM 不允许在事务内：锁内事务外回收磁盘页（WAL 下只短暂阻塞写，读不阻塞）；
            // 失败不影响正确性（只是磁盘没立刻瘦下去，后续写/检查点自然收编）。
            // R266 注：此 VACUUM 仅限全清场景（items/refs 已空，rowid 锚无对象）——
            // 非全清的物理压实**严禁裸 VACUUM**（非 IPK 表 rowid 重排团灭 tag_items/
            // refs.canonRowid；只许显式保 rowid 重建换装，设计 §6.3 红线）
            runCatching { d.execSQL("VACUUM") }
                .onFailure { Log.w(TAG, "清空后回收磁盘页失败（不影响正确性）", it) }
            // R266 v11：合并批迁断点同步复位（清库重爬后新转发由写入路径实时归并，
            // 批迁从头扫=空库 O(1) 完成；旧 done 标记不挡）
            runCatching { mergeStateFile().delete() }
            runCatching { File(storeDir, "merge_v11.json.tmp").delete() }
            _mergeProgress.value = null
            // R287：挂起的合并导入一并清场（库已空，合再无意义；暂存源库回收磁盘）
            runCatching { File(storeDir, IndexImportMerge.STAGING_DIR_NAME).deleteRecursively() }
            runCatching { File(storeDir, IndexImportMerge.STATE_FILE_NAME).delete() }
            runCatching { File(storeDir, IndexImportMerge.STATE_FILE_NAME + ".tmp").delete() }
            _importMergeProgress.value = null
        }
    }

    /**
     * 索引库磁盘占用总字节：search.db 主文件 + WAL/SHM 旁文件之和。
     * 纯文件 stat（不开库、任意线程可调用），库还没建（从未建过索引）返回 0。
     * 注意：WAL 文件在检查点合并前可能仍占空间——刚清空后短暂显示非零是正常现象。
     */
    fun dbSizeBytes(): Long {
        var total = 0L
        for (suffix in arrayOf("", "-wal", "-shm")) {
            val f = File(storeDir, DB_NAME + suffix)
            if (f.exists()) total += f.length()
        }
        return total
    }

    // ---------------- 搜索（IO 线程调用；全磁盘操作，内存占用与索引规模无关） ----------------

    /**
     * 全索引本地搜索：
     *  - 精确（[fuzzy]=false，默认）：查询 fold+tokenize（与建表同一 [SearchTextKit.tokenize]
     *    口径）→ FTS5 `MATCH '"t1" "t2" ...'`（词位短语 AND）→ 回 items 表取全字段 →
     *    段级连续 contains 复核（bigram 共现≠连续出现，与旧物化复核同口径）；
     *  - 模糊（[fuzzy]=true）：词位 OR + [SearchTextKit.synonymVariants] 同义词组扩展；
     *  - R288 相关度排序（BM25 轻量变体，[SearchRanker] 纯函数件）：**召回有界后
     *    Kotlin 算分**（先召回再排序，不搞一次 SQL 搞定）——相关度降序优先、
     *    dateEpochSec 降序兜底/并列、key 升序确定性收尾；FTS5 模糊路例外=引擎
     *    `bm25(fts)` 本就是相关度序（bm25 不可用时降级时间倒序）。集合统计
     *    （N/avgdl/df）走 rowid 随机采样估计（[rankStatsOrNull]），统计不可用
     *    （空库/样本太薄/查询异常）时**时间倒序直通**（现状零回归兜底）；
     *  - 召回硬顶 [SEARCH_HARD_CAP]；R293 分代分层召回治召回侧时间偏置
     *    （[likeRecall] 词频闸：估计命中 >4×cap 的超高频词改走时间分代召回，
     *    老年代高相关帖进召回集；判定真值 [RecallSlicePolicy]，残余收窄=
     *    代内仍时间倒序截断+闸下轻微截断不分层，详 r293_notes）；
     *  - 相册展开：命中 albumId≠0 的条目时按 (chatId, albumId) 带出同组全部媒体
     *    （空 caption 成员用同组非空 caption 补），展开后总量封顶 [EXPAND_HARD_CAP]。
     *
     * 已知语义差异（相对旧内存倒排表，均为可接受收窄）：
     *  - 模糊召回不再做拉丁长词 ED≤1 扩展（contentless FTS5 无词表可枚举；bm25 + 同义词
     *    已覆盖主要容错场景）；
     *  - 含标点等不可索引字符的词位被过滤（unicode61 同样不会索引它们，带着只会让 AND 必空集；
     *    跨标点连续串如"好，世"的旧式命中不再支持，标点本就无搜索价值）。
     * 任何 DB 异常只丢这一路：记日志返回空表（调用方还有其他本地源）。
     */
    fun search(query: String, fuzzy: Boolean = false): List<VideoItem> {
        val trimmed = query.trim()
        val phrase = SearchTextKit.fold(trimmed.trimStart('#', '＃'))
        if (phrase.isEmpty()) return emptyList()
        val segs = SEG_SPLIT_REGEX.split(phrase).filter { it.isNotBlank() }
        if (segs.isEmpty()) return emptyList()
        return runCatching {
            val d = openDb()
            val hits = when {
                // R167 #话题精确词模式：[SearchTextKit.isHashtagQuery] 分路（# 前缀+
                // 单词条）。词位候选与精确路同构（词位 AND，召回口径一致），复核换
                // [SearchTextKit.hashtagHits]（# 标记+词边界——防裸子串与词身延续
                // 误伤，尺子见该函数注释）。fuzzy 不参与：标签语义恒精确，同义词组
                // 对 # 词无意义。调用面（DeepSearchFeedSource/搜索页本地腿）凡
                // query 带 # 前缀一律自动获得本语义
                SearchTextKit.isHashtagQuery(trimmed) -> searchHashtag(d, phrase)
                useFts5 && fuzzy -> searchFuzzyFts(d, segs)
                useFts5 -> searchExactFts(d, segs)
                fuzzy -> searchFuzzyLike(d, segs)
                else -> searchExactLike(d, segs)
            }
            if (hits.isEmpty()) {
                emptyList()
            } else {
                // R228：Lazy 兜底（用户追加需求）——相册展开后对未解析条目（item_tags
                // NULL 且 caption 非空，存量回填 Job 未跑到）即时解析+回写+富化返回值；
                // 已解析条目零成本直通，封顶 [LAZY_PARSE_MAX]
                lazyEnrichParsed(d, expandAlbums(d, hits))
            }
        }.getOrElse {
            Log.e(TAG, "深度索引搜索失败 query=\"$query\" fuzzy=$fuzzy fts5=$useFts5", it)
            emptyList()
        }
    }

    /**
     * 提取查询词位（建表同口径 tokenize(forIndex=false)，段级 + 同义词扩展由参数控制）：
     * 过滤含非字母数字字符的词位（unicode61 不会索引它们；LIKE 口径下也不在 tokens 列中——
     * tokens 列存的同样是这些词位的空格连接串）。
     */
    private fun queryTokens(segs: List<String>, withSynonyms: Boolean): List<String> {
        val tokens = LinkedHashSet<String>()
        for (seg in segs) {
            for (t in SearchTextKit.tokenize(seg, forIndex = false)) {
                if (t.isNotEmpty() && t.all { it.isLetterOrDigit() }) tokens += t
            }
            if (withSynonyms) {
                // 同义词组扩展：组内词的词位一并召回（与旧倒排表模糊召回同口径）
                for (syn in SearchTextKit.synonymVariants(seg)) {
                    for (t in SearchTextKit.tokenize(syn, forIndex = false)) {
                        if (t.isNotEmpty() && t.all { it.isLetterOrDigit() }) tokens += t
                    }
                }
            }
        }
        return tokens.toList()
    }

    /**
     * 词位 AND 候选（FTS5 路径，精确搜索与 R167 hashtag 精确词共用）：二者候选集
     * 同构——词位 AND + 时间倒序 + [SEARCH_HARD_CAP] 封顶，差别只在复核谓词，
     * 由调用方各自施加。
     */
    private fun ftsAndCandidates(d: SQLiteDatabase, tokens: List<String>): List<VideoItem> {
        if (tokens.isEmpty()) return emptyList()
        // '"t1" "t2"'：FTS5 查询语法里空格分隔的多个短语 = AND（每个"短语"是单词位）
        val match = tokens.joinToString(" ") { "\"$it\"" }
        val out = ArrayList<VideoItem>()
        d.rawQuery(
            "SELECT $ITEM_COLS FROM fts JOIN items ON items.rowid = fts.rowid " +
                "WHERE fts MATCH ? ORDER BY items.dateEpochSec DESC LIMIT $SEARCH_HARD_CAP",
            arrayOf(match),
        ).use { c -> while (c.moveToNext()) out += rowToItem(c) }
        return out
    }

    /** 精确·FTS5 路径：词位短语 AND，段级连续 contains 复核 + R288 相关度重排 */
    private fun searchExactFts(d: SQLiteDatabase, segs: List<String>): List<VideoItem> {
        val tokens = queryTokens(segs, withSynonyms = false)
        val candidates = ftsAndCandidates(d, tokens)
        // 段级连续 contains 复核（对完整文本 fold，非建表截断版——与旧物化复核同口径）
        // R292：OCR 补丁臂——词位含已并 OCR 的行会被 fts 召回，复核尺必须同源覆盖，
        // 否则 OCR 命中全被 caption-only 复核滤掉（见 exactRecheckWithOcr）
        val filtered = exactRecheckWithOcr(d, candidates, segs)
        // R288：与 LIKE 精确路同一把 BM25 轻量尺（统计采样原料=caption/channelTitle
        // 两列，FTS5 库同样在场——精确路两引擎同尺收敛；统计不可用则时间序直通）
        return rescoreByRelevance(d, filtered, segs.joinToString(" "), tokens)
    }

    /** R292：是否存在已并词位的 OCR 行（apply Job 置位；试点期恒 false=复核零新开销） */
    @Volatile
    private var ocrAppliedSeen = false

    /**
     * R292 精确路复核（OCR 补丁臂）：先按原口径复核 caption+channelTitle；失败的候选
     * 若已有并入索引的 OCR 文本（item_ocr.applied=1，唯一真值），再用含 OCR 文本的
     * 同尺 fold 复核一次——词位配方既含 OCR，复核尺必须同源（否则 fts/LIKE 召回的
     * OCR 命中在本层被全灭，试点即失灵）。惰性批查：全部候选 caption 复核直过时
     * 零 SQL；[ocrAppliedSeen]=false（试点期/未开闸）时同样零 SQL。
     */
    private fun exactRecheckWithOcr(
        d: SQLiteDatabase,
        candidates: List<VideoItem>,
        segs: List<String>,
    ): List<VideoItem> {
        if (!ocrAppliedSeen || candidates.isEmpty()) {
            return candidates.filter { item ->
                val textFold = SearchTextKit.fold(item.caption + " " + item.channelTitle)
                segs.all { textFold.contains(it) }
            }
        }
        // OCR 文本惰性批取（首个复核失败者触发；key 主键经 items 回表 rowid 对齐）
        val ocrByKey by lazy(LazyThreadSafetyMode.NONE) {
            val m = HashMap<String, String>()
            val keys = candidates.map { it.key }
            for (chunk in keys.chunked(TIME_WINDOW_IN_CHUNK)) {
                d.rawQuery(
                    "SELECT i.key, o.text FROM $ITEM_OCR_TABLE o JOIN items i ON i.rowid=o.rowid " +
                        "WHERE o.applied=1 AND o.text != '' AND i.key IN (" +
                        chunk.joinToString(",") { "?" } + ")",
                    chunk.toTypedArray(),
                ).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) ?: "" }
            }
            m
        }
        return candidates.filter { item ->
            val textFold = SearchTextKit.fold(item.caption + " " + item.channelTitle)
            if (segs.all { textFold.contains(it) }) return@filter true
            val ocr = ocrByKey[item.key] ?: return@filter false
            val withOcr = SearchTextKit.fold(textFold + " " + ocr)
            segs.all { withOcr.contains(it) }
        }
    }

    /**
     * R167 hashtag 精确词搜索（**R293 换轨**：LIKE 设备召回主路=tag_items 倒排毫秒级，
     * r236 挂账①/r264 P1 收口）：
     *  - LIKE 设备：**tag_items 倒排为主路**（元素级精确——item_tags 写路径已逐成员
     *    落位，召回免复核；旧路两个已知缺口顺带根治：超长 caption 截断位外的 tag、
     *    混合相册空 caption 成员——item_tags 元素即索引行，不依赖 tokens 词位）；
     *    **解析/聚合覆盖在途**（[tagCoverageLikelyIncompleteThrottled]，r264 P1 口径）
     *    时旧路 LIKE 兜底臂并集（词位候选经 [likeRecall] 词频闸+双轨复核原口径，
     *    成本=现状不新增扫描）——双回填断点 done 后兜底臂**自动退役**，hashtag
     *    搜索全程零全表扫；
     *  - FTS5 设备：原路不动（词位候选+双轨复核；现网零 FTS5 设备=设计内死路径，
     *    换轨收益全在 LIKE 侧）。
     * 语义只认 # 标记：裸词 "tag"（无 #）不算命中（R167 旧路根因修复口径不动——
     * 去 # 后裸子串 contains 会让 "#tag" 误中 "今日tag合集"）。
     * R288：并集后单词位 BM25 重排（tag 反复出现的聚焦 caption 压过长清单体顺带提及）。
     */
    private fun searchHashtag(d: SQLiteDatabase, tagFold: String): List<VideoItem> {
        val tokens = queryTokens(listOf(tagFold), withSynonyms = false)
        if (tokens.isEmpty()) return emptyList()
        // R228 复核双轨（FTS5 路与 LIKE 兜底臂共用；任务书 D 层「tag 查询走 item_tags，
        // 精准命中不再 FTS 全文模糊」）：已解析（item_tags 非 NULL）→ 元素级精确（fold
        // 归一整词相等；空表=已解析无标签=权威排除）；存量未解析 → hashtagHits 旧口径
        //（# 标记+词边界复核），存量行为与 R167 逐字节一致（零破坏回退）。
        fun doubleTrack(item: VideoItem): Boolean {
            val tags = item.itemTags
            return if (tags != null) tags.any { SearchTextKit.fold(it) == tagFold }
            else SearchTextKit.hashtagHits(SearchTextKit.fold(item.caption), tagFold)
        }
        if (useFts5) {
            val filtered = ftsAndCandidates(d, tokens).filter(::doubleTrack)
            return rescoreByRelevance(d, filtered, tagFold, tokens)
        }
        // LIKE 设备：倒排主路（毫秒级；R313 起走 tagRecall 词频闸——超宽 tag 时间分代，
        // 老年代进候选集，清 R293 挂账③）+ 覆盖在途期 LIKE 兜底臂（收敛自动退役）
        val t0 = android.os.SystemClock.elapsedRealtime()
        val merged = LinkedHashMap<String, VideoItem>()
        for (it in tagRecall(d, tagFold, SEARCH_HARD_CAP)) merged[it.key] = it
        val invertedCount = merged.size
        var fallbackNew = 0
        val fallbackOn = tagCoverageLikelyIncompleteThrottled()
        if (fallbackOn) {
            for (it in likeRecall(d, tokens, isAnd = true).filter(::doubleTrack)) {
                if (merged.putIfAbsent(it.key, it) == null) fallbackNew++
            }
        }
        // V10-③ 观察口：倒排/兜底两臂计数+耗时（tag 键与 R236 tagItems 打点同口径先例）
        Log.d(
            TAG,
            "R293 searchHashtag 换轨：tag=#$tagFold 倒排=$invertedCount 兜底臂=$fallbackOn " +
                "兜底新增=$fallbackNew 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms",
        )
        return rescoreByRelevance(d, merged.values.toList(), tagFold, tokens)
    }

    /** 模糊·FTS5 路径：词位（含同义词扩展）OR + bm25 相关度排序（bm25 不可用时降级时间倒序） */
    private fun searchFuzzyFts(d: SQLiteDatabase, segs: List<String>): List<VideoItem> {
        val tokens = queryTokens(segs, withSynonyms = true)
        if (tokens.isEmpty()) return emptyList()
        val match = tokens.joinToString(" OR ") { "\"$it\"" }
        val order = if (bm25Ok) "bm25(fts)" else "items.dateEpochSec DESC"
        return runCatching {
            val out = ArrayList<VideoItem>()
            d.rawQuery(
                "SELECT $ITEM_COLS FROM fts JOIN items ON items.rowid = fts.rowid " +
                    "WHERE fts MATCH ? ORDER BY $order LIMIT $SEARCH_HARD_CAP",
                arrayOf(match),
            ).use { c -> while (c.moveToNext()) out += rowToItem(c) }
            out
        }.getOrElse { e ->
            if (!bm25Ok) throw e
            // 极端老 SQLite 的 contentless 表 bm25 异常：永久降级为时间倒序并重试一次
            bm25Ok = false
            Log.w(TAG, "bm25 不可用，模糊排序降级为发布时间倒序", e)
            searchFuzzyFts(d, segs)
        }
    }

    /**
     * 词位候选单路旧形（LIKE 兜底）：tokens 列 '% 词位 %' 逐词位 AND/OR（定界防子串
     * 误中），时间倒序，硬顶封顶。词位为纯字母数字（queryTokens 已过滤），不含 LIKE
     * 通配符 %/_，无需 ESCAPE。
     * 本机 SQLite 无 FTS5（R158 实锤）时精确/模糊/hashtag 全走 LIKE 族：4GB 索引
     * 全表扫 ~45s 量级（用户设备实测口径，见 r167_notes.md §1.3）。
     * **调用口收敛 [likeRecall]**（词频闸决定单路/分代）——本函数是闸下的单路落形，
     * 也是分层异常时的回落路径（R293 前全站唯一形态=现状零回归锚）。
     */
    private fun likeScanCandidates(d: SQLiteDatabase, tokens: List<String>, isAnd: Boolean): List<VideoItem> {
        if (tokens.isEmpty()) return emptyList()
        val where = tokens.joinToString(if (isAnd) " AND " else " OR ") { "tokens LIKE ?" }
        val args = tokens.map { "% $it %" }.toTypedArray()
        val out = ArrayList<VideoItem>()
        d.rawQuery(
            "SELECT $ITEM_COLS FROM items WHERE $where " +
                "ORDER BY dateEpochSec DESC LIMIT $SEARCH_HARD_CAP",
            args,
        ).use { c -> while (c.moveToNext()) out += rowToItem(c) }
        return out
    }

    /**
     * R293 LIKE 召回唯一决策口（任务①：召回侧时间偏置根治；判定真值
     * [RecallSlicePolicy] 纯函数件）：
     *  - 闸下（统计缺席/时间索引未就绪/估计命中 ≤ [RecallSlicePolicy.GATE_FACTOR]×cap）
     *    → [likeScanCandidates] 单路旧形直通（轻微截断分层不划算=现状零回归）；
     *  - 闸上（超高频词，截断率 ≥75%）→ [likeSlicedCandidates] 时间分代召回：老年代
     *    高相关帖进召回集，BM25 重排才有米下锅。
     */
    private fun likeRecall(d: SQLiteDatabase, tokens: List<String>, isAnd: Boolean): List<VideoItem> {
        if (tokens.isEmpty()) return emptyList()
        val stats = rankStatsOrNull(d)
        if (stats == null || !dateIndexReadyCached(d)) return likeScanCandidates(d, tokens, isAnd)
        val estimate = RecallSlicePolicy.estimateRecallSize(
            isAnd, tokens.map { dfOf(stats, it) }, stats.totalDocs,
        )
        if (!RecallSlicePolicy.shouldStratify(estimate, SEARCH_HARD_CAP)) {
            return likeScanCandidates(d, tokens, isAnd)
        }
        return runCatching { likeSlicedCandidates(d, tokens, isAnd) }
            .onFailure { Log.w(TAG, "R293 分代召回异常，回落单路旧形", it) }
            .getOrElse { likeScanCandidates(d, tokens, isAnd) }
    }

    /**
     * items 全库发布时间量程（idx_items_date 双端 O(1)；R313 从 [likeSlicedCandidates]
     * 抽出供 tag 分代 [tagItemsSlicedRecall] 共用——同一量程件唯一出处）。
     * **调用方必须先过 [dateIndexReadyCached] 门**（无索引时 MIN/MAX=全表扫）。
     * null=库空/无有效日期（调用方回落旧单路）。
     */
    private fun itemsDateRange(d: SQLiteDatabase): Pair<Long, Long>? {
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        d.rawQuery(
            "SELECT MIN(dateEpochSec), MAX(dateEpochSec) FROM items WHERE dateEpochSec > 0",
            null,
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                lo = c.getLong(0)
                hi = c.getLong(1)
            }
        }
        if (lo == Long.MAX_VALUE) return null
        return lo to hi
    }

    /**
     * 分代分层召回（闸上形态）：时间量程等分 [RECALL_SLICE_BUCKETS] 代，每代
     * `INDEXED BY idx_items_date` + 代内日期区间 + ORDER BY dateEpochSec DESC LIMIT k
     *（k=⌈cap×[RECALL_SLICE_OVERSAMPLE]/B⌉）——R202 queryWindow 真机在验的同款有界
     * 形状（覆盖列 WHERE 前导列齐全，不踩 R290b V7「无 WHERE+非覆盖 INDEXED BY=
     * no query solution」雷形；INDEXED BY 缺索引必炸，前置门=likeRecall 的
     * dateIndexReadyCached）。
     * 成本红线：各代区间互不相交→访问量并集 ≤ 全表一遍（最坏=现状同阶）；闸上形态
     * 恒为高频词→各代 LIMIT 早停，总访问量 ≪ N（snap2 实测见 r293_notes）。
     * 词位谓词整组括号化（OR 形态防 AND 优先级吞并日期谓词）。代间 key 去重
     * （区间互斥本不撞，首代 from=0 兜 dateEpochSec=0 行后仍互斥——防御性去重）。
     */
    private fun likeSlicedCandidates(d: SQLiteDatabase, tokens: List<String>, isAnd: Boolean): List<VideoItem> {
        val range = itemsDateRange(d) ?: return likeScanCandidates(d, tokens, isAnd)
        val slices = RecallSlicePolicy.slices(
            range.first,
            range.second,
            RECALL_SLICE_BUCKETS, SEARCH_HARD_CAP, RECALL_SLICE_OVERSAMPLE,
        )
        if (slices.isEmpty()) return likeScanCandidates(d, tokens, isAnd)
        val tokenWhere = tokens.joinToString(if (isAnd) " AND " else " OR ") { "tokens LIKE ?" }
        val merged = LinkedHashMap<String, VideoItem>(SEARCH_HARD_CAP * 2)
        val t0 = android.os.SystemClock.elapsedRealtime()
        for (s in slices) {
            val args = (tokens.map { "% $it %" } + listOf(s.fromSec.toString(), s.toSecExclusive.toString())).toTypedArray()
            d.rawQuery(
                "SELECT $ITEM_COLS FROM items INDEXED BY $DATE_INDEX_NAME " +
                    "WHERE ($tokenWhere) AND dateEpochSec >= ? AND dateEpochSec < ? " +
                    "ORDER BY dateEpochSec DESC LIMIT ${s.limit}",
                args,
            ).use { c -> while (c.moveToNext()) { val it = rowToItem(c); merged.putIfAbsent(it.key, it) } }
        }
        Log.d(
            TAG,
            "R293 分代召回：词位=${tokens.size} 代=${slices.size} 召回=${merged.size} " +
                "耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms（单路旧形上限 $SEARCH_HARD_CAP 条）",
        )
        return merged.values.toList()
    }

    /** 精确·LIKE 兜底路径：词位候选（R293 词频闸：单路/分代）+ 同口径段级复核 + R288 相关度重排 */
    private fun searchExactLike(d: SQLiteDatabase, segs: List<String>): List<VideoItem> {
        val tokens = queryTokens(segs, withSynonyms = false)
        val candidates = likeRecall(d, tokens, isAnd = true)
        // R292：OCR 补丁臂与 FTS5 路同件（LIKE 形态 tokens 列同样被 apply Job 并入 OCR 词位）
        val filtered = exactRecheckWithOcr(d, candidates, segs)
        return rescoreByRelevance(d, filtered, segs.joinToString(" "), tokens)
    }

    /**
     * 模糊·LIKE 兜底路径：词位（含同义词扩展）OR 召回（R293 词频闸：单路/分代）
     * + R288 相关度重排（OR 召回下 BM25 求和天然承载覆盖率：缺位词 tf=0 不贡献——
     * 替代旧「无 bm25 可用按发布时间倒序」降级）。
     */
    private fun searchFuzzyLike(d: SQLiteDatabase, segs: List<String>): List<VideoItem> {
        val tokens = queryTokens(segs, withSynonyms = true)
        val out = likeRecall(d, tokens, isAnd = false)
        return rescoreByRelevance(d, out, segs.joinToString(" "), tokens)
    }

    // ---------------- R288 相关度重排（BM25 轻量变体；算分=[SearchRanker] 纯函数件） ----------------
    //
    // 排序哲学：**召回有界后 Kotlin 算分**。召回=R293 [likeRecall] 词频闸（闸下单路
    // 旧形时间倒序 LIMIT；闸上时间分代，老年代高相关帖进召回集）。排序键唯一
    // 出处 [SearchRanker.rank]（相关度降序 → 时间降序 → key 升序）。
    //
    // 锁图（§八）：rankStatsLock 只护 rankStatsCache/rankDfCache 两件内存结构；
    // 搜索路径全程不持 writeLock（WAL 读不进写锁，与现状 LIKE 扫描同口径）；
    // 采样查询全只读。无「writeLock 内取 rankStatsLock」路径 → 无锁序环。

    /** 相关度集合统计快照（N/avgdl/df 采样原料；不可变，换代整体换装） */
    private class RankStats(
        val totalDocs: Long,
        val avgDocLen: Double,
        /** fold+截断（建表同口径）的样本文本——df 估计原料（countTerm 命中扫描） */
        val sampleTexts: List<String>,
        val loadedAtMs: Long,
    )

    @Volatile
    private var rankStatsCache: RankStats? = null

    private val rankStatsLock = Any()

    /** 词位→df 估计缓存（统计代次内有效；换代清空——LinkedHashMap 超容整表清，foldCache 同族粗粒度淘汰） */
    private val rankDfCache = LinkedHashMap<String, Long>()

    /**
     * 集合统计读口（TTL 缓存）：新鲜直返；过期/缺失 → 锁内双检后 [loadRankStats] 重采样。
     * 返回 null = 统计不可用（空库/样本太薄/查询异常）——调用方时间序直通，不炸不堵。
     */
    private fun rankStatsOrNull(d: SQLiteDatabase): RankStats? {
        val now = System.currentTimeMillis()
        rankStatsCache?.let { if (now - it.loadedAtMs < RANK_STATS_TTL_MS) return it }
        synchronized(rankStatsLock) {
            rankStatsCache?.let { if (now - it.loadedAtMs < RANK_STATS_TTL_MS) return it }
            val stats = loadRankStats(d) ?: return null
            rankStatsCache = stats
            rankDfCache.clear() // 换代：旧统计口径的 df 估计一并作废
            Log.d(
                TAG,
                "R288 相关度统计刷新：N=${stats.totalDocs} avgdl=${"%.1f".format(stats.avgDocLen)} " +
                    "样本=${stats.sampleTexts.size}",
            )
            return stats
        }
    }

    /**
     * 集合统计采样（全部只读查询）：
     *  - N = COUNT(*) items（单语句聚合，百万行级秒级——相对同路径 LIKE 全表扫是零头，
     *    TTL 10min 摊薄）；
     *  - 样本 = rowid 随机点查 [RANK_SAMPLE_ROWS] 行（分块 ≤900 占位符；PK B 树探测
     *    毫秒级；已删行 rowid 空洞自然 miss，实取偏少不碍）；
     *  - avgdl = 样本 [SearchRanker.estimateDocTokens] 均值（与算分侧 dl 同一把尺子，
     *    自洽不漂移）；
     *  - 采样原料 = caption/channelTitle 两列（LIKE/FTS5 双形态库都在场——FTS5 精确路
     *    同享本统计），fold+截断 [MAX_INDEX_TEXT_LEN] 与建表同口径。
     * 样本实取 < [RANK_SAMPLE_MIN_ROWS] = 太薄（小库/rowid 稀疏）→ null（宁可不排）。
     */
    private fun loadRankStats(d: SQLiteDatabase): RankStats? = runCatching {
        val total = d.rawQuery("SELECT COUNT(*) FROM items", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        if (total <= 0L) return null
        val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        if (maxRowid <= 0L) return null
        val rng = java.util.Random(System.nanoTime())
        val rowids = LongArray(RANK_SAMPLE_ROWS) { 1L + (rng.nextLong() and Long.MAX_VALUE) % maxRowid }
        val texts = ArrayList<String>(RANK_SAMPLE_ROWS)
        for (chunk in rowids.toList().chunked(900)) { // 老 SQLite 999 占位符上限留余量
            d.rawQuery(
                "SELECT caption, channelTitle FROM items WHERE rowid IN (" +
                    chunk.joinToString(",") { "?" } + ")",
                chunk.map { it.toString() }.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    texts += SearchTextKit.fold(
                        (c.getString(0) ?: "") + " " + (c.getString(1) ?: ""),
                    ).take(MAX_INDEX_TEXT_LEN)
                }
            }
        }
        if (texts.size < RANK_SAMPLE_MIN_ROWS) return null
        var sum = 0L
        for (t in texts) sum += SearchRanker.estimateDocTokens(t)
        if (sum <= 0L) return null
        RankStats(total, sum.toDouble() / texts.size, texts, System.currentTimeMillis())
    }.getOrNull()

    /**
     * 词位文档频率（df）采样估计（锁内缓存）：样本命中 0 → df=0（[SearchRanker.idf]
     * 平滑到最大值——样本外稀有词=最高区分度，方向正确）；命中 h → h×N/样本量 线性
     * 放大。1024 样本对 df ≥ N/32 级的常见词 CV 可接受，对真稀有词塌到 idf 上限——
     * 两个 regime 都是想要的排序方向（估计噪声只影响中段词的小数点后位次）。
     */
    private fun dfOf(stats: RankStats, token: String): Long {
        synchronized(rankStatsLock) {
            rankDfCache[token]?.let { return it }
            var hits = 0
            for (t in stats.sampleTexts) if (SearchRanker.countTerm(t, token) > 0) hits++
            val df = if (hits == 0) 0L else (hits.toDouble() * stats.totalDocs / stats.sampleTexts.size).toLong()
            if (rankDfCache.size >= RANK_DF_CACHE_MAX) rankDfCache.clear()
            rankDfCache[token] = df
            return df
        }
    }

    /**
     * 相关度重排（searchExactLike/searchFuzzyLike/searchHashtag/searchExactFts 共用；
     * searchFuzzyFts 例外=引擎 bm25 本就在排）。
     * 短路直通（时间序现状零回归）：items≤1 / tokens 空 / 统计不可用。
     * 成本口径：2000 候选 ×（fold 600 字 + ≤16 词位计数扫描 + dl 估算）≈ 千万级
     * 字符操作——相对同路径 LIKE 全表扫（45s 级）是零头；日志打点留证。
     */
    private fun rescoreByRelevance(
        d: SQLiteDatabase,
        items: List<VideoItem>,
        phrase: String,
        tokens: List<String>,
    ): List<VideoItem> {
        if (items.size <= 1 || tokens.isEmpty()) return items
        val stats = rankStatsOrNull(d) ?: return items
        val terms = tokens.map { SearchRanker.Term(it, SearchRanker.idf(stats.totalDocs, dfOf(stats, it))) }
        val corpus = SearchRanker.Corpus(stats.totalDocs, stats.avgDocLen)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val out = SearchRanker.rank(
            items,
            scoreOf = { item ->
                val textFold = SearchTextKit.fold(item.caption + " " + item.channelTitle)
                    .take(MAX_INDEX_TEXT_LEN)
                SearchRanker.scoreDoc(terms, textFold, SearchRanker.estimateDocTokens(textFold), corpus, phrase)
            },
            dateSecOf = { it.dateEpochSec.toLong() },
            keyOf = { it.key },
        )
        Log.d(TAG, "R288 相关度重排：候选=${items.size} 词位=${terms.size} 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        return out
    }

    /**
     * 相册展开（双保险）："一条消息多种媒体都要能被搜到"。
     *  1. albumId≠0（迁移后新爬/回填数据）：按 (chatId, albumId) 精确带出同组全部媒体；
     *  2. albumId=0（迁移存量：老 JSON 没存 albumId）：邻近窗口近似——TG 相册成员的
     *     messageId 天然连续，按 chatId 分组把命中 messageId ±[ALBUM_WINDOW_RADIUS]
     *     归并成区间（相接/重叠的窗口合一，密集命中不重复扫描），带出窗口内全部媒体条目
     *     （索引只收录媒体条目，无需再过滤类型）。代价：可能带进相邻的独立消息——
     *     用户实测口径是"宁多勿漏"。
     * 组内共享 caption 语义：空 caption 成员用同组/窗口内非空 caption 补（命中条目优先，
     * 其次组内任一非空成员）——不补的话这些成员会在调用方的相关性打分里拿 0 分被过滤掉。
     * 展开后总量封顶 [EXPAND_HARD_CAP]（512MB 堆护栏）。
     */
    private fun expandAlbums(d: SQLiteDatabase, hits: List<VideoItem>): List<VideoItem> {
        val byKey = LinkedHashMap<String, VideoItem>(hits.size * 2)
        for (it in hits) byKey[it.key] = it

        // —— 第一路：albumId≠0 精确同组带出 ——
        val albums = LinkedHashSet<Pair<Long, Long>>()
        for (it in hits) if (it.albumId != 0L) albums += it.chatId to it.albumId
        if (albums.isNotEmpty()) {
            // 命中条目里的组 caption 预提（O(hits) 一次，免逐组 O(albums×hits) 扫描）
            val hitCaption = HashMap<Pair<Long, Long>, String>()
            for (it in hits) {
                if (it.albumId != 0L && it.caption.isNotBlank()) {
                    hitCaption.putIfAbsent(it.chatId to it.albumId, it.caption)
                }
            }
            for ((chatId, albumId) in albums) {
                if (byKey.size >= EXPAND_HARD_CAP) return byKey.values.toList()
                val members = ArrayList<VideoItem>()
                d.rawQuery(
                    "SELECT $ITEM_COLS FROM items WHERE chatId=? AND albumId=? ORDER BY messageId ASC",
                    arrayOf(chatId.toString(), albumId.toString()),
                ).use { c -> while (c.moveToNext()) members += rowToItem(c) }
                // R266 v11：相册成员可能部分被收编（idx_refs_album 臂补全）——相册
                // 完整性不破（收编成员经 canonical 合成回原位）
                val refMembers = ArrayList<RefsPubRow>()
                d.rawQuery(
                    "SELECT $REFS_PUB_COLS FROM $REPOSTS_TABLE WHERE chatId=? AND albumId=? ORDER BY messageId ASC",
                    arrayOf(chatId.toString(), albumId.toString()),
                ).use { c -> while (c.moveToNext()) refMembers += cursorToRefsRow(c) }
                if (refMembers.isNotEmpty()) {
                    members += synthesizeRefItems(d, refMembers).values
                    members.sortBy { it.messageId } // 双臂并入后还原 messageId 升序
                }
                addMembers(
                    byKey, members,
                    hitCaption[chatId to albumId]
                        ?: members.firstOrNull { it.caption.isNotBlank() }?.caption,
                )
            }
        }

        // —— 第二路：albumId=0 存量命中，邻近窗口近似带出 ——
        val legacyHits = hits.filter { it.albumId == 0L }
        if (legacyHits.isNotEmpty()) {
            val byChat = legacyHits.groupBy { it.chatId }
            for ((chatId, chatHits) in byChat) {
                if (byKey.size >= EXPAND_HARD_CAP) break
                // 命中 messageId 排序后归并 ±RADIUS 窗口为区间（相接/重叠即合一）
                val sorted = chatHits.map { it.messageId }.sorted()
                var lo = sorted.first() - ALBUM_WINDOW_RADIUS
                var hi = sorted.first() + ALBUM_WINDOW_RADIUS
                var i = 1
                while (true) {
                    if (i < sorted.size && sorted[i] - ALBUM_WINDOW_RADIUS <= hi) {
                        hi = maxOf(hi, sorted[i] + ALBUM_WINDOW_RADIUS) // 窗口相接：并入当前区间
                        i++
                        continue
                    }
                    expandWindowRange(d, byKey, chatId, lo, hi, chatHits) // 区间封口：带出窗口媒体
                    if (byKey.size >= EXPAND_HARD_CAP) return byKey.values.toList()
                    if (i >= sorted.size) break
                    lo = sorted[i] - ALBUM_WINDOW_RADIUS
                    hi = sorted[i] + ALBUM_WINDOW_RADIUS
                    i++
                }
            }
        }
        return byKey.values.toList()
    }

    /**
     * 邻近窗口带出（albumId=0 存量命中的相册近似）：[lo, hi] messageId 区间内全部媒体条目
     * 并入结果（chatId+messageId 索引范围扫描，毫秒级）；空 caption 成员补窗口内非空 caption
     * （窗口内命中条目的非空 caption 优先，其次窗口内任一非空成员）。
     */
    private fun expandWindowRange(
        d: SQLiteDatabase,
        byKey: LinkedHashMap<String, VideoItem>,
        chatId: Long,
        lo: Long,
        hi: Long,
        chatHits: List<VideoItem>,
    ) {
        val members = ArrayList<VideoItem>()
        d.rawQuery(
            "SELECT $ITEM_COLS FROM items WHERE chatId=? AND messageId BETWEEN ? AND ? ORDER BY messageId ASC",
            arrayOf(chatId.toString(), lo.toString(), hi.toString()),
        ).use { c -> while (c.moveToNext()) members += rowToItem(c) }
        // R266 v11：窗口内被收编成员补全（refs 臂同区间，canonical 合成回原位）
        val refMembers = ArrayList<RefsPubRow>()
        d.rawQuery(
            "SELECT $REFS_PUB_COLS FROM $REPOSTS_TABLE WHERE chatId=? AND messageId BETWEEN ? AND ? ORDER BY messageId ASC",
            arrayOf(chatId.toString(), lo.toString(), hi.toString()),
        ).use { c -> while (c.moveToNext()) refMembers += cursorToRefsRow(c) }
        if (refMembers.isNotEmpty()) {
            members += synthesizeRefItems(d, refMembers).values
            members.sortBy { it.messageId }
        }
        addMembers(
            byKey, members,
            chatHits.firstOrNull { it.messageId in lo..hi && it.caption.isNotBlank() }?.caption
                ?: members.firstOrNull { it.caption.isNotBlank() }?.caption,
        )
    }

    /** 展开成员并入结果（key 去重；空 caption 成员补组 caption）；达 [EXPAND_HARD_CAP] 即停 */
    private fun addMembers(
        byKey: LinkedHashMap<String, VideoItem>,
        members: List<VideoItem>,
        groupCaption: String?,
    ) {
        for (m in members) {
            if (byKey.size >= EXPAND_HARD_CAP) return
            if (byKey.containsKey(m.key)) continue // 直接命中的成员保留自身 caption
            byKey[m.key] = if (m.caption.isBlank() && !groupCaption.isNullOrEmpty()) {
                m.copy(caption = groupCaption)
            } else {
                m
            }
        }
    }

    /**
     * 行 → VideoItem：playUri=null（ensurePlayable 链现取）、coverBytes=null（封面走消息反解）。
     * R228：18 列口径下尾读 parsed_title（null=回退旧口径）/item_tags（JSON→List；
     * null=未解析存量，空表=已解析无标签）。**列数防御**：查询方一律走 [ITEM_COLS]
     * （18 列）；万一出现窄游标（columnCount<18，防御性兜底）按 null 处理不炸。
     */
    private fun rowToItem(c: android.database.Cursor, base: Int = 0): VideoItem {
        val mime = c.getString(base + 10)?.takeIf { it.isNotBlank() } ?: "video/mp4"
        val parsedTitle = if (c.columnCount > base + COL_PARSED_TITLE) c.getString(base + COL_PARSED_TITLE) else null
        val itemTags = if (c.columnCount > base + COL_ITEM_TAGS) parseTagsJson(c.getString(base + COL_ITEM_TAGS)) else null
        return VideoItem(
            key = c.getString(base + 0),
            chatId = c.getLong(base + 1),
            messageId = c.getLong(base + 2),
            albumId = c.getLong(base + 3),
            mediaType = c.getInt(base + 4),
            coverFileId = c.getInt(base + 5),
            caption = c.getString(base + 6) ?: "",
            channelTitle = c.getString(base + 7) ?: "",
            fileId = c.getInt(base + 8),
            fileUniqueId = c.getString(base + 9) ?: "",
            mimeType = mime,
            durationSec = c.getInt(base + 11),
            sizeBytes = c.getLong(base + 12),
            width = c.getInt(base + 13),
            height = c.getInt(base + 14),
            dateEpochSec = c.getInt(base + 15),
            playUri = null,   // 不落库：点播放走 ensurePlayable 既有链现取
            coverBytes = null, // 不落库：封面走 loadCoverBytesByMessage 消息反解
            parsedTitle = parsedTitle,
            itemTags = itemTags,
        )
    }

    // ---------------- R266 v11：两段法共用件（refs 臂查询 + canonical 合成） ----------------

    /** v11 refs 发布侧行（两段法第一段的 refs 臂产物；内容侧待 canonical 批量补） */
    private class RefsPubRow(
        val key: String,
        val chatId: Long,
        val messageId: Long,
        val albumId: Long,
        val mediaType: Int,
        val durationSec: Int,
        val coverFileId: Int,
        val channelTitle: String,
        val dateEpochSec: Long,
        val canonRowid: Long,
        val captionOverride: String?,
    )

    /** refs 臂查询列（顺序固定=RefsPubRow 读下标口径） */
    private val REFS_PUB_COLS =
        "key, chatId, messageId, albumId, mediaType, durationSec, coverFileId, channelTitle, " +
            "dateEpochSec, canonRowid, caption"

    /** refs 臂游标 → RefsPubRow（列序=REFS_PUB_COLS） */
    private fun cursorToRefsRow(c: android.database.Cursor): RefsPubRow =
        RefsPubRow(
            key = c.getString(0),
            chatId = c.getLong(1),
            messageId = c.getLong(2),
            albumId = c.getLong(3),
            mediaType = c.getInt(4),
            durationSec = c.getInt(5),
            coverFileId = c.getInt(6),
            channelTitle = c.getString(7) ?: "",
            dateEpochSec = c.getLong(8),
            canonRowid = c.getLong(9),
            captionOverride = if (c.isNull(10)) null else c.getString(10),
        )

    /**
     * v11 两段法第二段：refs 发布侧行 → VideoItem（canonical 批量 IN 补内容侧，
     * sampleRandom 两段法先例）。发布侧字段以 refs 行为准（本频道的发布身份/时刻/
     * 相册归属/封面）；内容侧（fileId/mime/size/宽高/parsed/itemTags）以 canonical
     * 为准；caption = 覆盖列 ?: canonical caption（D2 忠实保留）。canonical 缺失
     * （evict 提升窗口期防御）：该行跳过——宁可少显示一条不错指内容。
     */
    private fun synthesizeRefItems(d: SQLiteDatabase, refs: List<RefsPubRow>): Map<String, VideoItem> {
        if (refs.isEmpty()) return emptyMap()
        val canonByRowid = HashMap<Long, VideoItem>(refs.size * 2)
        for (chunk in refs.map { it.canonRowid }.distinct().chunked(TIME_WINDOW_IN_CHUNK)) {
            d.rawQuery(
                "SELECT rowid, $ITEM_COLS FROM items WHERE rowid IN (" +
                    chunk.joinToString(",") { "?" } + ")",
                chunk.map { it.toString() }.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) canonByRowid[c.getLong(0)] = rowToItem(c, base = 1)
            }
        }
        val out = HashMap<String, VideoItem>(refs.size * 2)
        for (r in refs) {
            val canon = canonByRowid[r.canonRowid] ?: continue
            out[r.key] = canon.copy(
                key = r.key,
                chatId = r.chatId,
                messageId = r.messageId,
                albumId = r.albumId,
                mediaType = r.mediaType,
                coverFileId = r.coverFileId,
                channelTitle = r.channelTitle,
                dateEpochSec = r.dateEpochSec.toInt(),
                caption = r.captionOverride ?: canon.caption,
            )
        }
        return out
    }

    /**
     * v11 两段法第一段的归并：items 臂（messageId 新→旧）与 refs 臂（同序）按
     * messageId DESC 归并取 [offset, offset+limit) 窗——两臂各自索引导航有界
     * （idx_items_chat_msg / idx_refs_chat_msg 同形），绝不走 posts_view 物化
     * （老 SQLite 不把 WHERE 压进 UNION ALL 分支，视图只服务全表型聚合）。
     * 返回窗内 items 行直接可用 + refs 行待 [synthesizeRefItems] 合成。
     */
    private fun mergeChannelWindow(
        itemsArm: List<VideoItem>,
        refsArm: List<RefsPubRow>,
        offset: Int,
        limit: Int,
    ): Pair<List<VideoItem>, List<RefsPubRow>> {
        val outItems = ArrayList<VideoItem>(limit)
        val outRefs = ArrayList<RefsPubRow>(limit)
        var i = 0
        var j = 0
        var skipped = 0
        while (outItems.size + outRefs.size < limit) {
            val it = itemsArm.getOrNull(i)
            val rf = refsArm.getOrNull(j)
            if (it == null && rf == null) break
            // 同 messageId 跨臂不可能（同 key 全域互斥）；取较大者，平手取 items 臂（防御）
            val takeItem = when {
                rf == null -> true
                it == null -> false
                else -> it.messageId >= rf.messageId
            }
            if (skipped < offset) {
                skipped++
                if (takeItem) i++ else j++
                continue
            }
            if (takeItem) {
                outItems += it!!
                i++
            } else {
                outRefs += rf!!
                j++
            }
        }
        return outItems to outRefs
    }

    /**
     * v11 频道域两段法总装（channelItems/Page/ByHourPage 共用）：两臂查询 →
     * [mergeChannelWindow] 归并 → refs 合成 → 按 messageId DESC 还原全序输出。
     * [refsWhere] = refs 臂附加谓词（如时段窗的小时谓词，不含前导 AND 也可自带）。
     */
    private fun channelItemsTwoArm(
        d: SQLiteDatabase,
        chatId: Long,
        limit: Int,
        offset: Int,
        extraPredicate: String = "",
        extraArgs: List<String> = emptyList(),
    ): List<VideoItem> {
        // 两臂各取 offset+limit（归并上界——单臂最坏全贡献窗口）
        val armNeed = (offset.toLong() + limit.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val itemsArm = ArrayList<VideoItem>(armNeed.coerceAtMost(4096))
        d.rawQuery(
            "SELECT $ITEM_COLS FROM items WHERE chatId=?$extraPredicate ORDER BY messageId DESC LIMIT $armNeed",
            (listOf(chatId.toString()) + extraArgs).toTypedArray(),
        ).use { c -> while (c.moveToNext()) itemsArm += rowToItem(c) }
        val refsArm = ArrayList<RefsPubRow>(armNeed.coerceAtMost(4096))
        d.rawQuery(
            "SELECT $REFS_PUB_COLS FROM $REPOSTS_TABLE WHERE chatId=?$extraPredicate ORDER BY messageId DESC LIMIT $armNeed",
            (listOf(chatId.toString()) + extraArgs).toTypedArray(),
        ).use { c -> while (c.moveToNext()) refsArm += cursorToRefsRow(c) }
        if (refsArm.isEmpty()) {
            // 无收编行=存量形态直通（省一次合成 Map；mergeChannelWindow 结果本就是 items 前缀窗）
            return itemsArm.drop(offset).take(limit)
        }
        val (winItems, winRefs) = mergeChannelWindow(itemsArm, refsArm, offset, limit)
        val synthesized = synthesizeRefItems(d, winRefs)
        val merged = ArrayList<VideoItem>(winItems.size + synthesized.size)
        merged += winItems
        merged += synthesized.values
        merged.sortByDescending { it.messageId }
        return merged
    }

    // ---------------- 爬取（逻辑原样保留，持久化目标换成 DB） ----------------

    /** 单频道爬取轮状态（游标/完成态/媒体清扫相位；每页由 persistPage 落库推进） */
    private class CrawlState(
        val chatId: Long,
        var cursor: Long,       // 已索引到的最老 messageId；0 = 未开始（媒体清扫期=清扫起点，冻结）
        var exhausted: Boolean, // true = 已翻到底
        var mediaPass: Int,     // 媒体清扫相位：-1=未进入（旧数据）/ 0视频 / 1图片 / 2音频 / 3=清扫完成
        // R45（根因 R2）：清扫相位内游标（messageId，0=相位起点）；每页落盘，
        // flood/进程死亡后从断点续扫，不再从相位页 0 整体重扫
        var sweepCursor: Long,
        // R45（根因 R3）：上次头部刷新墙钟毫秒（0=从未刷过=立即到期），6h 节流闸依据
        var lastHeadRefreshTs: Long,
        // R46（方案 c）：时间分层采样标记——true = 本频道是按月锚点采样覆盖而非全量翻页
        // （口径诚实：UI 展示覆盖度要能区分全量/采样；采样频道置 exhausted 出队，
        //  之后只剩 6h 节流头部刷新保新内容）
        var sampled: Boolean,
        // R163 智能分档：meta.tier 落库值（-1=LEGACY，0..3=T0..T3）。档位唯一写入方 =
        // setTier（B4 派工前评估落档）；本类按档位参数化四条爬取路径 + 收尾 evict。
        // 分档关时恒 -1（LEGACY 参数 = 分档前现状，行为零变化）
        var tier: Int,
        // R262b 死档标记（meta.dead v10）：true = 全相位清扫到底仍零产出的无媒体
        // 频道，头刷降频 7d 探活（DeadChannelPolicy 唯一判定出处）
        var dead: Boolean,
        // R284 时效感知（meta.ttlSec v12）：messageAutoDeleteTime 秒，0=不自毁。
        // 唯一写入方 = noteChatTtls（目录枚举顺手落，零新增请求）；头刷覆盖闸
        // （CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs）的判定原料
        var ttlSec: Int,
        // R286 活跃新鲜度（meta.lastMsgSec v13）：服务器 Chat.lastMessage.date 秒，
        // 0=未知。唯一写入方 = noteChatLastMessages（枚举拍只 UPDATE 已有行）；
        // 活跃度头刷提频闸（CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs）
        // 的判定原料
        var lastMsgSec: Long,
    )

    /**
     * 建/续建一个频道的全量索引（suspend，请在 IO 协程里调用）。
     * [onProgress] 每页回调一次当前累计已索引条数。
     * 断点从 meta 表读（JSON 迁移导入的断点也在这里，迁移完无缝续爬）；
     * 每页一个事务落库（items/fts INSERT OR IGNORE + meta 游标推进），
     * 单页失败（非 flood）直接中断本轮，已爬页不丢，下次调用自动续爬。
     *
     * R46：per-chatId 单飞闸——同一频道同时在爬最多一份（轮循环/设置页手动入口/
     * 搜索证据插队三路可并发派工到同一频道；后到者零成本跳过，进度不丢）。
     *
     * R163 B3 智能分档：[tiering] 缺省 = 分档关——档位读 meta.tier（唯一写入方
     * setTier，B4 派工前落档；缺省 LEGACY），爬取四路径参数与分档前逐项一致
     * （纪律①行为零变化）；分档开时档位参数化条数上限/头刷节流/采样闸豁免/
     * 加深闸/探针页预算（映射唯一出处 [IndexTierPolicy]），收尾触发全局预算
     * evict（分档关永不触发）。
     */
    suspend fun indexChannel(
        gateway: TdGateway,
        chatId: Long,
        tiering: IndexTierPolicy.TieringConfig = IndexTierPolicy.TieringConfig(),
        onProgress: (Int) -> Unit,
    ) {
        // P0 演示模式护栏：Mock 网关游标恒非空（"o:$n" 永远有"下一页"）且条目 key 永远新鲜——
        // 尾部续爬永不终止、DB 无限膨胀。演示数据对深度搜索本无意义，直接拒绝建索引
        // （错误文案透到设置页 _error/Toast）。
        if (gateway is MockTdGateway) {
            throw UnsupportedOperationException("演示模式不支持建立深度索引")
        }
        if (healWriteBlocked("indexChannel")) return // R289 只读回退闸（爬取主路全停，读路径照常）
        if (!indexingChats.add(chatId)) {
            Log.d(TAG, "单飞闸生效：chatId=$chatId 已在爬取中，本次派工跳过（防同频道双爬）")
            return
        }
        try {
            indexChannelInner(gateway, chatId, tiering, onProgress)
            // R163 收尾存储约束检查：分档关时零开销直通（纪律①——现状无界增长照旧）；
            // 分档开时扳机=磁盘水位线 ∨ 用户索引库预算（修订令版，见 evictIfNeeded）
            if (tiering.enabled) runCatching { evictIfNeeded(tiering) }
        } finally {
            indexingChats.remove(chatId)
        }
    }

    /** indexChannel 本体（单飞闸包住的重入安全壳之下；调用方须先过 [indexingChats]） */
    private suspend fun indexChannelInner(
        gateway: TdGateway,
        chatId: Long,
        tiering: IndexTierPolicy.TieringConfig,
        onProgress: (Int) -> Unit,
    ) {
        val d = openDb()
        val st = readMeta(d, chatId)
        // R163 B3：档位（meta.tier 落库值）。档位参数映射（条数上限/头刷节流/采样豁免/
        // 加深闸/探针预算）唯一出处 = IndexTierPolicy，LEGACY 档各参数与分档前常数
        // 逐项相等（反射机检测试锁同步）。
        // R255 W2 档位闸（CrawlThroughputPolicy.effectiveTier 唯一实现处）：分档关时
        // **不读 meta.tier**（一律 LEGACY 参数）——历史残留档位行（R163 B7 注入
        // 180×T1+168×T3、evict 复位 tier=3）在关闭态曾被 tierCap/headRefreshIntervalMs/
        // deepenAllowed 照读：T3 条上限=0 秒退、T1=20000 提前封口、T3 头刷=MAX_VALUE
        // （16416 实测 348 个毒行全场空转 ≈240 条/h）。本行是「分档关=现状锚」
        // （B3 设计意图）的权威闸。
        val tier = CrawlThroughputPolicy.effectiveTier(st.tier, tiering.enabled)
        // R163 B3 探针模式：分档开的 T2/T3 全新频道首爬只跑探针页预算（每派工
        // [probePageBudget] 个清扫页 ≈100 条，评估 R 后升档才放开深度；怪兽采样闸
        // 对探针档跳过——探针比按月采样更便宜）
        val probePages = if (tiering.enabled && (tier == IndexTierPolicy.IndexTier.T2 || tier == IndexTierPolicy.IndexTier.T3)) {
            IndexTierPolicy.probePageBudget(tiering)
        } else {
            Int.MAX_VALUE
        }
        // R255 W3 探针解锁（CrawlThroughputPolicy.itemCapFor 唯一实现处）：探针档条上限
        // 下限=探针页预算×页大小——T3 tierCap=0 曾让探针在首个 cap 检查（total>=0）
        // 即死，R163 探针从未真正取到过一页（丰度升档链路首次真正可用）
        val itemCap = CrawlThroughputPolicy.itemCapFor(
            IndexTierPolicy.tierCap(tier, tiering), probePages, PAGE_SIZE,
        )

        // 单页落库（同事务）：items/fts INSERT OR IGNORE（key 判重幂等，与旧 byKey.putIfAbsent
        // 同口径，已存在条目不更新）+ meta 游标推进。返回本页**新增**条数——头部刷新
        // "页内出现已收录条目即停"的判定依据。爬取与迁移并发时由 writeLock 串行化
        // （编译语句非线程安全；单页事务毫秒级，互不拖累）。
        // R255 W7：第 4 参 [sweepCursor]（≥0=本页顺带推进清扫相位内游标，-1=不动）——
        // 媒体清扫页原为「页事务+独立游标事务」两笔，并入后每页一笔事务（断点语义
        // 不变：同页原子落盘）。
        fun persistPage(
            items: List<VideoItem>,
            newCursor: Long,
            newExhausted: Boolean,
            sweepCursor: Long = -1L,
        ): Int {
            var fresh = 0
            // R255 W11b：LIKE 词位串页级预计算（锁外 CPU——writeLock 是 8 车道页落库
            // 的全局串行点，fold+tokenize 每页 ~100-300ms 曾在锁内白占；FTS5 路径
            // 词位延到插入成功后算（W6），无需预计算）
            val precomputed = if (!useFts5 && items.isNotEmpty()) items.map { indexTokensOf(it) } else null
            synchronized(writeLock) {
                d.beginTransaction()
                try {
                    for ((i, item) in items.withIndex()) {
                        if (insertItemLocked(d, item, backfillOnConflict = true, precomputedTokens = precomputed?.get(i))) fresh++
                    }
                    // R228：入库后同事务解析落位（单条=行 caption；相册=库内成员全集
                    // 逐成员——跨页/跨相位成员到齐后重解析自愈）。runCatching 防御：
                    // 解析是热路径上的新代码，自身异常绝不回滚爬取页事务（INSERT OR
                    // IGNORE 幂等下重爬虽安全但烧配额——丢解析保页面）
                    // R255 W6：fresh==0（全冲突页/触顶收尾空页）跳过解析——重解析同值
                    // 幂等但纯浪费（头部刷新 20 页×100 条全冲突页曾是每页一次的纯
                    // CPU 税）；相册末成员到达页必有 fresh>0，跨页自愈链不受影响；
                    // 漏网行由 R228 Lazy 兜底在搜索命中时补解析（既有机制不变）
                    if (fresh > 0) runCatching { applyParsedCaptionsLocked(d, chatId, items) }
                        .onFailure { Log.w(TAG, "R228 解析落位失败（本页跳过，页事务不受影响）：${it.message}") }
                    upsertMetaCrawlLocked(d, chatId, newCursor, newExhausted)
                    // R262b 死档钩（判定唯一出处 DeadChannelPolicy；此处是运行期落形）：
                    //  ①完成态落位且零产出（非采样）→ 标死——置 exhausted=true 的
                    //    路径全是已验证到底（清扫 DONE/头部翻到底/尾爬到西/日历跳爬
                    //    完成），当前修复版代码下的完成态可信，直接收口；
                    //  ②任何爬取路径采到新鲜条目 → 清死档（死档不是单向门——
                    //    7d 探活刷到新帖即复活回 6h 头刷）。
                    if (newExhausted && !st.sampled && countItems(d, chatId) == 0 && !st.dead) {
                        setDeadLocked(d, chatId, true)
                        st.dead = true
                        Log.i(TAG, "死档标记 chatId=$chatId（完成态零产出，头刷降频 7d 探活）")
                    }
                    if (DeadChannelPolicy.shouldReviveDeadOnFresh(fresh) && st.dead) {
                        setDeadLocked(d, chatId, false)
                        st.dead = false
                        Log.i(TAG, "死档复活 chatId=$chatId（采到新鲜条目 $fresh 条，恢复 6h 头刷）")
                    }
                    // R255 W7：相位内游标同事务推进（原独立 setSweepCursor 事务删除）
                    if (sweepCursor >= 0L) setSweepCursorLocked(d, chatId, sweepCursor)
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }
            st.cursor = newCursor
            if (newExhausted) st.exhausted = true
            if (sweepCursor >= 0L) st.sweepCursor = sweepCursor
            return fresh
        }

        val td = gateway as? TdLibGateway

        // ---- R46 时间分层采样闸（方案 c）：全新频道先探媒体总量估规模 ----
        // 规模证据 = probeMediaTotalCount（2 次 SearchChatMessages 计数请求；本环境实测
        // GetChatMessageCalendar 挂死不可用，见网关侧注释）。超 [SAMPLE_MONSTER_MIN_ITEMS] 的
        // 怪兽频道不做全量翻页（最坏 500 清扫页），改"按月锚点进历史"采样
        // （[sampleChannelByMonthAnchors]：逐月 GetChatMessageByDate 锚点 + 每活跃月
        // 三类型各 ≤[SAMPLE_PAGES_PER_MONTH_PER_TYPE] 个清扫页），采样完成打 sampled 标记
        // + 置 exhausted 出队（之后只剩 6h 节流头部刷新保新内容）。
        // 非怪兽频道照走下方全量媒体清扫，行为零变化；探测失败（flood/超时）不赌，
        // 静默走全量清扫。仅首次进爬（未进过清扫相位）的频道评估——
        // 已在全量爬半途的频道不切采样（断点语义不掺水）。
        // R163 B3 例外：T0 豁免（关注频道就是要全量，怪兽也全爬——M2）；
        // T2/T3 探针档跳过（探针 1 页比按月采样更便宜，升档后再谈深度）。
        // 分档关时 tier=LEGACY 两者都不命中=现状。
        if (td != null && !st.exhausted && st.cursor == 0L && st.mediaPass < 0 && !st.sampled &&
            !IndexTierPolicy.samplingGateExempt(tier) && probePages == Int.MAX_VALUE
        ) {
            if (!crawlBudgetAllow(2, "采样闸规模探测", chatId)) return
            rateGate.await(crawlRateIntervalMs()) // R255 W5：平滑配速（探测 2 请求记 1 槽位，锚点请求同口径）
            val mediaTotal = td.probeMediaTotalCount(chatId)
            if (mediaTotal != null) {
                if (mediaTotal > SAMPLE_MONSTER_MIN_ITEMS) {
                    Log.d(TAG, "采样闸命中怪兽频道 chatId=$chatId 媒体总量=$mediaTotal > $SAMPLE_MONSTER_MIN_ITEMS，改按月采样")
                    sampleChannelByMonthAnchors(td, d, st, ::persistPage, onProgress)
                    return
                }
                Log.d(TAG, "采样闸评估 chatId=$chatId 媒体总量=$mediaTotal ≤ $SAMPLE_MONSTER_MIN_ITEMS，走全量清扫")
            }
        }

        // ---- 媒体清扫通道（第八轮提速主路径：SearchChatMessages + 媒体过滤器，跳过纯文字闲聊页） ----
        // 游标兼容性已实证：fetchAggregatedMedia 内部 SearchChatMessages(fromMessageId) 向下翻页，
        // 游标 = 本页最老消息 id——与 GetChatHistory 游标同为 messageId 语义，两通道互通，落库路径不变。
        // 适用全部未到底频道（真实网关）：全新频道从顶部扫；旧部分爬频道（cursor>0）从旧游标向下扫
        // （其上区域旧爬取已全覆盖）；中断按持久化的 (mediaPass, 清扫起点) 续扫。
        // 清扫完成后 st.exhausted=true——后续轮次只剩头部刷新（旧路 GetChatHistory，
        // 评论级联/文字批注关联在头部刷新里继续生效，新内容保真度不丢）。
        if (td != null && !st.exhausted) {
            // R255 W2：tier 由调用方闸定后的有效档位透传（mediaSweep 不再读 st.tier 防泄漏）
            when (mediaSweep(td, d, st, ::persistPage, onProgress, pageBudget = probePages, itemCap = itemCap, tier = tier)) {
                SweepResult.DONE -> {
                    // 三类型全到底 = 频道媒体全量完成（cursor 保持清扫起点值，此后头部刷新接管）
                    persistPage(emptyList(), st.cursor, true)
                    onProgress(countItems(d, chatId))
                    synchronized(sweepFailCount) { sweepFailCount.remove(chatId) }
                    // 浅爬标记频道顺带补深（旧路低速预算制；无标记立即返回；
                    // R163 B3：档位闸——T1/T2 触顶即终态不加深，见 deepenLegacy 入口）
                    deepenLegacy(gateway, d, st, ::persistPage, onProgress, tier)
                    return
                }
                SweepResult.CAPPED -> {
                    // R255 W1 触顶即终态：完成态落盘出队（不计失败、不退旧路兜底——
                    // needDeepen 已在 mediaSweep 内对 deepenAllowed 档位置位，cap 提升
                    // 后经 setTier 升档复活或加深轮自然续）
                    persistPage(emptyList(), st.cursor, true)
                    onProgress(countItems(d, chatId))
                    synchronized(sweepFailCount) { sweepFailCount.remove(chatId) }
                    return
                }
                SweepResult.BUDGET_CUT -> {
                    // R163 B3 探针/预算截断：正常中断不是失败——不进失败熔断计数
                    // （相位游标已落盘断点续扫，下轮派工按 B4 档位节奏继续）；
                    // 也不退旧路兜底（探针档的语义就是少量页，旧路会绕过页预算）
                    Log.d(
                        TAG,
                        "探针/档位页预算截断 chatId=$chatId tier=$tier 预算页=$probePages " +
                            "累计=${countItems(d, chatId)}（断点已落盘，评估升档后放开深度）",
                    )
                    return
                }
                SweepResult.DENIED -> {
                    // R255：爬取泳道预算耗尽（滑窗恢复后下轮续扫）——非失败：不进熔断
                    // 计数（防"干涸期连拒 3 次被毒到旧路兜底"）、不退旧路、相位断点已落盘
                    Log.d(TAG, "媒体清扫预算拒 chatId=$chatId（非失败：断点已落盘，滑窗恢复后续扫）")
                    return
                }
                SweepResult.INTERRUPTED -> {}
            }
            // 清扫中断（flood/失败）：相位已落库，下轮续扫。
            // 连续失败熔断：同一频道连灭 [SWEEP_FALLBACK_AFTER_FAILS] 轮就退回旧路兜底一轮
            // （防 fetchAggregatedMedia 系统性不可用导致频道永远停滞）
            val fails = synchronized(sweepFailCount) {
                val n = (sweepFailCount[chatId] ?: 0) + 1
                sweepFailCount[chatId] = n
                n
            }
            if (fails < SWEEP_FALLBACK_AFTER_FAILS) return
            Log.w(TAG, "媒体清扫连续失败 $fails 轮，本轮退回 GetChatHistory 旧路兜底 chatId=$chatId")
            synchronized(sweepFailCount) { sweepFailCount.remove(chatId) }
        }

        // ---- R46 熔断闸（§5 落地）：GetChatHistory 泳道撞 429 后全 app 该方法族静默到
        // retry-after 结束（熔断表持久化跨进程）。熔断期本轮的旧路段（日历跳爬/头部刷新/
        // 尾爬/加深）整体跳过——不轮番撞墙；媒体清扫是 SearchChatMessages 泳道（不同族），
        // 已在上方照常跑完。
        if (td != null && td.floodBreaker.isSilenced(FloodCircuitBreaker.FAMILY_GET_CHAT_HISTORY)) {
            Log.d(
                TAG,
                "GetChatHistory 熔断期整轮跳过 chatId=$chatId" +
                    "（剩 ${td.floodBreaker.remainingSec(FloodCircuitBreaker.FAMILY_GET_CHAT_HISTORY)}s 解封）",
            )
            return
        }

        // ---- 首次建索引：日历直方图跳爬（W3 考古加速；有游标的增量续爬不走这里） ----
        // "首次"判定：cursor==0 且未到底。跳爬成功收尾（完成/触顶/中断）直接 return；
        // 日历不可用/锚点异常则零进度回退到下方两阶段线性爬。
        if (st.cursor == 0L && !st.exhausted) {
            // td 已在上方采样闸段声明（Mock 入口拦截；非 TdLib 网关防御）
            if (td != null) {
                // R46 预算记账：日历取数按估值记（本环境实测 GetChatMessageCalendar 不可用
                // 时返回空表 → 自动回退线性爬；语义不变）
                if (!crawlBudgetAllow(CALENDAR_FETCH_COST, "日历跳爬取数", chatId)) return
                val days = td.getChatActivityDays(chatId)
                if (days.isEmpty()) {
                    Log.w(TAG, "发帖日历为空或不可用，按两阶段线性爬 chatId=$chatId")
                } else if (indexByCalendarDays(gateway, td, d, st, days, onProgress, ::persistPage)) {
                    return // 跳爬本轮收尾（完成/触顶/失败中断），页级事务已各自落库
                } else {
                    Log.w(TAG, "日历跳爬锚点异常，回退两阶段线性爬 chatId=$chatId")
                }
            }
        }

        // ---- 阶段一：头部增量刷新（把上次建索引之后新发的视频补进来） ----
        // R45 节流（根因 R3）：已到底频道的头部刷新加最小间隔闸（lastHeadRefreshTs 落盘，
        // 不足间隔本轮直接跳过）——维护期"全部已到底频道每轮都刷一次头部"曾是每轮
        // 千次级常驻请求的来源；未到底频道不受节流（其主活是尾部续爬，头部刷新顺路
        // 且便宜）。调度侧另有入队预筛（headRefreshDueChannelIds），这里的判定是
        // 权威兜底（手动触发/兜底轮同样受闸）。
        // R163 B3：节流间隔档位化（T0 高频保新 / 其余=现状 6h；LEGACY=现状）——
        // [IndexTierPolicy.headRefreshIntervalMs] 唯一出处
        val headIntervalMs = IndexTierPolicy.headRefreshIntervalMs(tier, tiering)
        // R262b：死档降频探活（6h→7d，DeadChannelPolicy 唯一出处）——权威兜底闸
        // 与入队预筛（headRefreshDueChannelIds）同口径
        // R284：时效覆盖闸再钳一刀（自毁群一轮覆盖 ≤TTL/2——7 天自毁 3.5 天一轮；
        // ttlSec=0 直通=现状锚）。与入队预筛同一条公式同一出处，两闸口径恒一致
        // R286：活跃新鲜度闸第四刀（热 30min/活跃 2h；lastMsgSec=0 直通=现状锚）
        val nowMs = System.currentTimeMillis()
        val effectiveHeadIntervalMs = CrawlThroughputPolicy.activityAwareHeadRefreshIntervalMs(
            CrawlThroughputPolicy.ttlAwareHeadRefreshIntervalMs(
                DeadChannelPolicy.headRefreshIntervalMs(st.dead, headIntervalMs), st.ttlSec,
            ),
            st.lastMsgSec, nowMs / 1_000L,
        )
        val headThrottled = st.exhausted && st.lastHeadRefreshTs > 0L &&
            nowMs - st.lastHeadRefreshTs < effectiveHeadIntervalMs
        if (headThrottled) {
            Log.d(
                TAG,
                "头部刷新节流生效 chatId=$chatId：距上次 ${(nowMs - st.lastHeadRefreshTs) / 3_600_000}h " +
                    "< ${effectiveHeadIntervalMs / 3_600_000}h（tier=$tier dead=${st.dead}" +
                    (if (st.ttlSec > 0) " ttl=${st.ttlSec / 86_400}d" else "") +
                    (if (st.lastMsgSec > 0) " lastMsg=${(nowMs / 1_000L - st.lastMsgSec) / 3_600}h前" else "") + "），本轮跳过",
            )
        }
        var cursor: String? = null
        var rounds = 0
        while (!headThrottled && rounds < HEAD_MAX_ROUNDS) {
            if (rounds == 0) {
                // 实际执行刷新即记时（节流闸口径 = 距上次"刷过"，成不成都算——
                // 防 flood 期每轮重复撞同一频道）
                setLastHeadRefreshTs(d, chatId, nowMs)
                st.lastHeadRefreshTs = nowMs
            }
            rounds++
            val page = fetchPageWithRetry(gateway, chatId, cursor) ?: break
            if (page.nextCursor == null) {
                // 头部一路翻到底（小频道首次建索引直接走这里）；
                // 游标保持原值（nextCursor 为空无从推进），只置完成态
                persistPage(page.items, st.cursor, true)
                onProgress(countItems(d, chatId))
                return
            }
            // 页内全部条目新鲜才继续往下翻；出现已收录条目 = 新内容已接上轨，头部刷新结束。
            // 游标每页都推进：页间游标链连续 + "cursor 以新全覆盖"不变式保证推进安全
            // （覆盖语义与旧实现一致，且重叠页的新收录区间也计入游标，不漏不重）
            val fresh = persistPage(page.items, page.nextCursor.toLongOrNull() ?: st.cursor, false)
            if (page.items.isEmpty() || fresh < page.items.size) break
            cursor = page.nextCursor
            onProgress(countItems(d, chatId))
            delay(pacer.current()) // AIMD 自适应限速（干净提速、撞 flood 翻倍降）
        }

        // ---- 阶段二：尾部续爬（从上次游标继续往历史深处翻） ----
        if (!st.exhausted) {
            var tailCursor: String? = if (st.cursor > 0L) st.cursor.toString() else null
            var tailRounds = 0
            var total = countItems(d, chatId) // R255 W9：会话内累计（fresh 增量）
            while (true) {
                // P0 硬上限。R255 W1 拆分：**条数触顶=终态**（完成态落盘出队——cap 是
                // 设计边界，旧语义"触顶不置 exhausted 下轮重撞"是 16384 基线 0 条/小时
                // 的僵尸病根）；轮数触顶=可续中断（游标已按页推进落盘，下次断点续爬）。
                // R163 B3：条数上限档位化（LEGACY=现状 [TAIL_MAX_ITEMS]；触顶加深标记
                // 仅 deepenAllowed 档——T1/T2 档位上限就是设计边界，不靠加深绕过）
                if (total >= itemCap) {
                    // 条数触顶 = 浅爬标记：维护轮加深续爬从断点补更老历史
                    if (IndexTierPolicy.deepenAllowed(tier)) setNeedDeepen(d, chatId, true)
                    Log.w(
                        TAG,
                        "尾部续爬触条数上限 chatId=$chatId items=$total/$itemCap tier=$tier" +
                            "（R255 触顶即终态：完成态出队）",
                    )
                    persistPage(emptyList(), st.cursor, true)
                    return
                }
                if (tailRounds >= TAIL_MAX_ROUNDS) {
                    Log.w(
                        TAG,
                        "尾部续爬触页数上限，本轮停止 chatId=$chatId " +
                            "items=$total/$itemCap rounds=$tailRounds/$TAIL_MAX_ROUNDS（断点续爬）",
                    )
                    break
                }
                tailRounds++
                val page = fetchPageWithRetry(gateway, chatId, tailCursor) ?: break
                val next = page.nextCursor?.toLongOrNull()
                if (next == null) {
                    total += persistPage(page.items, st.cursor, true) // 爬到底，终态必落
                    onProgress(total)
                    break
                }
                total += persistPage(page.items, next, false) // 游标 = 已索引到的最老 messageId
                tailCursor = page.nextCursor
                onProgress(total)
                delay(pacer.current()) // AIMD 自适应限速（干净提速、撞 flood 翻倍降）
            }
        }

        // ---- 阶段三：加深续爬（needDeepen 浅爬频道补深；维护轮低速进行，预算制） ----
        deepenLegacy(gateway, d, st, ::persistPage, onProgress, tier)
    }

    /**
     * 加深续爬（第九轮：needDeepen 浅爬频道补深——旧 5000 封顶时代/触 [TAIL_MAX_ITEMS] 的频道，
     * 其更老历史索引里没有）：旧路 GetChatHistory 从 meta.cursor 断点继续往更老翻，
     * 补评论级联/文字批注/过滤漏网媒体。低速预算制：每轮 ≤[DEEPEN_MAX_PAGES] 页
     * （AIMD 限速），耗尽留标记下轮续爬（游标已按页推进落库）；到频道底清除标记。
     * 完成态保留语义：persistPage 的 exhausted 参数透传当前值（加深受扫完成频道不掉回未完成）。
     */
    private suspend fun deepenLegacy(
        gateway: TdGateway,
        d: SQLiteDatabase,
        st: CrawlState,
        persistPage: (List<VideoItem>, Long, Boolean, Long) -> Int,
        onProgress: (Int) -> Unit,
        tier: IndexTierPolicy.IndexTier = IndexTierPolicy.IndexTier.LEGACY, // R255 W2：调用方闸定后的有效档位
    ) {
        // R163 B3 档位闸：加深只对 LEGACY（现状照跑）/T0 开放——T1/T2 触档位上限即终态
        // （上限就是设计边界，加深是绕过上限的后门，不开）；分档关时恒 LEGACY=现状
        // （R255 W2：effectiveTier 透传，不再读 st.tier 防档位泄漏）
        if (!IndexTierPolicy.deepenAllowed(tier)) return
        if (!readNeedDeepen(d, st.chatId)) return
        Log.d(TAG, "加深续爬开爬 chatId=${st.chatId} 断点cursor=${st.cursor} 间隔=${pacer.current()}ms")
        var cur: String? = if (st.cursor > 0L) st.cursor.toString() else null
        var pages = 0
        while (pages < DEEPEN_MAX_PAGES) {
            if (countItems(d, st.chatId) >= TAIL_MAX_ITEMS) break // 又触条数顶：留标记，下轮再议
            val page = fetchPageWithRetry(gateway, st.chatId, cur) ?: break // 失败：断点已落库，下轮续爬
            pages++
            val next = page.nextCursor?.toLongOrNull()
            if (next == null) {
                persistPage(page.items, st.cursor, true, -1L) // 到频道底：加深完成（完成态一并置位）
                setNeedDeepen(d, st.chatId, false)
                Log.d(TAG, "加深续爬到频道底 chatId=${st.chatId}，needDeepen 清除（累计=${countItems(d, st.chatId)}）")
                return
            }
            persistPage(page.items, next, st.exhausted, -1L) // 游标向更老推进；完成态透传不翻转
            cur = page.nextCursor
            onProgress(countItems(d, st.chatId))
            delay(pacer.current())
        }
        if (pages > 0) {
            Log.d(TAG, "加深续爬本轮收尾 chatId=${st.chatId} 页数=$pages cursor=${st.cursor}（未到底，下轮继续）")
        }
    }

    /**
     * 日历跳爬（仅首次建索引调用；[days] = 日 epoch → 当天消息数，调用方已判非空）。
     * 返回 true = 本轮已收尾（爬到底/触硬上限/失败中断，页级事务皆已落库，调用方直接 return）；
     * 返回 false = 锚点预取阶段失败（尚未开爬、零进度），调用方回退两阶段线性爬。
     *
     * 段归并：日 epoch 相差正好 [CAL_DAY_SECONDS] 秒视为连续，连续段只花 1 个锚点请求
     * （段尾消息 id）；单日段即"按日跳"的原始形态。段按新→旧处理、段内连续回爬，
     * 配合"段间空档日本无消息"，保证任意时刻 cursor 以新的消息都已索引——
     * 这就是中途任意退出都能交给增量路续爬的依据。
     */
    private suspend fun indexByCalendarDays(
        gateway: TdGateway,
        td: TdLibGateway,
        d: SQLiteDatabase,
        st: CrawlState,
        days: List<Pair<Int, Int>>,
        onProgress: (Int) -> Unit,
        persistPage: (List<VideoItem>, Long, Boolean, Long) -> Int,
    ): Boolean {
        // 1) 连续段归并（升序扫描后反转成新→旧）。段 = (最老日 epoch, 最新日 epoch)
        val daysAsc = days.sortedBy { it.first }
        val segments = ArrayList<Pair<Int, Int>>()
        var segBegin = daysAsc[0].first
        var prev = daysAsc[0].first
        for (i in 1 until daysAsc.size) {
            val day = daysAsc[i].first
            if (day - prev != CAL_DAY_SECONDS) { // 断档：前一天没消息，旧段封口、新段开工
                segments += segBegin to prev
                segBegin = day
            }
            prev = day
        }
        segments += segBegin to prev
        segments.reverse() // 新→旧：与线性爬同向，cursor 不变式成立

        // 2) 预取全部段锚点（段最新日 23:59:59 UTC 的最后一条消息 id，1 次请求/段）。
        //    锚点必须严格递减（新段段尾消息 id 恒大于老段的），违例说明日历/锚点数据异常，
        //    不赌残缺数据：整体回退线性爬（此刻尚未开爬，零浪费）
        val anchors = LongArray(segments.size)
        for (i in segments.indices) {
            val dayEnd = segments[i].second + CAL_DAY_SECONDS - 1
            // R46 预算记账：锚点 = 1 次 GetChatMessageByDate，按 1 次实记；
            // 耗尽即本轮收尾（尚未开爬零进度损失，滑窗恢复后下轮重来）
            if (!crawlBudgetAllow(1, "日历锚点", st.chatId)) return true
            rateGate.await(crawlRateIntervalMs()) // R255 W5：平滑配速（见 SmoothRateGate 注释）
            val anchor = td.getMessageIdByDate(st.chatId, dayEnd)
            if (anchor == null || (i > 0 && anchor >= anchors[i - 1])) {
                Log.w(TAG, "日历跳爬锚点异常 chatId=${st.chatId} 段=$i/${segments.size} anchor=$anchor")
                return false
            }
            anchors[i] = anchor
            delay(pacer.current()) // 锚点请求同口径限速（AIMD 自适应）
        }
        Log.d(TAG, "日历跳爬开爬 chatId=${st.chatId} 活跃日=${daysAsc.size} 段=${segments.size}")

        // 3) 逐段跳爬：floor = 下一（更老）段的锚点 = 本段最老消息的前驱（段间空档无消息），
        //    爬过 floor 即段完成、直接跳下一段——空档日子零请求跳过。
        //    最老段 floor=0：日历覆盖范围之外（含超月数上限的更老历史）线性直爬到频道底，
        //    完整性不依赖日历覆盖深度。
        var pages = 0
        var total = countItems(d, st.chatId) // R255 W9：会话内累计（fresh 增量），免每页 COUNT 索引扫
        for (i in segments.indices) {
            val floor = if (i + 1 < anchors.size) anchors[i + 1] else 0L
            var segCursor: String? = anchors[i].toString()
            while (true) {
                // 与线性尾爬同一组 P0 硬上限。R255 W1 拆分：条数触顶=终态（完成态出队），
                // 页数触顶=可续中断（断点下次续爬）
                if (total >= TAIL_MAX_ITEMS) {
                    // 条数触顶 = 浅爬标记：维护轮加深续爬从断点补更老历史
                    setNeedDeepen(d, st.chatId, true)
                    Log.w(
                        TAG,
                        "日历跳爬触条数上限 chatId=${st.chatId} items=$total/$TAIL_MAX_ITEMS" +
                            "（R255 触顶即终态：完成态出队）",
                    )
                    persistPage(emptyList(), st.cursor, true, -1L)
                    return true
                }
                if (pages >= TAIL_MAX_ROUNDS) {
                    Log.w(
                        TAG,
                        "日历跳爬触页数上限，本轮停止 chatId=${st.chatId} " +
                            "items=$total/$TAIL_MAX_ITEMS pages=$pages/$TAIL_MAX_ROUNDS（断点续爬）",
                    )
                    return true
                }
                pages++
                val page = fetchPageWithRetry(gateway, st.chatId, segCursor) ?: return true
                val next = page.nextCursor?.toLongOrNull()
                if (next == null) { // 空页 = 爬到频道最底 = 全量完成（与线性口径一致）
                    total += persistPage(page.items, st.cursor, true, -1L) // 终态必落
                    onProgress(total)
                    return true
                }
                total += persistPage(page.items, next, false, -1L) // 维持"cursor 以新全覆盖"：段新→旧 + 段内连续 + 空档本无消息
                onProgress(total)
                if (next <= floor) break // 本段爬完（floor 及以下属于更老的段）
                segCursor = page.nextCursor
                delay(pacer.current()) // AIMD 自适应限速（干净提速、撞 flood 翻倍降）
            }
            Log.d(TAG, "日历跳爬完成段 ${i + 1}/${segments.size} chatId=${st.chatId} cursor=${st.cursor}")
        }
        // 正常到不了这里（最老段 floor=0 只能靠空页退出）；防御性置到底态，防下次无效重爬
        persistPage(emptyList(), st.cursor, true, -1L)
        return true
    }

    // ---------------- 媒体清扫（第八轮提速主路径） ----------------

    /**
     * 媒体清扫：SearchChatMessages + SearchMessagesFilter（Video→Photo→Audio 三相位依次清扫），
     * 服务端只回媒体消息——纯文字闲聊页整体跳过（文字占多数的频道页数少一个数量级）。
     * 复用网关既有公开方法 fetchAggregatedMedia 单频道调用（TdLibGateway 不许动）；
     * 其内部带相册 caption 共享/邻居窗口展开，清扫数据 caption 质量与旧路一致。
     *
     * 断点设计：meta.cursor 在清扫期**冻结为清扫起点**（全新频道=0=从顶部扫；旧部分爬频道
     * =旧游标，其上区域旧爬取已全覆盖——由"cursor 以新全覆盖"不变式保证起点合法性，
     * 即便与旧路兜底交替执行也正确）；meta.mediaPass 持久化当前相位。
     * R45（根因 R2）：相位内游标也页级落盘（meta.sweepCursor）——旧版只在内存，
     * flood/进程死亡后整相位（最多 500 页）从页 0 重扫，flood 环境下期望请求量是
     * 理想值的数十倍；现在中断后从断点续扫（INSERT OR IGNORE 幂等兜底，落盘游标
     * 只是少付重扫的网络请求）。相位推进/清扫完成时游标归 0（游标只对当前相位有效）。
     * 已存在条目由 insertItemLocked 顺手回填 albumId（backfillOnConflict，存量修复顺带完成）。
     *
     * 返回 true = 三类型全部到底（调用方置 exhausted 收尾）；false = 本轮中断
     * （flood/失败/触硬上限；相位已落库，下轮续扫）。
     */
    /** 媒体清扫结果四态（R163 B3 三态 + R255 W1）：DONE 三相位到底 / INTERRUPTED 失败（旧 false）/
     *  BUDGET_CUT 探针·档位页预算截断（正常中断，不进熔断计数、不退旧路兜底）/
     *  CAPPED 条数触顶（R255 W1 触顶即终态——正常边界，不进熔断、不退旧路、完成态出队）/
     *  DENIED 爬取预算耗尽（R255：预算拒不是失败，不进熔断计数、不退旧路） */
    private enum class SweepResult { DONE, INTERRUPTED, BUDGET_CUT, CAPPED, DENIED }

    /**
     * R163 B3 参数化：[pageBudget] 本轮派工的清扫页预算（探针档 = [IndexTierPolicy.probePageBudget]，
     * 常规档 = Int.MAX_VALUE 不限——现状）；[itemCap] 条数上限（档位化，LEGACY = 现状
     * [TAIL_MAX_ITEMS]，R255 W3 探针档已含下限）；[tier] 有效档位（R255 W2：调用方已过
     * 分档闸——分档关恒 LEGACY，此处不再读 st.tier 防泄漏）。触条数顶时 needDeepen 仅对
     * deepenAllowed 档位（LEGACY/T0）置位——T1/T2 触顶即终态（档位上限就是设计边界，不靠加深绕过）。
     */
    private suspend fun mediaSweep(
        td: TdLibGateway,
        d: SQLiteDatabase,
        st: CrawlState,
        persistPage: (List<VideoItem>, Long, Boolean, Long) -> Int,
        onProgress: (Int) -> Unit,
        pageBudget: Int = Int.MAX_VALUE,
        itemCap: Int = TAIL_MAX_ITEMS,
        tier: IndexTierPolicy.IndexTier = IndexTierPolicy.IndexTier.LEGACY,
    ): SweepResult {
        if (st.mediaPass < 0) {
            // 首次进入清扫：锁定相位 0（cursor 即清扫起点，全程冻结）
            setMediaPass(d, st.chatId, 0)
            st.mediaPass = 0
        }
        Log.d(
            TAG,
            "媒体清扫开扫 chatId=${st.chatId} 起点=${st.cursor} 相位=${st.mediaPass} " +
                "间隔=${pacer.current()}ms 页预算=${if (pageBudget == Int.MAX_VALUE) "不限" else pageBudget.toString()} 条上限=$itemCap",
        )
        var budgetLeft = pageBudget
        var total = countItems(d, st.chatId) // R255 W9：会话内累计（fresh 增量），免每页 2 次 COUNT 索引扫
        while (st.mediaPass <= 2) {
            val typeName = when (st.mediaPass) {
                0 -> "视频"
                1 -> "图片"
                else -> "音频"
            }
            // 相位内游标（R45：落盘断点优先——中断续扫不重扫；0=未落过，退回相位起点口径：
            // 起点=0 → null = 从最新消息扫起）
            var mc: String? = when {
                st.sweepCursor > 0L -> {
                    Log.d(TAG, "媒体清扫相位游标续扫 chatId=${st.chatId} 相位=${st.mediaPass} 断点游标=${st.sweepCursor}")
                    "${st.chatId}:${st.sweepCursor}"
                }
                st.cursor > 0L -> "${st.chatId}:${st.cursor}"
                else -> null
            }
            var passPages = 0
            var passDone = false
            while (passPages < TAIL_MAX_ROUNDS) {
                // R163 B3：页预算先于取页判定（预算耗尽=正常截断，相位游标已落盘续扫）
                if (budgetLeft <= 0) return SweepResult.BUDGET_CUT
                if (total >= itemCap) {
                    // 触条数顶：deepenAllowed 档位（LEGACY/T0）标记待加深（维护轮旧路补深）；
                    // T1/T2 触顶即终态——档位上限是设计边界
                    if (IndexTierPolicy.deepenAllowed(tier)) {
                        setNeedDeepen(d, st.chatId, true)
                    }
                    // R255 W1 触顶即终态：旧语义把触顶当失败（INTERRUPTED→熔断计数→3 轮
                    // 转旧路兜底烧预算零产出→下轮枚举拍重新入队→无限空转——16384 基线
                    // 实测 0 条/小时的病根）。调用方据此落完成态出队（6h 头刷接管保新帖）。
                    Log.w(
                        TAG,
                        "媒体清扫触条数上限 chatId=${st.chatId} items=$total/$itemCap" +
                            "（R255 触顶即终态：完成态出队，头刷 6h 接管保新）",
                    )
                    return SweepResult.CAPPED
                }
                // R255：预算耗尽（denied）不是失败——不进熔断计数不退旧路（旧语义把
                // 预算拒混进失败计数，干涸期连拒 3 次就把频道毒到旧路兜底=3 倍预算/页）
                val fetch = fetchMediaPageWithRetry(td, st.chatId, st.mediaPass, mc)
                val page = fetch.page ?: return if (fetch.denied) SweepResult.DENIED else SweepResult.INTERRUPTED
                passPages++
                budgetLeft--
                // R255 W7：相位内游标并入页事务（原独立事务删除）；R255 W9：累计计数
                val nextMsg = parseMediaCursor(st.chatId, page.nextCursor)
                total += persistPage(
                    page.items, st.cursor, false,
                    if (nextMsg != null && nextMsg > 0L) nextMsg else -1L,
                )
                onProgress(total)
                if (page.nextCursor == null) { // 本类型到底（网关把游标置 -1 → allEnded → null）
                    passDone = true
                    break
                }
                mc = page.nextCursor
                delay(pacer.current()) // AIMD 自适应限速
            }
            if (!passDone) {
                if (budgetLeft <= 0) return SweepResult.BUDGET_CUT // 页数触顶因预算：正常截断
                // 相位页数触顶：本轮收尾，相位游标已随页落盘，下轮从断点续扫（不重扫）
                Log.w(TAG, "媒体清扫相位页数触顶 chatId=${st.chatId} 类型=$typeName pages=$passPages（断点游标已落盘）")
                return SweepResult.INTERRUPTED
            }
            // 相位到底但预算已尽：不再推进相位（推进了也没有页可扫），正常截断
            if (budgetLeft <= 0) return SweepResult.BUDGET_CUT
            st.mediaPass++
            setMediaPass(d, st.chatId, st.mediaPass) // 相位推进落库（中断续扫依据）
            setSweepCursor(d, st.chatId, 0L) // 相位推进：游标归零（游标只对当前相位有效）
            st.sweepCursor = 0L
            Log.d(
                TAG,
                "媒体清扫相位完成 chatId=${st.chatId} 类型=$typeName 页数=$passPages " +
                    "累计=$total 间隔=${pacer.current()}ms",
            )
        }
        return SweepResult.DONE
    }

    /**
     * 媒体清扫取一页（fetchAggregatedMedia 单频道调用：SearchChatMessages + 类型过滤器）。
     * 返回 [MediaPageFetch.page]=null = 本轮放弃（相位/起点已落库，下轮续扫）；
     * [MediaPageFetch.denied]=true 时放弃原因是**预算耗尽**（R255：预算拒不是失败——
     * 调用方不进熔断计数、不退旧路兜底；旧语义把预算拒混进失败计数，干涸期 3 次拒
     * 就把频道毒到旧路兜底=3 倍预算/页的贵车道，干涸后也回不来）。
     *
     * 第十轮两处提速：
     *  - expandCaptions=false：跳过空 caption 邻居窗口展开（每页最多 4 次
     *    GetChatHistory 是清扫期限流暴露面的最大来源；页内相册共享 caption 免费保留）。
     *    代价：清扫入库的历史条目里，"文字批注帖关联"的 caption 不再补——
     *    新内容由旧路头部刷新（未过滤时间线，caption 关联零额外请求）保真，
     *    历史区 caption 缺口由后续旧路加深/兜底轮低成本回补（INSERT OR IGNORE
     *    幂等，backfillOnConflict 会把空 caption 刷成网络真值）；
     *  - 失败退避不再一刀切：最多 3 次，退避 5s→15s 递增（旧版 2 次×5s 一遇
     *    短 flood 就放弃整个频道本轮进度，是"flood 期吞吐塌方"的实锤根因之一）。
     *
     * 失败判定（网关把单频道异常吞成"空页 + 游标原地"——fetchAggregatedMedia 逐频道
     * runCatching 不向上抛）：nextCursor 非空但解析不出"比送出游标更老的正数 msgId"
     * 即判失败，按 flood 口径处理（AIMD ×2 降速 + 递增退避重试，再败返回 null）。
     * 正常到底 = nextCursor == null（页内可能带末批条目）。
     */
    private class MediaPageFetch(val page: FeedPage?, val denied: Boolean)

    private suspend fun fetchMediaPageWithRetry(
        td: TdLibGateway,
        chatId: Long,
        mediaType: Int,
        cursor: String?,
    ): MediaPageFetch {
        val sentMsg = parseMediaCursor(chatId, cursor) // 送出游标的 msgId（null=从最新/起点）
        var attempt = 0
        while (attempt < SWEEP_PAGE_MAX_ATTEMPTS) {
            attempt++
            // R46 预算记账：清扫页 = 1 次 SearchChatMessages，按 1 次实记；
            // 预算耗尽本轮放弃（相位/游标已落盘，滑窗恢复后下轮断点续扫）
            if (!crawlBudgetAllow(1, "媒体清扫取页", chatId)) return MediaPageFetch(null, denied = true)
            rateGate.await(crawlRateIntervalMs()) // R255 W5：平滑配速（见 SmoothRateGate 注释）
            val page = runCatching {
                td.fetchAggregatedMedia(listOf(chatId), mediaType, cursor, PAGE_SIZE, expandCaptions = false)
            }.getOrNull()
            if (page != null) {
                if (page.nextCursor == null) { // 到底（末页可能带条目）
                    pacer.onPageSuccess()
                    return MediaPageFetch(page, denied = false)
                }
                val nextMsg = parseMediaCursor(chatId, page.nextCursor)
                if (nextMsg != null && nextMsg > 0L && nextMsg != sentMsg) { // 游标正常向老推进
                    pacer.onPageSuccess()
                    return MediaPageFetch(page, denied = false)
                }
                // 游标原地踏步/不可解析：网关吞了异常（flood/超时），按 flood 口径
            }
            pacer.onFlood()
            if (attempt < SWEEP_PAGE_MAX_ATTEMPTS) {
                // 递增退避：5s、15s——短 flood 当场骑过去，长 flood 由 AIMD 惩罚窗接管
                val backoff = FLOOD_BACKOFF_MS * (2L * attempt - 1L)
                Log.d(
                    TAG,
                    "媒体清扫取页失败降速：间隔 ×2 → ${pacer.current()}ms" +
                        "（chatId=$chatId 类型=$mediaType 第 $attempt 次，退避 ${backoff / 1000}s）",
                )
                delay(backoff)
            }
        }
        return MediaPageFetch(null, denied = false)
    }

    /** 解析单频道媒体游标（"chatId:msgId" 格式，负 chatId 两段式 split 不受影响）；不可解析返回 null */
    private fun parseMediaCursor(chatId: Long, cursor: String?): Long? {
        if (cursor.isNullOrBlank()) return null
        val prefix = "$chatId:"
        return cursor.split('|').firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)?.toLongOrNull()
    }

    // ---------------- 时间分层采样（R46 方案 c：怪兽频道按月锚点采样，不全量翻页） ----------------

    /**
     * 按月锚点采样爬取（采样闸判定为怪兽频道后调用）。
     *
     * 锚点来源：逐月回溯 GetChatMessageByDate（本月末 → 上月末 → …，封顶
     * [SAMPLE_MAX_MONTHS] 个月）——本环境实测 GetChatMessageCalendar 挂死不可用，
     * GetChatMessageByDate 毫秒级可靠（见网关侧注释），且"不晚于 date 的最后一条"
     * 语义天然跳过空月：本月锚点 == 上月锚点 = 该月无消息（跳过零请求翻页）；
     * 连续 [SAMPLE_EMPTY_MONTH_STREAK] 个空月 = 摸到频道历史边界，提前收工。
     * 当月（offset=0）锚点时刻在未来 → 返回最新一条消息，恰是本月采样起点。
     *
     * 每活跃月从锚点向下按媒体类型各采 ≤[SAMPLE_PAGES_PER_MONTH_PER_TYPE] 个清扫页
     * （SearchChatMessages 单请求页，与媒体清扫同一取页/预算/退避口径）；
     * 类型空页 = 该类型在锚点之下的全历史都没有——记 typeExhausted，更老月份直接跳过
     * （fromMessageId 语义是"向更老翻"，空页结论对更老月恒成立，零浪费）。
     *
     * 游标/完成态口径：采样只往 items/fts 落幂等数据（INSERT OR IGNORE），meta.cursor
     * 保持 0——采样不是连续时间线，"cursor 以新全覆盖"不变式对它无意义，诚实做法
     * 是不伪造游标；完成后 meta.sampled=1 + exhausted=1 落盘：调度侧该频道出队，
     * 之后只剩 6h 节流头部刷新保新内容（头部刷新从最新向下、撞见已收录条目即停——
     * 采样已覆盖最新月，撞线很快）。
     *
     * 请求包络：≤ [SAMPLE_MAX_MONTHS] 锚点 + 每活跃月 ≤ 3×[SAMPLE_PAGES_PER_MONTH_PER_TYPE] 页。
     * 怪兽频道全量清扫最坏 500 页 → 采样 ~10 请求/活跃月，净减一个数量级以上。
     */
    private suspend fun sampleChannelByMonthAnchors(
        td: TdLibGateway,
        d: SQLiteDatabase,
        st: CrawlState,
        persistPage: (List<VideoItem>, Long, Boolean, Long) -> Int,
        onProgress: (Int) -> Unit,
    ) {
        val typeExhausted = BooleanArray(3) // 见上：类型空页结论对更老月恒成立
        var totalPages = 0
        var activeMonths = 0
        var prevAnchor = -1L
        var emptyStreak = 0
        val nowSec = (System.currentTimeMillis() / 1000L).toInt()
        val thisMonthKey = monthKeyOf(nowSec)
        for (offset in 0 until SAMPLE_MAX_MONTHS) {
            val mk = thisMonthKey - offset // 月序号是线性值（year*12+month），跨年自然回卷
            // 预算耗尽：本轮收工（已采页均已落库；sampled 标记不打，下轮采样闸重进续采）
            if (!crawlBudgetAllow(1, "采样月锚点", st.chatId)) return
            rateGate.await(crawlRateIntervalMs()) // R255 W5：平滑配速
            val anchor = td.getMessageIdByDate(st.chatId, monthEndEpochSec(mk))
            if (anchor == null || anchor == prevAnchor) {
                // 空月：该月无消息（锚点与上月相同）/ 已摸到频道历史头（null）
                if (anchor != null) prevAnchor = anchor
                if (++emptyStreak >= SAMPLE_EMPTY_MONTH_STREAK) break
                continue
            }
            emptyStreak = 0
            prevAnchor = anchor
            activeMonths++
            delay(pacer.current()) // 锚点请求同口径限速（AIMD）
            var monthPages = 0
            for (type in 0..2) {
                if (typeExhausted[type]) continue
                var cursor: String? = "${st.chatId}:$anchor"
                var typePages = 0
                while (typePages < SAMPLE_PAGES_PER_MONTH_PER_TYPE) {
                    val page = fetchMediaPageWithRetry(td, st.chatId, type, cursor).page ?: break
                    typePages++
                    monthPages++
                    totalPages++
                    persistPage(page.items, st.cursor, false, -1L) // 采样期 cursor 不伪造（恒为起点 0）
                    onProgress(countItems(d, st.chatId))
                    if (page.nextCursor == null) { // 该类型向老已到底
                        typeExhausted[type] = true
                        break
                    }
                    cursor = page.nextCursor
                    delay(pacer.current()) // AIMD 自适应限速
                }
            }
            Log.d(TAG, "采样爬取 chatId=${st.chatId} 月份=${monthLabelOf(mk)} 页数=$monthPages")
        }
        // 采样完成：打 sampled 标记（口径诚实：UI 可区分全量/采样），置完成态出队
        setSampled(d, st.chatId, true)
        st.sampled = true
        persistPage(emptyList(), st.cursor, true, -1L)
        onProgress(countItems(d, st.chatId))
        Log.d(
            TAG,
            "采样爬取完成 chatId=${st.chatId} 活跃月=$activeMonths 总页数=$totalPages " +
                "累计=${countItems(d, st.chatId)}（按月抽样非全量，sampled=1 已落盘）",
        )
    }

    /** 日 epoch → UTC 月序号（year*12+month0）；与网关口径一致全用 UTC，跨时区不漂 */
    private fun monthKeyOf(dayEpochSec: Int): Int {
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = dayEpochSec * 1000L
        return utc.get(java.util.Calendar.YEAR) * 12 + utc.get(java.util.Calendar.MONTH)
    }

    /** 月序号 → 该月最后一天的 23:59:59 UTC（GetChatMessageByDate 的月锚点时刻） */
    private fun monthEndEpochSec(monthKey: Int): Int {
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.clear()
        utc.set(java.util.Calendar.YEAR, monthKey / 12)
        utc.set(java.util.Calendar.MONTH, monthKey % 12)
        utc.set(java.util.Calendar.DAY_OF_MONTH, 1)
        utc.add(java.util.Calendar.MONTH, 1) // 下月 1 号 00:00:00 UTC
        return (utc.timeInMillis / 1000L).toInt() - 1
    }

    /** 月序号 → 展示标签（yyyy-MM，UTC；只进日志） */
    private fun monthLabelOf(monthKey: Int): String = "%04d-%02d".format(monthKey / 12, monthKey % 12 + 1)

    // ---------------- 本地缓存白嫖（R46 方案 b：onlyLocal 零网络，只采不推进游标） ----------------

    /**
     * R165 白嫖冷却账本（chatId → 上次白嫖**尝试**时刻毫秒）。
     * ConcurrentHashMap：派工协程并发调；不落盘（冷却只是防重复空转，重启重扫一次无妨）。
     */
    private val harvestLocalAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * R239 用户浏览写回（Read-Through Cache 的写侧）：用户使用中触发的 TG API 查询
     * （时间线历史翻页等）结果**顺便保存进深索引**——索引的入库路径从「爬虫构建」
     * 单线扩展为「爬虫+用户浏览」双源，用户看过的时段下次零 API 命中。
     *
     * 架构师风险表（r239_notes 22:55）六条对策的落点：
     *  - 复用现有 ingest 管道：本体=[persistLocalOnly]（INSERT OR IGNORE 主键判重
     *    幂等 + R228 入库即解析 + meta 行**原值回写不推进爬虫游标**）——不新建写
     *    路径、不另起解析标准（风险 1/3）；媒体过滤在调用侧完成（fetchFromChannel
     *    的 toVideoItem 已滤——风险 2）；写锁走 persistLocalOnly 内同一把
     *    writeLock（风险 5）；
     *  - 档位/调度口径零污染：游标不推进、exhausted 不置位（与白嫖通道同款
     *    「碰过但没爬过」语义，untouchedChannelIds 调度照常——风险 4 的结构性等价，
     *    source 列挂账待 v10 迁移窗口）；
     *  - 体积软顶：会话内每日 [BROWSE_INGEST_DAILY_CAP] 条（风险 6，超顶静默跳过——
     *    用户浏览写回是加菜不是主粮）。
     *
     * 调用方（PursueViewModel）在 IO 线程调用；失败静默 0（写回绝不影响主链路）。
     * 返回本次新入库条数。
     */
    fun ingestBrowsedPage(chatId: Long, items: List<VideoItem>): Int {
        if (items.isEmpty()) return 0
        if (healWriteBlocked("ingestBrowsedPage")) return 0 // R289 只读回退闸
        // 每日软顶（会话内计数；跨进程重启重置=软语义，防的是单会话长刷爆库）
        if (!browseIngestAdmit(items.size)) return 0
        return runCatching {
            val d = openDb()
            val st = readMeta(d, chatId)
            persistLocalOnly(d, st, items)
        }.getOrElse {
            Log.w(TAG, "R239 用户浏览写回失败（静默不影响主链路）: chatId=$chatId ${it.message}")
            0
        }
    }

    /**
     * R286 新帖快通道（多频道批量版浏览写回）：feed 拉取/追更实时流产出的页
     * （已过 toVideoItem 媒体过滤）**顺手入索引**——「刚发的」不等头刷立刻可搜。
     *
     * 与 [ingestBrowsedPage] 同一管道同一纪律（INSERT OR IGNORE 幂等 + 入库即解析
     * + meta 原值回写不推进游标 + 日软顶共享账本），两处差异：
     *  - 跨频道：一页聚合（≤40 频道混排）按 chatId 分组落库；
     *  - **单 writeLock + 单事务**：逐频道调单频道版每页 10~20 事务（writeLock
     *    是爬虫页落库的串行点，R255 W11 锁持有教训），批量版一页一事务。
     *
     * 调用方在 IO 线程调用（feed 路径 fire-and-forget 协程，见 FeedViewModel.
     * loadMore）；失败静默 0（写回绝不影响主链路）。返回本次新入库条数。
     */
    fun ingestBrowsedPages(items: List<VideoItem>): Int {
        if (items.isEmpty()) return 0
        if (healWriteBlocked("ingestBrowsedPages")) return 0 // R289 只读回退闸
        if (!browseIngestAdmit(items.size)) return 0
        return runCatching {
            val d = openDb()
            persistLocalOnlyBatch(d, items)
        }.getOrElse {
            Log.w(TAG, "R286 新帖快通道写回失败（静默不影响主链路）: ${it.message}")
            0
        }
    }

    /** 浏览写回日软顶闸（两入口共用）：放行=true 并已记账；超顶=false 静默 */
    private fun browseIngestAdmit(n: Int): Boolean {
        val today = System.currentTimeMillis() / 86_400_000L
        synchronized(browseIngestLedger) {
            if (browseIngestLedger.day != today) {
                browseIngestLedger.day = today
                browseIngestLedger.count = 0
            }
            if (browseIngestLedger.count >= BROWSE_INGEST_DAILY_CAP) return false
            browseIngestLedger.count += n
            return true
        }
    }

    /**
     * [persistLocalOnly] 的多频道批量真身（[ingestBrowsedPages] 专用）：按 chatId
     * 分组，单 writeLock+单事务落整页；每组语义与单频道版逐项一致（幂等插入+
     * fresh>0 才解析+meta 原值回写建行口径）。
     */
    private fun persistLocalOnlyBatch(d: SQLiteDatabase, items: List<VideoItem>): Int {
        val byChat = items.groupBy { it.chatId }
        // R255 W11b：LIKE 词位锁外预计算（key 唯一索引；FTS5 路径词位延算照旧）
        val precomputed = if (!useFts5) {
            HashMap<String, String>(items.size * 2).also { m -> items.forEach { m[it.key] = indexTokensOf(it) } }
        } else {
            null
        }
        var freshTotal = 0
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                for ((chatId, chatItems) in byChat) {
                    val st = readMeta(d, chatId)
                    var fresh = 0
                    for (item in chatItems) {
                        if (insertItemLocked(d, item, backfillOnConflict = true, precomputedTokens = precomputed?.get(item.key))) fresh++
                    }
                    // R228 同口径解析（fresh==0 全冲突页跳过，同 W6）；meta 原值回写
                    if (fresh > 0) runCatching { applyParsedCaptionsLocked(d, chatId, chatItems) }
                        .onFailure { Log.w(TAG, "R228 解析落位失败（快通道组跳过，页事务不受影响）：${it.message}") }
                    if (fresh > 0) upsertMetaCrawlLocked(d, chatId, st.cursor, st.exhausted) // 原值回写，游标不推进
                    freshTotal += fresh
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        return freshTotal
    }

    /** [ingestBrowsedPage] 每日软顶账本（会话内；day=Unix 日号） */
    private class BrowseIngestLedger {
        @Volatile var day: Long = -1L
        @Volatile var count: Int = 0
    }

    private val browseIngestLedger = BrowseIngestLedger()

    /**
     * 本地缓存白嫖（只采不推进游标）：用户 Feed 浏览过的频道，其近期历史已沉淀在
     * TDLib 本地消息库——走网关 onlyLocal=true 变体零网络采进索引（采到多少算多少）。
     *
     * 口径红线（宁漏勿错）：
     *  - **不推进 meta.cursor、不置 exhausted**——本地缓存覆盖区间未知（TDLib 没有
     *    "列出本地缓存区间"的 API），推进游标会把没采到的区间谎报成已覆盖；
     *  - INSERT OR IGNORE 幂等天然兼容；有网络的正式爬取轮到时照常
     *    （本通道只是调度侧正式爬取前的零成本加菜）；
     *  - 零网络零 flood 成本：不过 FloodBudget、不碰 pacer、不过熔断表。
     *  - R165 冷却：本通道不过预算闸，先前同一频道**每个派工周期都重扫**（0 产频道
     *    整夜 5 页 onlyLocal 空转，16384 夜 03:41 日志实锤）。现在单频道冷却
     *    [IndexCrawlGate.HARVEST_LOCAL_MIN_INTERVAL_MS]（0 产也记尝试时刻——0 产的更需要冷却）。
     *
     * 返回本次新采条数（失败/本地无缓存 = 0）。
     */
    suspend fun harvestLocalCache(td: TdLibGateway, chatId: Long): Int {
        val now = System.currentTimeMillis()
        if (!IndexCrawlGate.shouldHarvestLocal(harvestLocalAt[chatId] ?: 0L, now)) return 0
        if (healWriteBlocked("harvestLocalCache")) return 0 // R289 只读回退闸
        val d = openDb()
        val st = readMeta(d, chatId)
        var from = 0L
        var pages = 0
        var grabbed = 0
        while (pages < LOCAL_GRAB_MAX_PAGES) {
            val page = td.fetchLocalHistoryForIndex(chatId, from, PAGE_SIZE) ?: break
            pages++
            if (page.items.isNotEmpty()) grabbed += persistLocalOnly(d, st, page.items)
            val next = page.nextCursor?.toLongOrNull() ?: break // 本地覆盖到此为止
            if (next <= 0L || (from > 0L && next >= from)) break // 游标踏步/异常：停（防死循环）
            from = next
        }
        // 无论采到几条都记尝试时刻（含 openDb 抛异常之前的正常路径；异常路径不记，下轮重试）
        harvestLocalAt[chatId] = now
        if (pages > 0) {
            Log.d(TAG, "本地缓存白嫖 chatId=$chatId 采到 $grabbed 条（页数=$pages，只采不推进游标，零网络零 flood 成本）")
        }
        return grabbed
    }

    /**
     * 白嫖通道落库（[harvestLocalCache] 专用）：只插条目（幂等 + 存量字段顺手回填），
     * meta 行**有实际采到条目时**才按原值回写（cursor/exhausted 原样不动——行不存在
     * 按 (0, false) 建行，语义 = "碰过但没爬过"，由 [untouchedChannelIds] 识别保持
     * "未爬过"调度档）；一条没采到则连 meta 行都不建，不污染调度/统计口径。
     */
    private fun persistLocalOnly(d: SQLiteDatabase, st: CrawlState, items: List<VideoItem>): Int {
        var fresh = 0
        // R255 W11b：LIKE 词位预计算（锁外，同 persistPage 口径）
        val precomputed = if (!useFts5 && items.isNotEmpty()) items.map { indexTokensOf(it) } else null
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                for ((i, item) in items.withIndex()) {
                    if (insertItemLocked(d, item, backfillOnConflict = true, precomputedTokens = precomputed?.get(i))) fresh++
                }
                // R228：白嫖通道与主爬同一解析口径（用户口径：入库时解析，不分通道）；
                // runCatching 防御同 persistPage——解析异常不回滚条目落库。
                // R255 W6：fresh==0（全冲突重复浏览页）跳过解析——同主爬口径
                if (fresh > 0) runCatching { applyParsedCaptionsLocked(d, st.chatId, items) }
                    .onFailure { Log.w(TAG, "R228 解析落位失败（白嫖页跳过，页事务不受影响）：${it.message}") }
                if (fresh > 0) upsertMetaCrawlLocked(d, st.chatId, st.cursor, st.exhausted) // 原值回写，游标不推进
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        return fresh
    }

    // ---------------- 翻页与限流 ----------------

    /**
     * 爬取泳道预算记账闸（R46 §5 落地，收割器同款 FloodBudget 独立车道）：
     * 放行 = true（已记账 [cost] 次）；耗尽 = false（本轮静默跳过，不记账）。
     * 滑动窗口 1 小时，额度上限由 FeedViewModel 每枚举拍从 AppPrefs.floodCrawlPerHour
     * 刷新。预算是唯一主动限速手段；AIMD pacer 退化为撞 429 后的反应层（不再承担
     * 唯一限速）——口径见类头第十一轮注释。
     */
    private fun crawlBudgetAllow(cost: Int, what: String, chatId: Long): Boolean {
        if (crawlBudget.tryAcquire(cost)) return true
        Log.w(
            TAG,
            "爬取泳道预算耗尽：$what 跳过 chatId=$chatId" +
                "（窗口上限 ${crawlBudget.maxOpsPerHour} 次/小时，滑窗恢复后续爬）",
        )
        return false
    }

    /**
     * 取一页（每页 [PAGE_SIZE] 条）；flood 类异常退避 [FLOOD_BACKOFF_MS] 后重试一次，
     * 仍失败或其他异常返回 null（调用方中断本轮，进度已按页落库）。
     * AIMD 钩子：成功（含重试成功）→ [AdaptivePacer.onPageSuccess]；
     * flood → [AdaptivePacer.onFlood]（×2 降速 + 5 分钟惩罚窗）。
     */
    private suspend fun fetchPageWithRetry(
        gateway: TdGateway,
        chatId: Long,
        cursor: String?,
    ): FeedPage? {
        val td = gateway as? TdLibGateway
        // R46 熔断闸（方法族 "GetChatHistory"）：静默期一发不打（整轮跳过由调用方收口）
        if (td != null && td.floodBreaker.isSilenced(FloodCircuitBreaker.FAMILY_GET_CHAT_HISTORY)) {
            Log.d(
                TAG,
                "GetChatHistory 熔断静默中，旧路取页跳过 chatId=$chatId" +
                    "（剩 ${td.floodBreaker.remainingSec(FloodCircuitBreaker.FAMILY_GET_CHAT_HISTORY)}s 解封）",
            )
            return null
        }
        // R46 预算记账：旧路逻辑页 = 2~6 次 GetChatHistory + ≤3 次评论级联，
        // 按 [LEGACY_PAGE_COST] 估值记账（预算是安全包络不是精算，估值取保守方向）
        if (!crawlBudgetAllow(LEGACY_PAGE_COST, "旧路取页", chatId)) return null
        rateGate.await(crawlRateIntervalMs()) // R255 W5：平滑配速（见 SmoothRateGate 注释）
        val first = runCatching {
            gateway.fetchVideos(FeedRequest(channelId = chatId), cursor, PAGE_SIZE)
        }
        if (first.isSuccess) {
            pacer.onPageSuccess()
            return first.getOrThrow()
        }
        val e = first.exceptionOrNull() ?: return null
        if (!isFlood(e)) return null // 非限流失败不重试，直接放弃本轮
        pacer.onFlood()
        // R46：429 上报熔断表（方法族 "GetChatHistory"，持久化跨进程）——
        // 上报后全 app 该族静默到 retry-after 结束，各泳道不再轮番撞墙
        td?.floodBreaker?.report429(
            FloodCircuitBreaker.FAMILY_GET_CHAT_HISTORY,
            (retryAfterMs(e) ?: 0L) / 1000,
        )
        // 第十轮：优先按服务端点名要求的 "retry after N" 精确退避（flood wait
        // 是红线，服务端说等多久就等多久，宁多勿少）；没说再退回固定 5s
        val backoff = maxOf(FLOOD_BACKOFF_MS, retryAfterMs(e) ?: 0L)
        Log.d(TAG, "flood 降速：间隔 ×2 → ${pacer.current()}ms，退避 ${backoff / 1000}s（chatId=$chatId）")
        delay(backoff)
        if (!crawlBudgetAllow(LEGACY_PAGE_COST, "旧路取页重试", chatId)) return null // R46：重试同样记账
        rateGate.await(crawlRateIntervalMs()) // R255 W5：重试同口径平滑配速
        return runCatching {
            gateway.fetchVideos(FeedRequest(channelId = chatId), cursor, PAGE_SIZE)
        }.getOrNull()?.also { pacer.onPageSuccess() }
    }

    /** 限流判定：TDLib 的 429/Too Many Requests 在网关层被包成异常消息，按关键词识别 */
    private fun isFlood(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        return "flood" in msg || "too many requests" in msg || "429" in msg
    }

    /** 从 flood 异常消息解析服务端点名的精确退避（"retry after 12" 类，单位秒→毫秒）；没有返回 null */
    private fun retryAfterMs(e: Throwable): Long? =
        RETRY_AFTER_REGEX.find(e.message ?: "")
            ?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(1000L)

    // ---------------- DB 写入（调用方须在 writeLock + 事务内） ----------------

    // 编译语句缓存（只在 writeLock 内访问，无需额外同步）
    private var stmtInsertItem: SQLiteStatement? = null
    private var stmtInsertFts: SQLiteStatement? = null
    private var stmtBackfill: SQLiteStatement? = null
    private var stmtBackfillCaption: SQLiteStatement? = null
    private var stmtFtsDelete: SQLiteStatement? = null
    /** R238：相册成员 caption 列共享回填（collectAlbumRowsLocked 配套） */
    private var stmtAlbumCapBackfill: SQLiteStatement? = null
    private var stmtMetaIgnore: SQLiteStatement? = null
    private var stmtMetaUpdateCrawl: SQLiteStatement? = null
    private var stmtMetaMarkImported: SQLiteStatement? = null
    private var stmtMetaUpdatePass: SQLiteStatement? = null
    private var stmtMetaUpdateDeepen: SQLiteStatement? = null
    private var stmtMetaUpdateSweepCursor: SQLiteStatement? = null
    private var stmtMetaUpdateHeadTs: SQLiteStatement? = null
    private var stmtMetaUpdateSampled: SQLiteStatement? = null
    private var stmtMetaUpdateTier: SQLiteStatement? = null
    /** R255 W1 配套：升档复活（exhausted=0 回写）编译语句（writeLock 内复用） */
    private var stmtMetaRevive: SQLiteStatement? = null

    /** R262b：死档标记写（meta.dead）编译语句（writeLock 内复用） */
    private var stmtMetaSetDead: SQLiteStatement? = null
    private var stmtEvictReset: SQLiteStatement? = null

    /** R266 v11：refs 落库/items rowid 点删/refs canonical 修正步编译语句（writeLock 内复用） */
    private var stmtInsertRef: SQLiteStatement? = null
    private var stmtItemsDeleteByRowid: SQLiteStatement? = null
    private var stmtRefsFixCanon: SQLiteStatement? = null

    /**
     * 单条入库：items INSERT OR IGNORE（key 判重幂等，与旧 byKey.putIfAbsent 同口径——
     * 已存在条目不更新，TG 侧 caption 变更不追更，与旧实现一致）；新插入才同步写 fts 行
     * （rowid 对齐 items.rowid，contentless 表只 INSERT 不 UPDATE/DELETE）。
     * 返回 true = 本次新插入。LIKE 兜底路径（无 FTS5）把词位串写进 items.tokens 列。
     *
     * [backfillOnConflict] = 存量字段低成本回填（爬取路径 true / 迁移路径 false）：
     * key 冲突（爬取重叠/续爬重见）时顺手 UPDATE——albumId/mediaType/coverFileId 三字段
     * （迁移存量条目 albumId=0，网络页数据更准；三字段不进索引不动 fts 行），以及
     * **caption 空→非空回填**（迁移存量大量空 caption 老条目；caption 进索引，须同事务
     * 同步 fts 词位行，见 [backfillConflictLocked]）。迁移路径必须 false：防 JSON 旧值
     * 回退爬取新值（迁移与爬取可并发同事务域交错）。
     */
    private fun insertItemLocked(
        d: SQLiteDatabase,
        item: VideoItem,
        backfillOnConflict: Boolean,
        /** R255 W11b：页级预计算词位（LIKE 路径锁外算好传入——缩短 writeLock 持有；
         *  null=调用方未预计算（迁移/白嫖旧路径），锁内现算 */
        precomputedTokens: String? = null,
    ): Boolean {
        // R266 v11 归并·第一步：refs key 点查（主键探测 µs 级）——已收编转发行
        // 的重见（头部刷新重爬/白嫖重扫）→ 刷新 refs 发布侧字段，**不回 items**
        // （不变式 2：items.key 与 refs.key 全域互斥）。mergeActive=false（指纹
        // 索引未就绪）期间本步跳过=存量形态零变化。
        if (mergeActive && refKeyExists(d, item.key)) {
            if (backfillOnConflict) refreshRefLocked(d, item)
            return false
        }
        val stmt = stmtInsertItem ?: run {
            // R228：插入仍只写 16 基础列（[ITEM_INSERT_COLS]）——解析字段须待入库后
            // 查相册成员全集才能算（applyParsedCaptionsLocked 同事务 UPDATE 落位）
            val cols = if (useFts5) {
                "INSERT OR IGNORE INTO items($ITEM_INSERT_COLS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            } else {
                "INSERT OR IGNORE INTO items($ITEM_INSERT_COLS,tokens) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            }
            d.compileStatement(cols).also { stmtInsertItem = it }
        }
        // R255 W6：LIKE 路径 tokens 必须随 INSERT 绑定（无独立 fts 行）——原位计算不变；
        // FTS5 路径词位串**延迟到插入成功后**再算：冲突页（头部刷新/续爬重见）不再白付
        // fold+tokenize（600 字截断+CJK bigram+去重连接）的每条 CPU 税——全冲突页
        // 从此近零解析成本（16384/16416 无 FTS5 走 LIKE 原口径，零变化）
        stmt.clearBindings()
        stmt.bindString(1, item.key)
        stmt.bindLong(2, item.chatId)
        stmt.bindLong(3, item.messageId)
        stmt.bindLong(4, item.albumId)
        stmt.bindLong(5, item.mediaType.toLong())
        stmt.bindLong(6, item.coverFileId.toLong())
        stmt.bindString(7, item.caption)
        stmt.bindString(8, item.channelTitle)
        stmt.bindLong(9, item.fileId.toLong())
        stmt.bindString(10, item.fileUniqueId)
        stmt.bindString(11, item.mimeType)
        stmt.bindLong(12, item.durationSec.toLong())
        stmt.bindLong(13, item.sizeBytes)
        stmt.bindLong(14, item.width.toLong())
        stmt.bindLong(15, item.height.toLong())
        stmt.bindLong(16, item.dateEpochSec.toLong())
        if (!useFts5) {
            // R255 W11b：预计算优先（锁外算好）；无则锁内现算（旧路径）
            val tokens = precomputedTokens ?: indexTokensOf(item)
            stmt.bindString(17, " $tokens ") // LIKE 口径：词位串带首尾空格，'% 词位 %' 定界可中
        }
        val rowid = stmt.executeInsert()
        if (rowid == -1L) {
            // key 已存在：爬取路径顺手回填（存量 albumId=0 / 空 caption → 网络真值；同事务内）
            if (backfillOnConflict) backfillConflictLocked(d, item)
            return false
        }
        var insertedTokens: String? = null // R266：FTS5 词位留底——转学收编删除须逐字节一致
        if (useFts5) {
            val fs = stmtInsertFts
                ?: d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
                    .also { stmtInsertFts = it }
            fs.clearBindings()
            fs.bindLong(1, rowid)
            val tokens = indexTokensOf(item) // R255 W6：插入成功才计算（见上注释）
            fs.bindString(2, tokens)
            fs.executeInsert()
            insertedTokens = tokens
        }
        // R266 v11 归并·第三步（同事务「先插后转」，设计 §6.2）：新行已落，查同指纹
        // **其他**行（idx_items_fuid 点查）——有则本行是转发副本，当场转学收编进
        // refs（行移动同事务原子，外部不可见中间态）；无则本行=首发/单件保持全量行。
        // canonical 冻结不换马（D1 附注）：目标=既有行（迁移窗口期可能是临时
        // canonical，批迁 Job 组事务收尾的 canonRowid 修正步一锤定音）。
        // 「先插后转」而非「先查再插」：INSERT OR IGNORE 的原子 key 判重语义原样
        // 保留，同页两条同 key 同 fuid 竞态天然安全。
        if (mergeActive && item.fileUniqueId.isNotEmpty()) {
            var otherRowid = -1L
            d.rawQuery(
                "SELECT rowid FROM items WHERE fileUniqueId=? AND rowid != ? LIMIT 1",
                arrayOf(item.fileUniqueId, rowid.toString()),
            ).use { c -> if (c.moveToFirst()) otherRowid = c.getLong(0) }
            if (otherRowid > 0L) {
                // canonical caption 点查（覆盖列裁决原料，D2）
                var canonCaption = ""
                d.rawQuery(
                    "SELECT caption FROM items WHERE rowid=?",
                    arrayOf(otherRowid.toString()),
                ).use { c -> if (c.moveToFirst()) canonCaption = c.getString(0) ?: "" }
                // FTS5 形态：先删刚写的 fts 词位行（contentless 删除要逐字节一致的
                // 词位串——插入时留底的同一串，免二次 fold+tokenize）；LIKE 形态随行删
                if (useFts5) ftsDeleteLocked(d, rowid, insertedTokens ?: indexTokensOf(item))
                // 转学收编：发布侧字段+覆盖列搬 refs（INSERT OR IGNORE 幂等），删 items 本行
                insertRefLocked(
                    d,
                    RefRow(
                        key = item.key, chatId = item.chatId, messageId = item.messageId,
                        albumId = item.albumId, mediaType = item.mediaType,
                        durationSec = item.durationSec, coverFileId = item.coverFileId,
                        channelTitle = item.channelTitle, dateEpochSec = item.dateEpochSec.toLong(),
                        fuid = item.fileUniqueId, canonRowid = otherRowid,
                        captionOverride = RepostMergePolicy.captionOverride(item.caption, canonCaption),
                    ),
                )
                val del = stmtItemsDeleteByRowid
                    ?: d.compileStatement("DELETE FROM items WHERE rowid=?")
                        .also { stmtItemsDeleteByRowid = it }
                del.clearBindings()
                del.bindLong(1, rowid)
                del.executeUpdateDelete()
                // 新插入行无 parsed/item_tags（解析在页事务后置 applyParsedCaptionsLocked，
                // 届时按 key IN items 查不到本行=自然跳过——refs 不进解析流程，D5/§6.4）
                return true // 计数仍算新增（设计 §6.2：转学=新发布入库，不是冲突丢弃）
            }
        }
        return true
    }

    /** v11 refs key 存在性点查（主键探测，µs 级；调用方已持 writeLock+事务） */
    private fun refKeyExists(d: SQLiteDatabase, key: String): Boolean =
        d.rawQuery("SELECT 1 FROM $REPOSTS_TABLE WHERE key=?", arrayOf(key)).use { it.moveToFirst() }

    /**
     * v11 refs 重见刷新（key 已收编的转发再次被爬到；调用方已持 writeLock+事务）：
     * 发布侧三字段（albumId/mediaType/coverFileId）照 items 回填口径顺手更新；
     * caption 覆盖列**仅当新爬到非空文案时**按 D2 政策重算（与 canonical 现值比对——
     * canonical caption 可能被回填更新过，覆盖判定须以现值为准）；新 caption 为空
     * 不动覆盖列（空不回填，与 backfillConflictLocked 的 caption 空→非空单向口径镜像）。
     */
    private fun refreshRefLocked(d: SQLiteDatabase, item: VideoItem) {
        var canonRowid = -1L
        d.rawQuery(
            "SELECT canonRowid FROM $REPOSTS_TABLE WHERE key=?",
            arrayOf(item.key),
        ).use { c -> if (c.moveToFirst()) canonRowid = c.getLong(0) }
        if (canonRowid <= 0L) return // 行被并发收编走（防御：writeLock 内不应发生）
        if (item.caption.isNotEmpty()) {
            var canonCaption = ""
            d.rawQuery(
                "SELECT caption FROM items WHERE rowid=?",
                arrayOf(canonRowid.toString()),
            ).use { c -> if (c.moveToFirst()) canonCaption = c.getString(0) ?: "" }
            val upd = d.compileStatement(
                "UPDATE $REPOSTS_TABLE SET albumId=?, mediaType=?, coverFileId=?, caption=? WHERE key=?",
            )
            try {
                upd.clearBindings()
                upd.bindLong(1, item.albumId)
                upd.bindLong(2, item.mediaType.toLong())
                upd.bindLong(3, item.coverFileId.toLong())
                val ov = RepostMergePolicy.captionOverride(item.caption, canonCaption)
                if (ov == null) upd.bindNull(4) else upd.bindString(4, ov)
                upd.bindString(5, item.key)
                upd.executeUpdateDelete()
            } finally {
                runCatching { upd.close() }
            }
        } else {
            val upd = d.compileStatement(
                "UPDATE $REPOSTS_TABLE SET albumId=?, mediaType=?, coverFileId=? WHERE key=?",
            )
            try {
                upd.clearBindings()
                upd.bindLong(1, item.albumId)
                upd.bindLong(2, item.mediaType.toLong())
                upd.bindLong(3, item.coverFileId.toLong())
                upd.bindString(4, item.key)
                upd.executeUpdateDelete()
            } finally {
                runCatching { upd.close() }
            }
        }
    }

    /**
     * key 冲突回填（爬取路径专用，调用方已在 writeLock + 事务内）：
     *  1. 常规三字段（albumId/mediaType/coverFileId）：不进索引，直接 UPDATE 不动 fts 行；
     *  2. caption（**已存为空 且 新爬到非空**才回填）：迁移存量里大量 caption 为空的老条目
     *     （形态A/B 关联修复前爬的 JSON），不回填用户搜到也看不到文字说明。
     *     caption 进索引，必须**同事务同步 fts**——contentless 表删除需旧词位串：
     *     旧 caption/channelTitle 已从库读出原值，用 [indexTokensOf] 现算旧词位
     *     → `fts(fts,rowid,tokens) VALUES('delete',?,?)` 删旧行 → 再 INSERT 新词位行
     *     （rowid 不变，回表对齐不变）。LIKE 兜底路径（无 FTS5）改 UPDATE items.tokens 列。
     */
    private fun backfillConflictLocked(d: SQLiteDatabase, item: VideoItem) {
        // caption 回填前置探针：新爬到 caption 非空才值得查库（新 caption 为空必然无回填）
        var rowid = -1L
        var oldCaption: String? = null
        var oldChannelTitle = ""
        if (item.caption.isNotEmpty()) {
            d.rawQuery(
                "SELECT rowid, caption, channelTitle FROM items WHERE key=?",
                arrayOf(item.key),
            ).use { c ->
                if (c.moveToFirst()) {
                    rowid = c.getLong(0)
                    oldCaption = c.getString(1) ?: ""
                    oldChannelTitle = c.getString(2) ?: ""
                }
            }
        }
        val oc = oldCaption
        if (oc == null || oc.isNotEmpty()) {
            // 无 caption 回填（探针跳过/行缺失/旧 caption 非空）：三字段回填，不动 fts 行
            val bf = stmtBackfill
                ?: d.compileStatement("UPDATE items SET albumId=?, mediaType=?, coverFileId=? WHERE key=?")
                    .also { stmtBackfill = it }
            bf.clearBindings()
            bf.bindLong(1, item.albumId)
            bf.bindLong(2, item.mediaType.toLong())
            bf.bindLong(3, item.coverFileId.toLong())
            bf.bindString(4, item.key)
            bf.executeUpdateDelete()
            return
        }
        // caption 空→非空：一次 UPDATE 连同三字段 + caption（LIKE 路径连同 tokens 列）。
        // 词位与库存 channelTitle 对齐（channelTitle 不在回填范围，保持索引与库存行一致）
        // R292：已并入索引的 OCR 文本随行保持（配方同源——新 caption 若变高信息，
        // 配方内低信息闸自然把 OCR 后缀摘出，无需此处特判）
        val ocrText = if (rowid > 0) ocrAppliedTextLocked(d, rowid) else null
        val newTokens = indexTokensOf(item.caption, oldChannelTitle, ocrText)
        val bf = stmtBackfillCaption ?: d.compileStatement(
            if (useFts5) {
                "UPDATE items SET albumId=?, mediaType=?, coverFileId=?, caption=? WHERE key=?"
            } else {
                "UPDATE items SET albumId=?, mediaType=?, coverFileId=?, caption=?, tokens=? WHERE key=?"
            },
        ).also { stmtBackfillCaption = it }
        bf.clearBindings()
        bf.bindLong(1, item.albumId)
        bf.bindLong(2, item.mediaType.toLong())
        bf.bindLong(3, item.coverFileId.toLong())
        bf.bindString(4, item.caption)
        if (!useFts5) bf.bindString(5, " $newTokens ") // LIKE 口径：词位串带首尾空格
        bf.bindString(if (useFts5) 5 else 6, item.key)
        bf.executeUpdateDelete()
        if (useFts5 && rowid > 0) {
            // fts 同步（同事务）：contentless 删除要旧词位串——旧 caption 已从库读出原值，现算
            // （R292：旧词位含已并入 OCR 文本——同一 ocrText 进两侧配方，逐字节一致不破）
            val oldTokens = indexTokensOf(oc, oldChannelTitle, ocrText)
            val del = stmtFtsDelete ?: d.compileStatement(
                "INSERT INTO fts(fts, rowid, tokens) VALUES('delete', ?, ?)"
            ).also { stmtFtsDelete = it }
            del.clearBindings()
            del.bindLong(1, rowid)
            del.bindString(2, oldTokens)
            del.executeInsert()
            val ins = stmtInsertFts
                ?: d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
                    .also { stmtInsertFts = it }
            ins.clearBindings()
            ins.bindLong(1, rowid)
            ins.bindString(2, newTokens)
            ins.executeInsert()
        }
    }

    /** 建表词位串：取条目的 caption/channelTitle，口径见 [indexTokensOf] 重载 */
    private fun indexTokensOf(item: VideoItem): String = indexTokensOf(item.caption, item.channelTitle)

    /**
     * 建表词位串：fold(caption+channelTitle[+ocr 后缀]) 截断 [MAX_INDEX_TEXT_LEN] →
     * tokenize(forIndex=true) 去重 → 空格连接。caption 回填路径的 fts 删除/重建用同一口径
     * （contentless 删除要求旧词位串与被删行**逐字节一致**）。
     *
     * R292 三参重载：[ocrText]=该行已并入索引的 OCR 文本（item_ocr.applied=1 的行；
     * 未结算/无行传 null）。并入判定唯一出处 [OcrPolicy.ocrIndexSuffix]——仅低信息
     * caption 才并（防 BM25 信号稀释），是 (caption, ocrText) 的纯函数：建/删/重算
     * 三处对同一输入恒同结论，contentless 逐字节一致性由此守住。
     */
    // R287：internal（IndexImportMerge 新入行词位/合并 caption 重算同源配方）
    internal fun indexTokensOf(caption: String, channelTitle: String, ocrText: String? = null): String {
        // 单条目文本截断：几千字清单 caption 的搜索价值全在头部（沿用旧倒排表口径）
        val textFold = SearchTextKit.fold(
            caption + " " + channelTitle + OcrPolicy.ocrIndexSuffix(caption, ocrText),
        ).take(MAX_INDEX_TEXT_LEN)
        // 去重：同一词位只留一份（旧倒排表 .toSet() 同口径；bm25 的 tf 因此恒 1，排序靠 idf 区分）
        return SearchTextKit.tokenize(textFold, forIndex = true).toSet().joinToString(" ")
    }

    /**
     * R292：该行已并入索引的 OCR 文本（item_ocr applied=1 且 text 非空白 → 返回 text；
     * 无行/未结算/空白 → null）。fts 删除/重算四处的「现词位原料」同源查询口
     * （item_ocr rowid 主键点查，微秒级）。脱敏：返回值只进词位配方，绝不进日志。
     */
    private fun ocrAppliedTextLocked(d: SQLiteDatabase, rowid: Long): String? {
        d.rawQuery(
            "SELECT text FROM $ITEM_OCR_TABLE WHERE rowid=? AND applied=1",
            arrayOf(rowid.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                val t = c.getString(0)
                if (!t.isNullOrBlank()) return t
            }
        }
        return null
    }

    /**
     * 爬取路径 meta 落库：行不存在则插入、存在则更新游标/完成态
     * （minSdk 26 的 SQLite 3.18 无 UPSERT 语法，INSERT OR IGNORE + UPDATE 两步走）。
     * imported 标记只由迁移路径写，这里恒插 0 且 UPDATE 不动它。
     */
    /** R262b：死档标记写（调用方持 writeLock+事务；meta 行已由 upsertMeta 保证存在） */
    private fun setDeadLocked(d: SQLiteDatabase, chatId: Long, dead: Boolean) {
        val stmt = stmtMetaSetDead
            ?: d.compileStatement("UPDATE meta SET dead=? WHERE chatId=?").also { stmtMetaSetDead = it }
        stmt.clearBindings()
        stmt.bindLong(1, if (dead) 1L else 0L)
        stmt.bindLong(2, chatId)
        stmt.executeUpdateDelete()
    }

    private fun upsertMetaCrawlLocked(d: SQLiteDatabase, chatId: Long, cursor: Long, exhausted: Boolean) {
        val ins = stmtMetaIgnore
            ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                .also { stmtMetaIgnore = it }
        ins.clearBindings()
        ins.bindLong(1, chatId)
        ins.bindLong(2, cursor)
        ins.bindLong(3, if (exhausted) 1L else 0L)
        ins.bindLong(4, 0L)
        ins.executeInsert()
        val upd = stmtMetaUpdateCrawl
            ?: d.compileStatement("UPDATE meta SET cursor=?, exhausted=? WHERE chatId=?")
                .also { stmtMetaUpdateCrawl = it }
        upd.clearBindings()
        upd.bindLong(1, cursor)
        upd.bindLong(2, if (exhausted) 1L else 0L)
        upd.bindLong(3, chatId)
        upd.executeUpdateDelete()
    }

    /**
     * 迁移路径 meta 落库：行不存在才写入 JSON 游标/完成态；已存在（并发爬取写过更新的值）
     * 只补 imported 标记，绝不回退爬取侧的游标。
     */
    private fun upsertMetaImportedLocked(d: SQLiteDatabase, chatId: Long, cursor: Long, exhausted: Boolean) {
        val ins = stmtMetaIgnore
            ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                .also { stmtMetaIgnore = it }
        ins.clearBindings()
        ins.bindLong(1, chatId)
        ins.bindLong(2, cursor)
        ins.bindLong(3, if (exhausted) 1L else 0L)
        ins.bindLong(4, 1L)
        ins.executeInsert()
        val mark = stmtMetaMarkImported
            ?: d.compileStatement("UPDATE meta SET imported=1 WHERE chatId=?")
                .also { stmtMetaMarkImported = it }
        mark.clearBindings()
        mark.bindLong(1, chatId)
        mark.executeUpdateDelete()
    }

    /**
     * 媒体清扫相位落库（writeLock + 单事务）：行不存在则按清扫起点语义插入
     * （cursor=0 全新起点；已存在的行只改 mediaPass——cursor 在清扫期冻结为起点，
     * exhausted 由调用方收尾时经 upsertMetaCrawlLocked 置位）。
     */
    private fun setMediaPass(d: SQLiteDatabase, chatId: Long, mediaPass: Int) {
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                val ins = stmtMetaIgnore
                    ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                        .also { stmtMetaIgnore = it }
                ins.clearBindings()
                ins.bindLong(1, chatId)
                ins.bindLong(2, 0L)
                ins.bindLong(3, 0L)
                ins.bindLong(4, 0L)
                ins.executeInsert()
                val upd = stmtMetaUpdatePass
                    ?: d.compileStatement("UPDATE meta SET mediaPass=? WHERE chatId=?")
                        .also { stmtMetaUpdatePass = it }
                upd.clearBindings()
                upd.bindLong(1, mediaPass.toLong())
                upd.bindLong(2, chatId)
                upd.executeUpdateDelete()
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    /**
     * needDeepen 标记读写（浅爬频道加深）：
     * 置位时机——爬取触 [TAIL_MAX_ITEMS] 条数硬顶（含媒体清扫/旧路尾爬/日历跳爬三处）
     * 与 v3 迁移时对旧 5000 封顶时代数据的一次性识别；清除时机——加深续爬到频道底。
     */
    private fun setNeedDeepen(d: SQLiteDatabase, chatId: Long, need: Boolean) {
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                val ins = stmtMetaIgnore
                    ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                        .also { stmtMetaIgnore = it }
                ins.clearBindings()
                ins.bindLong(1, chatId)
                ins.bindLong(2, 0L)
                ins.bindLong(3, 0L)
                ins.bindLong(4, 0L)
                ins.executeInsert()
                val upd = stmtMetaUpdateDeepen
                    ?: d.compileStatement("UPDATE meta SET needDeepen=? WHERE chatId=?")
                        .also { stmtMetaUpdateDeepen = it }
                upd.clearBindings()
                upd.bindLong(1, if (need) 1L else 0L)
                upd.bindLong(2, chatId)
                upd.executeUpdateDelete()
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    /** 读 needDeepen 标记（无记录 = false） */
    private fun readNeedDeepen(d: SQLiteDatabase, chatId: Long): Boolean =
        d.rawQuery("SELECT needDeepen FROM meta WHERE chatId=?", arrayOf(chatId.toString())).use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        }

    /**
     * 清扫相位内游标落盘（R45，根因 R2；writeLock + 单事务）：每页推进即写，
     * flood/进程死亡后从断点续扫，不再从相位页 0 整体重扫；
     * 相位推进/清扫完成时归 0（游标只对当前相位有效）。行不存在按清扫起点语义插入。
     */
    private fun setSweepCursor(d: SQLiteDatabase, chatId: Long, sweepCursor: Long) {
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                val ins = stmtMetaIgnore
                    ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                        .also { stmtMetaIgnore = it }
                ins.clearBindings()
                ins.bindLong(1, chatId)
                ins.bindLong(2, 0L)
                ins.bindLong(3, 0L)
                ins.bindLong(4, 0L)
                ins.executeInsert()
                val upd = stmtMetaUpdateSweepCursor
                    ?: d.compileStatement("UPDATE meta SET sweepCursor=? WHERE chatId=?")
                        .also { stmtMetaUpdateSweepCursor = it }
                upd.clearBindings()
                upd.bindLong(1, sweepCursor)
                upd.bindLong(2, chatId)
                upd.executeUpdateDelete()
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    /**
     * R255 W7：清扫游标 UPDATE（调用方已持 writeLock+**已开事务**——相位推进归零
     * 与 [persistPage] 第 4 参两处消费；meta 行由同事务在前的 upsertMeta 保证存在）。
     * 断点语义与旧 setSweepCursor 完全一致，只是并入页事务省一笔 begin/commit。
     */
    private fun setSweepCursorLocked(d: SQLiteDatabase, chatId: Long, sweepCursor: Long) {
        val upd = stmtMetaUpdateSweepCursor
            ?: d.compileStatement("UPDATE meta SET sweepCursor=? WHERE chatId=?")
                .also { stmtMetaUpdateSweepCursor = it }
        upd.clearBindings()
        upd.bindLong(1, sweepCursor)
        upd.bindLong(2, chatId)
        upd.executeUpdateDelete()
    }

    /**
     * 头部刷新节流时间戳落盘（R45，根因 R3；writeLock + 单事务）：
     * 实际执行头部刷新即记时，已到底频道距上次不足 [HEAD_REFRESH_MIN_INTERVAL_MS] 不再刷。
     */
    private fun setLastHeadRefreshTs(d: SQLiteDatabase, chatId: Long, tsMs: Long) {
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                val ins = stmtMetaIgnore
                    ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                        .also { stmtMetaIgnore = it }
                ins.clearBindings()
                ins.bindLong(1, chatId)
                ins.bindLong(2, 0L)
                ins.bindLong(3, 0L)
                ins.bindLong(4, 0L)
                ins.executeInsert()
                val upd = stmtMetaUpdateHeadTs
                    ?: d.compileStatement("UPDATE meta SET lastHeadRefreshTs=? WHERE chatId=?")
                        .also { stmtMetaUpdateHeadTs = it }
                upd.clearBindings()
                upd.bindLong(1, tsMs)
                upd.bindLong(2, chatId)
                upd.executeUpdateDelete()
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    /**
     * 时间分层采样标记落盘（R46 方案 c；writeLock + 单事务）：sampled=1 表示该频道
     * 是"按月锚点采样"覆盖而非全量翻页——口径诚实标记，UI 展示覆盖度据此区分全量/采样。
     */
    private fun setSampled(d: SQLiteDatabase, chatId: Long, sampled: Boolean) {
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                val ins = stmtMetaIgnore
                    ?: d.compileStatement("INSERT OR IGNORE INTO meta(chatId, cursor, exhausted, imported) VALUES(?,?,?,?)")
                        .also { stmtMetaIgnore = it }
                ins.clearBindings()
                ins.bindLong(1, chatId)
                ins.bindLong(2, 0L)
                ins.bindLong(3, 0L)
                ins.bindLong(4, 0L)
                ins.executeInsert()
                val upd = stmtMetaUpdateSampled
                    ?: d.compileStatement("UPDATE meta SET sampled=? WHERE chatId=?")
                        .also { stmtMetaUpdateSampled = it }
                upd.clearBindings()
                upd.bindLong(1, if (sampled) 1L else 0L)
                upd.bindLong(2, chatId)
                upd.executeUpdateDelete()
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    // ---------------- R163 B3：存储约束 evict（分档开才生效；纪律①分档关=现状无界增长） ----------------

    /** 「触发但无可淘汰档位」限频记（10 分钟一笔防刷屏：每次爬取收尾都会走到） */
    @Volatile private var lastEvictNoCandidateLogAt = 0L

    /**
     * 存储约束检查（每频道爬取收尾调用，[indexChannel] 外壳）——**修订令版扳机**：
     * 全库行数硬顶（205 万）已废（用户令：死数字剪裁活偏好，与按关注分档同族病；
     * 体积=「关注了多少好频道」的涌现结果）。新扳机=真实约束（[IndexTierPolicy.evictNeeded]）：
     *  - 磁盘水位线：索引库所在分区**剩余可用空间** < [TieringConfig.evictMinFreeBytes]
     *    （[android.os.StatFs.availableBytes]，零权限，分档开时默认生效）；
     *  - 用户索引库预算：库体积（主文件+WAL/SHM，[dbSizeBytes]）> [TieringConfig.evictIndexBudgetBytes]
     *    （设置项，默认关=只看水位线）。
     * 未触发直接返回（零成本：一次 statfs + 一次文件 stat，不开库）。
     * 触发后从最低价值频道渐进收删：**候选 = T3、T2 档**（T3 先于 T2——同档按
     * lastHeadRefreshTs 最旧先删，最久未维护 = 最冷）；T0（关注全量）/T1（高互动
     * 深索引）/LEGACY（未分类，不臆测）**绝不淘汰**。每轮最多收删
     * [EVICT_MAX_CHANNELS_PER_PASS] 个频道（渐进收敛：删一轮看一次水位，字节约束
     * 下不设行数 overflow 线，删到水位/预算线下次收尾自然停）。
     */
    private fun evictIfNeeded(tiering: IndexTierPolicy.TieringConfig) {
        if (!tiering.enabled) return // 双保险（外壳已判；纯函数防将来直调）
        val freeBytes = runCatching {
            android.os.StatFs(storeDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE) // 测不到=没证据不触发（闸更保守）
        val dbBytes = dbSizeBytes()
        if (!IndexTierPolicy.evictNeeded(freeBytes, dbBytes, tiering)) return
        if (healWriteBlocked("evictIfNeeded")) return // R289 只读回退闸（只读期绝不删行）
        val d = openDb()
        // 候选序：T3（3）先于 T2（2）——tier 降序恰等于价值升序；同档最久未刷先删。
        // B7 实测补：必须 EXISTS 有条目——零条目的 T3（探针空频道）不进候选，
        // 否则「候选 4 个却零收删完成」的空转日志刷屏（收删它们的收益恒为 0）
        val candidates = d.rawQuery(
            "SELECT chatId FROM meta WHERE tier IN (3, 2) AND " +
                "(EXISTS (SELECT 1 FROM items WHERE items.chatId = meta.chatId) OR " +
                "EXISTS (SELECT 1 FROM $REPOSTS_TABLE WHERE $REPOSTS_TABLE.chatId = meta.chatId)) " +
                "ORDER BY tier DESC, lastHeadRefreshTs ASC LIMIT ?",
            arrayOf(EVICT_MAX_CHANNELS_PER_PASS.toString()),
        ).use { c ->
            val out = ArrayList<Long>(EVICT_MAX_CHANNELS_PER_PASS)
            while (c.moveToNext()) out += c.getLong(0)
            out
        }
        val mb = { v: Long -> v / (1024L * 1024) }
        if (candidates.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastEvictNoCandidateLogAt > 10L * 60_000) {
                lastEvictNoCandidateLogAt = now
                Log.w(
                    TAG,
                    "存储约束触发（剩余可用=${mb(freeBytes)}MB 库体积=${mb(dbBytes)}MB）" +
                        "但无可淘汰档位（T0/T1/LEGACY 保护）",
                )
            }
            return
        }
        Log.w(
            TAG,
            "存储约束触发收删（剩余可用=${mb(freeBytes)}MB 库体积=${mb(dbBytes)}MB），" +
                "本轮收删候选 ${candidates.size} 个频道 " +
                "（T3>T2 最冷优先，每轮至多 $EVICT_MAX_CHANNELS_PER_PASS 个，渐进收敛）",
        )
        for (chatId in candidates) {
            runCatching { evictChannel(d, chatId) }
                .onFailure { Log.w(TAG, "频道收删失败（跳过，下轮重试）：chatId=$chatId ${it.message}") }
        }
    }

    /**
     * 单频道收删（writeLock + 单事务，原子）：**整频道删除天然相册原子**
     * （同组相册成员同频道必同删，不会剩半张专辑）。
     *  1. FTS5 路径：逐行 `fts(fts,rowid,tokens) VALUES('delete',?)`——词位从
     *     items 行的 caption/channelTitle **现算**（[indexTokensOf] 同一函数 =
     *     插入/删除同源，无分词漂移；与 backfillConflictLocked 的 caption 回填
     *     是同一先例配方）；LIKE 兜底路径无需额外动作（tokens 是 items 列随行删）。
     *     注意：delete 指令在 items 行删除**之前**逐行执行（词位原料必须在场）。
     *  2. DELETE FROM items WHERE chatId=?（chatId 索引命中）。
     *  3. meta 复位为"未爬"态 + tier 置 T3：淘汰=回到按需档，等探索配额/搜索触发
     *     重评估（防 LRU 删完又被常规车道立刻派工爬回来）。
     * 返回删除条数（0 = 无行/异常已回滚）。
     */
    private fun evictChannel(d: SQLiteDatabase, chatId: Long): Int {
        var count = 0
        var promoted = 0
        var refsRemoved = 0
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                // R266 v11：本频道 items 行逐行判定——被 refs 引用的 canonical 先
                // 提升重铸（D8：存活方最早发布者接任，§3.5-2），其余走现状删除。
                // 行集先物化再逐行处理（循环体内会 INSERT 新 items 行/改 refs——
                // 游标边扫边写同表行为未定义，物化后游标已关）
                class EvictRow(val rowid: Long, val caption: String, val channelTitle: String, val fuid: String)
                val rows = ArrayList<EvictRow>()
                d.rawQuery(
                    "SELECT rowid, caption, channelTitle, fileUniqueId FROM items WHERE chatId=?",
                    arrayOf(chatId.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        rows += EvictRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "")
                    }
                }
                // R292：本频道已并入索引的 OCR 文本一次批量预取（逐行点查 50k 行不值；
                // 试点期 applied=1 恒空表=零成本）。词位配方同源——contentless 删除
                // 逐字节一致生死线
                val ocrByRowid = HashMap<Long, String>()
                d.rawQuery(
                    "SELECT rowid, text FROM $ITEM_OCR_TABLE WHERE chatId=? AND applied=1",
                    arrayOf(chatId.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val t = c.getString(1)
                        if (!t.isNullOrBlank()) ocrByRowid[c.getLong(0)] = t
                    }
                }
                for (r in rows) {
                    // FTS5 路径：删旧词位（词位原料必须在场——先删 fts 再动行）
                    if (useFts5) ftsDeleteLocked(d, r.rowid, indexTokensOf(r.caption, r.channelTitle, ocrByRowid[r.rowid]))
                    count++
                    if (r.fuid.isEmpty()) continue
                    // refs 引用点查（idx_refs_fuid）：存活方（≠本频道）最早发布者=提升
                    // 候选（D1 首发序同源口径）；无存活方 refs → 组随频道整体消亡
                    var ref: RefsPubRow? = null
                    d.rawQuery(
                        "SELECT $REFS_PUB_COLS FROM $REPOSTS_TABLE WHERE fuid=? AND chatId != ? " +
                            "ORDER BY dateEpochSec ASC, messageId ASC LIMIT 1",
                        arrayOf(r.fuid, chatId.toString()),
                    ).use { c -> if (c.moveToFirst()) ref = cursorToRefsRow(c) }
                    val promoteTo = ref ?: continue
                    promoteCanonicalLocked(d, r.rowid, r.fuid, promoteTo)
                    promoted++
                }
                // refs 臂行数（含本频道 refs；canonical 在他处——直接删无孤儿）
                refsRemoved = d.rawQuery(
                    "SELECT COUNT(*) FROM $REPOSTS_TABLE WHERE chatId=?",
                    arrayOf(chatId.toString()),
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                if (count == 0 && refsRemoved == 0) return 0
                d.execSQL("DELETE FROM items WHERE chatId=$chatId")
                d.execSQL("DELETE FROM $REPOSTS_TABLE WHERE chatId=$chatId")
                // R236：tags 加速层同步清理（tag_items 的 chat 域索引/标签账本整频道
                // 前缀——PK chatId 前缀命中）；与 items 删除同事务=原子（频道收删后
                // tags 表零残留，重爬时 delta 从零重新聚合）。R266：提升重铸行的
                // tags 已重挂到新频道（chatId≠本频道），不被本批清理误伤。
                d.execSQL("DELETE FROM $TAG_ITEMS_TABLE WHERE chatId=$chatId")
                d.execSQL("DELETE FROM $TAGS_TABLE WHERE chatId=$chatId")
                // R292：item_ocr 整频道同步清理（同事务原子；重爬后由回填 Job 重识别）
                d.execSQL("DELETE FROM $ITEM_OCR_TABLE WHERE chatId=$chatId")
                val reset = stmtEvictReset
                    ?: d.compileStatement(
                        "UPDATE meta SET cursor=0, exhausted=0, mediaPass=-1, sweepCursor=0, " +
                            "lastHeadRefreshTs=0, needDeepen=0, sampled=0, tier=3, dead=0 WHERE chatId=?",
                    ).also { stmtEvictReset = it }
                reset.clearBindings()
                reset.bindLong(1, chatId)
                reset.executeUpdateDelete()
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        if (count > 0 || refsRemoved > 0) {
            Log.i(
                TAG,
                "频道收删完成 chatId=$chatId 删除 items=$count refs=$refsRemoved" +
                    (if (promoted > 0) "（canonical 提升重铸 $promoted 行至存活频道）" else "") +
                    "（已复位为 T3 待探索/搜索触发重评估）",
            )
        }
        return count + refsRemoved
    }

    /**
     * v11 evict canonical 提升重铸（D8/§3.5-2；调用方已持 writeLock+事务）：
     * 被删频道的 canonical 行还有他频道 refs 引用时，从 refs 取存活方最早发布者
     * 重铸为新 items 全量行（**新 rowid**）：
     *  1. 内容侧全字段+parsed 两列从旧 canonical 原样拷贝；发布侧字段取提升源
     *     refs 行；caption=覆盖列 ?: 旧 canonical caption（D2 忠实保留链不断）；
     *  2. 词位按**新行**的 caption/channelTitle 现算入库（LIKE tokens 列 /
     *     FTS5 fts 行；旧行 fts 词位由调用方先删）；
     *  3. tag_items 重挂新 rowid（delta 增集到提升源频道；旧频道侧由调用方
     *     整频道毯式清理收口——tags 表旧频道行 wholesale DELETE 已含减集）；
     *  4. 全组 refs 的 canonRowid 改指新 rowid（idx_refs_fuid 命中）+ 提升源
     *     refs 行自身删除（它已升格为 items 全量行——key 全域互斥不破）。
     */
    private fun promoteCanonicalLocked(d: SQLiteDatabase, oldRowid: Long, fuid: String, ref: RefsPubRow) {
        // 旧 canonical 内容侧+parsed 原料（rowid 点查）
        var fileId = 0
        var fileUniqueId = fuid
        var mimeType = "video/mp4"
        var sizeBytes = 0L
        var width = 0
        var height = 0
        var canonCaption = ""
        var parsedTitle: String? = null
        var itemTagsJson: String? = null
        d.rawQuery(
            "SELECT fileId, fileUniqueId, mimeType, sizeBytes, width, height, caption, " +
                "$PARSED_TITLE_COL, $ITEM_TAGS_COL FROM items WHERE rowid=?",
            arrayOf(oldRowid.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                fileId = c.getInt(0)
                fileUniqueId = c.getString(1) ?: fuid
                mimeType = c.getString(2)?.takeIf { it.isNotBlank() } ?: "video/mp4"
                sizeBytes = c.getLong(3)
                width = c.getInt(4)
                height = c.getInt(5)
                canonCaption = c.getString(6) ?: ""
                parsedTitle = if (c.isNull(7)) null else c.getString(7)
                itemTagsJson = if (c.isNull(8)) null else c.getString(8)
            }
        }
        val newCaption = ref.captionOverride ?: canonCaption
        val newKey = ref.key
        // 重铸插入（16 基础列 + LIKE 形态 tokens；FTS5 词位随后入 fts 表）
        val ins = d.compileStatement(
            if (useFts5) {
                "INSERT OR IGNORE INTO items($ITEM_INSERT_COLS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            } else {
                "INSERT OR IGNORE INTO items($ITEM_INSERT_COLS,tokens) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            },
        )
        val newRowid: Long
        try {
            ins.clearBindings()
            ins.bindString(1, newKey)
            ins.bindLong(2, ref.chatId)
            ins.bindLong(3, ref.messageId)
            ins.bindLong(4, ref.albumId)
            ins.bindLong(5, ref.mediaType.toLong())
            ins.bindLong(6, ref.coverFileId.toLong())
            ins.bindString(7, newCaption)
            ins.bindString(8, ref.channelTitle)
            ins.bindLong(9, fileId.toLong())
            ins.bindString(10, fileUniqueId)
            ins.bindString(11, mimeType)
            ins.bindLong(12, ref.durationSec.toLong())
            ins.bindLong(13, sizeBytes)
            ins.bindLong(14, width.toLong())
            ins.bindLong(15, height.toLong())
            ins.bindLong(16, ref.dateEpochSec)
            if (!useFts5) {
                ins.bindString(17, " ${indexTokensOf(newCaption, ref.channelTitle)} ") // LIKE 口径：首尾空格定界
            }
            newRowid = ins.executeInsert()
        } finally {
            runCatching { ins.close() }
        }
        if (newRowid == -1L) {
            // key 冲突（防御：writeLock 内不应发生——refs 与 items 全域互斥）：
            // 不重铸，组内 refs 保持指旧 rowid（旧行随频道删除后由 canonical 解析
            // 的 fuid 回退查兜底，§3.3 不变式 1 防御条款）
            Log.e(TAG, "v11 evict 提升重铸 key 冲突（不重铸，留证上报）：$fuid")
            return
        }
        if (useFts5) {
            val fs = stmtInsertFts
                ?: d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
                    .also { stmtInsertFts = it }
            fs.clearBindings()
            fs.bindLong(1, newRowid)
            fs.bindString(2, indexTokensOf(newCaption, ref.channelTitle))
            fs.executeInsert()
        }
        // parsed 两列原样随迁（内容侧派生物，与新 caption 同源同义）
        if (parsedColsReady && (parsedTitle != null || itemTagsJson != null)) {
            val upd = d.compileStatement("UPDATE items SET $PARSED_TITLE_COL=?, $ITEM_TAGS_COL=? WHERE rowid=?")
            try {
                upd.clearBindings()
                if (parsedTitle == null) upd.bindNull(1) else upd.bindString(1, parsedTitle)
                if (itemTagsJson == null) upd.bindNull(2) else upd.bindString(2, itemTagsJson)
                upd.bindLong(3, newRowid)
                upd.executeUpdateDelete()
            } finally {
                runCatching { upd.close() }
            }
        }
        // tag_items 重挂新 rowid（增集到提升源频道；原形取旧 canonical item_tags
        // JSON——保首见原形口径；旧频道侧减集由调用方毯式清理收口）
        val canonTags = parseTagsJson(itemTagsJson) ?: tagAttributionOfLocked(d, oldRowid).toList()
        if (canonTags.isNotEmpty()) {
            applyTagDeltaBatchLocked(
                d,
                listOf(TagDeltaRow(newRowid, ref.chatId, ref.dateEpochSec, emptySet(), canonTags)),
            )
        }
        // 全组 refs 改指新 canonical + 提升源 refs 行删除
        val fix = stmtRefsFixCanon
            ?: d.compileStatement("UPDATE $REPOSTS_TABLE SET canonRowid=? WHERE fuid=?")
                .also { stmtRefsFixCanon = it }
        fix.clearBindings()
        fix.bindLong(1, newRowid)
        fix.bindString(2, fuid)
        fix.executeUpdateDelete()
        val delRef = d.compileStatement("DELETE FROM $REPOSTS_TABLE WHERE key=?")
        try {
            delRef.clearBindings()
            delRef.bindString(1, newKey)
            delRef.executeUpdateDelete()
        } finally {
            runCatching { delRef.close() }
        }
    }

    /** 读爬取断点（v4 起含相位内游标与头部刷新时间戳；v5 起含采样标记；v6 起含档位；
     *  v10 起含死档标记；v12 起含自毁 TTL 秒数；v13 起含服务器最新帖秒）；
     *  无记录 = 全新未开始（档位缺省 LEGACY，与分档前行为一致） */
    private fun readMeta(d: SQLiteDatabase, chatId: Long): CrawlState =
        d.rawQuery(
            "SELECT cursor, exhausted, mediaPass, sweepCursor, lastHeadRefreshTs, sampled, tier, dead, ttlSec, lastMsgSec FROM meta WHERE chatId=?",
            arrayOf(chatId.toString()),
        ).use { c ->
            if (c.moveToFirst()) {
                CrawlState(
                    chatId, c.getLong(0), c.getInt(1) != 0, c.getInt(2), c.getLong(3), c.getLong(4),
                    c.getInt(5) != 0, c.getInt(6), c.getInt(7) != 0, c.getInt(8), c.getLong(9),
                )
            } else {
                CrawlState(chatId, 0L, false, -1, 0L, 0L, false, -1, false, 0, 0L)
            }
        }

    /** 频道已索引条数（chatId 索引命中的 COUNT）。
     *  R266 v11 D6 含转发口径：items+refs 双表相加（爬取上限/死档判定/进度展示
     *  全是「频道发布口径」——只发过转发的频道不是空频道；两臂各命中自身
     *  chatId 前缀索引，毫秒级） */
    private fun countItems(d: SQLiteDatabase, chatId: Long): Int {
        val items = d.rawQuery("SELECT COUNT(*) FROM items WHERE chatId=?", arrayOf(chatId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val refs = d.rawQuery("SELECT COUNT(*) FROM $REPOSTS_TABLE WHERE chatId=?", arrayOf(chatId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        return items + refs
    }

    /** 迁移判重：该频道 JSON 是否已导入完成（中断续导依据） */
    private fun isImported(d: SQLiteDatabase, chatId: Long): Boolean =
        d.rawQuery("SELECT imported FROM meta WHERE chatId=?", arrayOf(chatId.toString())).use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        }

    // ---------------- 一次性迁移：旧 per-channel JSON → SQLite ----------------

    /**
     * 旧索引 JSON 文件列表（含 .json.tmp 半截写文件）。
     * R266 修门房误杀（类问题修复，不修点）：旧口径「扩展名 .json/.json.tmp 即旧索引
     * 文件」会把 deepindex 目录里**一切非数字命名的状态文件**当垃圾删掉——R228/R236/
     * R238/R262b/R266 的批迁断点文件（caption_backfill_v8r2、tags_backfill_v9r2、
     * album_caption_backfill、merge_v11）每次冷启被删一次，全部批迁 Job 断点归零
     * 从头重扫（delta 幂等保正确性但白扫；merge_v11 游标归零=数天工程永不收敛）。
     * 新口径：**只认「chatId 数字.json[.tmp]」命名**（旧 per-channel 索引的唯一合法
     * 命名形态）；非数字命名的 .json=各 Job 私有断点，门房不碰。
     */
    private fun legacyJsonFiles(): List<File> =
        storeDir.listFiles { f ->
            if (!f.isFile) return@listFiles false
            val isJson = f.extension == "json" || f.name.endsWith(".json.tmp")
            if (!isJson) return@listFiles false
            // 双保险合流（回移植架构师裁决）：R266 数字命名硬门 + R262b 回填断点
            // 白名单（isBackfillStateFileName——前缀判定，master 4018e40 的
            // BackfillGatekeeperTest 锚定件；两闸任一放行都不删）
            if (isBackfillStateFileName(f.name)) return@listFiles false
            f.name.removeSuffix(".tmp").removeSuffix(".json").toLongOrNull() != null
        }?.sortedBy { it.name } ?: emptyList()

    /**
     * 首跑发现旧 JSON 即启动后台迁移（只点一次火）。旧倒排表落盘文件（inverted.bin）
     * 是死文件（机制已删除），顺手清掉。
     */
    private fun maybeStartMigration(d: SQLiteDatabase) {
        val files: List<File>
        synchronized(dbLock) {
            if (migrationStarted) return
            migrationStarted = true
            files = legacyJsonFiles()
        }
        runCatching { File(storeDir, "inverted.bin").delete() }
        runCatching { File(storeDir, "inverted.bin.tmp").delete() }
        if (files.isEmpty()) return
        ioScope.launch { migrateLegacy(d, files) }
    }

    /**
     * JSON→SQLite 流式导入：每频道一批事务（解析 ≤[TAIL_MAX_ITEMS] 条瞬时占用），
     * 提交成功才删对应 JSON（崩溃可续导：meta.imported 判重 + items INSERT OR IGNORE 幂等）。
     * 单频道失败不阻塞整体；解析失败的文件按损坏处理直接删（永远不可恢复，留着只会
     * 每次启动空转迁移）；事务失败的文件保留下次重试。全程 [migrationProgress] 外露进度。
     */
    private fun migrateLegacy(d: SQLiteDatabase, files: List<File>) {
        val total = files.size
        var done = 0
        _migrationProgress.value = 0 to total
        Log.d(TAG, "JSON→SQLite 一次性迁移开始：$total 个文件")
        try {
            for (f in files) {
                try {
                    when {
                        f.name.endsWith(".json.tmp") -> f.delete() // 半截写文件直接删
                        else -> {
                            val chatId = f.nameWithoutExtension.toLongOrNull()
                            when {
                                chatId == null -> f.delete() // 命名无法辨认：非索引文件，删
                                isImported(d, chatId) -> f.delete() // 上次中断前已导入完成：续导跳过
                                else -> {
                                    // 解析失败（损坏/IO）按不可恢复处理：删除（重爬可从断点/从头重建）
                                    val parsed = runCatching { readChannelItems(f) }
                                        .onFailure { Log.w(TAG, "迁移解析失败，按损坏文件处理 ${f.name}", it) }
                                        .getOrNull()
                                    if (parsed == null) {
                                        f.delete()
                                    } else {
                                        synchronized(writeLock) {
                                            d.beginTransaction()
                                            try {
                                                for (item in parsed.items.values) insertItemLocked(d, item, backfillOnConflict = false)
                                                upsertMetaImportedLocked(d, parsed.chatId, parsed.cursor, parsed.exhausted)
                                                d.setTransactionSuccessful()
                                            } finally {
                                                d.endTransaction()
                                            }
                                        }
                                        if (f.delete()) {
                                            Log.d(TAG, "迁移完成 ${f.name}：${parsed.items.size} 条")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // 事务级失败（SQLite 异常等）：保留文件下次启动重试，不阻塞整体迁移
                    Log.e(TAG, "迁移频道文件失败 ${f.name}（跳过，下次启动重试）", e)
                } finally {
                    done++
                    _migrationProgress.value = done to total
                }
            }
        } finally {
            _migrationProgress.value = null // 进度流结束置 null（UI 提示消失；异常中断也复位）
            Log.d(TAG, "JSON→SQLite 迁移完成：$done/$total")
        }
    }

    // ---------------- R202 时间维度钥匙：idx_items_date 后台单飞构建 ----------------

    /** idx_items_date 是否已存在（sqlite_master 探测，毫秒级；任意连接可查） */
    private fun hasDateIndex(d: SQLiteDatabase): Boolean =
        d.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(DATE_INDEX_NAME),
        ).use { it.moveToFirst() }

    /**
     * 时间索引就绪探测（带进程内缓存）：ready 流已置真直接短路；否则现探
     * sqlite_master（只读连接可探——WAL 快照在建库事务提交后才可见，天然保守），
     * 探到即顺手置真（openDb 未先跑的进程里，时间窗查询自身就能点亮 ready 流）。
     */
    private fun dateIndexReadyCached(d: SQLiteDatabase): Boolean {
        if (_dateIndexReady.value) return true
        val has = hasDateIndex(d)
        if (has) _dateIndexReady.value = true
        return has
    }

    /**
     * 时间索引后台单飞（openDb 尾部点火，幂等）：
     *  - 索引已在（旧库已建过/新库瞬间建完）→ 只点亮 ready 流，零成本；
     *  - 缺失 → ioScope 后台 IO 协程 [buildDateIndex]（writeLock 内 CREATE INDEX
     *    IF NOT EXISTS 单事务）。**绝不挡 openDb 调用方**（openDb 被 search/金矿/
     *    爬取/设置页多方触发——同步建索引 = 首开分钟级卡死，R183 金矿冷缓存 137s
     *    的教训即此类长查询挡用户路径）。
     *  - 建库期间爬取写事务在 writeLock 上排队（分钟级停顿，可接受——爬虫本就在
     *    IO 协程）；搜索/金矿/时间窗读走 WAL 与 openGoldDb 只读连接不受影响。
     *  - 与 clearAll/evictChannel 等写路径由 writeLock 串行互斥（建库中不会被清库
     *    打断；clearAll 后索引随表空置仍存在，无需重建）。
     */
    private fun maybeStartDateIndexBuild(d: SQLiteDatabase) {
        if (dateIndexBuildStarted) return
        if (hasDateIndex(d)) {
            _dateIndexReady.value = true
            return
        }
        synchronized(dbLock) {
            if (dateIndexBuildStarted) return
            dateIndexBuildStarted = true
        }
        ioScope.launch { buildDateIndex(d) }
    }

    /**
     * 建索引本体（后台 IO 协程）：前后 [itemCountFingerprint] 无损对账（R163 v6
     * 迁移验证范式同源——CREATE INDEX 理论不改行，但照规矩对账留证）+ 耗时打点
     * （真库实测数据入 r202_notes）。**对账两头都读在 writeLock 内**（before 若在
     * 锁外读，爬取页事务可能在读数与建索引之间穿插提交→行数只增不减却被误报
     * 「不一致」）。失败（磁盘满等）不上抛：本进程不重试，下次 openDb 探 idx
     * 仍缺失自动重启（IF NOT EXISTS 幂等，回滚无半成品）。
     */
    private fun buildDateIndex(d: SQLiteDatabase) {
        try {
            var before: Triple<Int, Long, Long>
            var after: Triple<Int, Long, Long>
            val elapsed: Long
            synchronized(writeLock) {
                before = itemCountFingerprint(d)
                val t0 = System.currentTimeMillis()
                d.execSQL(CREATE_ITEMS_DATE_INDEX_SQL)
                elapsed = System.currentTimeMillis() - t0
                after = itemCountFingerprint(d)
            }
            _dateIndexReady.value = true
            if (before == after) {
                Log.d(
                    TAG,
                    "v7 时间索引建成：耗时 ${elapsed}ms，items ${after.first} 条" +
                        "（rowid ${after.second}..${after.third}，迁移无损对账一致）",
                )
            } else {
                Log.e(
                    TAG,
                    "v7 时间索引建库前后对账不一致！before=$before after=$after" +
                        "（CREATE INDEX 不应改行，此日志出现=立即上报架构师）",
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "v7 时间索引后台构建失败（下次启动自动重试）", e)
        }
    }

    /** items 行集指纹：(COUNT, MIN(rowid), MAX(rowid)) 三值一致即行集未变（无损对账锚） */
    private fun itemCountFingerprint(d: SQLiteDatabase): Triple<Int, Long, Long> =
        d.rawQuery("SELECT COUNT(*), MIN(rowid), MAX(rowid) FROM items", null).use { c ->
            if (c.moveToFirst()) Triple(c.getInt(0), c.getLong(1), c.getLong(2)) else Triple(0, 0L, 0L)
        }

    // ---------------- R266 v11：idx_items_fuid 后台单飞构建 ----------------

    /** idx_items_fuid 是否已存在（sqlite_master 探测，毫秒级；任意连接可查） */
    private fun hasFuidIndex(d: SQLiteDatabase): Boolean =
        d.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(FUID_INDEX_NAME),
        ).use { it.moveToFirst() }

    /**
     * 指纹索引就绪探测（带进程内缓存；[dateIndexReadyCached] 同款结构）：
     * ready 流已置真直接短路；否则现探 sqlite_master，探到即顺手置真。
     */
    // R287：internal（IndexImportMerge 复用同一就绪闸——指纹索引未就绪绝不按 fuid 查库）
    internal fun fuidIndexReadyCached(d: SQLiteDatabase): Boolean {
        if (_fuidIndexReady.value) return true
        val has = hasFuidIndex(d)
        if (has) _fuidIndexReady.value = true
        return has
    }

    /**
     * v11 指纹索引后台单飞（openDb 尾部点火，幂等；[maybeStartDateIndexBuild] 同款纪律）：
     *  - 索引已在 → 只点亮 ready 流并链式点火 [maybeStartMergeBackfill]，零成本；
     *  - 缺失 → ioScope 后台 [buildFuidIndex]（writeLock 内 CREATE INDEX 单事务）。
     *  **绝不挡 openDb 调用方**（7.4M 行建索引=分钟级长事务，首开秒级红线）。
     */
    private fun maybeStartFuidIndexBuild(d: SQLiteDatabase) {
        if (fuidIndexBuildStarted) return
        if (hasFuidIndex(d)) {
            _fuidIndexReady.value = true
            maybeStartMergeBackfill(d)
            maybeStartImportMerge(d) // R287：链式点火（挂起的合并导入随索引就绪开跑）
            return
        }
        synchronized(dbLock) {
            if (fuidIndexBuildStarted) return
            fuidIndexBuildStarted = true
        }
        ioScope.launch { buildFuidIndex(d) }
    }

    /**
     * 建指纹索引本体（后台 IO 协程）：前后 [itemCountFingerprint] 无损对账 +
     * 耗时打点（buildDateIndex 同款范式）。建成后点亮 ready 流并链式点火合并批迁。
     * 失败不上抛：本进程不重试，下次 openDb 探 idx 仍缺失自动重启（幂等无半成品）。
     */
    private fun buildFuidIndex(d: SQLiteDatabase) {
        try {
            var before: Triple<Int, Long, Long>
            var after: Triple<Int, Long, Long>
            val elapsed: Long
            synchronized(writeLock) {
                before = itemCountFingerprint(d)
                val t0 = System.currentTimeMillis()
                d.execSQL(CREATE_ITEMS_FUID_INDEX_SQL)
                elapsed = System.currentTimeMillis() - t0
                after = itemCountFingerprint(d)
            }
            _fuidIndexReady.value = true
            if (before == after) {
                Log.d(
                    TAG,
                    "v11 指纹索引建成：耗时 ${elapsed}ms，items ${after.first} 条" +
                        "（rowid ${after.second}..${after.third}，无损对账一致）",
                )
            } else {
                Log.e(
                    TAG,
                    "v11 指纹索引建库前后对账不一致！before=$before after=$after" +
                        "（CREATE INDEX 不应改行，此日志出现=立即上报架构师）",
                )
            }
            maybeStartMergeBackfill(d)
            maybeStartImportMerge(d) // R287：链式点火（指纹索引建成回调同款）
        } catch (e: Throwable) {
            Log.e(TAG, "v11 指纹索引后台构建失败（下次启动自动重试）", e)
        }
    }

    // ---------------- R266 v11：存量转发合并批迁 Job（R228/R236 范式） ----------------

    /** 合并批迁断点状态（storeDir/merge_v11.json；崩溃重启续跑不重头——组级幂等） */
    private data class MergeState(val cursor: Long, val groups: Int, val merged: Int, val done: Boolean)

    private fun mergeStateFile(): File = File(storeDir, "merge_v11.json")

    private fun readMergeState(): MergeState? = runCatching {
        val f = mergeStateFile()
        if (!f.exists()) return null
        val txt = f.readText()
        MergeState(
            cursor = Regex("\"cursor\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L,
            groups = Regex("\"groups\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            merged = Regex("\"merged\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            done = txt.contains("\"done\":true"),
        )
    }.getOrNull()

    private fun writeMergeState(s: MergeState) {
        // 原子写（tmp+rename；tags_backfill_v9r2 同族——半途死亡留残串则正则失配
        // 游标归零重扫，组级幂等保正确性）
        runCatching {
            val tmp = File(storeDir, "merge_v11.json.tmp")
            tmp.writeText("{\"cursor\":${s.cursor},\"groups\":${s.groups},\"merged\":${s.merged},\"done\":${s.done}}")
            if (!tmp.renameTo(mergeStateFile())) {
                tmp.delete()
                mergeStateFile().writeText(
                    "{\"cursor\":${s.cursor},\"groups\":${s.groups},\"merged\":${s.merged},\"done\":${s.done}}",
                )
            }
        }
    }

    /** v11 合并批迁单飞点火（两处入口：openDb 尾 + 指纹索引建成回调；索引未就绪不点） */
    private fun maybeStartMergeBackfill(d: SQLiteDatabase) {
        if (mergeBackfillStarted) return
        if (!fuidIndexReadyCached(d)) return // 索引未就绪：等建成回调再点（绝不全表扫找组）
        synchronized(dbLock) {
            if (mergeBackfillStarted) return
            mergeBackfillStarted = true
        }
        ioScope.launch { runMergeBackfill(d) }
    }

    /**
     * 存量转发合并批迁（后台 IO 协程，storage-merge-design.md §4.2 组级事务算法）：
     *  - **游标**：items.rowid 倒序单调（最近条目先），状态文件每批落盘断点续迁；
     *  - **组发现**：批扫 500 行取 fileUniqueId → 指纹索引 COUNT 点查（≥2 才是组）；
     *    组本体经指纹索引全集取回——组成员无论散布多广都随首次遭遇**整组一锤定音**
     *    （组级事务内行级幂等：INSERT OR IGNORE+重挂 delta+DELETE 三重幂等，
     *    崩溃任意点重跑零效果）；巨型组（最大 1.47 万副本）不产生长事务外的
     *    特殊路径——组事务行级推进，整组一个事务（设计 §3.1 大组工程注）；
     *  - **错峰纪律（R208 同款）**：ColdStartQuietGate 起跑让路；有收编批 1s 间隔
     *    + SystemStormGate 高负载窗持有即等；零收编批（单件沙漠）200ms 短间隔；
     *  - **与爬虫共存**：canonical 先行选定冻结不换马（D1 附注）；迁移窗口期新爬
     *    转发经写入路径转学直接落 refs（canonical 可能挂临时行——本 Job 组事务
     *    收尾的 canonRowid 修正步一锤定音）；
     *  - **收尾对账**：items+refs 总数、组内唯一性全量校验（HAVING COUNT(*)>1
     *    残留=冲突闸跳过的组，宁不并不错并——如实记日志不追杀）。
     */
    private suspend fun runMergeBackfill(d: SQLiteDatabase) {
        try {
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) } // R208：冷启动让路+开机风暴窗
            val state = readMergeState()
            if (state?.done == true) {
                Log.d(TAG, "v11 转发合并：上次已完成（done 标记），跳过")
                return
            }
            val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (maxRowid <= 0L) {
                writeMergeState(MergeState(0L, 0, 0, true)) // 空库：直接完成
                return
            }
            var cursor = state?.cursor?.takeIf { it > 0L } ?: (maxRowid + 1)
            var groups = state?.groups ?: 0
            var merged = state?.merged ?: 0
            var batches = 0
            _mergeProgress.value = merged to groups
            Log.i(TAG, "v11 转发合并批迁启动：断点 rowid=$cursor（已收编 $merged 行 / $groups 组）")
            while (true) {
                // 批扫：只带定位两列（rowid+指纹）——组本体经指纹索引另行取全字段
                val batchFuids = LinkedHashSet<String>(MERGE_BATCH_ROWS)
                var minRowidSeen = Long.MAX_VALUE
                d.rawQuery(
                    "SELECT rowid, fileUniqueId FROM items WHERE rowid < ? AND fileUniqueId != '' " +
                        "ORDER BY rowid DESC LIMIT $MERGE_BATCH_ROWS",
                    arrayOf(cursor.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val rid = c.getLong(0)
                        if (rid < minRowidSeen) minRowidSeen = rid
                        val fuid = c.getString(1)
                        if (!fuid.isNullOrEmpty()) batchFuids += fuid
                    }
                }
                if (minRowidSeen == Long.MAX_VALUE) break // 游标耗尽
                var batchMerged = 0
                for (fuid in batchFuids) {
                    // 组规模点查（idx_items_fuid 覆盖计数，µs 级）：单件组零动作跳过
                    val cnt = d.rawQuery(
                        "SELECT COUNT(*) FROM items WHERE fileUniqueId=?",
                        arrayOf(fuid),
                    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    if (cnt < 2) continue
                    // 组级事务（R262b 批失败重试同口径：原地重试 ≤2 次，仍败跳组——
                    // 跳过的组由下一代断点文件兜回；组级幂等保重试安全）
                    var attempt = 0
                    while (true) {
                        attempt++
                        val groupResult = runCatching {
                            synchronized(writeLock) {
                                d.beginTransaction()
                                var m = -1
                                try {
                                    m = mergeGroupLocked(d, fuid)
                                    if (m >= 0) d.setTransactionSuccessful()
                                    m
                                } finally {
                                    d.endTransaction()
                                }
                            }
                        }
                        if (groupResult.isSuccess) {
                            val m = groupResult.getOrDefault(-1)
                            if (m > 0) {
                                groups++
                                batchMerged += m
                            }
                            break
                        }
                        val err = groupResult.exceptionOrNull()
                        if (attempt > BACKFILL_BATCH_MAX_RETRIES) {
                            Log.e(TAG, "v11 合并组重试 $BACKFILL_BATCH_MAX_RETRIES 次仍败，跳过本组：${err?.message}")
                            break
                        }
                        Log.w(TAG, "v11 合并组失败（原地重试 $attempt/$BACKFILL_BATCH_MAX_RETRIES）：${err?.message}")
                        delay(MERGE_BATCH_GAP_MS)
                    }
                }
                merged += batchMerged
                batches++
                cursor = minRowidSeen // 游标推进：本批读到的最低 rowid（单调不回扫）
                writeMergeState(MergeState(cursor, groups, merged, false))
                _mergeProgress.value = merged to groups
                if (batches % MERGE_LOG_EVERY == 0) {
                    Log.d(TAG, "v11 转发合并：已收编 $merged 行 / $groups 组（断点 rowid=$cursor）")
                }
                delay(if (batchMerged > 0) MERGE_BATCH_GAP_MS else MERGE_IDLE_GAP_MS)
                if (batchMerged > 0 && !SystemStormGate.clearNow()) {
                    SystemStormGate.awaitClear("v11 转发合并") // R208：高负载窗持有即等
                }
            }
            // 收尾总账（§4.4）：行数对账 + 组内唯一性全量校验（idx_items_fuid 索引序
            // GROUP BY，免排序一趟扫；残留>0=冲突闸跳过组，宁不并不错并如实记账）
            val itemsLeft = d.rawQuery("SELECT COUNT(*) FROM items", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            val refsTotal = d.rawQuery("SELECT COUNT(*) FROM $REPOSTS_TABLE", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
            val dupGroups = d.rawQuery(
                "SELECT COUNT(*) FROM (SELECT fileUniqueId FROM items WHERE fileUniqueId != '' " +
                    "GROUP BY fileUniqueId HAVING COUNT(*) > 1)",
                null,
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            writeMergeState(MergeState(0L, groups, merged, true))
            _mergeProgress.value = null
            Log.i(
                TAG,
                "v11 转发合并完成：收编 $merged 行 / $groups 组（$batches 批）；" +
                    "总账 items=$itemsLeft + refs=$refsTotal；组内唯一性残留 $dupGroups 组" +
                    "（>0=冲突防御闸跳过，宁不并不错并）",
            )
        } catch (e: Throwable) {
            // 断点已随每批落盘：崩溃/取消后下次启动续跑（不重头）
            Log.e(TAG, "v11 转发合并中断（断点已落盘，下次启动续跑）", e)
        }
    }

    /**
     * 组级收编事务本体（调用方已持 writeLock+事务；**幂等**——重跑零效果）：
     *  1. 组行全集按首发序取回（D1：(dateEpochSec, messageId) 最小=canonical，
     *     冻结不换马）；组规模 <2=已被收编/天然单件 → 零动作；
     *  2. R12 冲突防御闸：mediaType/sizeBytes 组内冲突 → 跳过收编+日志留证
     *     （fuid 同文件身份实测 750,560 组零冲突，此闸只防未来脏数据）；
     *  3. 逐非 canonical 行：refs 收编（caption 覆盖列=D2 忠实保留）→ tag_items
     *     **同事务先重挂** canonical（§6.3 rowid 对齐生死线）→ FTS5 形态删旧词位
     *     （现算同源配方）→ 删 items 行；
     *  4. canonRowid 修正步：UPDATE 全组 refs 指向最终 canonical（迁移窗口期写入
     *     路径转学挂的临时 canonical 在此一锤定音）。
     * @return 收编行数（0=组已收编过；<0=冲突闸跳过，调用方不提交事务）
     */
    private fun mergeGroupLocked(d: SQLiteDatabase, fuid: String): Int {
        val rows = ArrayList<MergeGroupRow>(8)
        d.rawQuery(
            "SELECT rowid, key, chatId, messageId, albumId, mediaType, durationSec, coverFileId, " +
                "channelTitle, dateEpochSec, caption, sizeBytes, $ITEM_TAGS_COL FROM items " +
                "WHERE fileUniqueId=? ORDER BY dateEpochSec ASC, messageId ASC",
            arrayOf(fuid),
        ).use { c ->
            while (c.moveToNext()) {
                rows += MergeGroupRow(
                    rowid = c.getLong(0), key = c.getString(1), chatId = c.getLong(2),
                    messageId = c.getLong(3), albumId = c.getLong(4), mediaType = c.getInt(5),
                    durationSec = c.getInt(6), coverFileId = c.getInt(7),
                    channelTitle = c.getString(8) ?: "", dateEpochSec = c.getLong(9),
                    caption = c.getString(10) ?: "", sizeBytes = c.getLong(11),
                    itemTagsJson = c.getString(12),
                )
            }
        }
        if (rows.size < 2) return 0 // 单件/已收编（幂等跳过）
        if (!RepostMergePolicy.groupMergeable(rows.map { it.mediaType to it.sizeBytes })) {
            Log.w(TAG, "v11 合并组冲突闸：组内 mediaType/sizeBytes 不一致，跳过收编（留待人工核查）")
            return -1
        }
        val canonical = rows[0] // 首发序首行=canonical（D1；rows 已按 (date,msgId) 升序）
        var mergedRows = 0
        for (r in rows.drop(1)) {
            // 1) refs 收编（INSERT OR IGNORE 幂等；覆盖列 NULL=与 canonical 同）
            insertRefLocked(
                d,
                RefRow(
                    key = r.key, chatId = r.chatId, messageId = r.messageId, albumId = r.albumId,
                    mediaType = r.mediaType, durationSec = r.durationSec, coverFileId = r.coverFileId,
                    channelTitle = r.channelTitle, dateEpochSec = r.dateEpochSec, fuid = fuid,
                    canonRowid = canonical.rowid,
                    captionOverride = RepostMergePolicy.captionOverride(r.caption, canonical.caption),
                ),
            )
            // 2) tag_items 同事务先重挂（rowid 对齐生死线）：r 侧全摘 + canonical 侧补挂
            //    （delta 幂等——重解析同值零写；D5 内容级挂首发频道）
            rehangTagsLocked(d, r, canonical)
            // 3) FTS5 形态：删 r 词位（contentless 删除需逐字节一致的旧词位串——
            //    [indexTokensOf] 同源现算，backfillConflictLocked/evictChannel 先例配方；
            //    R292：旧词位含已并入 OCR 文本，[ocrAppliedTextLocked] 同源取料）；
            //    LIKE 形态零动作（tokens 列随行删）
            if (useFts5) ftsDeleteLocked(d, r.rowid, indexTokensOf(r.caption, r.channelTitle, ocrAppliedTextLocked(d, r.rowid)))
            // 4) 删 items 行（rowid 点删）+ R292 item_ocr 随行删（canonical 另行重识别）
            d.compileStatement("DELETE FROM $ITEM_OCR_TABLE WHERE rowid=?").apply {
                bindLong(1, r.rowid)
            }.use { it.executeUpdateDelete() }
            val del = stmtItemsDeleteByRowid
                ?: d.compileStatement("DELETE FROM items WHERE rowid=?").also { stmtItemsDeleteByRowid = it }
            del.clearBindings()
            del.bindLong(1, r.rowid)
            del.executeUpdateDelete()
            mergedRows++
        }
        // 5) canonRowid 修正步（组处理到此一锤定音；idx_refs_fuid 命中）
        val fix = stmtRefsFixCanon
            ?: d.compileStatement("UPDATE $REPOSTS_TABLE SET canonRowid=? WHERE fuid=?")
                .also { stmtRefsFixCanon = it }
        fix.clearBindings()
        fix.bindLong(1, canonical.rowid)
        fix.bindString(2, fuid)
        fix.executeUpdateDelete()
        return mergedRows
    }

    /**
     * tag_items 同事务重挂（mergeGroupLocked 配套）：被收编行 r 的标签归属全摘，
     * canonical 侧补上缺挂的（D5 内容级账本——tags 只指 canonical rowid）。
     * 原形来源=各行 item_tags JSON（display 保首见原形；tag_items 里只有 fold 键）。
     */
    private fun rehangTagsLocked(d: SQLiteDatabase, r: MergeGroupRow, canonical: MergeGroupRow) {
        val rTags = tagAttributionOfLocked(d, r.rowid)
        if (rTags.isEmpty()) return
        val canonTags = tagAttributionOfLocked(d, canonical.rowid)
        val canonNew = (parseTagsJson(canonical.itemTagsJson) ?: canonTags.toList()) +
            (parseTagsJson(r.itemTagsJson) ?: rTags.toList())
        applyTagDeltaBatchLocked(
            d,
            listOf(
                TagDeltaRow(r.rowid, r.chatId, r.dateEpochSec, rTags, emptyList()),
                TagDeltaRow(canonical.rowid, canonical.chatId, canonical.dateEpochSec, canonTags, canonNew),
            ),
        )
    }

    /** FTS5 contentless 删除指令（现算词位串须与被删行逐字节一致——三处先例同源）。
     *  R287：internal（IndexImportMerge 合并改 caption 时同步删旧词位） */
    internal fun ftsDeleteLocked(d: SQLiteDatabase, rowid: Long, tokens: String) {
        val del = stmtFtsDelete
            ?: d.compileStatement("INSERT INTO fts(fts, rowid, tokens) VALUES('delete', ?, ?)")
                .also { stmtFtsDelete = it }
        del.clearBindings()
        del.bindLong(1, rowid)
        del.bindString(2, tokens)
        del.executeInsert()
    }

    /** v11 别名行载体（写入路径转学/批迁收编/evict 提升/R287 合并导入共用同一落库口） */
    internal class RefRow(
        val key: String,
        val chatId: Long,
        val messageId: Long,
        val albumId: Long,
        val mediaType: Int,
        val durationSec: Int,
        val coverFileId: Int,
        val channelTitle: String,
        val dateEpochSec: Long,
        val fuid: String,
        val canonRowid: Long,
        val captionOverride: String?,
    )

    /** 批迁组行载体（mergeGroupLocked 组全集；itemTagsJson=标签重挂的原形来源） */
    private class MergeGroupRow(
        val rowid: Long,
        val key: String,
        val chatId: Long,
        val messageId: Long,
        val albumId: Long,
        val mediaType: Int,
        val durationSec: Int,
        val coverFileId: Int,
        val channelTitle: String,
        val dateEpochSec: Long,
        val caption: String,
        val sizeBytes: Long,
        val itemTagsJson: String?,
    )

    /**
     * refs 落库（INSERT OR IGNORE 幂等——批迁重跑/写入路径转学竞态天然安全；
     * 调用方已持 writeLock+事务）。captionOverride=null → 覆盖列绑 NULL（与 canonical 同）。
     * R287：internal（IndexImportMerge 别名挂入同口）。
     */
    internal fun insertRefLocked(d: SQLiteDatabase, r: RefRow): Boolean {
        val stmt = stmtInsertRef
            ?: d.compileStatement(
                "INSERT OR IGNORE INTO $REPOSTS_TABLE(" +
                    "key, chatId, messageId, albumId, mediaType, durationSec, coverFileId, " +
                    "channelTitle, dateEpochSec, fuid, canonRowid, caption) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            ).also { stmtInsertRef = it }
        stmt.clearBindings()
        stmt.bindString(1, r.key)
        stmt.bindLong(2, r.chatId)
        stmt.bindLong(3, r.messageId)
        stmt.bindLong(4, r.albumId)
        stmt.bindLong(5, r.mediaType.toLong())
        stmt.bindLong(6, r.durationSec.toLong())
        stmt.bindLong(7, r.coverFileId.toLong())
        stmt.bindString(8, r.channelTitle)
        stmt.bindLong(9, r.dateEpochSec)
        stmt.bindString(10, r.fuid)
        stmt.bindLong(11, r.canonRowid)
        if (r.captionOverride == null) stmt.bindNull(12) else stmt.bindString(12, r.captionOverride)
        return stmt.executeInsert() != -1L
    }

    // ---------------- R228 caption 结构化：列就绪 / ingest 解析落位 / 存量回填 ----------------

    /**
     * v8 结构化列幂等加列（openDb 同步路径调用；列已在零成本直通）。
     * ALTER ADD COLUMN 带 NULL 缺省=元数据级瞬时操作（与行数无关，v2~v6 同款）；
     * 逐列探测-补列保证半途死亡重入不炸 duplicate column。成功置 [parsedColsReady]。
     */
    private fun ensureParsedCols(d: SQLiteDatabase) {
        if (!hasItemsColumn(d, PARSED_TITLE_COL)) {
            d.execSQL("ALTER TABLE items ADD COLUMN $PARSED_TITLE_COL TEXT")
        }
        if (!hasItemsColumn(d, ITEM_TAGS_COL)) {
            d.execSQL("ALTER TABLE items ADD COLUMN $ITEM_TAGS_COL TEXT")
        }
        parsedColsReady = true
    }

    /** items 表列存在探测（PRAGMA table_info；任意连接可查，毫秒级） */
    private fun hasItemsColumn(d: SQLiteDatabase, column: String): Boolean =
        d.rawQuery("PRAGMA table_info(items)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == column) { found = true; break }
            found
        }

    /**
     * R286：meta 表列幂等加列（v12/v13 起用，R293 已同族收口 v2~v6/v10——meta 迁移
     * 加列全族守卫），[ensureParsedCols] 同款逐列探测-补列——降级重入防雷：新库
     *（v13）被旧码（DB_VERSION=12）打开时 openDb 尾部会把 user_version 写回旧值，
     * 再进新包时裸 ALTER 必炸 duplicate column（openDb 失败契约=永久抛异常变砖，
     * R290 V1 排雷实锤）。列已在零成本直通。
     */
    private fun ensureMetaColumn(d: SQLiteDatabase, column: String, ddlType: String) {
        val has = d.rawQuery("PRAGMA table_info(meta)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == column) { found = true; break }
            found
        }
        if (!has) d.execSQL("ALTER TABLE meta ADD COLUMN $column $ddlType")
    }

    /**
     * R228 后台单飞骨架（R202 v7 同款纪律结构；迁移策略裁定见 openDb v8 分支）：
     * 列已在（同步路径已加/新库 CREATE 直出）→ 置就绪位零成本直通；万一缺失
     * （同步 ALTER 因磁盘错误失败后 openDb 重试前）→ ioScope 后台 writeLock 内
     * 幂等补列修复。绝不挡 openDb 调用方。
     */
    private fun maybeStartParsedColsNet(d: SQLiteDatabase) {
        if (parsedColsReady) return
        ioScope.launch {
            try {
                synchronized(writeLock) { ensureParsedCols(d) }
                Log.d(TAG, "R228 结构化列后台修复完成：parsed_title/item_tags 已就绪")
            } catch (e: Throwable) {
                Log.e(TAG, "R228 结构化列后台修复失败（下次 openDb 同步重试）", e)
            }
        }
    }

    /**
     * R228 ingest 解析落位（每页事务内、INSERT 之后调用；调用方已持 writeLock+事务）：
     *  - 单条（albumId=0）：**以库内行 caption 为准**解析（INSERT OR IGNORE 不追更
     *    caption——网络页 caption 可能与库行不同步，解析须与库内容一致，防
     *    parsed_title 描述新 caption 而行内 caption 是旧值的漂移）；
     *  - 相册（albumId≠0）：查库内成员全集（含跨页/跨相位先到成员——混合类型相册
     *    分视频/图片/音频三相位入库，成员分批到齐）→ 首个非空 caption=相册共享文案
     *    → CaptionParser 逐成员落位（第 N 段→成员 N）。**幂等重入**：成员到齐后
     *    下一批重解析覆盖旧值=跨页/跨相位自愈（partial 相册 4/6 段数≠成员数先回退，
     *    6/6 到齐后翻正）。
     * item_tags 是「已解析」权威标记（回退模式也落 tags，可为空数组）；
     * parsed_title 仅逐条模式落值（NULL=消费方回退 caption 首行=R219 旧口径）。
     *
     * R255 W11 批化：本函数从「逐行写」改为「先收集后批量写」——单条+相册两路
     * 解析结果汇成 [ParsedWriteRow] 列表，一次 [applyParsedRowsBatchLocked] 落库
     * （逐行 UPDATE + 旧归属一次 IN 批查 + tags/tag_items delta 聚合批化）。
     * 语义与逐行版逐项一致（见 applyTagDeltaBatchLocked 的等价性注释）；动机：
     * writeLock 是 8 车道页落库的串行点，逐行 tags delta（每行 1 点查+每标签
     * 3 语句≈每页 1000+ 语句）曾把锁持有拖到 ~0.9s/页——吞吐天花板被 ingest
     * 卡死而非网络（A 臂实测 1.15 页/s 全局、单车道 7s/页的分解实锤）。
     */
    private fun applyParsedCaptionsLocked(d: SQLiteDatabase, chatId: Long, items: List<VideoItem>) {
        if (!parsedColsReady || items.isEmpty()) return
        val albumIds = LinkedHashSet<Long>()
        val singleKeys = ArrayList<String>(items.size)
        for (it in items) {
            if (it.albumId == 0L) singleKeys += it.key else albumIds += it.albumId
        }
        val writes = ArrayList<ParsedWriteRow>(items.size)
        if (singleKeys.isNotEmpty()) {
            for (chunk in singleKeys.chunked(TIME_WINDOW_IN_CHUNK)) {
                // 先收集后写（同连接上不边读游标边执行 UPDATE——语句重启风险规避）。
                // R236：多读 rowid/chatId/dateEpochSec（tags 聚合 delta 的行标识与时间证据）
                val rows = ArrayList<SingleRow>(chunk.size)
                d.rawQuery(
                    "SELECT rowid, key, chatId, dateEpochSec, caption FROM items WHERE key IN (" +
                        chunk.joinToString(",") { "?" } + ")",
                    chunk.toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) {
                        rows += SingleRow(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4) ?: "")
                    }
                }
                // R255 W11：解析（纯 CPU）仍锁内（caption 须读未提交的本事务行），
                // 落库改批收集
                for (r in rows) collectSingleRow(r, writes)
            }
        }
        for (albumId in albumIds) collectAlbumRowsLocked(d, chatId, albumId, writes)
        if (writes.isNotEmpty()) applyParsedRowsBatchLocked(d, writes)
    }

    /** R236：单条解析原料行（rowid/chatId/dateSec 供 tags 聚合 delta） */
    private class SingleRow(val rowid: Long, val key: String, val chatId: Long, val dateSec: Long, val caption: String)

    /** R255 W11：解析结果待写行（批量落库的收集容器；chatId 随行——R228 批跨频道） */
    private class ParsedWriteRow(
        val rowid: Long,
        val key: String,
        val chatId: Long,
        val dateSec: Long,
        val title: String?,
        val tags: List<String>,
    )

    /** 单条解析收集（albumId=0；空 caption 行跳过——NULL=旧口径，完成判定排除） */
    private fun collectSingleRow(r: SingleRow, out: MutableList<ParsedWriteRow>) {
        if (r.caption.isBlank()) return
        val p = CaptionParser.parse(r.caption, CaptionAlbumContext(1, 0))
        out += ParsedWriteRow(r.rowid, r.key, r.chatId, r.dateSec, p.perItemTitle, p.storedTags())
    }

    /**
     * 相册解析收集：成员全集（messageId 升序=TG 相册发送序=成员序）逐成员解析共享文案
     * → [ParsedWriteRow]（批量落库）；R238 caption 列回填保持原位（相册成员少，
     * 逐行回填成本可忽略）。全组空 caption（载体未到）返回空——后续批/相位载体
     * 到达后自愈。R236：多读 rowid/dateEpochSec（tags 聚合 delta 的行标识与时间证据）。
     *
     * R238 追加（form 4/7 断点的落库修）：**caption 列共享回填**——相册空 caption
     * 成员（混合类型相册跨相位分批入库：视频先行、图片载体后到；或翻页边界劈开：
     * 载体在邻页）此前 caption 列留空，只回填了 parsed_title/item_tags；回退模式
     * （段数≠成员数，parsed_title 恒 NULL）的消费链 caption='' → 标题位只剩频道名
     * （DB 消费方：搜索/频道主页直出/回溯注入）。R219「形态A 共享」语义在此落库：
     * 只补空白、绝不覆盖非空（与 toVideoItem.withOverride 同口径）。
     * fts/LIKE 同事务同步（backfillConflictLocked 同款）：contentless 删除要旧词位串
     * ——旧行 caption 空，旧词位=channelTitle 单独口径（insertItemLocked 建行同一
     * 算式可复算）；LIKE 路径连同 items.tokens 列。
     */
    private fun collectAlbumRowsLocked(
        d: SQLiteDatabase,
        chatId: Long,
        albumId: Long,
        out: MutableList<ParsedWriteRow>,
    ): Int {
        class Member(
            val rowid: Long,
            val key: String,
            val messageId: Long,
            val dateSec: Long,
            val caption: String,
            val channelTitle: String,
        )
        val members = ArrayList<Member>(10)
        var shared: String? = null
        d.rawQuery(
            "SELECT rowid, key, messageId, dateEpochSec, caption, channelTitle FROM items WHERE chatId=? AND albumId=? ORDER BY messageId ASC",
            arrayOf(chatId.toString(), albumId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val cap = c.getString(4) ?: ""
                members += Member(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), cap, c.getString(5) ?: "")
                if (shared == null) cap.takeIf { it.isNotBlank() }?.let { shared = it }
            }
        }
        val cap = shared ?: return 0
        for ((idx, m) in members.withIndex()) {
            val r = CaptionParser.parse(cap, CaptionAlbumContext(members.size, idx))
            out += ParsedWriteRow(m.rowid, m.key, chatId, m.dateSec, r.perItemTitle, r.storedTags())
            // R238 caption 列回填：空成员补共享文案（幂等——回填后不再命中 caption=''）
            if (m.caption.isBlank()) {
                backfillAlbumCaptionRowLocked(d, m.rowid, cap, m.channelTitle)
            }
        }
        return members.size
    }

    /**
     * R238：相册成员 caption 列回填单行（调用方持 writeLock+事务）。
     * UPDATE items SET caption=? WHERE rowid=? + fts/LIKE 同步（口径与
     * [backfillConflictLocked] 的 caption 空→非空路径完全一致：旧词位现算删、
     * 新词位插；LIKE 路径改 tokens 列）。
     */
    private fun backfillAlbumCaptionRowLocked(d: SQLiteDatabase, rowid: Long, caption: String, channelTitle: String) {
        val stmt = stmtAlbumCapBackfill ?: d.compileStatement(
            if (useFts5) {
                "UPDATE items SET caption=? WHERE rowid=?"
            } else {
                "UPDATE items SET caption=?, tokens=? WHERE rowid=?"
            },
        ).also { stmtAlbumCapBackfill = it }
        // R292：已并入索引的 OCR 文本随行（配方同源——共享 caption 多数变高信息，
        // 配方内低信息闸自然把 OCR 后缀摘出新词位；旧词位含 OCR 侧由同一变量保证）
        val ocrText = ocrAppliedTextLocked(d, rowid)
        val newTokens = indexTokensOf(caption, channelTitle, ocrText)
        stmt.clearBindings()
        stmt.bindString(1, caption)
        if (!useFts5) {
            stmt.bindString(2, " $newTokens ") // LIKE 口径：词位串带首尾空格
            stmt.bindLong(3, rowid)
        } else {
            stmt.bindLong(2, rowid)
        }
        stmt.executeUpdateDelete()
        if (useFts5) {
            // contentless 删除要求旧词位串与被删行逐字节一致：旧行 caption 空 →
            // 词位=channelTitle 单独口径（insertItemLocked 建行同一算式）；
            // R292：若该行 OCR 已并入（旧 caption 空=低信息），旧词位含 OCR 后缀
            val oldTokens = indexTokensOf("", channelTitle, ocrText)
            val del = stmtFtsDelete ?: d.compileStatement(
                "INSERT INTO fts(fts, rowid, tokens) VALUES('delete', ?, ?)"
            ).also { stmtFtsDelete = it }
            del.clearBindings()
            del.bindLong(1, rowid)
            del.bindString(2, oldTokens)
            del.executeInsert()
            val ins = stmtInsertFts
                ?: d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
                    .also { stmtInsertFts = it }
            ins.clearBindings()
            ins.bindLong(1, rowid)
            ins.bindString(2, newTokens)
            ins.executeInsert()
        }
    }

    /**
     * 解析字段落库（UPDATE by key 主键；title null=回退模式只落 tags 标记）。
     *
     * R236：**tags 表聚合的唯一写点**——本函数是 item_tags 的四路汇点
     * （persistPage/persistLocalOnly ingest 页、R228 存量回填 Job、Lazy 兜底），
     * delta 聚合挂在此处即全路自动维护。旧归属从 tag_items 按 rowid 点查
     * （**刻意不用本行 item_tags JSON 当旧值**——v9 之前的存量行 JSON 非空而
     * tag_items 归属为空，用 JSON 当旧值会把这批行永久跳过聚合）；重解析同值
     * =零 delta 零写=幂等（计数不膨胀）。
     *
     * R255 W11：单行路径=批量核的委托（单一实现防语义漂移）；ingest 热路径走
     * [applyParsedRowsBatchLocked]（页级批化，见其注释）。
     */
    private fun writeParsedRowLocked(
        d: SQLiteDatabase,
        rowid: Long,
        chatId: Long,
        dateSec: Long,
        key: String,
        title: String?,
        tags: List<String>,
    ) {
        val stmt = stmtParsedUpsert
            ?: d.compileStatement("UPDATE items SET $PARSED_TITLE_COL=?, $ITEM_TAGS_COL=? WHERE key=?")
                .also { stmtParsedUpsert = it }
        stmt.clearBindings()
        if (title == null) stmt.bindNull(1) else stmt.bindString(1, title)
        stmt.bindString(2, tagsToJson(tags))
        stmt.bindString(3, key)
        stmt.executeUpdateDelete()
        // R236 delta 聚合（tags+tag_items）：旧归属点查（idx_tag_items_rowid，µs 级）
        val oldTags = tagAttributionOfLocked(d, rowid)
        applyTagDeltaLocked(d, rowid, chatId, dateSec, oldTags, tags)
    }

    /**
     * R255 W11：批级批量落库（调用方已持 writeLock+事务）——ingest 热路径与
     * R228 存量回填批共用（chatId 随行携带——回填批跨频道）：
     *  1. 逐行 UPDATE parsed_title/item_tags（每行一语句，stmtParsedUpsert 复用）；
     *  2. 旧归属**一次 IN 批查**（替代每行点查；idx_tag_items_rowid 命中）；
     *  3. tags/tag_items delta 走 [applyTagDeltaBatchLocked]（同批聚合批化）。
     * 语义与逐行 writeParsedRowLocked 完全一致（同一事务内最终状态相同）。
     * @return 净增集大小（delta 批核口径——回填 Job 进度记账同源）
     */
    private fun applyParsedRowsBatchLocked(d: SQLiteDatabase, rows: List<ParsedWriteRow>): Int {
        val stmt = stmtParsedUpsert
            ?: d.compileStatement("UPDATE items SET $PARSED_TITLE_COL=?, $ITEM_TAGS_COL=? WHERE key=?")
                .also { stmtParsedUpsert = it }
        // 1) 逐行解析字段落库
        for (r in rows) {
            stmt.clearBindings()
            if (r.title == null) stmt.bindNull(1) else stmt.bindString(1, r.title)
            stmt.bindString(2, tagsToJson(r.tags))
            stmt.bindString(3, r.key)
            stmt.executeUpdateDelete()
        }
        // 2) 旧归属一次批查（chunk ≤999 参数上限口径）
        val oldTags = HashMap<Long, HashSet<String>>(rows.size)
        for (chunk in rows.chunked(TIME_WINDOW_IN_CHUNK)) {
            d.rawQuery(
                "SELECT rowid_ref, tag FROM $TAG_ITEMS_TABLE WHERE rowid_ref IN (" +
                    chunk.joinToString(",") { "?" } + ")",
                chunk.map { it.rowid.toString() }.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) {
                    oldTags.getOrPut(c.getLong(0)) { HashSet<String>(4) } += c.getString(1)
                }
            }
        }
        // 3) delta 批核
        return applyTagDeltaBatchLocked(
            d,
            rows.map { TagDeltaRow(it.rowid, it.chatId, it.dateSec, oldTags[it.rowid] ?: emptySet(), it.tags) },
        )
    }

    /**
     * R236：该 items 行在 tag_items 里的现存归属（fold 键集合）。
     * 调用方须在 writeLock 内（读主连接；点查走 idx_tag_items_rowid）。
     * R287：internal（IndexImportMerge 冲突行标签并集的 delta 旧值点查同口）。
     */
    internal fun tagAttributionOfLocked(d: SQLiteDatabase, rowid: Long): Set<String> {
        val out = HashSet<String>(4)
        d.rawQuery(
            "SELECT tag FROM $TAG_ITEMS_TABLE WHERE rowid_ref=?",
            arrayOf(rowid.toString()),
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    /** R255 W11：delta 批核输入行（旧归属由调用方批查后喂入）。
     *  R287：internal（IndexImportMerge 合并增集同口） */
    internal class TagDeltaRow(
        val rowid: Long,
        val chatId: Long,
        val dateSec: Long,
        val oldTags: Set<String>,
        val newTags: List<String>,
    )

    /**
     * R236 tags 表 delta 聚合（调用方已持 writeLock+事务）——**单一实现**：
     * 单行 [applyTagDeltaLocked] 是本批核 rows=1 的委托（防两份语义漂移）。
     *  - **增集**（newTags fold ∉ oldTags）：tag_items INSERT OR IGNORE
     *    (tag,rowid,chatId) + tags INSERT OR IGNORE（首见=计数 1+display 原形+
     *    first/last 首值；IGNORE 命中→UPDATE itemCount+N 与 first/last 极值，
     *    CASE 守 NULL——dateSec<=0 视为无时间证据，极值不更新）；
     *  - **减集**（oldTags ∉ newTags）：tag_items DELETE + tags itemCount-M，
     *    ≤0 整行删。**已知近似**：残余行（计数>0）的 first/lastSeenSec 不重算
     *    （减集仅相册自愈/caption 空→非空回填的罕见路径；再增集会把极值拉回），
     *    tags 表是加速层不是账本权威，此口径注释声明；
     *  - 重解析同值（增=减=空）零写幂等——ingest 重见页/相册自愈反复重写不计数膨胀。
     *
     * R255 W11 批化等价性（与逐行版最终状态逐项相同，同事务内中间态不可见）：
     *  - 同 (chatId,fold) 的 N 个行增集 → 计数 +N（首见 INSERT 计 1+UPDATE 补
     *    N-1，或非首见 UPDATE +N）、first/last=增集行 dateSec 的 MIN/MAX；
     *  - tag_items 增行 INSERT OR IGNORE 多值批量（≤300 元组/语句——SQLITE
     *    MAX_VARIABLE_NUMBER=999 安全覆盖；同参数不同 arity 每批现编译）；
     *  - 减集 M 个行 → itemCount-M + prune 一次（逐行版逐次 prune 的最终态
     *    等价：deleted iff 最终计数≤0）；
     *  - net 口径 = 增集总数−减集总数（回填 Job 进度记账不变）。
     *
     * tags/tag 列=**fold 归一键**（SearchTextKit.fold——与 R228 searchHashtag 比对、
     * CaptionParser 提取去重同一把尺子）；display=首见原形（INSERT OR IGNORE 保首见）。
     *
     * @return 净增集大小（增集−减集；重解析同值=0）——回填 Job 的进度记账口径。
     * R287：internal（IndexImportMerge 新入行/冲突行并集的标签增集入账同口——
     * 合并台账不并源表，边从 item_tags 真值经本聚合器重建，计数天然重算）。
     */
    internal fun applyTagDeltaBatchLocked(d: SQLiteDatabase, rows: List<TagDeltaRow>): Int {
        if (rows.isEmpty()) return 0
        // 单趟折叠归一 + 增/减集划分（纯内存；dateSec 随行携带）
        class RowDelta(val rowid: Long, val chatId: Long, val dateSec: Long, val adds: Map<String, String>, val removes: Set<String>)
        val deltas = ArrayList<RowDelta>(rows.size)
        for (r in rows) {
            if (r.oldTags.isEmpty() && r.newTags.isEmpty()) continue
            // 新标签折叠归一（保首个原形——与 tagsToJson 存储序一致）
            val folded = LinkedHashMap<String, String>(r.newTags.size)
            for (t in r.newTags) {
                val f = SearchTextKit.fold(t)
                if (f.isNotEmpty() && !folded.containsKey(f)) folded[f] = t
            }
            val adds = LinkedHashMap<String, String>(folded.size)
            for ((fold, display) in folded) if (fold !in r.oldTags) adds[fold] = display
            val removes = HashSet<String>()
            for (fold in r.oldTags) if (!folded.containsKey(fold)) removes += fold
            if (adds.isEmpty() && removes.isEmpty()) continue
            deltas += RowDelta(r.rowid, r.chatId, r.dateSec, adds, removes)
        }
        if (deltas.isEmpty()) return 0
        // ---- 增集聚合：同 (chatId,fold) 汇总 N/min/max + 元组清单 ----
        class TagAgg(val display: String, var count: Int, var minDate: Long, var maxDate: Long, var hasDate: Boolean)
        val aggByChatFold = HashMap<Long, HashMap<String, TagAgg>>()
        val addTuples = ArrayList<Triple<String, Long, Long>>() // (fold, rowid, chatId)
        var net = 0
        for (rd in deltas) {
            for ((fold, display) in rd.adds) {
                net++
                addTuples += Triple(fold, rd.rowid, rd.chatId)
                val agg = aggByChatFold.getOrPut(rd.chatId) { HashMap() }
                    .getOrPut(fold) { TagAgg(display, 0, Long.MAX_VALUE, Long.MIN_VALUE, false) }
                agg.count++
                if (rd.dateSec > 0L) {
                    agg.hasDate = true
                    if (rd.dateSec < agg.minDate) agg.minDate = rd.dateSec
                    if (rd.dateSec > agg.maxDate) agg.maxDate = rd.dateSec
                }
            }
        }
        // ---- 增集发射 ----
        // 1) tag_items 多值批量 INSERT OR IGNORE（≤[TAG_BULK_TUPLES_PER_STMT] 元组/语句）
        // R260 实锤坏 SQL 修复：旧写法把 N 元组拼进同一对括号（2 元组=VALUES(?,?,?,?,?,?)
        // = SQLITE_ERROR「6 values for 3 columns」）——≥2 标签的批整页静默丢失（tags/
        // tag_items 双缺，一致性对账看不见的假绿）。SQL 形状收口到纯函数
        // [tagItemsBulkInsertSql]（机检锚：元组数=括号组数）。
        for (tupleChunk in addTuples.chunked(TAG_BULK_TUPLES_PER_STMT)) {
            val sql = tagItemsBulkInsertSql(tupleChunk.size)
            val bulk = d.compileStatement(sql)
            var bind = 1
            for ((fold, rowid, chatId) in tupleChunk) {
                bulk.bindString(bind, fold)
                bulk.bindLong(bind + 1, rowid)
                bulk.bindLong(bind + 2, chatId)
                bind += 3
            }
            bulk.executeInsert()
        }
        // 2) tags 首见 INSERT OR IGNORE + 计数/极值 UPDATE（每 distinct 2 语句）
        if (aggByChatFold.isNotEmpty()) {
            val tagIns = stmtTagsInsert
                ?: d.compileStatement(
                    "INSERT OR IGNORE INTO $TAGS_TABLE(chatId, tag, display, itemCount, firstSeenSec, lastSeenSec) " +
                        "VALUES(?,?,?,1,?,?)",
                ).also { stmtTagsInsert = it }
            val updN = stmtTagsDeltaN
                ?: d.compileStatement(
                    "UPDATE $TAGS_TABLE SET itemCount=itemCount+?," +
                        "firstSeenSec=CASE WHEN ? IS NULL THEN firstSeenSec " +
                        "WHEN firstSeenSec IS NULL THEN ? ELSE MIN(firstSeenSec, ?) END," +
                        "lastSeenSec=CASE WHEN ? IS NULL THEN lastSeenSec " +
                        "WHEN lastSeenSec IS NULL THEN ? ELSE MAX(lastSeenSec, ?) END " +
                        "WHERE chatId=? AND tag=?",
                ).also { stmtTagsDeltaN = it }
            for ((chatId, byFold) in aggByChatFold) {
                for ((fold, a) in byFold) {
                    tagIns.clearBindings()
                    tagIns.bindLong(1, chatId)
                    tagIns.bindString(2, fold)
                    tagIns.bindString(3, a.display)
                    if (a.hasDate) {
                        tagIns.bindLong(4, a.minDate)
                        tagIns.bindLong(5, a.maxDate)
                    } else {
                        tagIns.bindNull(4)
                        tagIns.bindNull(5)
                    }
                    val inserted = tagIns.executeInsert() != -1L
                    // 首见 INSERT 已计 1：残余 N-1（非首见则整 N）由 UPDATE 补齐——
                    // 最终计数与逐行版逐次 +1 的终态一致（同事务原子性兜底）
                    val remainder = if (inserted) a.count - 1 else a.count
                    if (remainder > 0 || a.hasDate) {
                        updN.clearBindings()
                        updN.bindLong(1, remainder.toLong())
                        if (a.hasDate) {
                            updN.bindLong(2, a.minDate)
                            updN.bindLong(3, a.minDate)
                            updN.bindLong(4, a.minDate)
                            updN.bindLong(5, a.maxDate)
                            updN.bindLong(6, a.maxDate)
                            updN.bindLong(7, a.maxDate)
                        } else {
                            repeat(6) { updN.bindNull(it + 2) }
                        }
                        updN.bindLong(8, chatId)
                        updN.bindString(9, fold)
                        updN.executeUpdateDelete()
                    }
                }
            }
        }
        // ---- 减集发射：逐行 DELETE tag_items（罕见路径）+ 每 distinct 一次 -M + prune ----
        var hasRemoves = false
        for (rd in deltas) if (rd.removes.isNotEmpty()) { hasRemoves = true; break }
        if (hasRemoves) {
            val del = stmtTagItemsDelete
                ?: d.compileStatement(
                    "DELETE FROM $TAG_ITEMS_TABLE WHERE rowid_ref=? AND tag=?",
                ).also { stmtTagItemsDelete = it }
            val decN = stmtTagsRemoveN
                ?: d.compileStatement(
                    "UPDATE $TAGS_TABLE SET itemCount=itemCount-? WHERE chatId=? AND tag=?",
                ).also { stmtTagsRemoveN = it }
            val prune = stmtTagsPrune
                ?: d.compileStatement(
                    "DELETE FROM $TAGS_TABLE WHERE chatId=? AND tag=? AND itemCount<=0",
                ).also { stmtTagsPrune = it }
            val removeAgg = HashMap<Long, HashMap<String, Int>>()
            for (rd in deltas) {
                for (fold in rd.removes) {
                    net--
                    del.clearBindings()
                    del.bindLong(1, rd.rowid)
                    del.bindString(2, fold)
                    del.executeUpdateDelete()
                    removeAgg.getOrPut(rd.chatId) { HashMap() }.merge(fold, 1, Int::plus)
                }
            }
            for ((chatId, byFold) in removeAgg) {
                for ((fold, m) in byFold) {
                    decN.clearBindings()
                    decN.bindLong(1, m.toLong())
                    decN.bindLong(2, chatId)
                    decN.bindString(3, fold)
                    decN.executeUpdateDelete()
                    prune.clearBindings()
                    prune.bindLong(1, chatId)
                    prune.bindString(2, fold)
                    prune.executeUpdateDelete()
                }
            }
        }
        return net
    }

    /**
     * R236 单行 delta（[applyTagDeltaBatchLocked] 的 rows=1 委托——R255 W11 起单一实现）。
     */
    private fun applyTagDeltaLocked(
        d: SQLiteDatabase,
        rowid: Long,
        chatId: Long,
        dateSec: Long,
        oldTags: Set<String>,
        newTags: List<String>,
    ): Int = applyTagDeltaBatchLocked(d, listOf(TagDeltaRow(rowid, chatId, dateSec, oldTags, newTags)))

    /** item_tags JSON 数组序列化（标签词身=[\p{L}\p{N}_]，无引号/逗号转义面，手拼安全） */
    private fun tagsToJson(tags: List<String>): String =
        if (tags.isEmpty()) "[]" else tags.joinToString(",", "[", "]") { "\"$it\"" }

    /** item_tags JSON 数组反序列化（null/残串→null=未解析存量；"[]"→空表=已解析无标签）。
     *  R287：internal（IndexImportMerge 解析来源行标签同口） */
    internal fun parseTagsJson(json: String?): List<String>? {
        if (json.isNullOrBlank()) return null
        val body = json.trim()
        if (!body.startsWith("[") || !body.endsWith("]")) return null
        return body.removePrefix("[").removeSuffix("]")
            .split(',')
            .mapNotNull { it.trim().removePrefix("\"").removeSuffix("\"").takeIf { it.isNotEmpty() } }
    }

    /**
     * R228 Lazy 兜底（用户追加需求：查询命中未回填条目即时解析+回写）——search()
     * 收尾调用：item_tags NULL（存量未回填）且 caption 非空的条目当场解析（相册
     * 上下文=按频道批量点查成员序，idx_items_album 命中），回写持久化（尽力而为，
     * 失败不丢本次内存结果）并富化返回值。封顶 [LAZY_PARSE_MAX] 防大结果集全量重解析。
     */
    private fun lazyEnrichParsed(d: SQLiteDatabase, items: List<VideoItem>): List<VideoItem> {
        if (!parsedColsReady) return items
        val pending = items.filter { it.itemTags == null && it.caption.isNotBlank() }.take(LAZY_PARSE_MAX)
        if (pending.isEmpty()) return items
        // 相册成员序表（chatId → albumId → messageId 升序）：一次点查/频道
        val albumMembers = HashMap<Long, Map<Long, List<Long>>>()
        for (chatId in pending.map { it.chatId }.distinct()) {
            val albumIds = pending.filter { it.chatId == chatId && it.albumId != 0L }
                .map { it.albumId }.distinct()
            if (albumIds.isEmpty()) continue
            val byAlbum = HashMap<Long, ArrayList<Long>>(albumIds.size)
            for (chunk in albumIds.chunked(TIME_WINDOW_IN_CHUNK)) {
                d.rawQuery(
                    "SELECT albumId, messageId FROM items WHERE chatId=? AND albumId IN (" +
                        chunk.joinToString(",") { "?" } + ") ORDER BY albumId, messageId ASC",
                    (listOf(chatId.toString()) + chunk.map { it.toString() }).toTypedArray(),
                ).use { c ->
                    while (c.moveToNext()) byAlbum.getOrPut(c.getLong(0)) { ArrayList<Long>(10) }.add(c.getLong(1))
                }
            }
            albumMembers[chatId] = byAlbum
        }
        val parsed = HashMap<String, Pair<String?, List<String>>>(pending.size)
        for (it in pending) {
            val ctx: CaptionAlbumContext? = if (it.albumId == 0L) {
                CaptionAlbumContext(1, 0)
            } else {
                val members = albumMembers[it.chatId]?.get(it.albumId)
                val idx = members?.indexOf(it.messageId) ?: -1
                if (members != null && idx >= 0) CaptionAlbumContext(members.size, idx) else null
            }
            val r = CaptionParser.parse(it.caption, ctx)
            parsed[it.key] = r.perItemTitle to r.storedTags()
        }
        // R236：tags 聚合 delta 需要 (rowid, chatId, dateSec)——按 key 批量点查
        // （key 主键索引命中；行标识与时间证据一份数据齐）
        val rowMeta = HashMap<String, LongArray>(parsed.size) // key → [rowid, chatId, dateSec]
        for (chunk in parsed.keys.chunked(TIME_WINDOW_IN_CHUNK)) {
            d.rawQuery(
                "SELECT key, rowid, chatId, dateEpochSec FROM items WHERE key IN (" +
                    chunk.joinToString(",") { "?" } + ")",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) rowMeta[c.getString(0)] = longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3))
            }
        }
        // 回写（runCatching：只读连接/锁竞争失败不丢内存结果；writeLock 串行化防爬取互踩）
        runCatching {
            synchronized(writeLock) {
                d.beginTransaction()
                try {
                    for ((key, p) in parsed) {
                        val meta = rowMeta[key] ?: continue // 行不在（已被 evict 等）：跳过
                        writeParsedRowLocked(d, meta[0], meta[1], meta[2], key, p.first, p.second)
                    }
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }
        }
        return items.map { item ->
            val p = parsed[item.key]
            if (p == null) item else item.copy(parsedTitle = p.first, itemTags = p.second)
        }
    }

    // ---------------- R228 存量回填 Job（用户追加需求：大几百万条存量也要更新） ----------------

    /**
     * 回填断点状态（storeDir/caption_backfill_v8r2.json；崩溃重启续跑不重头）。
     * R262b 起含 sweep（第几轮全量重扫——完成口径诚实化的轮次记账）。
     */
    private data class BackfillState(val phase: Int, val cursor: Long, val filled: Int, val sweep: Int, val done: Boolean)

    /**
     * R262b 断点文件换代（v8 → v8r2）：R255 W11 括号 bug（R261 已修 SQL）时代
     * 回填批整批回滚而游标照进 + 收尾无残留闸 done 假完成——旧文件的 done/cursor
     * 不可信（实锤：快照库未解析存量 441 万行，r262_notes.md）。换代=以修复后
     * 代码强制一次全量重扫；旧文件留作考古不读。
     */
    private fun backfillStateFile(): File = File(storeDir, "caption_backfill_v8r2.json")

    private fun readBackfillState(): BackfillState? = runCatching {
        val f = backfillStateFile()
        if (!f.exists()) return null
        val txt = f.readText()
        BackfillState(
            phase = Regex("\"phase\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            cursor = Regex("\"cursor\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L,
            filled = Regex("\"filled\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            sweep = Regex("\"sweep\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 1,
            done = txt.contains("\"done\":true"),
        )
    }.getOrNull()

    private fun writeBackfillState(s: BackfillState) {
        runCatching { backfillStateFile().writeText("{\"phase\":${s.phase},\"cursor\":${s.cursor},\"filled\":${s.filled},\"sweep\":${s.sweep},\"done\":${s.done}}") }
    }

    /** 回填单飞点火（openDb 尾部；与 R202 时间索引同款单飞闸模式） */
    private fun maybeStartLegacyBackfill(d: SQLiteDatabase) {
        if (!parsedColsReady) return // 列未就绪不点火（后台修复完成后本进程下次 openDb 再起）
        if (legacyBackfillStarted) return
        synchronized(dbLock) {
            if (legacyBackfillStarted) return
            legacyBackfillStarted = true
        }
        ioScope.launch { runLegacyCaptionBackfill(d) }
    }

    /**
     * 存量 caption 解析回填（后台 IO 协程）：
     *  - **错峰纪律（R208，用户口径「不抢爬虫/前台带宽」）**：起跑前
     *    ColdStartQuietGate（冷启动让路+开机风暴窗）持有则等；批间
     *    [BACKFILL_BATCH_GAP_MS] 秒级间隔 + SystemStormGate 高负载窗持有即等；
     *  - **热数据优先（用户口径「T0 档位优先」）**：相位 0 只回填 tier∈{T0,T1} 频道
     *    行（meta 表档位，R163；分档关时全 LEGACY→相位 0 空、直接相位 1），相位 1
     *    全量兜底；
     *  - **断点续传**：rowid 倒序单调游标（rowid<cursor DESC LIMIT 批）+ 状态文件
     *    落盘（每批一次）；崩溃重启从断点续跑（幂等：已回填行被 item_tags IS NULL
     *    过滤天然跳过）——「最近条目先」以入库序倒序近似（date 索引序扫描会随回填
     *    进度从顶部重复走已填行=二次方退化，rowid 游标单调无此病）；
     *  - 完成判定：相位 1 游标耗尽 + 复核 `item_tags IS NULL AND caption != ''` 计数=0
     *    （空 caption 行天然保持 NULL，不在完成口径内）→ done 落盘（后续启动 O(1) 跳过）。
     */
    private suspend fun runLegacyCaptionBackfill(d: SQLiteDatabase) {
        try {
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) } // R208：冷启动让路+开机风暴窗
            val state = readBackfillState()
            if (state?.done == true) {
                Log.d(TAG, "R228 存量回填：上次已完成（done 标记），跳过")
                return
            }
            val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (maxRowid <= 0L) {
                writeBackfillState(BackfillState(2, 0L, 0, 1, true)) // 空库：直接完成
                return
            }
            // 相位 0 档位热集（R163 meta.tier；0/1=T0/T1，-1=LEGACY 分档关）。
            // 热集为空（分档关默认态）直接跳相位 0——省一次全表扫描的纯空转
            //（每批都要读 500 行只为 continue，3.5M 行库≈2 小时白扫）
            val hotChats = HashSet<Long>()
            d.rawQuery("SELECT chatId FROM meta WHERE tier IN (0, 1)", null).use { c ->
                while (c.moveToNext()) hotChats += c.getLong(0)
            }
            var phase = if (hotChats.isEmpty()) 1 else (state?.phase ?: 0)
            var cursor = if (state != null && state.phase == phase && state.cursor > 0) state.cursor else maxRowid + 1
            var filled = state?.filled ?: 0
            var sweep = (state?.sweep ?: 1).coerceAtLeast(1)
            var batches = 0
            // R262b 外圈=全量重扫轮（完成口径诚实化：收尾残留>0 不置 done，轮次封顶内再扫）
            while (true) {
                while (phase <= 1) {
                    val rows = ArrayList<BackfillRow>(BACKFILL_BATCH_ROWS)
                    var minRowidSeen = Long.MAX_VALUE
                    d.rawQuery(
                        "SELECT rowid, key, chatId, messageId, albumId, dateEpochSec, caption, $ITEM_TAGS_COL FROM items " +
                            "WHERE rowid < ? ORDER BY rowid DESC LIMIT $BACKFILL_BATCH_ROWS",
                        arrayOf(cursor.toString()),
                    ).use { c ->
                        while (c.moveToNext()) {
                            val rowid = c.getLong(0)
                            if (rowid < minRowidSeen) minRowidSeen = rowid
                            if (c.getString(7) != null) continue // 已解析（含空数组=权威空）：跳过
                            val caption = c.getString(6) ?: ""
                            if (caption.isBlank()) continue // 空文案行保持 NULL（完成口径排除）
                            if (phase == 0 && c.getLong(2) !in hotChats) continue // 相位 0 只吃热频道
                            // R236：rowid/dateSec 随行携带（tags 聚合 delta 的行标识与时间证据）
                            rows += BackfillRow(rowid, c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getLong(5), caption)
                        }
                    }
                    if (minRowidSeen == Long.MAX_VALUE) {
                        // 本相位 rowid 走到头（无行可读）：转下一相位（minRowidSeen==1 的末批
                        // 正常处理，下一轮 rowid<1 空读自然收口）
                        phase++
                        cursor = maxRowid + 1
                        writeBackfillState(BackfillState(phase, cursor, filled, sweep, false))
                        continue
                    }
                    var wrote = 0
                    // R262b 批失败重试：旧版批失败游标照进=失败行永久跳过（W11 bug 期
                    // 441 万行未解析的放大器）。现在同批原地重试 ≤[BACKFILL_BATCH_MAX_RETRIES]
                    // 次（幂等重入安全），仍败才跳批——跳过行由收尾复核的下一轮重扫兜回。
                    var attempt = 0
                    while (true) {
                        attempt++
                        val batchResult = runCatching {
                            synchronized(writeLock) {
                                d.beginTransaction()
                                try {
                                    // R255 W11：批内收集→批量核（R228 批跨频道——chatId 随行携带；
                                    // 收集器/批量核与 ingest 热路径同一实现）
                                    val writes = ArrayList<ParsedWriteRow>(rows.size)
                                    val albumIds = LinkedHashSet<Pair<Long, Long>>()
                                    for (r in rows) {
                                        if (r.albumId == 0L) {
                                            collectSingleRow(SingleRow(r.rowid, r.key, r.chatId, r.dateSec, r.caption), writes)
                                        } else {
                                            albumIds += r.chatId to r.albumId // 相册组批量一次（成员全集解析）
                                        }
                                    }
                                    var singles = writes.size
                                    for ((chatId, albumId) in albumIds) singles += collectAlbumRowsLocked(d, chatId, albumId, writes)
                                    wrote = singles
                                    if (writes.isNotEmpty()) applyParsedRowsBatchLocked(d, writes)
                                    d.setTransactionSuccessful()
                                } finally {
                                    d.endTransaction()
                                }
                            }
                        }
                        if (batchResult.isSuccess) break
                        val err = batchResult.exceptionOrNull()
                        if (attempt > BACKFILL_BATCH_MAX_RETRIES) {
                            Log.e(TAG, "R228 存量回填批重试 $BACKFILL_BATCH_MAX_RETRIES 次仍败，跳过本批（收尾重扫兜回）：rowid≈$cursor ${err?.message}")
                            break
                        }
                        Log.w(TAG, "R228 存量回填批失败（原地重试 $attempt/$BACKFILL_BATCH_MAX_RETRIES）：${err?.message}")
                        delay(BACKFILL_BATCH_GAP_MS)
                    }
                    filled += wrote
                    batches++
                    cursor = minRowidSeen // 游标推进：本批读到的最低 rowid（含跳过行——单调不回扫）
                    writeBackfillState(BackfillState(phase, cursor, filled, sweep, false))
                    if (batches % BACKFILL_LOG_EVERY == 0) {
                        Log.d(TAG, "R228 存量回填：第${sweep}轮 相位$phase 已回填 $filled 条（断点 rowid=$cursor）")
                    }
                    delay(BACKFILL_BATCH_GAP_MS) // 秒级错峰：不抢爬虫/前台带宽（用户口径）
                    if (!SystemStormGate.clearNow()) {
                        SystemStormGate.awaitClear("R228 存量回填") // R208：高负载窗持有即等
                    }
                }
                // R262b 完成口径诚实化：item_tags IS NULL 且 caption 非空的残留>0 时不置
                // done（旧版无条件 done=假完成，W11 bug 期失败行再无重扫机会——「进度文件
                // 在动但 parsed_title 不涨」的收口根因）。轮次封顶防毒行活锁。
                // （只读连接不可见批内新写——主连接本协程独占此窗，复核在 writeLock 外读
                //  WAL 已提交态，语义准确）
                val remain = d.rawQuery(
                    "SELECT COUNT(*) FROM items WHERE $ITEM_TAGS_COL IS NULL AND caption != ''",
                    null,
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                if (remain == 0 || sweep >= BACKFILL_MAX_SWEEPS) {
                    writeBackfillState(BackfillState(2, 0L, filled, sweep, true))
                    Log.i(TAG, "R228 存量回填完成：共回填 $filled 条（$batches 批，第 $sweep 轮）；未解析残留 $remain 条（新爬未追上/竞态行，下轮自愈）")
                    break
                }
                sweep++
                phase = if (hotChats.isEmpty()) 1 else 0
                cursor = maxRowid + 1
                writeBackfillState(BackfillState(phase, cursor, filled, sweep, false))
                Log.i(TAG, "R228 存量回填：第 ${sweep - 1} 轮收尾残留 $remain 条未解析（前序批失败/竞态），开第 $sweep 轮重扫")
            }
        } catch (e: Throwable) {
            // 断点已随每批落盘：崩溃/取消后下次启动续跑（不重头）
            Log.e(TAG, "R228 存量回填中断（断点已落盘，下次启动续跑）", e)
        }
    }

    /** 回填批行（轻量：只带解析所需字段；R236 起含 rowid/dateSec 供 tags 聚合） */
    private class BackfillRow(
        val rowid: Long,
        val key: String,
        val chatId: Long,
        val messageId: Long,
        val albumId: Long,
        val dateSec: Long,
        val caption: String,
    )

    // ---------------- R292 封面 OCR 试点（T1a）：存量回填 Job + 词位并入 Job ----------------
    //
    // 设计唯一出处 content-understanding-eval.md（用户已终审 T1a）+ r292_notes.md 设计定稿。
    // 与 R228/R236/R285 回填族同范式：rowid 单调游标 + 断点 JSON 原子写 + 秒级错峰 +
    // ColdStartQuietGate/SystemStormGate + 与爬虫共存（ioScope 后台单飞）。
    // 与既有四例的两点差异（任务书口径）：
    //  1. **窗口闸**：只 WiFi（非计量）+充电跑——窗关 sleep 复探不烧资源（闸原料在
    //     调度侧，经 windowOpen 注入，本类不碰 Context）；
    //  2. **试点上限**：非放量态跑到 [COVER_OCR_PILOT_LIMIT] 条即收工写试点报告
    //     （全聚合零原文——§七扩令：识别文本只入库，日志/报告只出计数与长度分桶）。
    //
    // 点火不在 openDb（引擎/封面链/窗口闸都不在本类手里）——FeedOcrPart 接线后调
    // [maybeStartCoverOcrBackfill]； MockTdGateway 演示模式无真封面链，调度侧不点火。

    /** 封面 OCR 回填断点读写（tmp+rename 原子写，album_caption_backfill 同款先例） */
    private fun coverOcrBackfillStateFile(): File = File(storeDir, COVER_OCR_BACKFILL_STATE_FILE)

    private fun readCoverOcrBackfillState(): CoverOcrBackfillState? = runCatching {
        val f = coverOcrBackfillStateFile()
        if (!f.exists()) return null
        decodeCoverOcrBackfillState(f.readText())
    }.getOrNull()

    private fun writeCoverOcrBackfillState(s: CoverOcrBackfillState) {
        runCatching {
            val tmp = File(storeDir, "$COVER_OCR_BACKFILL_STATE_FILE.tmp")
            tmp.writeText(encodeCoverOcrBackfillState(s))
            tmp.renameTo(coverOcrBackfillStateFile())
        }
    }

    /**
     * 封面 OCR 回填点火（FeedOcrPart 接线后调用；单飞）。
     *
     * @param fetchCover 封面字节链（GatewayCover 现成链 loadCoverBytesByMessage 的
     *     薄封装；参数 chatId/messageId/coverFileId/key——落盘 coverFileId 跨进程失效
     *     由链内铸造验身自动转消息反解）。返回 null=本条拿不到封面（计 failed）
     * @param engine 端上 OCR 引擎（bundled ML Kit；全本地零外发）
     * @param windowOpen 窗口闸（调度侧现读：充电 ∧ 非计量网络；false=sleep 复探）
     * @param fullBackfill 放量闸（AppPrefs.ocrBackfillFull；false=试点 1 万条收工）
     */
    fun maybeStartCoverOcrBackfill(
        fetchCover: suspend (chatId: Long, messageId: Long, coverFileId: Int, key: String) -> ByteArray?,
        engine: OcrEngine,
        windowOpen: () -> Boolean,
        fullBackfill: () -> Boolean,
    ) {
        if (coverOcrBackfillStarted) return
        synchronized(dbLock) {
            if (coverOcrBackfillStarted) return
            coverOcrBackfillStarted = true
        }
        ioScope.launch {
            runCatching { openDb() }.getOrNull()?.let { d ->
                runCoverOcrBackfill(d, fetchCover, engine, windowOpen, fullBackfill)
            }
        }
    }

    /** 回填候选批行（只带取封面所需字段；词位并判的 caption 在写库批内现查） */
    private class CoverOcrRow(
        val rowid: Long,
        val key: String,
        val chatId: Long,
        val messageId: Long,
        val coverFileId: Int,
    )

    /**
     * 封面 OCR 回填本体（后台 IO 协程）：
     *  - 候选=视频（mediaType=0）∧ 有封面 fileId ∧ 低信息 caption（SQL 预筛
     *    length(trim(caption))<10，与 [OcrPolicy.LOW_INFO_CAPTION_MAX_LEN] 同口径；
     *    精确判定在词位配方侧纯函数）∧ 未识别过（NOT EXISTS item_ocr）；
     *  - 断点 rowid 倒序单调游标（v8r2 同族），每批原子落盘，崩溃续跑不重头；
     *  - 窗关（非 WiFi/未充电）sleep [COVER_OCR_WINDOW_RETRY_MS] 复探，不烧资源；
     *  - 失败（下载/解码/识别任一段）不写墓碑只计数——游标照样推进，不留活锁；
     *  - 识别文本只进 item_ocr（applied=0），**生产检索词位零变化**（试点期红线）；
     *  - 收工口径：游标到底（无候选）或（非放量 ∧ processed≥[COVER_OCR_PILOT_LIMIT]）
     *    → 写试点报告（全聚合）+ done 落盘。
     */
    private suspend fun runCoverOcrBackfill(
        d: SQLiteDatabase,
        fetchCover: suspend (chatId: Long, messageId: Long, coverFileId: Int, key: String) -> ByteArray?,
        engine: OcrEngine,
        windowOpen: () -> Boolean,
        fullBackfill: () -> Boolean,
    ) {
        val startedAtMs = System.currentTimeMillis()
        try {
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) } // 冷启动让路+开机风暴窗
            val state0 = readCoverOcrBackfillState()
            if (state0?.done == true && !fullBackfill()) {
                Log.d(TAG, "R292 封面 OCR 回填：上次已完成（done 标记，放量闸未开），跳过")
                return
            }
            // 放量闸复活：试点 done（到量收工）不挡放量——从断点续跑；到底 done（cursor=0）
            // 则从头补扫（NOT EXISTS 幂等跳过已识别行，顺带兜回本代失败行）
            val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (maxRowid <= 0L) {
                writeCoverOcrBackfillState(CoverOcrBackfillState(0L, 0, 0, 0, 0, true)) // 空库：直接完成
                return
            }
            var cursor = if (state0 != null && state0.cursor > 0) state0.cursor else maxRowid + 1
            var processed = state0?.processed ?: 0
            var nonempty = state0?.nonempty ?: 0
            var empty = state0?.empty ?: 0
            var failed = state0?.failed ?: 0
            // 试点报告长度分桶随断点持久化（跨重启续跑不丢；只记长度不记文本）
            val lenBuckets = parseOcrBuckets(state0?.buckets ?: "0,0,0,0,0")
            var batches = 0
            while (true) {
                // 窗口闸（任务书：只 WiFi+充电跑）——窗关 sleep 复探，不推进不烧资源
                if (!windowOpen()) {
                    delay(COVER_OCR_WINDOW_RETRY_MS)
                    continue
                }
                // 试点上限（放量闸开=Int.MAX 直通；任务书：先跑 1 万出实得率供终审）
                if (!fullBackfill() && processed >= COVER_OCR_PILOT_LIMIT) {
                    writeCoverOcrBackfillState(CoverOcrBackfillState(cursor, processed, nonempty, empty, failed, true, formatOcrBuckets(lenBuckets)))
                    writeCoverOcrPilotReport(engine.engineVer, processed, nonempty, empty, failed, lenBuckets, startedAtMs, fullBackfill())
                    Log.i(TAG, "R292 封面 OCR 试点到量收工：处理 $processed（非空 $nonempty/无字 $empty/失败 $failed），报告已落盘待终审放量")
                    return
                }
                val rows = ArrayList<CoverOcrRow>(COVER_OCR_BATCH_ROWS)
                var minRowidSeen = Long.MAX_VALUE
                d.rawQuery(
                    "SELECT rowid, key, chatId, messageId, coverFileId FROM items " +
                        "WHERE mediaType=0 AND coverFileId>=0 AND length(trim(caption))<${OcrPolicy.LOW_INFO_CAPTION_MAX_LEN} " +
                        "AND rowid < ? AND NOT EXISTS(SELECT 1 FROM $ITEM_OCR_TABLE o WHERE o.rowid=items.rowid) " +
                        "ORDER BY rowid DESC LIMIT $COVER_OCR_BATCH_ROWS",
                    arrayOf(cursor.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val rowid = c.getLong(0)
                        if (rowid < minRowidSeen) minRowidSeen = rowid
                        rows += CoverOcrRow(rowid, c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4))
                    }
                }
                if (minRowidSeen == Long.MAX_VALUE || rows.isEmpty()) {
                    // 游标到底：收工（done；报告落盘——含全量跑完与试点未跑满两形态）
                    writeCoverOcrBackfillState(CoverOcrBackfillState(0L, processed, nonempty, empty, failed, true, formatOcrBuckets(lenBuckets)))
                    writeCoverOcrPilotReport(engine.engineVer, processed, nonempty, empty, failed, lenBuckets, startedAtMs, fullBackfill())
                    Log.i(TAG, "R292 封面 OCR 回填到底收工：处理 $processed（非空 $nonempty/无字 $empty/失败 $failed），报告已落盘")
                    return
                }
                // 逐条：取封面（链内 15s 超时）→ 识别（引擎内 10s 超时）→ 收集结果。
                // 识别即弃：字节只在内存过引擎，本链不囤任何图片副本（存储闸口径）
                val results = ArrayList<Pair<CoverOcrRow, String>>(rows.size)
                for (r in rows) {
                    val bytes = runCatching { fetchCover(r.chatId, r.messageId, r.coverFileId, r.key) }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        failed++
                        continue
                    }
                    val text = runCatching { engine.recognize(bytes) }.getOrElse {
                        failed++
                        null
                    } ?: continue
                    if (text.isBlank()) empty++ else {
                        nonempty++
                        lenBuckets[ocrLenBucket(SearchTextKit.fold(text).length)]++
                    }
                    results += r to text
                }
                // 写库批（writeLock + 单事务；失败原地重试同族口径，耗尽跳批——
                // 跳过行无墓碑且游标单调不回扫=本代不再重试，计 failed 如实上报；
                // 引擎换代/状态文件换代重跑时兜回，试点口径接受）
                if (results.isNotEmpty()) {
                    var attempt = 0
                    while (true) {
                        attempt++
                        val batchResult = runCatching {
                            synchronized(writeLock) {
                                d.beginTransaction()
                                try {
                                    val ins = d.compileStatement(
                                        "INSERT OR REPLACE INTO $ITEM_OCR_TABLE(rowid, chatId, text, engineVer, applied, ts) " +
                                            "VALUES(?,?,?,?,0,?)",
                                    )
                                    val nowSec = System.currentTimeMillis() / 1000
                                    try {
                                        for ((r, text) in results) {
                                            ins.clearBindings()
                                            ins.bindLong(1, r.rowid)
                                            ins.bindLong(2, r.chatId)
                                            ins.bindString(3, text) // 脱敏红线：只入库，绝不进日志
                                            ins.bindLong(4, engine.engineVer.toLong())
                                            ins.bindLong(5, nowSec)
                                            ins.executeInsert()
                                        }
                                    } finally {
                                        runCatching { ins.close() }
                                    }
                                    d.setTransactionSuccessful()
                                } finally {
                                    d.endTransaction()
                                }
                            }
                        }
                        if (batchResult.isSuccess) break
                        if (attempt > BACKFILL_BATCH_MAX_RETRIES) {
                            Log.e(TAG, "R292 封面 OCR 写库批重试 $BACKFILL_BATCH_MAX_RETRIES 次仍败，跳过本批（本代不重试，计失败换代兜回）：rowid≈$cursor")
                            break
                        }
                        Log.w(TAG, "R292 封面 OCR 写库批失败（原地重试 $attempt/$BACKFILL_BATCH_MAX_RETRIES）")
                        delay(COVER_OCR_BATCH_GAP_MS)
                    }
                }
                processed += rows.size
                batches++
                cursor = minRowidSeen // 游标推进：本批读到的最低 rowid（单调不回扫）
                writeCoverOcrBackfillState(CoverOcrBackfillState(cursor, processed, nonempty, empty, failed, false, formatOcrBuckets(lenBuckets)))
                if (batches % COVER_OCR_LOG_EVERY == 0) {
                    // 脱敏：进度只出计数，识别文本绝不进日志
                    Log.d(TAG, "R292 封面 OCR 回填：已处理 $processed（非空 $nonempty/无字 $empty/失败 $failed，断点 rowid=$cursor）")
                }
                delay(COVER_OCR_BATCH_GAP_MS) // 秒级错峰：不抢爬虫/前台带宽
                if (!SystemStormGate.clearNow()) {
                    SystemStormGate.awaitClear("R292 封面 OCR 回填") // 高负载窗持有即等
                }
            }
        } catch (e: Throwable) {
            // 断点已随每批落盘：崩溃/取消后下次点火续跑（不重头）
            Log.e(TAG, "R292 封面 OCR 回填中断（断点已落盘，下次点火续跑）", e)
        }
    }

    /** 试点报告落盘（全聚合零原文；tmp+rename 原子写同族） */
    private fun writeCoverOcrPilotReport(
        engineVer: Int,
        processed: Int,
        nonempty: Int,
        empty: Int,
        failed: Int,
        lenBuckets: IntArray,
        startedAtMs: Long,
        fullBackfill: Boolean,
    ) {
        runCatching {
            val tmp = File(storeDir, "$COVER_OCR_PILOT_REPORT_FILE.tmp")
            tmp.writeText(
                encodeCoverOcrPilotReport(
                    engineVer, processed, nonempty, empty, failed, lenBuckets,
                    startedAtMs, System.currentTimeMillis(), fullBackfill,
                ),
            )
            tmp.renameTo(File(storeDir, COVER_OCR_PILOT_REPORT_FILE))
        }
    }

    // ---------------- R292 词位并入（apply）Job：试点终审放量后把 OCR 词位并进索引 ----------------

    /** apply 断点读写（rowid 升序游标；tmp+rename 原子写同族） */
    private fun coverOcrApplyStateFile(): File = File(storeDir, COVER_OCR_APPLY_STATE_FILE)

    private fun readCoverOcrApplyState(): CoverOcrApplyState? = runCatching {
        val f = coverOcrApplyStateFile()
        if (!f.exists()) return null
        decodeCoverOcrApplyState(f.readText())
    }.getOrNull()

    private fun writeCoverOcrApplyState(s: CoverOcrApplyState) {
        runCatching {
            val tmp = File(storeDir, "$COVER_OCR_APPLY_STATE_FILE.tmp")
            tmp.writeText(encodeCoverOcrApplyState(s))
            tmp.renameTo(coverOcrApplyStateFile())
        }
    }

    /**
     * 词位并入点火（FeedOcrPart 接线；[ocrSearchEnabled] 闸=AppPrefs.ocrSearchEnabled，
     * 试点期默认 false=不点火——生产检索零行为变化，用户终审试点报告后开闸）。
     * 闸开期间每轮 openDb 后由接线侧重调本口（单飞闸防重）。
     */
    fun maybeStartCoverOcrApply(ocrSearchEnabled: () -> Boolean) {
        if (!ocrSearchEnabled()) return
        if (coverOcrApplyStarted) return
        synchronized(dbLock) {
            if (coverOcrApplyStarted) return
            coverOcrApplyStarted = true
        }
        ioScope.launch {
            runCatching { openDb() }.getOrNull()?.let { d ->
                runCoverOcrApply(d, ocrSearchEnabled)
            }
        }
    }

    /**
     * 词位并入本体：把 item_ocr 里 applied=0 的行结算掉——
     *  - OCR 非空白 且 caption 低信息（[OcrPolicy.isLowInfoCaption] 唯一出处）→
     *    重建该行 fts 词位（旧词位=无 OCR 配方现算[applied=0 恒不并]，新词位=含 OCR
     *    配方；contentless 删除逐字节一致）+ LIKE 形态改 items.tokens 列；
     *  - 其余（OCR 空白/caption 高信息防稀释/items 行已不在）→ 只标记 applied=1；
     *  - 闸中途关闭（用户回拨）→ 停跑留断点，下次闸开续跑（部分并入态口径诚实：
     *    applied 标记=该行 fts 是否含 OCR 的唯一真值，删除重算四处同源现算）。
     */
    private suspend fun runCoverOcrApply(d: SQLiteDatabase, ocrSearchEnabled: () -> Boolean) {
        try {
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) }
            val state0 = readCoverOcrApplyState()
            var cursor = state0?.cursor ?: 0L
            var applied = state0?.applied ?: 0
            var skipped = state0?.skipped ?: 0
            var batches = 0
            // done 复活闸：done 只代表「上次扫描时无未结算行」——回填 Job 后续还会新产
            // applied=0 行（含比 cursor 小的 rowid——回填游标倒序走，行序与 apply 不相关），
            // 故选批无条件扫 applied=0（idx_item_ocr_applied 覆盖索引导航，不依赖游标下界），
            // done 行在还有未结算行时自动复活续跑
            if (state0?.done == true) {
                val pending = d.rawQuery(
                    "SELECT COUNT(*) FROM $ITEM_OCR_TABLE WHERE applied=0", null,
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                if (pending == 0) {
                    Log.d(TAG, "R292 词位并入：上次已完成且无未结算行，跳过")
                    return
                }
                Log.i(TAG, "R292 词位并入：done 态但回填新产 $pending 行未结算，复活续跑")
            }
            while (true) {
                if (!ocrSearchEnabled()) {
                    Log.i(TAG, "R292 词位并入：闸中途关闭，停跑留断点（已并入 $applied/跳过 $skipped）")
                    return
                }
                class ApplyRow(val rowid: Long, val text: String, val caption: String, val channelTitle: String)
                val rows = ArrayList<ApplyRow>(COVER_OCR_APPLY_BATCH_ROWS)
                var maxRowidSeen = 0L
                d.rawQuery(
                    "SELECT o.rowid, o.text, i.caption, i.channelTitle FROM $ITEM_OCR_TABLE o " +
                        "JOIN items i ON i.rowid=o.rowid " +
                        "WHERE o.applied=0 ORDER BY o.rowid LIMIT $COVER_OCR_APPLY_BATCH_ROWS",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val rowid = c.getLong(0)
                        if (rowid > maxRowidSeen) maxRowidSeen = rowid
                        rows += ApplyRow(rowid, c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "")
                    }
                }
                if (rows.isEmpty()) {
                    writeCoverOcrApplyState(CoverOcrApplyState(cursor, applied, skipped, true))
                    Log.i(TAG, "R292 词位并入完成：并入 $applied 条，跳过 $skipped 条（$batches 批）")
                    return
                }
                var attempt = 0
                // 批内计数先入局部量（重试回滚后重算——直接累加总数会随重试重复计数）
                var batchApplied = 0
                var batchSkipped = 0
                var batchOk = false
                while (true) {
                    attempt++
                    batchApplied = 0
                    batchSkipped = 0
                    val batchResult = runCatching {
                        synchronized(writeLock) {
                            d.beginTransaction()
                            try {
                                for (r in rows) {
                                    val merge = r.text.isNotBlank() && OcrPolicy.isLowInfoCaption(r.caption)
                                    if (merge) {
                                        // 旧词位=无 OCR 配方（applied=0 恒不并）；新词位=含 OCR
                                        val oldTokens = indexTokensOf(r.caption, r.channelTitle, null)
                                        val newTokens = indexTokensOf(r.caption, r.channelTitle, r.text)
                                        if (useFts5) {
                                            ftsDeleteLocked(d, r.rowid, oldTokens)
                                            val ins = stmtInsertFts
                                                ?: d.compileStatement("INSERT INTO fts(rowid, tokens) VALUES(?,?)")
                                                    .also { stmtInsertFts = it }
                                            ins.clearBindings()
                                            ins.bindLong(1, r.rowid)
                                            ins.bindString(2, newTokens)
                                            ins.executeInsert()
                                        } else {
                                            val upd = d.compileStatement("UPDATE items SET tokens=? WHERE rowid=?")
                                            try {
                                                upd.bindString(1, " $newTokens ") // LIKE 口径：首尾空格定界
                                                upd.bindLong(2, r.rowid)
                                                upd.executeUpdateDelete()
                                            } finally {
                                                runCatching { upd.close() }
                                            }
                                        }
                                        batchApplied++
                                    } else {
                                        batchSkipped++
                                    }
                                    val mark = d.compileStatement(
                                        "UPDATE $ITEM_OCR_TABLE SET applied=1 WHERE rowid=?",
                                    )
                                    try {
                                        mark.bindLong(1, r.rowid)
                                        mark.executeUpdateDelete()
                                    } finally {
                                        runCatching { mark.close() }
                                    }
                                }
                                d.setTransactionSuccessful()
                            } finally {
                                d.endTransaction()
                            }
                        }
                    }
                    if (batchResult.isSuccess) {
                        batchOk = true
                        break
                    }
                    if (attempt > BACKFILL_BATCH_MAX_RETRIES) {
                        // 批失败原地重试耗尽：本批行置 applied=2 失败墓碑（不再被选批命中，
                        // 防「无游标下界选批」形态下毒行活锁热转；换代/人工 UPDATE 回 0 可复捞）
                        runCatching {
                            synchronized(writeLock) {
                                d.beginTransaction()
                                try {
                                    for (r in rows) {
                                        d.compileStatement(
                                            "UPDATE $ITEM_OCR_TABLE SET applied=2 WHERE rowid=? AND applied=0",
                                        ).apply { bindLong(1, r.rowid) }.use { it.executeUpdateDelete() }
                                    }
                                    d.setTransactionSuccessful()
                                } finally {
                                    d.endTransaction()
                                }
                            }
                        }
                        Log.e(TAG, "R292 词位并入批重试 $BACKFILL_BATCH_MAX_RETRIES 次仍败，本批 ${rows.size} 行置失败墓碑（applied=2）：rowid≈$cursor")
                        break
                    }
                    Log.w(TAG, "R292 词位并入批失败（原地重试 $attempt/$BACKFILL_BATCH_MAX_RETRIES）")
                    delay(COVER_OCR_APPLY_GAP_MS)
                }
                if (batchOk) {
                    applied += batchApplied
                    skipped += batchSkipped
                    if (batchApplied > 0) ocrAppliedSeen = true // 精确路复核 OCR 补丁臂开闸
                }
                batches++
                cursor = maxRowidSeen
                writeCoverOcrApplyState(CoverOcrApplyState(cursor, applied, skipped, false))
                delay(COVER_OCR_APPLY_GAP_MS) // 秒级错峰：回填族同口径红线
                if (!SystemStormGate.clearNow()) {
                    SystemStormGate.awaitClear("R292 词位并入")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "R292 词位并入中断（断点已落盘，下次点火续跑）", e)
        }
    }

    // ---------------- R238 存量相册 caption 回填 Job（form 4/7 存量修复） ----------------

    /** 单飞闸（与 R228/R236 同款模式） */
    @Volatile
    private var albumCapBackfillStarted = false

    /** 断点状态（storeDir/album_caption_backfill.json；原子写，崩溃重启续跑不重头） */
    private data class AlbumCapBackfillState(val cursor: Long, val healed: Int, val done: Boolean)

    private fun albumCapBackfillStateFile(): File = File(storeDir, "album_caption_backfill.json")

    private fun readAlbumCapBackfillState(): AlbumCapBackfillState? = runCatching {
        val f = albumCapBackfillStateFile()
        if (!f.exists()) return null
        val txt = f.readText()
        AlbumCapBackfillState(
            cursor = Regex("\"cursor\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L,
            healed = Regex("\"healed\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            done = txt.contains("\"done\":true"),
        )
    }.getOrNull()

    private fun writeAlbumCapBackfillState(s: AlbumCapBackfillState) {
        // 原子写（tmp+rename，R236 同款——防进程被杀留半写残串）
        runCatching {
            val tmp = File(storeDir, "album_caption_backfill.json.tmp")
            tmp.writeText("{\"cursor\":${s.cursor},\"healed\":${s.healed},\"done\":${s.done}}")
            tmp.renameTo(albumCapBackfillStateFile())
        }
    }

    /** 回填单飞点火（openDb 尾部；列就绪前置：v8 列在才有得回填） */
    private fun maybeStartAlbumCaptionBackfill(d: SQLiteDatabase) {
        if (!parsedColsReady) return
        if (albumCapBackfillStarted) return
        synchronized(dbLock) {
            if (albumCapBackfillStarted) return
            albumCapBackfillStarted = true
        }
        ioScope.launch { runAlbumCaptionBackfill(d) }
    }

    /**
     * 存量相册成员 caption 列共享回填（后台 IO 协程，R228/R236 同款骨架）：
     *  - 驱动：rowid 倒序单调游标扫 `albumId≠0 AND caption=''` 的空成员行——
     *    （「最近条目先」入库序倒序近似，同 R228 口径；已回填行随 UPDATE 离开
     *    谓词，游标单调不回扫）；
     *  - 治疗单元：相册组（同 chatId+albumId）→ [collectAlbumRowsLocked]（读组内
     *    成员全集 + 共享 caption：回填 caption 列 + 刷新 parsed_title/item_tags，
     *    R238 前者为本 Job 主目标）；组内无 caption（载体永不曾入库）→ 该组跳过
     *    （空行留着，等载体经 ingest 自愈——诚实空，不冒充）；
     *  - 错峰纪律（R208）：ColdStartQuietGate 起跑等 + 批间 [BACKFILL_BATCH_GAP_MS]
     *    秒级间隔 + SystemStormGate 高负载窗持有即等；
     *  - 断点续传：每批落盘（原子写）；完成 done 标记 O(1) 跳过。
     *  幂等性：collectAlbumRowsLocked 重解析同值零 delta；caption 回填后行离开扫描谓词。
     */
    private suspend fun runAlbumCaptionBackfill(d: SQLiteDatabase) {
        try {
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) } // R208：冷启动让路+开机风暴窗
            val state = readAlbumCapBackfillState()
            if (state?.done == true) {
                Log.d(TAG, "R238 相册 caption 回填：上次已完成（done 标记），跳过")
                return
            }
            val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (maxRowid <= 0L) {
                writeAlbumCapBackfillState(AlbumCapBackfillState(0L, 0, true)) // 空库：直接完成
                return
            }
            var cursor = if (state != null && state.cursor > 0) state.cursor else maxRowid + 1
            var healed = state?.healed ?: 0
            var batches = 0
            while (true) {
                // 批驱动行：空 caption 的相册成员（只带分组键；治疗在 collectAlbumRowsLocked）
                val groups = LinkedHashSet<Pair<Long, Long>>()
                var minRowidSeen = Long.MAX_VALUE
                d.rawQuery(
                    "SELECT rowid, chatId, albumId FROM items " +
                        "WHERE albumId != 0 AND caption = '' AND rowid < ? ORDER BY rowid DESC LIMIT $BACKFILL_BATCH_ROWS",
                    arrayOf(cursor.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val rowid = c.getLong(0)
                        if (rowid < minRowidSeen) minRowidSeen = rowid
                        groups += c.getLong(1) to c.getLong(2)
                    }
                }
                if (minRowidSeen == Long.MAX_VALUE) break // 游标走完：空读收口
                var wrote = 0
                // 批级防御：单批异常只丢本批不杀整 Job（断点未推进该批重扫，幂等）
                runCatching {
                    synchronized(writeLock) {
                        d.beginTransaction()
                        try {
                            // R255 W11：组收集→批量核（同一实现；成员 caption 回填仍在
                            // 收集器内原位执行——healed 口径=成员数不变）
                            val writes = ArrayList<ParsedWriteRow>(groups.size * 4)
                            for ((chatId, albumId) in groups) wrote += collectAlbumRowsLocked(d, chatId, albumId, writes)
                            if (writes.isNotEmpty()) applyParsedRowsBatchLocked(d, writes)
                            d.setTransactionSuccessful()
                        } finally {
                            d.endTransaction()
                        }
                    }
                }.onFailure { Log.w(TAG, "R238 相册 caption 回填批失败（跳过本批继续）：${it.message}") }
                healed += wrote
                batches++
                cursor = minRowidSeen // 游标推进：本批读到的最低 rowid（含跳过组，单调不回扫）
                writeAlbumCapBackfillState(AlbumCapBackfillState(cursor, healed, false))
                if (batches % BACKFILL_LOG_EVERY == 0) {
                    Log.d(TAG, "R238 相册 caption 回填：已处理 $healed 条（断点 rowid=$cursor）")
                }
                delay(BACKFILL_BATCH_GAP_MS) // 秒级错峰：不抢爬虫/前台带宽（用户口径）
                if (!SystemStormGate.clearNow()) {
                    SystemStormGate.awaitClear("R238 相册 caption 回填") // R208：高负载窗持有即等
                }
            }
            writeAlbumCapBackfillState(AlbumCapBackfillState(cursor, healed, true))
            Log.i(TAG, "R238 相册 caption 回填完成：共处理 $healed 条（$batches 批）；载体缺失组留空待 ingest 自愈")
        } catch (e: Throwable) {
            // 断点已随每批落盘：崩溃/取消后下次启动续跑（不重头）
            Log.e(TAG, "R238 相册 caption 回填中断（断点已落盘，下次启动续跑）", e)
        }
    }

    // ---------------- R285 存量链式回填 Job（链②库内相册共享 + 链④库内邻近批注） ----------------

    /**
     * 链式回填断点状态（storeDir/chain_caption_backfill.json；原子写，崩溃重启续跑）。
     * 游标=chatId（升序推进——链④需按频道 messageId 序扫，rowid 游标不适用）；
     * healed2/healed4=链②/链④各自救回行数（记账口径=计划行数，幂等重写不膨胀——
     * 已救行 caption 非空天然离开候选域）。
     */
    private data class ChainBackfillState(val cursor: Long, val healed2: Int, val healed4: Int, val done: Boolean)

    private fun chainBackfillStateFile(): File = File(storeDir, "chain_caption_backfill.json")

    private fun readChainBackfillState(): ChainBackfillState? = runCatching {
        val f = chainBackfillStateFile()
        if (!f.exists()) return null
        val txt = f.readText()
        ChainBackfillState(
            cursor = Regex("\"cursor\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L,
            healed2 = Regex("\"healed2\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            healed4 = Regex("\"healed4\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            done = txt.contains("\"done\":true"),
        )
    }.getOrNull()

    private fun writeChainBackfillState(s: ChainBackfillState) {
        // 原子写（tmp+rename，tags_backfill_v9r2 同族——半途死亡留残串则正则失配游标
        // 归零重扫，幂等保正确性；R262b 门房白名单已含本文件名前缀）
        runCatching {
            val tmp = File(storeDir, "chain_caption_backfill.json.tmp")
            tmp.writeText("{\"cursor\":${s.cursor},\"healed2\":${s.healed2},\"healed4\":${s.healed4},\"done\":${s.done}}")
            if (!tmp.renameTo(chainBackfillStateFile())) {
                tmp.delete()
                chainBackfillStateFile().writeText(
                    "{\"cursor\":${s.cursor},\"healed2\":${s.healed2},\"healed4\":${s.healed4},\"done\":${s.done}}",
                )
            }
        }
    }

    /** 链式回填单飞点火（openDb 尾部；R228/R236/R238 同款模式；列未就绪不点火） */
    private fun maybeStartChainBackfill(d: SQLiteDatabase) {
        if (!parsedColsReady) return
        if (chainBackfillStarted) return
        synchronized(dbLock) {
            if (chainBackfillStarted) return
            chainBackfillStarted = true
        }
        ioScope.launch { runChainBackfill(d) }
    }

    /**
     * 存量空 caption 行的链式重算（后台 IO 协程）——用户实锤：「相册多资源+统一文字
     * 批注」形态的空 caption 成员，存量里永远空（新内容 toVideoItem 的 resolveTitle
     * 链②③④裁决结果直接进 caption 列，存量入库时没有这一步；R262b 的 v8r2/v9r2
     * 回填只重解析原文=空产空）。本 Job 在库内按链语义重算：
     *  - **链② 库内相册共享**：同 chatId+albumId 组内任何成员有 caption → 共享给
     *    空成员（全在 items 表内，零网络；治疗=collectAlbumRowsLocked 全体复用——
     *    caption 列回填+逐成员解析+tags delta 一次到位，与 R238 Job 同域幂等重叠）；
     *  - **链④ 库内邻近批注**：按 messageId 新→旧扫（相邻判定在序号域——
     *    [dev.tgfeed.core.td.TD_MSG_ID_SEQ_SHIFT] 唯一持有 2^20 位移），带文散媒体行（剥 #标签后仍有字母数字——CaptionParser 同一把尺子）
     *    代任文字帖批注源，批注给紧随的连续空 caption 媒体（判定核=
     *    [computeAdjacentNotes] 严格相邻模式——与网关 buildAdjacentNotes 同一实现，
     *    ANNOTATE_MAX_MEDIA 上限/ping 守卫/相册隔断全同源；严格闸=id 缺口即断，
     *    库内无文字帖行，缺口里可能正是真批注帖，宁漏勿错）；
     *  - **写回**：caption 列回填复用 [backfillAlbumCaptionRowLocked]（fts/LIKE
     *    同步）；parsed_title/item_tags 经 CaptionParser（相册成员带组上下文）→
     *    [applyParsedRowsBatchLocked] delta 幂等通道 → tags/tag_items 同步补；
     *  - **错峰纪律（R208 同款）**：ColdStartQuietGate 起跑让路；writeLock 短事务
     *    （读在锁外、写在锁内——与 v8r2/v9r2/merge_v11 三 Job 在 writeLock 上自然
     *    串行，互不抢锁）；有写频道 1s 间隔+SystemStormGate 持有即等，零写频道
     *    200ms 短间隔（扫过无治疗沙漠不空转）；
     *  - **断点续传**：chatId 升序游标每频道落盘；完成 done 标记 O(1) 跳过。
     *    完成口径说明：本 Job 只负责「库内证据可救」的行；全空组且无邻近批注源
     *    的行保持诚实空（链⑤=频道名回落——批注帖本体不入库，库内永远无证据）。
     */
    private suspend fun runChainBackfill(d: SQLiteDatabase) {
        try {
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) } // R208：冷启动让路+开机风暴窗
            val state = readChainBackfillState()
            if (state?.done == true) {
                Log.d(TAG, "R285 链式回填：上次已完成（done 标记），跳过")
                return
            }
            val startCursor = state?.cursor ?: Long.MIN_VALUE
            val chatIds = ArrayList<Long>(640)
            d.rawQuery(
                "SELECT DISTINCT chatId FROM items WHERE chatId > ? ORDER BY chatId ASC",
                arrayOf(startCursor.toString()),
            ).use { c -> while (c.moveToNext()) chatIds += c.getLong(0) }
            if (chatIds.isEmpty()) {
                writeChainBackfillState(ChainBackfillState(Long.MAX_VALUE, state?.healed2 ?: 0, state?.healed4 ?: 0, true))
                Log.i(TAG, "R285 链式回填完成：无可扫频道（空库或游标已尽）")
                return
            }
            var healed2 = state?.healed2 ?: 0
            var healed4 = state?.healed4 ?: 0
            var chats = 0
            Log.i(TAG, "R285 链式回填启动：待扫 ${chatIds.size} 频道（断点 chatId=$startCursor，已救 ②$healed2/④$healed4）")
            for (chatId in chatIds) {
                // 读在锁外（本协程此窗独占主连接读，WAL 已提交态）：单频道全量行
                // 新→旧序（idx_items_chat_msg 命中；最大频道实测 5.3 万行=瞬时）
                val planRows = ArrayList<ChainPlanRow>(4096)
                d.rawQuery(
                    "SELECT rowid, messageId, albumId, caption FROM items WHERE chatId=? ORDER BY messageId DESC",
                    arrayOf(chatId.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        planRows += ChainPlanRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3) ?: "")
                    }
                }
                val plan = planChainHealing(planRows)
                if (plan.isNotEmpty()) {
                    // 批失败重试（R262b 同口径）：原地重试 ≤BACKFILL_BATCH_MAX_RETRIES
                    // 次（幂等重入安全）；仍败跳过本频道且游标照样推进（毒频道防活锁——
                    // 与 R262b「跳批由收尾重扫兜回」不同：本 Job 无重扫轮，失败频道
                    // 如实记日志，遗留行由下一代断点文件兜回）
                    var ok = false
                    var attempt = 0
                    while (true) {
                        attempt++
                        val chatResult = runCatching { applyChainHealingPlan(d, chatId, plan) }
                        if (chatResult.isSuccess) {
                            ok = true
                            break
                        }
                        val err = chatResult.exceptionOrNull()
                        if (attempt > BACKFILL_BATCH_MAX_RETRIES) {
                            Log.e(TAG, "R285 链式回填频道重试 $BACKFILL_BATCH_MAX_RETRIES 次仍败，跳过本频道（遗留行由下一代断点文件兜回）：chatId尾号=${chatId.toString().takeLast(4)} ${err?.message}")
                            break
                        }
                        Log.w(TAG, "R285 链式回填频道失败（原地重试 $attempt/$BACKFILL_BATCH_MAX_RETRIES）：${err?.message}")
                        delay(BACKFILL_BATCH_GAP_MS)
                    }
                    if (ok) {
                        healed2 += plan.count { it.viaChain2 }
                        healed4 += plan.count { !it.viaChain2 }
                    }
                }
                chats++
                writeChainBackfillState(ChainBackfillState(chatId, healed2, healed4, false))
                if (chats % CHAIN_BACKFILL_LOG_EVERY == 0) {
                    Log.d(TAG, "R285 链式回填：已扫 $chats/${chatIds.size} 频道（②救 $healed2 / ④救 $healed4）")
                }
                delay(if (plan.isNotEmpty()) BACKFILL_BATCH_GAP_MS else TAGS_BACKFILL_IDLE_GAP_MS)
                if (plan.isNotEmpty() && !SystemStormGate.clearNow()) {
                    SystemStormGate.awaitClear("R285 链式回填") // R208：高负载窗持有即等（只对有 IO 的批）
                }
            }
            writeChainBackfillState(ChainBackfillState(Long.MAX_VALUE, healed2, healed4, true))
            Log.i(TAG, "R285 链式回填完成：$chats 频道，②救 $healed2 行 / ④救 $healed4 行；其余空行=库内无证据（诚实空）")
        } catch (e: Throwable) {
            // 断点已随每频道落盘：崩溃/取消后下次启动续跑（不重头）
            Log.e(TAG, "R285 链式回填中断（断点已落盘，下次启动续跑）", e)
        }
    }

    /**
     * 单频道治疗事务（自持 writeLock+事务；读已在锁外完成，本函数只做锁内核对与写）：
     *  1. **链④行先写**：锁内按 rowid 重查且 `caption=''` 守卫（竞态防御——R238 Job/
     *     本 Job 重跑/merge 收编都可能已动过这些行；已非空/已删行静默跳过）→ caption 列
     *     回填（[backfillAlbumCaptionRowLocked]，fts/LIKE 同步）→ CaptionParser
     *     按计划上下文解析收集；
     *  2. **链②组后写**：[collectAlbumRowsLocked] 全体复用（组内重读共享文案+caption 列
     *     回填+逐成员解析收集——读写窗口期 ingest 新到成员随重读自然入组）——必须
     *     排在④之后：④批注落库的相册成员已成组内共享载体（「④批一员→②接管全组」
     *     形态缝修复，16416 实证：全空 10 人相册被 6 上限劈开半愈），重读才能把
     *     批注共享给组内余下空成员；
     *  3. 收集汇成 [ParsedWriteRow] 一次 [applyParsedRowsBatchLocked] 落库
     *     （tags/tag_items delta 幂等通道——R262b 同口径）。
     */
    private fun applyChainHealingPlan(d: SQLiteDatabase, chatId: Long, plan: List<ChainHealing>) {
        synchronized(writeLock) {
            d.beginTransaction()
            try {
                val writes = ArrayList<ParsedWriteRow>(plan.size)
                // ① 链④行先写（锁内重查+空 caption 守卫）
                val chain4 = plan.filter { !it.viaChain2 }
                if (chain4.isNotEmpty()) {
                    val noteByRowid = HashMap<Long, ChainHealing>(chain4.size)
                    for (h in chain4) noteByRowid[h.rowid] = h
                    val meta = HashMap<Long, ArrayList<Any?>>(chain4.size) // rowid → [key, dateSec, channelTitle]
                    for (chunk in chain4.map { it.rowid }.chunked(TIME_WINDOW_IN_CHUNK)) {
                        d.rawQuery(
                            "SELECT rowid, key, dateEpochSec, channelTitle FROM items " +
                                "WHERE caption = '' AND rowid IN (" + chunk.joinToString(",") { "?" } + ")",
                            chunk.map { it.toString() }.toTypedArray(),
                        ).use { c ->
                            while (c.moveToNext()) {
                                meta[c.getLong(0)] = arrayListOf(c.getString(1), c.getLong(2), c.getString(3) ?: "")
                            }
                        }
                    }
                    for ((rowid, m) in meta) {
                        val h = noteByRowid[rowid] ?: continue
                        val key = m[0] as String
                        val dateSec = m[1] as Long
                        val channelTitle = m[2] as String
                        backfillAlbumCaptionRowLocked(d, rowid, h.note, channelTitle)
                        val p = CaptionParser.parse(h.note, CaptionAlbumContext(h.memberCount, h.memberIndex))
                        writes += ParsedWriteRow(rowid, key, chatId, dateSec, p.perItemTitle, p.storedTags())
                    }
                }
                // ② 链②组后写（albumId 去重——同组多空成员一次治疗；④已写行的组内
                //    共享文案此时可读）
                val groups2 = LinkedHashSet<Long>()
                for (h in plan) if (h.viaChain2) groups2 += h.albumId
                for (albumId in groups2) collectAlbumRowsLocked(d, chatId, albumId, writes)
                if (writes.isNotEmpty()) applyParsedRowsBatchLocked(d, writes)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    // ---------------- R236 v9 存量聚合 Job（item_tags 已解析行 → tags/tag_items） ----------------

    /** v9 聚合断点状态（storeDir/tags_backfill_v9r2.json；崩溃重启续跑不重头） */
    private data class TagsBackfillState(val cursor: Long, val aggregated: Int, val done: Boolean)

    /**
     * R262b 断点文件换代（v9 → v9r2）：R255 W11 括号 bug（R261 已修 SQL）同样
     * 击穿本 Job——applyTagDeltaBatchLocked 多值 INSERT 抛 SQLITE_ERROR 批整体回滚、
     * 游标照进、done 假完成，已解析行缺归属 24.5 万边（快照覆盖对账实锤：
     * 零归属 5.1 万行 + 部分缺 2.9 万行，r262_notes.md）。换代=以修复后代码全量
     * 重扫（delta 幂等：已齐行零写、缺失边补齐、陈旧边清除）；旧文件留作考古不读。
     */
    private fun tagsBackfillStateFile(): File = File(storeDir, "tags_backfill_v9r2.json")

    private fun readTagsBackfillState(): TagsBackfillState? = runCatching {
        val f = tagsBackfillStateFile()
        if (!f.exists()) return null
        val txt = f.readText()
        TagsBackfillState(
            cursor = Regex("\"cursor\":(-?\\d+)").find(txt)?.groupValues?.get(1)?.toLong() ?: 0L,
            aggregated = Regex("\"aggregated\":(\\d+)").find(txt)?.groupValues?.get(1)?.toInt() ?: 0,
            done = txt.contains("\"done\":true"),
        )
    }.getOrNull()

    private fun writeTagsBackfillState(s: TagsBackfillState) {
        // 原子写（写临时文件+rename）：进程被杀在 writeText 半途会留残串——
        // readTagsBackfillState 正则失配→游标归零从头重扫（16384 实测：断点
        // 2637358 被半写毁→重启后 3507558 重扫；delta 幂等保数据正确性但白扫
        // 870k 行。R228 同族现象（r228_notes④「cursor 上跳」）；本 Job 收口原子写）
        runCatching {
            val tmp = File(storeDir, "tags_backfill_v9r2.json.tmp")
            tmp.writeText("{\"cursor\":${s.cursor},\"aggregated\":${s.aggregated},\"done\":${s.done}}")
            if (!tmp.renameTo(tagsBackfillStateFile())) {
                tmp.delete()
                tagsBackfillStateFile().writeText(
                    "{\"cursor\":${s.cursor},\"aggregated\":${s.aggregated},\"done\":${s.done}}",
                )
            }
        }
    }

    /** v9 存量聚合单飞点火（openDb 尾部；R228 回填单飞闸同款模式） */
    private fun maybeStartTagsBackfill(d: SQLiteDatabase) {
        if (tagsBackfillStarted) return
        synchronized(dbLock) {
            if (tagsBackfillStarted) return
            tagsBackfillStarted = true
        }
        ioScope.launch { runTagsBackfill(d) }
    }

    /**
     * v9 存量聚合（后台 IO 协程，R228 回填 Job 范式复用）：
     *  - **只聚合 v9 之前已落库的 item_tags**（JSON 逐行解析→delta 入账）——
     *    v9 之后的解析事件（R228 caption 回填继续跑/新爬 ingest/Lazy 兜底）全经
     *    [writeParsedRowLocked] delta **实时聚合**，不依赖本 Job 重扫（写路径单一
     *    汇的收敛收益：本 Job 只兜历史，不追增量）；
     *  - **delta 幂等**：old 归属从 tag_items 按行批查（idx_tag_items_rowid），
     *    已聚合行零 delta 跳过——崩溃/断点回扫不重复计数；
     *  - **错峰纪律（R208 同款）**：ColdStartQuietGate 起跑让路；批间 SystemStormGate
     *    高负载窗持有即等；**有写入批** [TAGS_BACKFILL_BATCH_GAP_MS] 秒级间隔
     *    （不抢爬虫/前台带宽），**零写入批**短间隔 [TAGS_BACKFILL_IDLE_GAP_MS]
     *    （只读扫过 item_tags NULL 沙漠——3.5M 行库 1s/批=2h 空转 vs 200ms=23min）；
     *  - **断点续传**：rowid 倒序单调游标（最近条目先）+ 状态文件每批落盘；
     *  - 完成判定：游标耗尽 → done 落盘（后续启动 O(1) 跳过；R228 回填后续写
     *    的行经写路径实时入账，无需重开本 Job）。
     */
    private suspend fun runTagsBackfill(d: SQLiteDatabase) {
        try {
            runCatching { ColdStartQuietGate.awaitQuiet(20_000) } // R208：冷启动让路+开机风暴窗
            val state = readTagsBackfillState()
            if (state?.done == true) {
                Log.d(TAG, "R236 存量聚合：上次已完成（done 标记），跳过")
                return
            }
            val maxRowid = d.rawQuery("SELECT MAX(rowid) FROM items", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (maxRowid <= 0L) {
                writeTagsBackfillState(TagsBackfillState(0L, 0, true)) // 空库：直接完成
                return
            }
            var cursor = state?.cursor?.takeIf { it > 0L } ?: (maxRowid + 1)
            var aggregated = state?.aggregated ?: 0
            var batches = 0
            while (true) {
                // 批行：只带聚合所需字段（rowid/chatId/dateSec/item_tags JSON）
                data class Row(val rowid: Long, val chatId: Long, val dateSec: Long, val tagsJson: String)
                val rows = ArrayList<Row>(TAGS_BACKFILL_BATCH_ROWS)
                var minRowidSeen = Long.MAX_VALUE
                d.rawQuery(
                    "SELECT rowid, chatId, dateEpochSec, $ITEM_TAGS_COL FROM items " +
                        "WHERE rowid < ? ORDER BY rowid DESC LIMIT $TAGS_BACKFILL_BATCH_ROWS",
                    arrayOf(cursor.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val rowid = c.getLong(0)
                        if (rowid < minRowidSeen) minRowidSeen = rowid
                        val json = c.getString(3) ?: continue // 未解析行不在聚合域（R228 域）
                        rows += Row(rowid, c.getLong(1), c.getLong(2), json)
                    }
                }
                if (minRowidSeen == Long.MAX_VALUE) break // 游标耗尽（minRowidSeen==1 的末批正常处理）
                // 旧归属批查（一次 IN 查询/批——idx_tag_items_rowid 命中）
                val oldTags = HashMap<Long, HashSet<String>>(rows.size)
                if (rows.isNotEmpty()) {
                    for (chunk in rows.map { it.rowid }.chunked(TIME_WINDOW_IN_CHUNK)) {
                        d.rawQuery(
                            "SELECT rowid_ref, tag FROM $TAG_ITEMS_TABLE WHERE rowid_ref IN (" +
                                chunk.joinToString(",") { "?" } + ")",
                            chunk.map { it.toString() }.toTypedArray(),
                        ).use { c ->
                            while (c.moveToNext()) {
                                oldTags.getOrPut(c.getLong(0)) { HashSet<String>(4) } += c.getString(1)
                            }
                        }
                    }
                }
                var wrote = 0
                // R262b 批失败重试（caption 回填同口径）：旧版批失败游标照进=该批行
                // 永久跳聚（W11 bug 期 24.5 万缺边的放大器）——原地重试
                // ≤[BACKFILL_BATCH_MAX_RETRIES] 次（delta 幂等重入安全），仍败才跳批
                // （下一代断点文件再兜）。批级防御：单批异常只丢本批不杀整 Job。
                var attempt = 0
                while (true) {
                    attempt++
                    val batchResult = runCatching {
                        synchronized(writeLock) {
                            d.beginTransaction()
                            try {
                                // R255 W11：500 行一事务从逐行 delta（每行 1 点查+每标签 3
                                // 语句）改批量核——批内旧归属已在上方一次 IN 批查，直接喂
                                // [applyTagDeltaBatchLocked]（同一实现，语义幂等口径不变）
                                val inputs = rows.mapNotNull { r ->
                                    val newTags = parseTagsJson(r.tagsJson) ?: return@mapNotNull null // 残串防御：跳过
                                    TagDeltaRow(r.rowid, r.chatId, r.dateSec, oldTags[r.rowid] ?: emptySet(), newTags)
                                }
                                wrote = applyTagDeltaBatchLocked(d, inputs) // 净增集（delta 幂等：已聚合行=0）
                                d.setTransactionSuccessful()
                            } finally {
                                d.endTransaction()
                            }
                        }
                    }
                    if (batchResult.isSuccess) break
                    val err = batchResult.exceptionOrNull()
                    if (attempt > BACKFILL_BATCH_MAX_RETRIES) {
                        Log.e(TAG, "R236 存量聚合批重试 $BACKFILL_BATCH_MAX_RETRIES 次仍败，跳过本批：rowid≈$cursor ${err?.message}")
                        break
                    }
                    Log.w(TAG, "R236 存量聚合批失败（原地重试 $attempt/$BACKFILL_BATCH_MAX_RETRIES）：${err?.message}")
                    delay(TAGS_BACKFILL_BATCH_GAP_MS)
                }
                aggregated += wrote
                batches++
                cursor = minRowidSeen // 游标推进：本批读到的最低 rowid（含跳过行——单调不回扫）
                writeTagsBackfillState(TagsBackfillState(cursor, aggregated, false))
                if (batches % TAGS_BACKFILL_LOG_EVERY == 0) {
                    Log.d(TAG, "R236 存量聚合：已聚合 $aggregated 项（断点 rowid=$cursor）")
                }
                delay(if (wrote > 0) TAGS_BACKFILL_BATCH_GAP_MS else TAGS_BACKFILL_IDLE_GAP_MS)
                if (wrote > 0 && !SystemStormGate.clearNow()) {
                    SystemStormGate.awaitClear("R236 存量聚合") // R208：高负载窗持有即等（只对有 IO 的批）
                }
            }
            writeTagsBackfillState(TagsBackfillState(0L, aggregated, true))
            Log.i(TAG, "R236 存量聚合完成：共聚合 $aggregated 项（$batches 批）；后续解析经写路径实时入账")
        } catch (e: Throwable) {
            // 断点已随每批落盘：崩溃/取消后下次启动续跑（不重头）
            Log.e(TAG, "R236 存量聚合中断（断点已落盘，下次启动续跑）", e)
        }
    }

    // ---------------- 迁移用：频道 JSON 文件流式解析（低驻留，单频道瞬时） ----------------

    /** 频道文件解析结果（全量条目版；仅迁移用） */
    private class ParsedChannel(
        val chatId: Long,
        val cursor: Long,
        val exhausted: Boolean,
        val items: LinkedHashMap<String, VideoItem>,
    )

    /** 流式解析频道文件全量条目（单频道 ≤[TAIL_MAX_ITEMS] 条，数 MB 瞬时；字段口径同旧 toJson 落盘） */
    private fun readChannelItems(file: File): ParsedChannel? {
        var chatId = 0L
        var cursor = 0L
        var exhausted = false
        val items = LinkedHashMap<String, VideoItem>()
        file.bufferedReader().use { br ->
            val r = JsonReader(br)
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "chatId" -> chatId = r.nextLong()
                    "cursor" -> cursor = r.nextLong()
                    "exhausted" -> exhausted = r.nextBoolean()
                    "items" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            readItem(r)?.let { items.putIfAbsent(it.key, it) }
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        if (chatId == 0L) return null
        return ParsedChannel(chatId, cursor, exhausted, items)
    }

    /** 流式读单条 item（字段口径同旧落盘格式；缺 key 视为坏条目返回 null） */
    private fun readItem(r: JsonReader): VideoItem? {
        var key: String? = null
        var chatId = 0L
        var messageId = 0L
        var channelTitle = ""
        var caption = ""
        var durationSec = 0
        var width = 0
        var height = 0
        var fileId = 0
        var sizeBytes = 0L
        var mimeType = ""
        var dateEpochSec = 0
        var fileUniqueId = ""
        var mediaType = -1 // -1 = 老版本 JSON 无此字段，读后按 mimeType 推断
        var coverFileId = -1 // -1 = 无独立封面文件（老版本 JSON 无此字段同此口径）
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "key" -> key = r.nextString()
                "chatId" -> chatId = r.nextLong()
                "messageId" -> messageId = r.nextLong()
                "channelTitle" -> channelTitle = r.nextString()
                "caption" -> caption = r.nextString()
                "durationSec" -> durationSec = r.nextInt()
                "width" -> width = r.nextInt()
                "height" -> height = r.nextInt()
                "fileId" -> fileId = r.nextInt()
                "sizeBytes" -> sizeBytes = r.nextLong()
                "mimeType" -> mimeType = r.nextString()
                "playUri" -> if (r.peek() == JsonToken.NULL) r.nextNull() else r.nextString() // 旧字段，读即弃（不落库）
                "dateEpochSec" -> dateEpochSec = r.nextInt()
                "fileUniqueId" -> fileUniqueId = r.nextString()
                "mediaType" -> mediaType = r.nextInt()
                "coverFileId" -> coverFileId = r.nextInt()
                else -> r.skipValue() // 前向兼容：未知字段跳过
            }
        }
        r.endObject()
        val k = key ?: return null
        val mime = mimeType.ifBlank { "video/mp4" }
        return VideoItem(
            key = k,
            chatId = chatId,
            messageId = messageId,
            channelTitle = channelTitle,
            caption = caption,
            durationSec = durationSec,
            width = width,
            height = height,
            fileId = fileId,
            sizeBytes = sizeBytes,
            mimeType = mime,
            playUri = null,   // 不落库：点播放走 ensurePlayable 链现取
            coverBytes = null, // 不落库：封面走消息反解
            dateEpochSec = dateEpochSec,
            fileUniqueId = fileUniqueId,
            // 老版本 JSON 无 mediaType 字段：按 mimeType 推断（image/*→图文，audio/*→音频，否则视频）
            mediaType = if (mediaType >= 0) mediaType else when {
                mime.startsWith("image/") -> 1
                mime.startsWith("audio/") -> 2
                else -> 0
            },
            coverFileId = coverFileId,
            // 旧 JSON 未存 albumId（落盘格式就没有）：迁移条目 albumId=0，
            // 相册展开只覆盖迁移后新爬的条目（存量条目随自然续爬逐步带齐 albumId）
        )
    }

    private companion object {
        private const val TAG = "DeepSearchIndex"

        /** 数据库文件名（storeDir 下） */
        const val DB_NAME = "search.db"

        /** 库结构版本（PRAGMA user_version；结构变更时递增做迁移）。
         *  1 = 初版；2 = meta 补 mediaPass 列（媒体清扫相位持久化）；
         *  3 = meta 补 needDeepen 列（浅爬频道加深标记）；
         *  4 = meta 补 sweepCursor 列（R45：相位内游标落盘断点续扫）
         *      与 lastHeadRefreshTs 列（R45：头部刷新 6h 节流）；
         *  5 = meta 补 sampled 列（R46：时间分层采样标记，口径诚实——UI 区分全量/采样）；
         *  6 = meta 补 tier 列（R163 智能分档：-1=LEGACY 未分档/分档关，0..3=T0..T3。
         *      缺省 -1 = 存量频道行为与分档前完全一致——纪律①默认保守）；
         *  7 = items 加 idx_items_date(dateEpochSec, chatId) 时间索引（R202 时间维度
         *      钥匙——与 2~6 的 ALTER 瞬时迁移不同，索引本体走后台单飞构建，
         *      见 [maybeStartDateIndexBuild]；迁移不动任何行，无损）；
         *  8 = items 加 parsed_title（R228 caption 逐条解析标题，NULL=回退 caption
         *      首行旧口径）与 item_tags（JSON 数组，该条目 #标签——条目级∪相册级，
         *      NULL=未解析存量，消费方回退 hashtagHits 旧口径）。ALTER ADD COLUMN
         *      NULL 缺省=元数据级瞬时（v2~v6 同款同步路径），存量行零破坏（见
         *      openDb v8 分支的迁移策略裁定）；
         *  9 = R236 v2 tags 表双件：tags（chatId+tag 折叠键聚合账本：itemCount/
         *      display 首见原形/first·lastSeenSec——topTags/tagChannels 的毫秒级
         *      排序源）与 tag_items（tag→items.rowid 元素级倒排——tagItems 的
         *      毫秒级召回源；LIKE 设备 hashtag 检索 3-8 分钟→毫秒级的根治件，
         *      R167 §1.7 挂账落地）。空表幂等建（元数据级瞬时）；存量聚合走
         *      [maybeStartTagsBackfill] 后台 Job；写路径唯一汇=writeParsedRowLocked
         *      的 delta 聚合（四路解析写入全自动维护）。加速层不是替代：
         *      item_tags 列/R228 回填 Job 零触碰（红线）；
         *  10 = meta 加 dead 列（R262b 死档标记：全相位清扫到底仍零产出的无媒体
         *      频道，头刷降频 6h→7d 探活，探到新鲜自动复活——判定唯一出处
         *      [DeadChannelPolicy]；迁移附带一次性分诊：真死档标死 + 提前终态
         *      嫌疑复活重爬，体检数据见 r262_notes.md）；
         *  11 = R266 跨频道转发冗余存储层合并（storage-merge-design.md 终审稿，
         *      方案 C 正表收编+外挂别名表）：repost_refs 空表+四索引+posts_view
         *      兼容视图（元数据级瞬时，v9 同款先例）；idx_items_fuid 内容指纹
         *      索引走 [maybeStartFuidIndexBuild] 后台单飞（R202 v7 同款纪律，
         *      绝不进 openDb 同步路径）；存量 117.7 万转发行收编走
         *      [maybeStartMergeBackfill] 后台批迁 Job（断点续迁/与爬虫共存）；
         *  12 = R284 时效感知爬取调度：meta 加 ttlSec 列（TDLib
         *      Chat.messageAutoDeleteTime 秒，0=不自毁；javap 实证本 AAR 字段在
         *      场）。真值由目录枚举 [noteChatTtls] 顺手落（零新增请求——GetChat
         *      响应现成字段）；调度侧时效优先车道+头刷覆盖闸的判定原料
         *      （判定唯一出处 [CrawlThroughputPolicy]）
         * v13 起 meta 另带 lastMsgSec：R286 活跃新鲜度（服务器 Chat.lastMessage.date
         *      秒，0=未知；唯一写入方 [noteChatLastMessages]，只 UPDATE 已有行不
         *      建行）——活跃度头刷提频闸与滞后告警线的判定原料；
         * v14 起新增 item_ocr 表：R292 封面 OCR 试点（T1a）识别文本独立表
         *      （rowid 对齐 items + chatId 冗余 + text + engineVer + applied + ts；
         *      空表幂等建，v9/v11 同款先例）——识别文本绝不混 items.caption，
         *      引擎换代可整表重建不触主表。存量回填走 [maybeStartCoverOcrBackfill]
         *      并词位走 [maybeStartCoverOcrApply]（均后台 Job，FeedOcrPart 接线点火，
         *      试点期 applied 恒 0=生产检索零行为变化） */
        const val DB_VERSION = 14

        // ---------------- R289 索引库损坏自愈常量（判定真值在 [IndexHealPolicy]，此处为执行层锚） ----------------

        /**
         * 自愈守卫状态文件名（deepindex 目录——损坏计数必须活在被重建的 search.db
         * 之外；门房白名单 [isBackfillStateFileName] 同前缀双保险，防 R262b 西西弗斯洞）
         */
        const val HEAL_GUARD_FILE_NAME = "index_heal_guard.json"

        /** fts 后台重灌批行数（错峰纪律同 R228 回填族：批 5000+秒级间隔） */
        const val HEAL_FTS_REBUILD_BATCH = 5_000

        /** 后台重建批间隔（有写入批 1s——R228/R236/R262b 回填族同口径红线） */
        const val HEAL_REBUILD_BATCH_GAP_MS = 1_000L

        /**
         * 索引名→DDL 唯一映射（轻损修复后台重建/探针穿刺计划共用一份真值——
         * 防两处手写漂移；反射锚锁定键集与 DDL 内名一致）。date/fuid 两大索引
         * 不在此列（既有专属后台单飞探测缺失自动重建，修复侧只 DROP 不登记）。
         */
        private val INDEX_DDL_BY_NAME: Map<String, String> = linkedMapOf(
            "idx_items_chat_msg" to CREATE_ITEMS_CHAT_INDEX_SQL,
            "idx_items_album" to CREATE_ITEMS_ALBUM_INDEX_SQL,
            TAGS_TAG_INDEX to CREATE_TAGS_TAG_INDEX_SQL,
            TAG_ITEMS_CHAT_INDEX to CREATE_TAG_ITEMS_CHAT_INDEX_SQL,
            TAG_ITEMS_ROWID_INDEX to CREATE_TAG_ITEMS_ROWID_INDEX_SQL,
            REFS_CHAT_INDEX to CREATE_REFS_CHAT_INDEX_SQL,
            REFS_ALBUM_INDEX to CREATE_REFS_ALBUM_INDEX_SQL,
            REFS_FUID_INDEX to CREATE_REFS_FUID_INDEX_SQL,
            REFS_DATE_INDEX to CREATE_REFS_DATE_INDEX_SQL,
        )

        /**
         * R289b L3 索引穿刺规格清单（表/前导列真值唯一出处；SQL 生成=
         * [IndexHealPolicy.indexProbePlans]，反射锚单测锁定形状与 DDL 前导列一致）。
         * 安全形状铁律（R290b V7 误报实锤）：每条生成 SQL=「SELECT rowid（索引隐式
         * 覆盖列=永不触表）+ WHERE 前导列（样本等值/恒真范围）」——Android SQLite
         * 3.32.2 对「无 WHERE+非覆盖 INDEXED BY」恒抛 no query solution 的雷区
         * 由此根除。穿刺覆盖口径维持 R289 不变（refs_album/refs_date 本就不穿）。
         */
        private val INDEX_PROBE_SPECS: List<IndexHealPolicy.IndexProbeSpec> = listOf(
            IndexHealPolicy.IndexProbeSpec(
                "idx_items_chat_msg", "items", "chatId", "-9223372036854775807",
                usesChatSample = true, chatSampleOrdinal = 0,
            ),
            IndexHealPolicy.IndexProbeSpec(
                "idx_items_album", "items", "chatId", "-9223372036854775807",
                usesChatSample = true, chatSampleOrdinal = 1,
            ),
            IndexHealPolicy.IndexProbeSpec(
                DATE_INDEX_NAME, "items", "dateEpochSec", "0", probeRightLeaf = true,
            ),
            IndexHealPolicy.IndexProbeSpec(FUID_INDEX_NAME, "items", "fileUniqueId", "''"),
            IndexHealPolicy.IndexProbeSpec(TAGS_TAG_INDEX, "tags", "tag", "''"),
            IndexHealPolicy.IndexProbeSpec(
                TAG_ITEMS_CHAT_INDEX, "tag_items", "chatId", "-9223372036854775807",
                usesChatSample = true, chatSampleOrdinal = 0,
            ),
            IndexHealPolicy.IndexProbeSpec(TAG_ITEMS_ROWID_INDEX, "tag_items", "rowid_ref", "0"),
            IndexHealPolicy.IndexProbeSpec(
                REFS_CHAT_INDEX, REPOSTS_TABLE, "chatId", "-9223372036854775807",
                usesChatSample = true, chatSampleOrdinal = 0,
            ),
            IndexHealPolicy.IndexProbeSpec(REFS_FUID_INDEX, REPOSTS_TABLE, "fuid", "''"),
        )

        /** 加深续爬单轮页数预算（旧路 GetChatHistory 低速补深：评论级联/批注/漏网媒体） */
        const val DEEPEN_MAX_PAGES = 20

        /** 旧 5000 封顶时代浅爬频道识别下限（条数 ≥ 旧封顶减一页即视为触顶浅爬） */
        const val LEGACY_SHALLOW_ITEM_FLOOR = 4_900

        /** items 表全字段列（顺序固定，rowToItem 按下标读；R228 起含结构化两列） */
        private const val ITEM_COLS =
            "key,chatId,messageId,albumId,mediaType,coverFileId,caption,channelTitle," +
                "fileId,fileUniqueId,mimeType,durationSec,sizeBytes,width,height,dateEpochSec," +
                "parsed_title,item_tags"

        /** INSERT 用的基础列（R228 起与 ITEM_COLS 分轨：插入语句仍只写 16 基础列，
         *  解析字段由 ingest 后置 [applyParsedCaptionsLocked] 同事务 UPDATE 落位——
         *  相册上下文需入库后查成员全集，插前无上下文可写） */
        private const val ITEM_INSERT_COLS =
            "key,chatId,messageId,albumId,mediaType,coverFileId,caption,channelTitle," +
                "fileId,fileUniqueId,mimeType,durationSec,sizeBytes,width,height,dateEpochSec"

        /** rowToItem 下标：parsed_title（16）/item_tags（17）——R228 结构化两列 */
        private const val COL_PARSED_TITLE = 16
        private const val COL_ITEM_TAGS = 17

        /**
         * R236：tagItems JOIN 查询的列清单（[ITEM_COLS] 全限定 items. 前缀版——
         * tag_items 同名 chatId 列在 JOIN 下裸列名歧义，真机 EXPLAIN 实锤修正）
         */
        private val ITEM_COLS_FROM_ITEMS = ITEM_COLS.split(",")
            .joinToString(",") { "items.${it.trim()}" }

        /** R228 结构化列名（SQL 拼接用；反射机检锚同款 const val 口径） */
        const val PARSED_TITLE_COL = "parsed_title"
        const val ITEM_TAGS_COL = "item_tags"

        // ---------------- R236 v9 tags 表（表名/索引名锚：反射机检测试锁同步） ----------------

        /** v9 聚合账本表名（chatId+tag 折叠键 → 计数/首见原形/首末时间） */
        const val TAGS_TABLE = "tags"

        /** v9 元素级倒排表名（tag 折叠键 → items.rowid） */
        const val TAG_ITEMS_TABLE = "tag_items"

        /** tags 表 tag 前缀索引（tagChannels 全网 tag 频道账本毫秒级导航） */
        const val TAGS_TAG_INDEX = "idx_tags_tag"

        /** tag_items 的 chat 域索引（evict 整频道清理 + 未来 chat+tag 域查询） */
        const val TAG_ITEMS_CHAT_INDEX = "idx_tag_items_chat"

        /** tag_items 的 rowid 反查索引（delta 旧归属点查/回填批查） */
        const val TAG_ITEMS_ROWID_INDEX = "idx_tag_items_rowid"

        /** v9 存量聚合批行数（R228 回填 500/批同口径——错峰纪律用户口径） */
        const val TAGS_BACKFILL_BATCH_ROWS = 500

        /** v9 存量聚合批写入间隔（有实际写入时；零写入批走短间隔不空转） */
        const val TAGS_BACKFILL_BATCH_GAP_MS = 1_000L

        /** v9 存量聚合零写入批短间隔（扫过 item_tags NULL 沙漠不拖 1s/批——
         *  3.5M 行库≈7000 批，1s/批=2h 空转 vs 200ms=23min 只读扫描） */
        const val TAGS_BACKFILL_IDLE_GAP_MS = 200L

        /** v9 存量聚合进度日志节流（每 N 批一行） */
        const val TAGS_BACKFILL_LOG_EVERY = 50

        /**
         * R239 用户浏览写回（[ingestBrowsedPage]）每日软顶：单会话单日最多写回条数
         * （架构师风险表 #6「体积膨胀」对策——用户浏览写回是加菜不是主粮，超顶
         * 静默跳过；爬虫主路径不受此顶约束）
         */
        const val BROWSE_INGEST_DAILY_CAP = 10_000

        /**
         * v9 聚合账本（chatId+tag 主键=频道内 top 导航；tag 单列索引=全网 tag 频道账本）。
         *  - tag 列存 **fold 归一键**（SearchTextKit.fold——与 R228 searchHashtag 比对、
         *    CaptionParser 提取去重同一把尺子，三方同源不私抄）；
         *  - display 列存首见**原形**（展示口径：行星标签/topTags 不降级——拉丁不出
         *    小写、繁体不出简体；INSERT OR IGNORE 天然保首见）；
         *  - first/lastSeenSec：该 chat+tag 最早/最晚条目时刻（dateSec<=0 行保持
         *    NULL 不污染；减集路径只减计数不重算极值——残余行 min/max 可能陈旧，
         *    加速层口径注释声明，见 [applyTagDeltaLocked]）。
         */
        private const val CREATE_TAGS_SQL =
            "CREATE TABLE IF NOT EXISTS tags(" +
                "chatId INTEGER NOT NULL," +
                "tag TEXT NOT NULL," +
                "display TEXT NOT NULL," +
                "itemCount INTEGER NOT NULL DEFAULT 1," +
                "firstSeenSec INTEGER," +
                "lastSeenSec INTEGER," +
                "PRIMARY KEY (chatId, tag)" +
                ")"

        /** R236：全网 tag→频道账本索引（tagChannels 毫秒级） */
        private const val CREATE_TAGS_TAG_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $TAGS_TAG_INDEX ON tags(tag)"

        /**
         * v9 元素级倒排（tag 折叠键→items.rowid）：tagItems 毫秒级召回的唯一路径。
         * LIKE 设备 hashtag 检索的根治件（R167 §1.7 挂账）；顺带治 R228 已知缺口
         * （超长 caption 截断位外的 tag/混合相册空 caption 成员——不再依赖 tokens
         * 词位候选，item_tags 元素即索引行）。rowid 在行存续期稳定（INSERT OR
         * IGNORE 不改既有行 rowid）；chatId 冗余存一份供 evict 整频道清理
         * （idx_tag_items_chat 命中）与未来 chat+tag 域查询。
         */
        private const val CREATE_TAG_ITEMS_SQL =
            "CREATE TABLE IF NOT EXISTS tag_items(" +
                "tag TEXT NOT NULL," +
                "rowid_ref INTEGER NOT NULL," +
                "chatId INTEGER NOT NULL," +
                "PRIMARY KEY (tag, rowid_ref)" +
                ")"

        /** R236：tag_items 的 chat 域索引（evict 清理/未来 chat+tag 域查询） */
        private const val CREATE_TAG_ITEMS_CHAT_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $TAG_ITEMS_CHAT_INDEX ON tag_items(chatId, tag)"

        /** R236：tag_items 的 rowid 反查索引（delta 旧归属点查/回填批查） */
        private const val CREATE_TAG_ITEMS_ROWID_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $TAG_ITEMS_ROWID_INDEX ON tag_items(rowid_ref)"

        // ---------------- R266 v11：跨频道转发冗余存储层合并（方案 C：正表收编+外挂别名表） ----------------
        // 设计唯一出处 storage-merge-design.md（终审稿，D1~D8 按推荐值通过）：
        //  D1 canonical=组内首发（dateEpochSec,messageId 最小，R204 同口径），选定冻结不换马；
        //  D2 转发者独立 caption 忠实保留（refs.caption 覆盖列：NULL=与 canonical 同）；
        //  D3 频道页照常显示转发条目；D4 时间线/直方图=现状口径（每次转发各计一份）；
        //  D5 tags/倒排挂 canonical（内容级）；D6 计数含转发+诚实副标；
        //  D7 搜索命中=首发 canonical+副本清单；D8 evict 自动提升存活方最早发布者。

        /** v11 别名表名（被收编转发行的发布侧字段+fuid 引用+caption 覆盖列） */
        const val REPOSTS_TABLE = "repost_refs"

        /** refs 频道页/相册/时间窗/指纹四索引名（反射机检锚同款 const val 口径） */
        const val REFS_CHAT_INDEX = "idx_refs_chat_msg"
        const val REFS_ALBUM_INDEX = "idx_refs_album"
        const val REFS_FUID_INDEX = "idx_refs_fuid"
        const val REFS_DATE_INDEX = "idx_refs_date"

        /** v11 内容指纹索引名（写入路径查重+FirstSeenLedger 提速+迁移 Job 导航共用） */
        const val FUID_INDEX_NAME = "idx_items_fuid"

        /** v11 发布侧全集兼容视图名（外部直连读者的迁移落点/即席查询口） */
        const val POSTS_VIEW = "posts_view"

        /**
         * v11 别名表：被收编转发行的**发布侧**全集 + fuid 引用 + canonRowid 锚 +
         * caption 覆盖列（D2 忠实保留：NULL=与 canonical 相同；非 NULL（含空串）=
         * 本发布实际文案）。mediaType/durationSec 是冗余内容侧字段（统计免 JOIN——
         * durationSec 为设计终审稿外的实施期补列：FeedGenomePart/TasteMap 时长
         * 离群账两处外部聚合只读它，与 mediaType 同理由同冗余，成本 8B×117.7 万
         * ≈9.4MB，已在 r266_notes 申报）。回滚凭据=本表自身（反向 Job 可重铸 items 行）。
         */
        private const val CREATE_REPOSTS_SQL =
            "CREATE TABLE IF NOT EXISTS $REPOSTS_TABLE(" +
                "key TEXT PRIMARY KEY," +
                "chatId INTEGER NOT NULL," +
                "messageId INTEGER NOT NULL," +
                "albumId INTEGER NOT NULL DEFAULT 0," +
                "mediaType INTEGER NOT NULL DEFAULT 0," +
                "durationSec INTEGER NOT NULL DEFAULT 0," +
                "coverFileId INTEGER NOT NULL DEFAULT -1," +
                "channelTitle TEXT NOT NULL DEFAULT ''," +
                "dateEpochSec INTEGER NOT NULL DEFAULT 0," +
                "fuid TEXT NOT NULL," +
                "canonRowid INTEGER NOT NULL," +
                "caption TEXT" +
                ")"

        /** refs 频道页索引（messageId 新→旧序，与 idx_items_chat_msg 同形） */
        private const val CREATE_REFS_CHAT_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $REFS_CHAT_INDEX ON $REPOSTS_TABLE(chatId, messageId DESC)"

        /** refs 相册展开索引（相册成员可能部分被收编） */
        private const val CREATE_REFS_ALBUM_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $REFS_ALBUM_INDEX ON $REPOSTS_TABLE(chatId, albumId)"

        /** refs 指纹索引（FirstSeenLedger 副本清单/evict 提升候选/canonical 修正步） */
        private const val CREATE_REFS_FUID_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $REFS_FUID_INDEX ON $REPOSTS_TABLE(fuid)"

        /** refs 时间索引（D4 现状口径时间窗/直方图的 refs 臂索引导航） */
        private const val CREATE_REFS_DATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $REFS_DATE_INDEX ON $REPOSTS_TABLE(dateEpochSec, chatId)"

        /**
         * R266 v11 内容指纹索引：items(fileUniqueId)。与 v7 时间索引同族——大库
         * CREATE INDEX 是分钟级长事务，**绝不在 openDb 同步路径执行**（首开秒级
         * 红线）；此处只落版本号，索引本体走 [maybeStartFuidIndexBuild] 后台单飞
         * （探测-建-幂等可续建，就绪门 [_fuidIndexReady]）。
         */
        const val CREATE_ITEMS_FUID_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $FUID_INDEX_NAME ON items(fileUniqueId)"

        /**
         * v11 发布侧全集兼容视图（外部直连读者/即席查询的迁移落点）：发布侧七列
         * +durationSec（实施期补列，见 [CREATE_REPOSTS_SQL] 注）两表 UNION ALL。
         * **热路径纪律**：老 SQLite（minSdk 26=3.18）不把 WHERE 谓词压进 UNION ALL
         * 分支——频道域/带限查询走本视图会整集物化，故交互热路径一律用 Kotlin 侧
         * 双臂合并（items 臂+refs 臂各自索引导航，DeepSearchIndex 内两段法先例）；
         * 本视图只服务全表型聚合与即席查询（FirstSeenLedger 覆盖频道数等）。
         */
        private const val CREATE_POSTS_VIEW_SQL =
            "CREATE VIEW IF NOT EXISTS $POSTS_VIEW AS " +
                "SELECT chatId, messageId, albumId, mediaType, durationSec, coverFileId, channelTitle, dateEpochSec " +
                "FROM items UNION ALL " +
                "SELECT chatId, messageId, albumId, mediaType, durationSec, coverFileId, channelTitle, dateEpochSec " +
                "FROM $REPOSTS_TABLE"

        /** v11 合并批迁：批扫行数（rowid 倒序游标；R228/R236 批 500 同口径） */
        const val MERGE_BATCH_ROWS = 500

        /** v11 合并批迁批间隔（有收编批；零收编批走短间隔不空转——R236 沙漠间隔同族） */
        const val MERGE_BATCH_GAP_MS = 1_000L

        /** v11 合并批迁零收编批短间隔（单件沙漠扫描不拖 1s/批） */
        const val MERGE_IDLE_GAP_MS = 200L

        /** v11 合并批迁进度日志节流（每 N 批一行） */
        const val MERGE_LOG_EVERY = 50

        /** R228 存量回填批行数（用户口径「批 500 条」） */
        const val BACKFILL_BATCH_ROWS = 500

        /**
         * R262b：回填批失败原地重试上限（重试耗尽才跳批，跳过行由收尾复核的
         * 下一轮重扫兜回——旧版「批失败游标照进」是 W11 bug 期失败行永久丢失的
         * 放大器）；tags 聚合 Job（v9r2）同口径复用
         */
        const val BACKFILL_BATCH_MAX_RETRIES = 2

        /**
         * R262b：caption 回填全量重扫轮次封顶（完成口径诚实化的活锁闸——
         * 残留>0 即再扫一轮，但毒行/永久失败行不能拖死 Job；封顶轮后 done 落盘
         * 如实报残留，残留行由 Lazy 兜底/后续代重扫收口）
         */
        const val BACKFILL_MAX_SWEEPS = 3

        /** R228 存量回填批间隔（用户口径「秒级错峰」——不抢爬虫/前台带宽） */
        const val BACKFILL_BATCH_GAP_MS = 1_000L

        /** R228 存量回填进度日志节流（每 N 批一行） */
        const val BACKFILL_LOG_EVERY = 50

        /** R285 链式回填进度日志节流（每 N 个频道一行——治疗单元=频道） */
        const val CHAIN_BACKFILL_LOG_EVERY = 20

        /** R228 Lazy 兜底单次解析上限（防大结果集全量重解析撑爆搜索时延） */
        const val LAZY_PARSE_MAX = 200

        /** R283 互联卷数据底座：单臂取行上限（refs→canon / refs∩refs / 残双 canonical
         *  逐臂；防巨型频道失控——命中置 truncated 诚实降级） */
        const val INTERLINK_ARM_ROW_CAP = 2000

        /** R283 频道对时间线默认截断（⑥卷面行数预算） */
        const val INTERLINK_TIMELINE_LIMIT = 64

        /** R283 频道伙伴榜默认截断 */
        const val INTERLINK_PARTNER_LIMIT = 24

        /** 条目表：key 主键；保留 rowid（fts 行以 rowid 对齐回表，contentless 回表必需）。
         *  v8 起含 R228 结构化两列（可空；新库 CREATE 直出，旧库 openDb 幂等 ALTER 补列） */
        private const val CREATE_ITEMS_SQL =
            "CREATE TABLE IF NOT EXISTS items(" +
                "key TEXT PRIMARY KEY," +
                "chatId INTEGER NOT NULL," +
                "messageId INTEGER NOT NULL," +
                "albumId INTEGER NOT NULL DEFAULT 0," +
                "mediaType INTEGER NOT NULL DEFAULT 0," +
                "coverFileId INTEGER NOT NULL DEFAULT -1," +
                "caption TEXT NOT NULL DEFAULT ''," +
                "channelTitle TEXT NOT NULL DEFAULT ''," +
                "fileId INTEGER NOT NULL DEFAULT 0," +
                "fileUniqueId TEXT NOT NULL DEFAULT ''," +
                "mimeType TEXT NOT NULL DEFAULT ''," +
                "durationSec INTEGER NOT NULL DEFAULT 0," +
                "sizeBytes INTEGER NOT NULL DEFAULT 0," +
                "width INTEGER NOT NULL DEFAULT 0," +
                "height INTEGER NOT NULL DEFAULT 0," +
                "dateEpochSec INTEGER NOT NULL DEFAULT 0," +
                "parsed_title TEXT," +
                "item_tags TEXT" +
                ")"

        /** 频道页直出/统计用索引（messageId 新→旧序） */
        private const val CREATE_ITEMS_CHAT_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_items_chat_msg ON items(chatId, messageId DESC)"

        /** 相册展开用索引 */
        private const val CREATE_ITEMS_ALBUM_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_items_album ON items(chatId, albumId)"

        /** R202 时间索引名（sqlite_master 探测/后台建库幂等口共用；反射机检锚同款 const val 无 private——编译为 outer 类 public static 字段可直读，R163 DB_VERSION 先例口径） */
        const val DATE_INDEX_NAME = "idx_items_date"

        /**
         * R202 时间维度钥匙：dateEpochSec 前缀 = 全网时间窗（BETWEEN + ORDER BY
         * dateEpochSec DESC + LIMIT）纯索引导航有界回表；chatId 第二列 = 频道过滤
         * 在索引内覆盖判定（非目标频道行零回表丢弃），单频道/订阅集窗同享一索引。
         * 大库 CREATE INDEX = 分钟级长事务 → 只在 [buildDateIndex] 后台单飞执行，
         * **绝不进 openDb 同步路径**（首开秒级红线）。
         */
        const val CREATE_ITEMS_DATE_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_items_date ON items(dateEpochSec, chatId)"

        /**
         * R202 时间窗 IN 分块上限（老 SQLite 999 占位符上限留余量，SAMPLE_MAX_CHANNELS
         * 同口径）。采样硬顶已上移 [TimeWindowPolicy.QUERY_LIMIT_MAX]（公共唯一出处，
         * R203 挂账的 companion 不可外引问题由此收口）。
         */
        const val TIME_WINDOW_IN_CHUNK = 900

        /** 爬取游标 + 迁移标记表（v2 起带 mediaPass：媒体清扫相位，-1=未进入清扫/旧数据；
         *  v3 起带 needDeepen：浅爬频道加深标记，触顶/旧 5000 封顶时代数据 = 1；
         *  v4 起带 sweepCursor：清扫相位内游标（messageId，0=相位起点），页级落盘中断续扫；
         *  与 lastHeadRefreshTs：上次头部刷新墙钟毫秒（0=从未刷过=立即到期）；
         *  v5 起带 sampled：时间分层采样标记（R46，1=该频道是按月锚点采样覆盖而非全量）；
         *  v6 起带 tier：R163 智能分档（-1=LEGACY 分档关/未分类，0..3=T0..T3 档）。
         *  档位唯一写入方 = setTier（B4 调度侧派工前评估落档）；本类按档位参数化四条
         *  爬取路径（条数上限/头刷节流/采样豁免/加深闸）+ 收尾 evict（全局预算）；
         *  v12 起带 ttlSec：R284 时效感知（messageAutoDeleteTime 秒，0=不自毁；
         *  唯一写入方 = noteChatTtls，目录枚举顺手落，零新增请求）；
         *  v13 起带 lastMsgSec：R286 活跃新鲜度（服务器 Chat.lastMessage.date 秒，
         *  0=未知；唯一写入方 = noteChatLastMessages——只 UPDATE 已有行不建行） */
        private const val CREATE_META_SQL =
            "CREATE TABLE IF NOT EXISTS meta(" +
                "chatId INTEGER PRIMARY KEY," +
                "cursor INTEGER NOT NULL DEFAULT 0," +
                "exhausted INTEGER NOT NULL DEFAULT 0," +
                "imported INTEGER NOT NULL DEFAULT 0," +
                "mediaPass INTEGER NOT NULL DEFAULT -1," +
                "needDeepen INTEGER NOT NULL DEFAULT 0," +
                "sweepCursor INTEGER NOT NULL DEFAULT 0," +
                "lastHeadRefreshTs INTEGER NOT NULL DEFAULT 0," +
                "sampled INTEGER NOT NULL DEFAULT 0," +
                "tier INTEGER NOT NULL DEFAULT -1," +
                "dead INTEGER NOT NULL DEFAULT 0," +
                "ttlSec INTEGER NOT NULL DEFAULT 0," +
                "lastMsgSec INTEGER NOT NULL DEFAULT 0" +
                ")"

        /** contentless FTS5 虚表：只存词位字典+doclist，不存原文副本（原文在 items 表，rowid 回表） */
        private const val CREATE_FTS_SQL =
            "CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(tokens, content='', tokenize='unicode61')"

        // ---------------- R292 v14：封面 OCR 试点（T1a）item_ocr 独立表 ----------------
        // 设计唯一出处 content-understanding-eval.md（用户已终审 T1a）+ r292_notes.md 设计定稿。

        /** v14 OCR 表名（反射机检锚同款 const val 口径） */
        const val ITEM_OCR_TABLE = "item_ocr"

        /** item_ocr 的 chat 域索引名（evict 整频道清理用，tag_items 同族先例） */
        const val ITEM_OCR_CHAT_INDEX = "idx_item_ocr_chat"

        /** item_ocr 的并词位结算索引名（apply Job 扫 applied=0 批用） */
        const val ITEM_OCR_APPLIED_INDEX = "idx_item_ocr_applied"

        /**
         * v14 OCR 独立表：rowid 对齐 items.rowid（非外键，evict/收编路径显式同步删）——
         *  - text：识别文本（'' = 已识别无字墓碑，防重扫；**只入库不进日志**，§七扩令）；
         *  - engineVer：引擎代次（换代=整表可重建凭据）；
         *  - applied：0=未并词位（试点期恒 0=生产检索零变化）；1=已结算（已并入 fts
         *    或判定不并——低信息闸在词位配方里同源再判，见 [indexTokensOf] 三参重载）；
         *    2=结算失败墓碑（批重试耗尽置位，不再选批防毒行活锁；换代/人工回 0 复捞）；
         *  - chatId 冗余存一份（evict 整频道清理同 tag_items.chatId 先例）；
         *  - ts：识别时刻 epoch 秒（试点报告/排查用）。
         */
        private const val CREATE_ITEM_OCR_SQL =
            "CREATE TABLE IF NOT EXISTS $ITEM_OCR_TABLE(" +
                "rowid INTEGER PRIMARY KEY," +
                "chatId INTEGER NOT NULL," +
                "text TEXT NOT NULL," +
                "engineVer INTEGER NOT NULL," +
                "applied INTEGER NOT NULL DEFAULT 0," +
                "ts INTEGER NOT NULL" +
                ")"

        /** item_ocr 的 chat 域索引（evictChannel 整频道清理命中） */
        private const val CREATE_ITEM_OCR_CHAT_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $ITEM_OCR_CHAT_INDEX ON $ITEM_OCR_TABLE(chatId)"

        /** item_ocr 的结算相位索引（apply Job `applied=0 AND rowid>?` 批扫命中） */
        private const val CREATE_ITEM_OCR_APPLIED_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS $ITEM_OCR_APPLIED_INDEX ON $ITEM_OCR_TABLE(applied, rowid)"

        /** 封面 OCR 回填断点状态文件名（deepindex 下；门房白名单 cover_ocr_ 前缀双保险） */
        const val COVER_OCR_BACKFILL_STATE_FILE = "cover_ocr_backfill_v1.json"

        /** 词位并入（apply）断点状态文件名 */
        const val COVER_OCR_APPLY_STATE_FILE = "cover_ocr_apply_v1.json"

        /** 试点报告文件名（全聚合零原文，用户终审放量决策的唯一数据源） */
        const val COVER_OCR_PILOT_REPORT_FILE = "cover_ocr_pilot_report.json"

        /** 封面试点处理上限（任务书：先跑 1 万封面出实得率；放量闸开后失效） */
        const val COVER_OCR_PILOT_LIMIT = 10_000

        /** 封面 OCR 回填批条数（单件=下载+识别两重活，比纯解析批 500 调小一个量级） */
        const val COVER_OCR_BATCH_ROWS = 50

        /** 封面 OCR 回填批间隔（有写入批；回填族秒级错峰同口径红线） */
        const val COVER_OCR_BATCH_GAP_MS = 1_000L

        /** 封面 OCR 窗口关闭时的复探间隔（窗关=sleep 不烧 CPU，窗开即续跑） */
        const val COVER_OCR_WINDOW_RETRY_MS = 60_000L

        /** 封面 OCR 回填进度日志节流（每 N 批一行；内容只出计数，识别文本绝不进日志） */
        const val COVER_OCR_LOG_EVERY = 20

        /** 词位并入批行数（纯库活无下载，R228/R236 批 500 同口径） */
        const val COVER_OCR_APPLY_BATCH_ROWS = 500

        /** 词位并入批间隔（有写入批 1s——回填族同口径红线） */
        const val COVER_OCR_APPLY_GAP_MS = 1_000L

        /** 每页拉取条数（第十轮：50 → 100。TDLib GetChatHistory/SearchChatMessages
         *  单请求上限即 100——页数减半，pacer 间隔/逐页事务/限流暴露面同步减半；
         *  清扫通道经 fetchAggregatedMedia 单频道口径生效，旧路经 fetchFromChannel
         *  内部多轮凑页（maxRounds=6 不变，最坏请求包络不变） */
        const val PAGE_SIZE = 100

        /**
         * R255 W5：爬取预算滑窗时长（毫秒）——与 FeedViewModel 构造
         * `FloodBudget(3_600_000L, …)` 同源同值（窗口私有不可读，此处镜像声明；
         * [CrawlThroughputPolicyTest] 反射锚锁两处同源）。
         */
        const val CRAWL_BUDGET_WINDOW_MS = 3_600_000L

        /**
         * R255 W11：tag_items 多值 INSERT 每语句元组上限——SQLITE_MAX_VARIABLE_NUMBER
         * =999（minSdk 26 框架口径）安全覆盖：300 元组×3 参数=900 ≤999。
         */
        const val TAG_BULK_TUPLES_PER_STMT = 300

        // ---- AIMD 自适应限速参数（第八轮提速，替换旧固定 80ms/页） ----

        /** 页间隔初值（毫秒；第十轮 80→40：加性提速 -20ms/页下 2 页即探到下限，
         *  冷启动税减半；flood 红线不变——429 照旧 ×2 降速 + 惩罚窗冻结） */
        const val PACER_START_MS = 40L

        /** 页间隔下限（毫秒）：AIMD 加性提速的地板 */
        const val PACER_MIN_MS = 10L

        /** 加性提速步长（毫秒/页）：每页成功且处于惩罚窗外即 -20ms */
        const val PACER_ADDITIVE_MS = 20L

        /** 页间隔上限（毫秒）：flood 乘性降速（×2）的天花板 */
        const val PACER_MAX_MS = 2_000L

        /** flood 惩罚窗（毫秒）：窗内冻结不提速，连续这么久无 flood 才恢复加性回升 */
        const val PACER_QUIET_MS = 5 * 60_000L

        /** flood wait 退避基数（毫秒）：旧路重试一次/清扫递增退避的起步值；
         *  服务端点名 "retry after N" 时以 N 为准（见 retryAfterMs） */
        const val FLOOD_BACKOFF_MS = 5_000L

        /** 媒体清扫单页最大尝试次数（第十轮 2→3：递增退避 5s/15s，短 flood 当场骑过去） */
        const val SWEEP_PAGE_MAX_ATTEMPTS = 3

        /** flood 异常消息里服务端点名退避秒数的解析式（"Too Many Requests: retry after 12" 类） */
        private val RETRY_AFTER_REGEX = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)

        /** 媒体清扫同一频道连续失败轮数熔断：达到即退回旧路（GetChatHistory 线性爬）兜底一轮 */
        const val SWEEP_FALLBACK_AFTER_FAILS = 3

        /** 头部增量刷新的最大翻页轮数（防"上次索引之后爆量更新"时一次刷太久） */
        const val HEAD_MAX_ROUNDS = 20

        /** R45（根因 R3）：已到底频道头部刷新最小间隔（毫秒）。维护期"全部已到底频道
         *  每轮都刷头部"是每轮千次级常驻请求的来源；6h 闸把这份消耗压到接近零，
         *  新内容可见性代价 = 最迟 6h 进索引（Feed 浏览本身不受影响） */
        const val HEAD_REFRESH_MIN_INTERVAL_MS = 6L * 60 * 60_000L

        /** P0 尾部续爬硬上限：单频道索引总条数触顶即停（防失控爬取的库膨胀）。
         *  5000 → 50000（第七轮：SQLite 磁盘索引时代内存约束已消失，5000 截断大资源频道
         *  是"搜不全"的实锤根因之一）；触顶不置 exhausted，游标断点下次续爬 */
        const val TAIL_MAX_ITEMS = 50_000

        /** P0 尾部续爬硬上限：单次调用最多翻页轮数（≈ 50000 条/50 条每页，双保险） */
        const val TAIL_MAX_ROUNDS = 1_000

        /** 日历跳爬：一天的秒数（UTC 日取整/连续段判定，与网关侧 CALENDAR_DAY_SECONDS 同口径） */
        const val CAL_DAY_SECONDS = 86_400

        // ---- R46 邪修提速参数（方案 c 采样 / 方案 b 白嫖 / §5 泳道预算） ----

        /** 怪兽频道判定阈值（媒体总量探测 > 此值即按月采样，不做全量翻页） */
        const val SAMPLE_MONSTER_MIN_ITEMS = 5_000

        /** 采样爬取每个活跃月每种媒体类型抽取的页数上限（1 请求/页，含首月探测） */
        const val SAMPLE_PAGES_PER_MONTH_PER_TYPE = 3

        /** 采样回溯的最大月数（本月起向老逐月锚点，封顶 24 个月） */
        const val SAMPLE_MAX_MONTHS = 24

        /** 连续空月数达此值即判定摸到频道历史边界，提前收工（容忍活跃月之间的静默月） */
        const val SAMPLE_EMPTY_MONTH_STREAK = 3

        /** 活跃日直方图取数的预算记账估值（典型 1~4 个非空月 × 2 请求；预算是安全包络
         *  不是精算——怪兽 24 月封顶 48 次，相对省下的数百页仍净减一个数量级） */
        const val CALENDAR_FETCH_COST = 8

        /** 旧路逻辑页的预算记账估值（2~6 次 GetChatHistory + ≤3 次评论级联，取保守均值） */
        const val LEGACY_PAGE_COST = 3

        /** 本地缓存白嫖单频道单次最多页数（零网络请求；上限只是防极端本地库深扫占协程） */
        const val LOCAL_GRAB_MAX_PAGES = 5

        // R165 白嫖冷却：判定纯函数与间隔常量迁 IndexCrawlGate（公共调度策略单一出处，
        // 单测可及）；本类经 IndexCrawlGate.shouldHarvestLocal 引用

        // ---- R163 智能分档参数（evict 扳机/档位参数唯一出处 = IndexTierPolicy） ----

        /** R163 单次 evict 最多收删频道数（一次别删太狠：每频道一事务，大频道几万行
         *  的 fts 逐行删除耗时秒级；存储约束触发下删一轮看一次水位，渐进收敛）。
         *  扳机（磁盘水位线/用户索引库预算）与触发线默认值见 [IndexTierPolicy]：
         *  EVICT_MIN_FREE_BYTES_DEFAULT / TieringConfig.evictIndexBudgetBytes */
        const val EVICT_MAX_CHANNELS_PER_PASS = 4

        /** [channelIndexStats] 的 caption 样本上限（剧集规整率的统计样本量；200 条 ms 级） */
        const val RICHNESS_CAPTION_SAMPLE = 200

        /** 单条目建表文本截断长度（字符）：几千字清单 caption 的搜索价值全在头部
         *  （沿用旧倒排表第二轮加固口径——FTS5 磁盘索引虽无内存压力，截断仍省一半词位存储） */
        const val MAX_INDEX_TEXT_LEN = 600

        /** 搜索召回硬顶（条）：防超高频词位的全量排序/物化长尾；深度页/即时搜索预算均在其内 */
        const val SEARCH_HARD_CAP = 2_000

        // ---- R288 相关度重排参数（BM25 轻量变体的集合统计采样；算分件=[SearchRanker]） ----

        /** 集合统计（N/avgdl/df 采样）进程内缓存 TTL（毫秒）：TTL 内多次搜索共享一次采样 */
        const val RANK_STATS_TTL_MS = 10L * 60_000L

        /** df/avgdl 采样行数（rowid 随机点查，PK B 树探测毫秒级；分块 ≤900 占位符） */
        const val RANK_SAMPLE_ROWS = 1_024

        /** 采样实取下限：低于此值=样本太薄宁可不排（时间序直通，小库/rowid 稀疏防御） */
        const val RANK_SAMPLE_MIN_ROWS = 32

        /** 词位→df 估计缓存容量（统计代次内有效；超容整表清——foldCache 同族粗粒度淘汰） */
        const val RANK_DF_CACHE_MAX = 256

        // ---------------- R293 分代分层召回 / hashtag 换轨常量 ----------------
        /** 时间分代数（量程等分；偏置窗口收窄倍数与代内早停成本的平衡点） */
        const val RECALL_SLICE_BUCKETS = 8

        /**
         * 每代召回上限的过采样倍数（⌈cap×本值/B⌉=每代 LIMIT）：防「话题年代集中」
         * 形态被均分额度饿死。最坏成本证明=min(本值×单路旧形, 一遍全表)——本值=2
         * 即最坏不超现状全表扫同阶（snap2 实测口径见 r293_notes）。
         */
        const val RECALL_SLICE_OVERSAMPLE = 2

        /** hashtag 换轨覆盖粗判的进程内缓存 TTL（done 位分钟级漂移，秒级搜索不必每拍读盘） */
        const val TAG_COVERAGE_CACHE_TTL_MS = 60_000L

        /** 历史回溯抽样：单条 SQL 频道 IN 列表上限（老设备 999 占位符预算；超出随机抽子集）。
         *  R158 两段抽样后排除键不再进 SQL（Kotlin 结果过滤），999 预算由①②两段各自独享，
         *  ②段 rowid 数被 `limit + limit×8` 封顶（≤ 约 280），远在预算内 */
        private const val SAMPLE_MAX_CHANNELS = 400

        /** 相册展开后结果总量硬顶（条）：命中条目全是大相册时的 512MB 堆护栏 */
        const val EXPAND_HARD_CAP = 4_000

        /** 邻近窗口近似展开的半径（消息数）：TG 相册成员 messageId 天然连续，
         *  albumId=0 的迁移存量命中按 ±8 消息窗口带出同组媒体（宁多勿漏口径） */
        const val ALBUM_WINDOW_RADIUS = 8

        /** 查询切段正则（空白切词；与 SearchTextKit.analyze 的 "\\s+" 切段口径一致） */
        private val SEG_SPLIT_REGEX = Regex("\\s+")
    }
}

/**
 * 深度搜索结果 Feed：一次性返回本地索引命中列表（R288 起精确/模糊/hashtag 三路统一
 * 相关度优先+时间兜底——BM25 轻量变体 [SearchRanker]，召回有界后 Kotlin 算分；
 * FTS5 模糊路=引擎 bm25），没有翻页概念。request 带 searchQuery（标识这是个搜索源），
 * 但不走网关——数据全部来自 [DeepSearchIndex]（SQLite FTS5 磁盘索引：一次查询直接物化返回，
 * 旧 searchRefs/materialize 分块物化机制已随内存倒排表一并删除）。
 * [fuzzy] = 模糊搜索开关（AppPrefs.fuzzySearch 快照）：透传给索引查询口径。
 * [onHits] = 命中上报回调（IO 线程，紧随本次 search 结果物化触发）：调用方（VM）
 * 做 R163 B4/机制③频道升档——**零额外全表扫**（R278 修复：旧复查式升档在
 * deepSearchImpl 里把 search() 无条件再跑一遍，LIKE 设备=第二个 45s 全表扫的
 * 双扫雷；改为复用本次结果上报后雷区拆除，见 r264_notes.md §建议 P2）。
 */
class DeepSearchFeedSource(
    private val index: DeepSearchIndex,
    private val query: String,
    private val fuzzy: Boolean = false,
    private val onHits: ((List<VideoItem>) -> Unit)? = null,
) : FeedSource {
    override val request: FeedRequest = FeedRequest(searchQuery = query)

    private var served = false

    override suspend fun nextPage(limit: Int): FeedPage {
        if (served) return FeedPage(emptyList(), null)
        served = true
        // search 内部已按 SEARCH_HARD_CAP 封顶 + 相册展开；调用方在 IO 调度
        // （FeedViewModel.loadMore），磁盘查询毫秒级，无需再分块物化
        val out = index.search(query, fuzzy)
        // 命中上报复用本次结果（不再二次扫描）；回调异常不炸分页
        if (out.isNotEmpty()) runCatching { onHits?.invoke(out) }
        return FeedPage(out, nextCursor = null)
    }

    override fun reset() {
        served = false
    }
}

/**
 * R202 时间窗横截面 Feed（时光机墙点格子进 Feed 的源——墙显什么，滑进去刷的
 * 就是什么）：一次性返回精炼后的横截面列表（dateEpochSec 倒序），没有翻页概念
 * ——向上滑到窗顶即「这个时刻之前」的自然结束（诚实语义：横截面本来就是一段
 * 有界的时间切片，不是无限流）。[DeepSearchFeedSource] 同款 served 闸防
 * refresh 循环重发。
 *
 * 条目 playUri=null（播放走 ensurePlayable 既有链——与深搜索结果进 Feed 完全
 * 同一条已上线链路）；「查看上下文」动作仍可用（openChannelAt 锚定频道语义）。
 */
class TimeWindowFeedSource(
    private val crossSection: List<VideoItem>,
    private val windowLabel: String,
) : FeedSource {
    override val request: FeedRequest = FeedRequest()

    /** 源名（潜进源面包屑标题用，调用方传「时光机 · 2023年8月19日」类标签） */
    val label: String get() = windowLabel

    private var served = false

    override suspend fun nextPage(limit: Int): FeedPage {
        if (served) return FeedPage(emptyList(), null)
        served = true
        return FeedPage(crossSection, nextCursor = null)
    }

    override fun reset() {
        served = false
    }
}

/**
 * R167 #话题搜索源（混合双腿覆盖）：
 *
 * ## 为什么存在
 * caption #标签点击的语义是"全 TG 频道带该标签的内容"，但 R6 老接线走服务端
 * SearchMessages(ChatListMain) 只搜**已加入聊天**——Feed 信息流大量内容来自深索引
 * 爬取的**未加入频道**，标签所在频道没加入 → 服务端零命中（"什么都搜不到"根因）。
 * 本源主路改本地深索引（[DeepSearchIndex.search] 的 hashtag 精确词模式：# 前缀+
 * 词边界，防裸子串误伤），服务端搜索（已加入聊天）作第二腿并发补位——深索引
 * 未爬到的已加入频道内容由服务端补上，双路去重合并。
 *
 * ## 分页模型（45s 设备口径的关键设计，见 r167_notes.md §1.3）
 *  - **深索引腿构造即起跑**（deepScope.async，IO 卸载）：4GB 索引 LIKE 全表扫
 *    实测 ~45s——不等 UI、不等服务端腿，点标签瞬间就开始扫；
 *  - **首页 = 服务端腿先行**（秒级/超时兜底）：用户立刻有内容可播；深索引腿若
 *    已扫完（FTS5 设备毫秒级/模拟器 4s 常快于服务端）顺手并入首屏，否则后台
 *    继续扫。**服务端零命中且深索引未扫完时挂起等深索引**——本任务主场景
 *    （标签频道未加入 → 服务端注定空 → 主路就是深索引，45s loading 配
 *    FeedScreen 加载文案"正在扫 4GB 本地索引"）；
 *  - **第二页 = await 深索引全量**（时间倒序在前=主路）+ 服务端续页；第三页起
 *    纯服务端续页直至翻尽。
 *  - 状态机不返回"空 items+非空 cursor"页（loadMore 对非聚合源的空页不再续拉，
 *    会把深索引腿饿死在第二页之外）。
 *
 * ## 去重与复滤
 *  - 源内 served(key) 集合 + VM 层 known 集/foldReposts 双保险（loadMore 既有）；
 *  - 服务端腿结果过 [SearchTextKit.hashtagHits] 复滤（服务端词搜是子串语义，
 *    复滤保证与深索引腿同一精确词口径）。
 *
 * ## 观测（R165 教训：点击链盲区曾让"零日志"无从鉴别）
 * 源内 Log.i("TGFlow") 各腿完成行 + switchSourceImpl 切源打点（R165 已入）——
 * 点击→切源→两腿返回逐层有日志，真机取证可定位死在哪一层。
 *
 * ## 已知取舍
 *  - reset() 不重启深索引腿：同词结果幂等，45s 重扫不值（索引可能长进新条目，
 *    v2 tags 表秒查后解除，挂账见 r167_notes.md §1.7）；源被切走后深扫至多
 *    再烧一轮（45s IO，低频操作可接受）；
 *  - 服务端腿 [SERVER_LEG_TIMEOUT_MS] 超时=网关层 10s（R167 已补，R45 家族口径）
 *    之外的源内双保险：两层互为冗余，防 TdLib 冻死时 loading 永真；
 *  - [onDeepHits] 回调（IO 线程）：深索引腿完成时上报命中列表，调用方（VM）做
 *    R163 B4/机制③频道升档——**零额外全表扫**（升档一律走命中上报复用本次结果：
 *    复查式升档在 LIKE 设备=第二个 45s，不可接受，见 r167_notes.md §1.3 约束①；
 *    R278 已把普通深搜也并到同款回调制，复查式路径整体拆除）。
 */
class HashtagSearchFeedSource(
    private val index: DeepSearchIndex,
    gateway: TdGateway,
    private val tag: String,
    private val onDeepHits: ((List<VideoItem>) -> Unit)? = null,
) : FeedSource {

    /** 带 # 前缀的查询标识：DeepSearchIndex.search 据此走 hashtag 精确词模式；
     * 同时沿用"一次性搜索源带 searchQuery"约定（MainActivity 空态联动与
     * FeedViewModel.currentSourceSearchQuery 的筛选复位联动都靠它） */
    override val request: FeedRequest = FeedRequest(searchQuery = "#$tag")

    private val tagFold = SearchTextKit.fold(tag)

    /** 服务端腿：复用既有搜索源（网关层 10s 超时 + 聚合回退兜底全在链上） */
    private val server = SearchFeedSource(gateway, "#$tag")

    /** 深索引腿专属作用域：nextPage 的 coroutineScope 出口不等待它（结构化并发
     * 会把页返回卡到 45s 扫完）；源弃后扫至多再烧一轮（取舍见类注释） */
    private val deepScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /** 深索引腿（构造即起跑）：失败/零命中都归空表，job 永不异常完成（await 免 try） */
    private val deepJob = deepScope.async {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val hits = runCatching { index.search("#$tag") }.getOrElse { emptyList() }
        Log.i(
            "TGFlow",
            "R167 #话题深索引腿完成：#$tag 命中 ${hits.size} 条 耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms" +
                "（LIKE 设备 4GB 全表扫 ~45s 口径；FTS5 设备毫秒级）",
        )
        if (hits.isNotEmpty()) runCatching { onDeepHits?.invoke(hits) }
        hits
    }

    init {
        // 防连点叠加闸：用户改点新标签 = 旧标签源已被 switchSource 替换（loadMore
        // 的 source !== src 闸保证旧实例不会再被 nextPage/await），旧 tag 的在飞
        // 45s 全表扫取消掉省一轮 IO；同 tag 不动（refresh/reset 同一实例场景）。
        // 唯一 await 已 cancel job 的理论路径不存在（旧实例脱离 VM 后无调用方）
        val prev = latestScan.getAndSet(tag to deepJob)
        if (prev != null && prev.first != tag && prev.second.isActive) {
            prev.second.cancel()
            Log.i("TGFlow", "R167 #话题切词：取消旧标签 #${prev.first} 的在飞深索引扫描（省一轮 45s 级 IO，防连点叠加）")
        }
    }

    private val served = HashSet<String>()
    private var serverStarted = false
    private var serverDone = false
    private var servedDeep = false
    private var finished = false

    /** 非终页游标（自造标记，仅本源状态机自读；VM 只判 null/非 null） */
    private val contCursor = "h:more"

    override suspend fun nextPage(limit: Int): FeedPage {
        if (finished) return FeedPage(emptyList(), null)
        if (!serverStarted) {
            serverStarted = true
            // 首页：服务端腿先行（源内双保险超时；网关层 10s 之上再加一层）
            val sp = serverLeg(limit)
            serverDone = sp.nextCursor == null
            val serverHits = takeUnserved(sp.items)
            // 深索引腿已扫完（快设备）→ 首屏即全量；未完 → 后台继续，第二页并入
            val deepHits = if (deepJob.isCompleted && !servedDeep) collectDeep() else emptyList()
            Log.i(
                "TGFlow",
                "R167 #话题首页：#$tag 服务端 ${serverHits.size} 条（done=$serverDone）深索引已并入 ${deepHits.size} 条",
            )
            // 服务端零命中且深索引未并入：挂起等主路（不返回空页+非空 cursor——
            // 非聚合源的空页不会再触发续拉，会把深索引腿饿死在第二页之外）
            val deepDeferred = if (serverHits.isEmpty() && deepHits.isEmpty() && !servedDeep) {
                collectDeep()
            } else {
                deepHits
            }
            finished = serverDone && servedDeep
            return FeedPage(deepDeferred + serverHits, if (finished) null else contCursor)
        }
        // 第二页起：先等深索引全量（真机主路，未并入时挂起至多 45s），再续服务端页
        val deepHits = if (servedDeep) emptyList() else collectDeep()
        val serverHits = if (serverDone) emptyList() else {
            val sp = serverLeg(limit)
            serverDone = sp.nextCursor == null
            takeUnserved(sp.items)
        }
        finished = serverDone && servedDeep
        Log.i("TGFlow", "R167 #话题续页：#$tag 深索引 ${deepHits.size} 条 服务端 ${serverHits.size} 条（done=$serverDone）")
        return FeedPage(deepHits + serverHits, if (finished) null else contCursor)
    }

    override fun reset() {
        server.reset()
        served.clear()
        serverStarted = false
        serverDone = false
        servedDeep = false
        finished = false
        // deepJob 不重启（幂等结果重放，取舍见类注释）
    }

    /** 服务端腿取页（hashtag 复滤在 takeUnserved 内）：双保险超时——网关层 10s
     *  （R167 已补）之外的源内兜底，防 TdLib 冻死时 loading 永真 */
    private suspend fun serverLeg(limit: Int): FeedPage =
        try {
            kotlinx.coroutines.withTimeout(SERVER_LEG_TIMEOUT_MS) { server.nextPage(limit) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w("TGFlow", "R167 #话题服务端腿 ${SERVER_LEG_TIMEOUT_MS}ms 超时放弃：#$tag（深索引腿为主路，不受影响）")
            serverDone = true
            FeedPage(emptyList(), null)
        }

    /** 深索引腿结果收编（首次调用起 servedDeep=true；挂起至扫描完成）：key 去重保序 */
    private suspend fun collectDeep(): List<VideoItem> {
        servedDeep = true
        // deepJob 永不异常完成（构造内 runCatching 归空表），await 免 try
        return takeUnserved(deepJob.await())
    }

    private fun takeUnserved(items: List<VideoItem>): List<VideoItem> =
        items.filter { item ->
            // 服务端腿子串语义 → hashtag 精确词复滤（与深索引腿同一把尺子）；
            // 深索引腿本就是 hashtag 模式产物，复滤幂等直通
            SearchTextKit.hashtagHits(SearchTextKit.fold(item.caption), tagFold) &&
                served.add(item.key)
        }

    companion object {
        /** 服务端腿源内双保险超时（毫秒）：网关层 10s（R45 家族口径）之外再兜一层
         *  ——两层互为冗余；5s 余量给网关回退聚合路的收尾（回退链更长于 10s 时
         *  以源内超时为准放弃本腿，主路不受影响） */
        const val SERVER_LEG_TIMEOUT_MS = 15_000L

        /** 防连点叠加闸的进程级账本：最新一次 hashtag 深扫的 (tag, job)。
         *  AtomicReference：构造可在主线程（hashtagSearchImpl）并发安全 */
        private val latestScan =
            java.util.concurrent.atomic.AtomicReference<Pair<String, kotlinx.coroutines.Job>?>()
    }
}
