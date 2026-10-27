package dev.tgfeed.core.prefs

import android.content.Context
import dev.tgfeed.BuildConfig
import dev.tgfeed.core.feed.DemandSubscription
import dev.tgfeed.core.store.HarvestDiscoveredStore
import dev.tgfeed.core.tastemap.TasteTour
import java.io.File

/** 轻量配置存储：API 凭证 + 代理设置 + 上次使用模式（应用内可改，无需重新编译） */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("tgfeed", Context.MODE_PRIVATE)

    /**
     * R432：收割发现清单独立文件的落盘目录（数据目录全 App 唯一，各处 new 的
     * AppPrefs 实例取到同一路径；见 harvestDiscoveredJson）。不可得（纯 JVM 单测
     * 的 mock Context 没挂 filesDir 等）时为 null——harvestDiscoveredJson 回落旧
     * 主 SP 键路径，行为与迁移前完全一致
     */
    private val filesDir: File? = runCatching { context.filesDir }.getOrNull()

    init {
        // 金矿 P4-B：需求订阅匹配器挂载（幂等）。本类在 App.onCreate 即被 AppContainer
        // 构造（App.kt），是进程启动最早的必经点——在此 attach 保证收割钩子首次派发前
        // 监听器已就位（收割钩子的宿主 GroupHarvester 构造参数就有 AppPrefs，
        // attach 天然先于首次派发），用户无需先打开搜索/设置页也能收到到货通知。
        // runCatching 兜底：挂载失败绝不拖累配置存储本身。
        runCatching { DemandSubscription.attach(context.applicationContext) }
    }

    var apiId: Int
        get() = sp.getInt("api_id", 0)
        set(v) = sp.edit().putInt("api_id", v).apply()

    var apiHash: String
        get() = sp.getString("api_hash", "") ?: ""
        set(v) = sp.edit().putString("api_hash", v).apply()

    /**
     * 应用内代理开关（默认开）。R244 起网关首启探测兜底：历史遗留 false 值（含数据
     * 迁移带入）在「本地常见 SOCKS5 端口探测命中」时会被自动纠正为 true 并落盘
     * （TdLibGateway.init 代理段 + App 装配的 proxyPersistHook）——TDLib 原生库
     * 不读 Android 系统 HTTP 代理，手机上装了 Clash/V2Ray 也必须显式 AddProxy
     * 才走代理，否则直连被墙（新设备登录 P0 实锤②根因）。
     */
    var proxyEnabled: Boolean
        get() = sp.getBoolean("proxy_enabled", true)
        set(v) = sp.edit().putBoolean("proxy_enabled", v).apply()

    var proxyHost: String
        get() = sp.getString("proxy_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = sp.edit().putString("proxy_host", v).apply()

    var proxyPort: Int
        get() = sp.getInt("proxy_port", 7897)
        set(v) = sp.edit().putInt("proxy_port", v).apply()

    /** "demo" / "real" / ""（未选择过，首次启动显示配置页） */
    var lastMode: String
        get() = sp.getString("last_mode", "") ?: ""
        set(v) = sp.edit().putString("last_mode", v).apply()

    /** 自动连播开关：当前条目播完后自动跳下一条（默认开） */
    var autoAdvance: Boolean
        get() = sp.getBoolean("auto_advance", true)
        set(v) = sp.edit().putBoolean("auto_advance", v).apply()

    /**
     * 音量均衡开关（B 站同款，默认开）：不同内容自动拉到一致响度。
     * 实现见 core/playback/LoudnessNormalizer（ExoPlayer 音频链自定义 AudioProcessor，
     * 3 秒学习窗测 RMS → 定增益平滑套用，按内容 key 记忆）。
     * 播放器侧逐缓冲读本值，翻转即时生效（关 = 纯透传），无需重启/重建播放器。
     */
    var volumeNormalize: Boolean
        get() = sp.getBoolean("volume_normalize", true)
        set(v) = sp.edit().putBoolean("volume_normalize", v).apply()

    /** 视频缓存水位上限（GB，默认 3）；TdLibGateway 授权后超限自动清理最旧视频文件 */
    var cacheLimitGb: Int
        get() = sp.getInt("cache_limit_gb", 3)
        set(v) = sp.edit().putInt("cache_limit_gb", v.coerceAtLeast(0)).apply()

    // ---------------- R196 存储定期清理（StorageGovernor；写入方=治理器与设置页，读取侧全部带默认） ----------------

    /**
     * 定期清理间隔（分钟，默认 45，域 15~240）：StorageGovernor 整轮清理的最小间距
     * （探针循环 5min 一拍，间隔到点才跑整轮；压力触发不受本值约束）。
     * 无设置页入口（治理节奏属内部调优），run-as 写 shared_prefs 可调。
     */
    var storageGovernIntervalMin: Int
        get() = sp.getInt("storage_govern_interval_min", 45)
        set(v) = sp.edit().putInt("storage_govern_interval_min", v.coerceIn(15, 240)).apply()

    /**
     * temp 碎片压力阈值（MB，默认 500）：下载暂存区超过即提前触发整轮清理
     * （激进口径：2min 新鲜度窗口）。0 = 关闭 temp 压力触发。
     */
    var storageTempPressureMb: Int
        get() = sp.getInt("storage_temp_pressure_mb", 500)
        set(v) = sp.edit().putInt("storage_temp_pressure_mb", v.coerceAtLeast(0)).apply()

    /**
     * 总缓存压力阈值（GB，默认 8）：TDLib 文件缓存总量超过即提前触发整轮清理。
     * 0 = 关闭总量压力触发。
     */
    var storageTotalPressureGb: Int
        get() = sp.getInt("storage_total_pressure_gb", 8)
        set(v) = sp.edit().putInt("storage_total_pressure_gb", v.coerceAtLeast(0)).apply()

    /**
     * 全载文件保留上限（条，默认 3，R195 接口）：邻居预热全载文件登记表的保留条数，
     * 超出按最久未用淘汰（活跃/在飞排除）。0 = 不启用全载淘汰（登记表只做登记）。
     */
    var storageFullLoadRetention: Int
        get() = sp.getInt("storage_full_load_retention", 3)
        set(v) = sp.edit().putInt("storage_full_load_retention", v.coerceAtLeast(0)).apply()

    /**
     * 清理日志·上次清理时刻（墙钟毫秒，0=从未）：StorageGovernor 每轮整轮清理
     * （含手动「立即清理」）收尾时落盘；设置页「存储管理」卡的清理日志行数据源。
     */
    var storageLastCleanAtMs: Long
        get() = sp.getLong("storage_last_clean_at_ms", 0L)
        set(v) = sp.edit().putLong("storage_last_clean_at_ms", v).apply()

    /** 清理日志·上次释放字节数（temp 碎片 + 全载淘汰 + 水位闸合计） */
    var storageLastCleanFreedBytes: Long
        get() = sp.getLong("storage_last_clean_freed_bytes", 0L)
        set(v) = sp.edit().putLong("storage_last_clean_freed_bytes", v).apply()

    /**
     * 清理日志·上次分项摘要（如「暂存 2890MB·48 文件 / 全载淘汰 2 条 / 水位 1200MB」）。
     * 空串 = 从未清理。损坏读取侧按从未清理展示（不崩）。
     */
    var storageLastCleanSummary: String
        get() = sp.getString("storage_last_clean_summary", "") ?: ""
        set(v) = sp.edit().putString("storage_last_clean_summary", v).apply()

    // ---------------- R208 开机风暴治理（SystemStormGate；内部调优口径，run-as 写 shared_prefs 可调，无设置页入口） ----------------

    /**
     * 开机静默期（秒，默认 300=5min，域 0~1800）：系统重启后该时长内一切后台
     * 重活（深索引爬虫/预热/存储治理首轮）持有不起跑——P0 华为平板开机风暴
     * 事故治理（bootreason=abnormal 恢复应用+爬虫 100% 占空比→watchdog 复位；
     * 实测每次开机系统级风暴 ≥5min）。0 = 关闭开机静默闸（负载闸独立生效）。
     * 默认值唯一出处=SystemStormGate.DEFAULT_BOOT_QUIET_MS。
     */
    var stormBootQuietSec: Int
        get() = sp.getInt("storm_boot_quiet_sec", (dev.tgfeed.core.td.SystemStormGate.DEFAULT_BOOT_QUIET_MS / 1000).toInt())
        set(v) = sp.edit().putInt("storm_boot_quiet_sec", v.coerceIn(0, 1800)).apply()

    /**
     * CPU 余量退避阈值（百分比，默认 85，域 0~100）：/proc/stat 两采样差分的
     * CPU 忙闲分数（含 iowait）超过即退避——D 态地板机型（事故机实测 27 个
     * 华为常驻诊断内核线程永久 D 态，loadavg 地板 27）的可靠负载信号。0 = 关闭。
     */
    var stormCpuBusyPct: Int
        get() = sp.getInt("storm_cpu_busy_pct", dev.tgfeed.core.td.SystemStormGate.DEFAULT_CPU_BUSY_PCT)
        set(v) = sp.edit().putInt("storm_cpu_busy_pct", v.coerceIn(0, 100)).apply()

    /**
     * loadavg 退避倍率（默认 0=关，域 0~64）：/proc/loadavg 1 分钟负载超过
     * max(1, 核数)×本值即退避。默认关的原因：事故机 loadavg 含 D 态地板 27
     * 永不回落，按此判会永远持有；普通设备可按需开启。
     */
    var stormLoadPerCore: Int
        get() = sp.getInt("storm_load_per_core", dev.tgfeed.core.td.SystemStormGate.DEFAULT_LOAD_PER_CORE)
        set(v) = sp.edit().putInt("storm_load_per_core", v.coerceIn(0, 64)).apply()

    /**
     * 风暴闸持有等待上限（秒，默认 600=10min，域 30~3600）：awaitClear 持续
     * 等到该上限即兜底放行（防持续高载永久锁死后台活）。
     */
    var stormMaxHoldSec: Int
        get() = sp.getInt("storm_max_hold_sec", (dev.tgfeed.core.td.SystemStormGate.DEFAULT_MAX_HOLD_MS / 1000).toInt())
        set(v) = sp.edit().putInt("storm_max_hold_sec", v.coerceIn(30, 3600)).apply()

    /** 风暴闸总开关（默认开）：kill switch，关=恒放行回到 R208 前行为 */
    var stormGateEnabled: Boolean
        get() = sp.getBoolean("storm_gate_enabled", true)
        set(v) = sp.edit().putBoolean("storm_gate_enabled", v).apply()

    // ---------------- R163 智能索引分级（B6 接线；纪律①默认全关=行为零变化） ----------------

    /**
     * 智能索引分级总开关（默认关）。关 = 全库按旧规则爬（行为与引入前完全一致）；
     * 开 = 按四档分工：关注频道全量精建、常看/高质量频道深索引、新频道先小样
     * 探测（约 100 条，内容够好自动升深索引）、没价值的频道不占存储。详见设置页
     * 「了解改了什么」说明。启动时 FeedViewModel 读入 @Volatile 运行时态，设置页
     * 翻转即时生效（下一轮枚举/派工按新配置走）。
     */
    var indexTieringEnabled: Boolean
        get() = sp.getBoolean("index_tiering_enabled", false)
        set(v) = sp.edit().putBoolean("index_tiering_enabled", v).apply()

    /**
     * 智能爬取时机开关（默认关，独立于分级总开关）。关 = 现状（退后台即可爬）；
     * 开 = 只在好时机爬：充电 + 没在使用（后台且无播放）+ 非计费网络。计量网络
     * 是硬线（任何情况不爬，省流量费用）。用户正在等的爬取（搜索命中的深挖、
     * 刚关注的频道补全）不受此闸约束。连续 24 小时没有好时机时逐级放宽条件，
     * 保证索引不会永不更新。
     */
    var smartCrawlTiming: Boolean
        get() = sp.getBoolean("smart_crawl_timing", false)
        set(v) = sp.edit().putBoolean("smart_crawl_timing", v).apply()

    /**
     * 探索新频道开关（默认开——分级开启后的推荐子配置；总开关关时本值不参与任何
     * 判定）。开 = 把约三成爬取预算留给新频道探测（每 10 次派工 3 次探索），
     * 制度性保证没关注过的好频道也有机会被发现（防"只建关注频道→搜不到新东西→
     * 永远只关注那几个"的死循环）；关 = 预算全给已知频道维护。
     */
    var indexExploreQuota: Boolean
        get() = sp.getBoolean("index_explore_quota", true)
        set(v) = sp.edit().putBoolean("index_explore_quota", v).apply()

    /**
     * 坏片降权开关（默认关，独立生效不依赖分级总开关）。死链记账始终进行
     * （记账只是记数据不改行为）；开 = 近 7 天坏片率超三成且样本足够的频道在
     * 排序中降权一半（坏链太多的频道少推、不误导口味信号）。默认关 = 排序行为
     * 与引入前完全一致。
     */
    var deadMediaDemotion: Boolean
        get() = sp.getBoolean("dead_media_demotion", false)
        set(v) = sp.edit().putBoolean("dead_media_demotion", v).apply()

    /**
     * 索引库存储预算（GB，默认 0=关）。修订令版 evict 扳机：0 = 只看磁盘水位线
     * （剩余可用空间不足时才清理，系统级兜底）；>0 = 另加一道「索引库体积超过
     * 预算即清理」的自设上限（清理的都是最低价值频道，关注/高档频道绝不清理）。
     * 存储是「关注了多少好频道」的涌现结果——不再设全库行数硬顶。转换口径：
     * 1GB = 1,073,741,824 字节（GiB）。
     */
    var evictStorageBudgetGb: Int
        get() = sp.getInt("evict_storage_budget_gb", 0)
        set(v) = sp.edit().putInt("evict_storage_budget_gb", v.coerceIn(0, 500)).apply()

    /**
     * R191 手动钉住深索引的频道集合（chatId 集合，默认空）。钉住 = 用户显式授权该
     * 频道建深度索引：档位 T0 等待遇（全量上限+头刷提频+采样闸豁免），自动评估不
     * 降档（用户意志高于丰度/互动信号）；**未关注也可钉**（钉=深索引授权，不等关注）。
     * 取消钉 = 移除集合，频道回自动评估。入口：左抽屉频道行「深度索引」按钮。
     * 存储为逗号分隔 chatId（沿用 searchGroupChatIds 既有范式——chatId 无逗号歧义）；
     * 序列化/解析纯函数在 ManualDeepIndexPart（唯一写入方），损坏读取侧按空表重建。
     */
    var deepIndexPinnedChatIds: Set<Long>
        get() = dev.tgfeed.core.feed.parseChatIdCsv(
            sp.getString("deep_index_pinned_ids", "") ?: "",
        )
        set(v) = sp.edit()
            .putString("deep_index_pinned_ids", dev.tgfeed.core.feed.chatIdCsvOf(v))
            .apply()

    /** 后台音频：切后台/锁屏后继续听当前视频声音（前台服务保活，默认关） */
    var audioBackground: Boolean
        get() = sp.getBoolean("audio_background", false)
        set(v) = sp.edit().putBoolean("audio_background", v).apply()

    // ---------------- R223b 口味星图全宇宙 ----------------

    /**
     * 口味星图「按互链关系聚拢」开关（**默认开**——用户终审 r223_notes §终审裁定 2）。
     * 星图布局在口味相似度 MDS 之外叠加固定 20 轮互链弱弹簧微扰：互相引用/转发
     * 关联的频道轻轻拉近（自然长度防塌缩、最小间距斥力防重叠——口味簇结构保留，
     * z 深度轴不动）；关闭=纯口味几何。布局在进星图时一次性计算——**翻转后下次
     * 打开星图生效**（确定性布局重算，同数据同结果）。启动时 FeedViewModel 读入
     * @Volatile 运行时态，星图装配侧进页取值。
     * R356 起唯一入口=星图内图标快捷组（设置页行退役——键/默认/写法零变化）。
     */
    var tasteLinkPerturb: Boolean
        get() = sp.getBoolean("taste_link_perturb", true)
        set(v) = sp.edit().putBoolean("taste_link_perturb", v).apply()

    /**
     * R314 口味星图「互链连线」开关（**默认关**——用户走查拍板：恒星间实体白线
     * 出戏，全景不再铺白线网）。关=仅焦点星（选中恒星）的互链画细弱引力弧
     * （政策件 core/tastemap/TasteLinkLinesPolicy）；开=旧行为（全图两遍制实体
     * 直线网）。星图装配侧读舞台态——R356 起唯一入口=星图内图标快捷组（翻转即
     * 写键+舞台态，画面即时生效；旧「进页一次读」随设置页入口退役；键/默认/
     * 写法零变化）。
     */
    var tasteLinkLines: Boolean
        get() = sp.getBoolean("taste_link_lines", false)
        set(v) = sp.edit().putBoolean("taste_link_lines", v).apply()

    /**
     * R314 口味星图「星座」开关（**默认开**——星轨=星座合并去重：R206 今晚星轨
     * （暗光路+流星重播）与 R234 星座轨迹（亮渐变光路）归一为唯一星座层；流星
     * 自 R206 起是常驻氛围件，默认开=零在场性回退）。星图内图标快捷组翻转即写本键
     * （即时生效）——R356 起唯一入口（旧右上星标钮并入图标组，设置页行退役；
     * 键/默认/写法零变化）。延时摄影期光标未到「今天」时星座层
     * 不提前泄底（门径语义保留）。
     */
    var tasteConstellation: Boolean
        get() = sp.getBoolean("taste_constellation", true)
        set(v) = sp.edit().putBoolean("taste_constellation", v).apply()

    /**
     * R303c 口味星图「涌现布局」开关（**默认开**——用户亲批主线工程「星图换涌现
     * 布局」；终审拍板项⑦ V3 常驻降级位=关）。开=频道位置由四力（互链/tag 指纹/
     * 口味距离的弹簧+斥力+衰变）平衡涌现（装配期一次性定形，60fps 帧路径零新增
     * 成本）；关=经典 V3 球面布局（口味角+体量分位）。星图装配侧进页取值——
     * **翻转后下次打开星图生效**（与 [tasteLinkPerturb] 同口径；缓存按开关口径
     * 作废重算）。R356 起唯一入口=星图内图标快捷组（翻转即写键+vm 运行时同步，
     * 设置页行退役；键/默认/写法零变化）。
     */
    var tasteEmergentLayout: Boolean
        get() = sp.getBoolean("taste_emergent_layout", true)
        set(v) = sp.edit().putBoolean("taste_emergent_layout", v).apply()

    /**
     * R319 口味星图「宇宙底幕」开关（**默认开**——r315 S7 本包唯一新键，一键管
     * 底幕一层=CMB 余辉底纹+奥伯斯视界壳+「光在路上」暗点：**要纯黑就全黑**=
     * 用户原话语义；关=整层缺席回纯黑宇宙）。纯渲染件（非布局重算类——回页
     * R356 起唯一入口=星图内图标快捷组：翻转即写键+舞台态，画面即时生效；旧
     * 「星图内零第二入口=R314 删重复入口」定式随入口内迁反转——单入口原则
     * 不变（入口从设置页搬进星图，仍只有一处；键/默认/写法零变化）。
     */
    var tasteCmb: Boolean
        get() = sp.getBoolean("taste_cmb", true)
        set(v) = sp.edit().putBoolean("taste_cmb", v).apply()

    // ---------------- R277 星图渲染引擎（OpenGL P0 骨架——政策件=ui/gl/GlEnginePolicy） ----------------

    /**
     * 星图渲染引擎三态（**默认 0=自动**）：0=自动（GL 可用即 GL，崩溃计数超闸自动
     * 回退 Canvas）/1=Canvas（强制旧渲染器）/2=OpenGL（强制 GL——不受计数闸拦，
     * 但 ES3 探针闸仍拦）。值域/判定矩阵唯一出处=ui/gl/GlEnginePolicy（纯 Kotlin，
     * JVM 单测锚定——core 不反向依赖 ui，值域 0/1/2 与默认 0 在此自含声明）。
     * 设置页「发现新内容」卡三态行读写；星图槽位装配侧进页取值。
     */
    var renderEngine: Int
        get() = sp.getInt("render_engine", 0)
        set(v) = sp.edit().putInt("render_engine", v.coerceIn(0, 2)).apply()

    /** R277 GL 崩溃计数器（帧内异常+会话旗标决算两源累加；≥闸值自动态永落 Canvas） */
    var glCrashCount: Int
        get() = sp.getInt("gl_crash_count", 0)
        set(v) = sp.edit().putInt("gl_crash_count", v.coerceAtLeast(0)).apply()

    /**
     * R277 GL 会话旗标（**commit 同步落盘**——native 崩溃覆盖闸）：GL 渲染开始置
     * true、干净退出/回退清 false；冷启动见 true=上次进程死在 GL 渲染中 →
     * 崩溃计数 +1（政策件 GlEnginePolicy.crashCountOnColdStart）。
     */
    var glSessionActive: Boolean
        get() = sp.getBoolean("gl_session_active", false)
        set(v) {
            // commit 同步落盘（崩溃旗标必须赶进程死前——apply 的异步刷盘来不及）
            sp.edit().putBoolean("gl_session_active", v).commit()
        }

    /** R277 ES3 探针缓存（0=未探/1=可用/2=不可用——探针失败落盘，重启免复探） */
    var glEs3Probe: Int
        get() = sp.getInt("gl_es3_probe", 0)
        set(v) = sp.edit().putInt("gl_es3_probe", v.coerceIn(0, 2)).apply()

    // ---------------- R271 星图开局引导运镜（频控落盘——政策件 core/tastemap/TasteTour） ----------------

    /**
     * 开局引导巡航已自动播放次数（**硬顶=TasteTour.MAX_AUTO_PLAYS=2**——频控=防打扰
     * 语义：开播即计，用户手势中断也算播过）。判定纯函数 TasteTour.shouldAutoPlay。
     */
    var tasteTourCount: Int
        get() = sp.getInt("taste_tour_count", 0)
        set(v) = sp.edit().putInt("taste_tour_count", v.coerceIn(0, TasteTour.MAX_AUTO_PLAYS)).apply()

    /**
     * 上次自动巡航时的在图星数（「星数量大幅增长后再播一次」的基线——第二次开播闸=
     * 当前星数 ≥ 基线×TasteTour.GROWTH_FACTOR；首播时落盘）。
     */
    var tasteTourStarBase: Int
        get() = sp.getInt("taste_tour_star_base", 0)
        set(v) = sp.edit().putInt("taste_tour_star_base", v.coerceAtLeast(0)).apply()

    /**
     * 巡航开播落账（**commit 同步原子双写**——计数+基线同一事务；16384 实锤：
     * apply() 异步刷盘在进程被秒杀的窗口丢写（计数 0→1 落账后 58ms 被 force-stop，
     * 重启读回仍是 0=防打扰闸被击穿），glSessionActive 同款教训同修法）。
     */
    fun bumpTasteTour(starBase: Int) {
        sp.edit()
            .putInt("taste_tour_count", (tasteTourCount + 1).coerceIn(0, TasteTour.MAX_AUTO_PLAYS))
            .putInt("taste_tour_star_base", starBase.coerceAtLeast(0))
            .commit()
    }

    /**
     * R180 允许音频与视频同时播放（默认关 = 互斥）：
     * - 关（默认）：互斥语义——起音频停视频、起视频停音频（由系统音频焦点承载，
     *   双方播放器 handleAudioFocus=true）；且页面切换暂停（R180 导航闸）对音视频
     *   无例外都生效。
     * - 开：音频与视频可同时各自出声（共享音频播放器退出系统焦点竞争，
     *   [dev.tgfeed.AppContainer.setAllowAudioVideoSimultaneous] 即时切换）；
     *   且音频内容豁免页面切换暂停——背景听模式：切到别的页面音频继续（"播客式"
     *   后台播），视频照常暂停。判定矩阵单一来源 core/playback/NavPlaybackPolicy。
     */
    var allowAudioVideoSimultaneous: Boolean
        get() = sp.getBoolean("allow_audio_video_simultaneous", false)
        set(v) = sp.edit().putBoolean("allow_audio_video_simultaneous", v).apply()

    /**
     * 后台行为（onStop 时 MainActivity 分派）：
     * 0=继续播放（合规承载是 audioBackground=true 的前台服务保活；R158 起未开启
     *    后台音频时该档按暂停兜底，见 [effectiveBackgroundPause]——旧"无承载裸续播"
     *    是后台电量杀手）
     * 1=暂停当前播放
     * 2=自动把当前条目弹成悬浮窗
     */
    var backgroundAction: Int
        get() = sp.getInt("background_action", 0)
        set(v) = sp.edit().putInt("background_action", v.coerceIn(0, 2)).apply()

    /**
     * R158 退后台"实际按暂停处理"判定（MainActivity onStop/onStart 与 PlayerPool
     * 后台闸的统一口径）：
     *  - 1=暂停档直判；
     *  - 0=继续播放档在 [audioBackground]=false 时兜底按暂停——该档"续播"的唯一合规
     *    承载是 audioBackground=true 的前台服务通知；未开启时退后台是无 FGS 无通知
     *    无感知的裸续播，R155 体检实锤 CPU 12~124%、网络 ~220KB/s 持续（视频在黑屏
     *    里继续解码+下载+出声），纯电量杀手。这是改默认行为：旧行为什么都不做；
     *    回前台由 onStart 按同口径自动续播"被闸暂停的"内容（与 1 档同链路）
     *  - 2=弹悬浮窗档不在此判（悬浮窗用户可见，属继续播放场景）
     */
    val effectiveBackgroundPause: Boolean
        get() = backgroundAction == 1 || (backgroundAction == 0 && !audioBackground)

    /** 新手引导：Feed 页首启 */
    var firstRunFeed: Boolean
        get() = sp.getBoolean("first_run_feed", true)
        set(v) = sp.edit().putBoolean("first_run_feed", v).apply()

    /** 新手引导：分屏页首启 */
    var firstRunGrid: Boolean
        get() = sp.getBoolean("first_run_grid", true)
        set(v) = sp.edit().putBoolean("first_run_grid", v).apply()

    /**
     * R230 追加4 悬浮小窗「播完自动连播」（默认开）：小窗视频播完后自动接队列下一条
     * 并开播（跟主 feed autoAdvance 同语义）。开关入口在小窗自身（App 内浮窗控制条
     * 「连播」钮 / 系统悬浮窗右上常驻组），用户拍板口径=可控开关而非强制行为。
     * 队列供给：分屏悬浮落区弹出时带上分屏池后续若干条（App.container.floatingQueues，
     * 会话内存）；无队列的窗（Feed 侧弹出等）播完维持原行为。
     */
    var floatingAutoAdvance: Boolean
        get() = sp.getBoolean("floating_auto_advance", true)
        set(v) = sp.edit().putBoolean("floating_auto_advance", v).apply()

    /** 弹幕开关：频道评论以弹幕形式滚过视频画面（默认开） */
    var danmakuEnabled: Boolean
        get() = sp.getBoolean("danmaku_enabled", true)
        set(v) = sp.edit().putBoolean("danmaku_enabled", v).apply()

    /**
     * R175 频道主页媒体墙的封面大小偏好（dp，pinch 列数档位的落盘载体）：
     * 双指捏合换档后写入该档代表格子宽，下次进频道主页保持用户选择——
     * 用户原话「有的用户喜欢密集、有的喜欢封面大」，存「格子多大」而非「几列」，
     * 跨设备/跨屏宽口径自洽（密度无关、列数按当前屏宽涌现）。
     * 0 = 从未捏过 → 调用方回落 R171 自适应默认锚（MediaWallCellMin；此处不硬编码
     * 105，防常量漂移双源）。档位换算/夹域纯函数见 ui/Responsive.kt（R175 节）。
     */
    var mediaWallCellPref: Float
        get() = sp.getFloat("media_wall_cell_pref", 0f)
        set(v) = sp.edit().putFloat("media_wall_cell_pref", v).apply()

    /**
     * R174 捏合清屏教学气泡：true = 已展示过（全局一次性——zoom 首过 1.15 时弹
     * 「双指放大已清屏 · 轻点任意处退出」胶囊）。设置页「重新显示手势操作引导」
     * 复位该 flag 后，下次首过阈值会再弹一次（提案 §3.2 教学行）。
     */
    var pinchCleanGuideShown: Boolean
        get() = sp.getBoolean("pinch_clean_guide_shown", false)
        set(v) = sp.edit().putBoolean("pinch_clean_guide_shown", v).apply()

    /**
     * 已看内容彻底过滤（默认开）：开启时聚合 Feed（Aggregate/WeightedAggregate）
     * 在 loadMore 过滤链中直接剔除观看历史里的条目，不再只是混排降权 ×0.2；
     * 关闭时回落到 SEEN_PENALTY 降权行为。
     * 关注流（R176 起）：同一开关由 FollowingFeedSource 源内消费（判重闸原料，
     * 分层施闸——FRESH 严剔/RECYCLE 放宽为间隔回收），不再走 loadMore 事后滤。
     * 收藏夹/稍后再看/历史等本地源与单频道/搜索源不受此开关影响。
     */
    var excludeWatched: Boolean
        get() = sp.getBoolean("exclude_watched", true)
        set(v) = sp.edit().putBoolean("exclude_watched", v).apply()

    /** 内容类型开关：Feed 中是否出现视频（默认开） */
    var showVideos: Boolean
        get() = sp.getBoolean("show_videos", true)
        set(v) = sp.edit().putBoolean("show_videos", v).apply()

    /** R413 关键词屏蔽：是否同时匹配频道名（默认关——只匹配标题/caption/tag） */
    var muteMatchChannelTitle: Boolean
        get() = sp.getBoolean("mute_match_channel_title", false)
        set(v) = sp.edit().putBoolean("mute_match_channel_title", v).apply()

    /** R415 分屏格子按内容宽高比按行装箱（默认开；关 = 回退旧等分流式格位） */
    var gridJustifiedRows: Boolean
        get() = sp.getBoolean("grid_justified_rows", true)
        set(v) = sp.edit().putBoolean("grid_justified_rows", v).apply()

    /** 内容类型开关：图片（默认开） */
    var showPhotos: Boolean
        get() = sp.getBoolean("show_photos", true)
        set(v) = sp.edit().putBoolean("show_photos", v).apply()

    /** 内容类型开关：音频（默认开） */
    var showAudio: Boolean
        get() = sp.getBoolean("show_audio", true)
        set(v) = sp.edit().putBoolean("show_audio", v).apply()

    /** 自动深度索引：登录后后台自动建/续全部频道索引（断点续传；默认开） */
    var autoDeepIndex: Boolean
        get() = sp.getBoolean("auto_deep_index", true)
        set(v) = sp.edit().putBoolean("auto_deep_index", v).apply()

    /**
     * 视频清晰度全局偏好：目标高度档（1080/720/480…），0=自动（跟随主档，默认）。
     * 功能面板"清晰度"行写入；VideoPage 起播时按就近匹配选档（R122：≤偏好最高档，
     * 偏好低于全部档位时取最低档；变体高度是真实像素值，跨视频精确相等大概率不命中）
     */
    var qualityHeight: Int
        get() = sp.getInt("quality_height", 0)
        set(v) = sp.edit().putInt("quality_height", v.coerceAtLeast(0)).apply()

    /**
     * 智能降档总开关（默认开）：计量网络（移动数据等按流量计费链路）或系统省电模式下——
     *  1. 起播清晰度自动降一档（1080→720 / 720→480 / 自动(0)→720；480 及以下已触底不降）；
     *  2. 预载深度 -1（最低 1）。
     * 状态由 FeedViewModel 以 StateFlow 暴露（networkMetered/batterySaver/smartQuality），
     * 消费点在 VideoPage 起播选档与 FeedViewModel.preloadAheadFor；设置页暂无开关入口（预留）。
     */
    var smartQuality: Boolean
        get() = sp.getBoolean("smart_quality", true)
        set(v) = sp.edit().putBoolean("smart_quality", v).apply()

    /**
     * 第三方全网搜索机器人的用户名（不含 @，默认 jisou）。
     * 原理：这类机器人（极搜/soso 等）用爬虫账号遍历公开频道自建搜索引擎，
     * 私聊发关键词即回复 t.me 资源链接列表；Feed 搜索为空时的一键兜底入口。
     * 空白输入读取侧回落默认值，避免清空输入框后功能失效。
     */
    var searchBotUsername: String
        get() = sp.getString("search_bot_username", null).takeUnless { it.isNullOrBlank() } ?: "jisou"
        set(v) = sp.edit().putString("search_bot_username", v.trim().removePrefix("@")).apply()

    /**
     * 搜索机器人备用列表（逗号分隔用户名，不含 @，默认 "jisou,soso"）。
     * searchViaBot 主机器人超时/无结果时按序轮换，最多试 2 个备用；
     * 网关侧负责与主机器人去重（忽略大小写）、各项去空白去 @。
     * 空白输入读取侧回落默认值，避免清空后轮换功能失效。
     */
    var searchBotFallbacks: String
        get() = sp.getString("search_bot_fallbacks", null).takeUnless { it.isNullOrBlank() } ?: "jisou,soso"
        set(v) = sp.edit().putString("search_bot_fallbacks", v.trim()).apply()

    /**
     * 关键词订阅词表（逗号分隔的多个词，默认空）。
     * FeedViewModel.showKeywordFeed 切源时解析（兼容中文逗号），
     * 设置页"关键词订阅"输入框即时写入。
     */
    var keywordSubscriptions: String
        get() = sp.getString("keyword_subscriptions", "") ?: ""
        set(v) = sp.edit().putString("keyword_subscriptions", v.trim()).apply()

    /**
     * 追更通知开关（默认开）：订阅频道有新内容时发系统通知。
     * 项目无 WorkManager 依赖，由 FeedViewModel 的 viewModelScope 常驻轮询
     * （每 15 分钟一次，进程存活期间有效）驱动。
     */
    var notifyNewContent: Boolean
        get() = sp.getBoolean("notify_new_content", true)
        set(v) = sp.edit().putBoolean("notify_new_content", v).apply()

    // ---------------- R63 每日 digest 推送（core/digest/DailyDigest） ----------------

    /**
     * 每日 digest 通知总开关（默认开）：订阅频道当天有新内容时，傍晚发一条系统通知
     * （"你追的频道今天更新了 N 条"），点了拉起 app。
     * 设置页 UI 留待后续接（本任务不动设置页），本项先落 prefs 层供开关读取。
     */
    var digestEnabled: Boolean
        get() = sp.getBoolean("digest_enabled", true)
        set(v) = sp.edit().putBoolean("digest_enabled", v).apply()

    /**
     * digest 每日发送闸点（本地小时，0-23，默认 20 = 傍晚 20:00）：
     * 当天时间过闸点才允许发；app 启动补查 + 进程内睡到闸点自查两路驱动。
     */
    var digestHour: Int
        get() = sp.getInt("digest_hour", 20)
        set(v) = sp.edit().putInt("digest_hour", v.coerceIn(0, 23)).apply()

    /**
     * 最近一次 digest 实际发出的本地日期（yyyy-MM-dd，空 = 从未发过）：
     * 幂等闸——值等于今天 = 当日已发，重复触发只打判定日志不重发；
     * 只在通知真正发出后落盘（无新内容/无权限时不落，保留补报机会）。
     */
    var lastDigestDate: String
        get() = sp.getString("last_digest_date", "") ?: ""
        set(v) = sp.edit().putString("last_digest_date", v).apply()

    // ---------------- R392 播放编排三期(#6① 晨间定时开台,FeedPlayChainPart 晨间循环) ----------------

    /**
     * 晨间定时开台总开关(默认 **false**):每日闸点自动生成今日单并开台播单。
     * 判词(与 digest 默认开分立):digest=通知弱动作,默认开可接受;晨间开台=
     * 自动开播强意图动作,主动开通才对——默认关,设置口=播放台列表(QueueStations
     * Screen 晨间定时行)。
     * 调度=进程内协程闸点循环(DailyDigest 判词,VM viewModelScope——r392b_notes
     * §1.4/§3.2):零新后台常驻、零新权限面;已知局限=app 不开/进程被杀则当日
     * 不触发,下次启动补查兜底(迟到窗口内)。
     */
    var morningStationEnabled: Boolean
        get() = sp.getBoolean("morning_station_enabled", false)
        set(v) = sp.edit().putBoolean("morning_station_enabled", v).apply()

    /**
     * 晨间闸点(每日的分钟数 0..1439,默认 450=7:30):「每日 HH:mm」的存储形态
     * (显示层拆 HH:mm;分钟粒度=电台晨间语义够用)。改设置即时重挂循环
     * (setMorningStationAlarmImpl,sleepJob 重挂同款纪律)。
     */
    var morningStationMinuteOfDay: Int
        get() = sp.getInt("morning_station_minute_of_day", 450)
        set(v) = sp.edit().putInt("morning_station_minute_of_day", v.coerceIn(0, 1439)).apply()

    /**
     * 最近一次晨间开台真实开成的本地日期(yyyy-MM-dd,空=从未):幂等闸——
     * 值等于今天=当日已开,重复触发只打判定日志;**只在今日台真正生成并开台后
     * 落盘**(原料缺空态/超时未知不落,补报机会留给下次触发——digest「发出才落」
     * 同纪律)。
     */
    var lastMorningStationDate: String
        get() = sp.getString("last_morning_station_date", "") ?: ""
        set(v) = sp.edit().putString("last_morning_station_date", v).apply()

    // ---------------- R203 那年今日推送（core/onthisday/OnThisDayPush） ----------------

    /**
     * 那年今日推送总开关（默认开）：周年日有存货时晨间发一条回忆杀系统通知，
     * 点进直开 TL-2 页。与 digest 同族但独立渠道（系统设置可单独关）。
     */
    var onThisDayPushEnabled: Boolean
        get() = sp.getBoolean("on_this_day_push_enabled", true)
        set(v) = sp.edit().putBoolean("on_this_day_push_enabled", v).apply()

    /**
     * 那年今日推送闸点（本地小时，0-23，默认 10 = 晨间回忆杀时段；与 digest 的
     * 傍晚 20 点错开，避免同刻双通知叠抽屉）。当天时间过闸点才允许发。
     */
    var onThisDayPushHour: Int
        get() = sp.getInt("on_this_day_push_hour", 10)
        set(v) = sp.edit().putInt("on_this_day_push_hour", v.coerceIn(0, 23)).apply()

    /**
     * 最近一次那年今日推送实际发出的本地日期（yyyy-MM-dd，空 = 从未发过）：
     * 幂等闸（「每年今日至多 1 条」的当日档）——值等于今天 = 当日已发；
     * 只在通知真正发出后落盘（无存货/索引未就绪/无权限时不落，保留补报机会）。
     */
    var lastOnThisDayPushDate: String
        get() = sp.getString("last_on_this_day_push_date", "") ?: ""
        set(v) = sp.edit().putString("last_on_this_day_push_date", v).apply()

    /**
     * R215 ② feed 那年今日卡最近注入的本地日期（yyyy-MM-dd，空 = 从未注入）：
     * 「每天至多注入一张」幂等闸——打开 app 时值等于今天 = 当天已注入，不再注；
     * 只在卡片真正成卡（当年今日有 ≥2 条个人观看记忆）时落盘，无记忆不落
     * （保留后续会话补试机会——记忆历史稳定，当天重试结果相同，成本一次盘读）。
     */
    var lastOnThisDayCardDate: String
        get() = sp.getString("last_on_this_day_card_date", "") ?: ""
        set(v) = sp.edit().putString("last_on_this_day_card_date", v).apply()

    /**
     * WiFi 追更预载开关（默认开）：App 前台存活期间，网络从计量（移动数据）切到
     * 非计量（WiFi）时，自动扫描订阅频道 24h 内未看的新视频并逐条下载
     * （单轮 ≤20 条，串行低优），到家连上 WiFi 即把追更内容备好、离线可刷。
     * 项目无 WorkManager 依赖，由 FeedViewModel 监听 networkMetered 状态流的跳变沿驱动。
     */
    var wifiPrefetch: Boolean
        get() = sp.getBoolean("wifi_prefetch", true)
        set(v) = sp.edit().putBoolean("wifi_prefetch", v).apply()

    /**
     * 诊断通道开关（R51，默认开）：开启后 App 在局域网常驻一个只读 HTTP 端点
     * （core/diag/DiagServer，固定 18963 起、冲突 +1 重试），开发者随时
     * curl http://<设备IP>:<port>/diag 拉取设备信息/熔断表/崩溃日志/最近 logcat。
     * 端点只读（仅 GET /diag 出快照，其余 404），不接收任何写入。
     */
    var diagServerEnabled: Boolean
        get() = sp.getBoolean("diag_server_enabled", true)
        set(v) = sp.edit().putBoolean("diag_server_enabled", v).apply()

    /** R420 后台定时同步间隔（分钟；0=关闭，可选 15/30/60，默认 30；非法值由 BackgroundSyncPolicy 归一） */
    var backgroundSyncMinutes: Int
        get() = sp.getInt("bg_sync_minutes", 30)
        set(v) = sp.edit().putInt("bg_sync_minutes", v).apply()

    /**
     * 通知权限是否已问过（R52，默认 false）：Android 13+ 的 POST_NOTIFICATIONS
     * 只在首次冷启动弹一次系统权限框——拒绝后不再反复弹（每次冷启动都弹会把
     * 启动页盖住，实测拖慢"首内容可见"感知）；想开可走系统设置。
     */
    var notifPermAsked: Boolean
        get() = sp.getBoolean("notif_perm_asked", false)
        set(v) = sp.edit().putBoolean("notif_perm_asked", v).apply()

    /** 摇一摇换一批开关（默认开）：Feed 页摇手机刷新当前 Feed；分屏页不启用 */
    var shakeToRefresh: Boolean
        get() = sp.getBoolean("shake_to_refresh", true)
        set(v) = sp.edit().putBoolean("shake_to_refresh", v).apply()

    /**
     * R195 WiFi 激进预载开关（**默认开**，用户拍板）：
     * - 开（默认）：非计量网络（WiFi）下观看时，起播稳定 5s 后由网关侧
     *   AggressivePreloadManager 以低优先级（TDLib p8，泵窗口 32 恒高）把
     *   **当前在播条目整文件**后台下满（带宽跑满、拖动/回看零等待）；当前条整载
     *   完成后继续预载下 1~2 条（先窗口级前缀后整文件，同受本开关与计量守卫
     *   约束）。seek 抢占闸（R166）对整载一视同仁：跳变查询先取消整载重锚，
     *   稳定后自动重启（整载可抢占可重启=红线）。
     * - 关：回落精瘦窗口模式（现行行为：泵只拉播放位前方 16MB 前瞻窗口）。
     * - 计量网络（移动数据等）：无论开关与否整载一律不发（NetworkMeteredBus
     *   守卫），同样回落精瘦窗口模式。
     * 消费点：网关侧 1Hz 决策循环每拍直读（设置页翻转 ≤1s 生效，无需重启）。
     */
    var aggressivePreload: Boolean
        get() = sp.getBoolean("aggressive_preload", true)
        set(v) = sp.edit().putBoolean("aggressive_preload", v).apply()

    /**
     * 模糊搜索开关（默认关 = 精确匹配）：
     *  - 关（精确）：本地搜索走"fold 归一后按空格切段、各段 AND 连续子串"口径——
     *    "三体 小说" = 同时含"三体"和"小说"（而不是只含"三体"的"三体电视剧"也算中）；
     *    无空格查询 = 连续字符串精确匹配；
     *  - 开（模糊）：启用 SearchTextKit.analyze 的模糊全家桶——CJK bigram 词位覆盖、
     *    拉丁词编辑距离 ≤1 容错、同义词组扩展。
     * 注意：简繁/全角/大小写归一（SearchTextKit.fold）不算模糊，两种模式常开。
     * 搜索页类型 tab 行右端"模糊"chip 读写本项；切换后已发起的搜索按新口径立即重搜。
     */
    var fuzzySearch: Boolean
        get() = sp.getBoolean("fuzzy_search", false)
        set(v) = sp.edit().putBoolean("fuzzy_search", v).apply()

    /**
     * R371 搜索结果排序档（跨会话记忆，与 fuzzySearch 同模式）：
     * 存 [dev.tgfeed.core.feed.SearchSortOrder] 的 name（RELEVANCE/NEWEST/CHANNEL_HEAT/
     * DURATION），缺省 RELEVANCE=现状混排（默认档负锚：不改变既有本地在前+服务端
     * searchScore 序）。反解经 SearchSortOrder.fromName（未知/损坏值回落默认档不崩）。
     * 唯一读写方=搜索页排序 chips（单源纪律，禁止旁路第二入口）。
     */
    var searchSortOrder: String
        get() = sp.getString("search_sort_order", "RELEVANCE") ?: "RELEVANCE"
        set(v) = sp.edit().putString("search_sort_order", v).apply()

    // ---------------- 需求订阅（金矿 P4-B：搜不到的东西出现了就喊我，core/feed/DemandSubscription） ----------------

    /**
     * 需求订阅词表（JSON 数组字符串，DemandSubscription 唯一写入方）：
     * 用户在搜索无结果时一键订阅该词；收割管道每轮入库的新链接命中订阅词即发系统通知。
     * 用 JSON 数组而非逗号分隔：搜索词本身可能含逗号/中文逗号（"三体,小说"是合法查询），
     * JSON 转义天然免疫分隔符歧义。损坏时读取侧按空表重建（不崩）。
     * 随数据迁移包走（BackupManager 全量打包 shared_prefs，tgfeed.xml 内含本项）。
     */
    var demandSubscriptionsJson: String
        get() = sp.getString("demand_subscriptions_json", "") ?: ""
        set(v) = sp.edit().putString("demand_subscriptions_json", v).apply()

    /**
     * 需求订阅·到货通知总开关（默认开）：关闭后收割命中订阅词静默跳过（不记去重，
     * 重新开启后下轮收割可补报）。设置页"需求订阅"卡内开关。
     */
    var demandNotifyEnabled: Boolean
        get() = sp.getBoolean("demand_notify_enabled", true)
        set(v) = sp.edit().putBoolean("demand_notify_enabled", v).apply()

    /**
     * 已通知去重表（JSON 数组字符串，"fold订阅词|链接" 对，DemandSubscription 唯一写入方）：
     * 同一链接对同一订阅词只喊一次（收割每 15 分钟一轮，同一 (词,链接) 会反复入库累 hits，
     * 不去重就是每轮轰炸）。有界 LRU（DemandSubscription.MAX_NOTIFIED，超出掐最老），
     * 淘汰代价只是偶发重复通知，可接受。
     */
    var demandNotifiedJson: String
        get() = sp.getString("demand_notified_json", "") ?: ""
        set(v) = sp.edit().putString("demand_notified_json", v).apply()

    // ---------------- 资源搜索群收割（GroupHarvester，全程只读，绝不发消息） ----------------

    /**
     * 搜索群收割总开关（默认开）：后台轮询"发关键词→bot 回资源链接"类资源搜索群，
     * 把 bot 应答里的 t.me 链接沉淀进发现图谱（group_search 边）与答案库（answer_store.json）。
     * 全程只读（GetChatHistory/GetUser/SearchPublicChat），绝不发任何消息；
     * 关闭后 GroupHarvester 主循环空转，零网络请求。
     */
    var groupHarvestEnabled: Boolean
        get() = sp.getBoolean("group_harvest_enabled", true)
        set(v) = sp.edit().putBoolean("group_harvest_enabled", v).apply()

    /**
     * 目标搜索群 chatId 清单（逗号分隔，默认只含已实锤的"超级搜索 搜万物看万片【全网资源】"）。
     * chatId 为 TDLib 内部长整型（超级群为 -100… 负数）；发现页/深链打开群时可拿到。
     * 空白 = 不收割任何群（等价于关闭但保留开关语义）。
     */
    var searchGroupChatIds: String
        get() = sp.getString("search_group_chat_ids", null) ?: "-1003031746047"
        set(v) = sp.edit().putString("search_group_chat_ids", v.trim()).apply()

    /**
     * 群内应答 bot 用户名白名单（逗号分隔，不含 @，默认 jisou 系已知搜索 bot）。
     * 命中白名单的 sender 免 GetUser 直接采信（省请求）；
     * 白名单外 sender 在 [harvestTrustAllBots] 开启时经 GetUser 实证 UserTypeBot 后采信。
     * 注意：白名单是"快速通道"而非安全边界——真正的归属过滤是 replyTo 配对
     * （bot 回复必须指向群内人类关键词消息才入库）。
     */
    var harvestBotUsernames: String
        get() = sp.getString("harvest_bot_usernames", null)
            ?: "jisou,soso,jisou2,jisou3,jisou123bot,tbbsou,tbbsoso,sososo"
        set(v) = sp.edit().putString("harvest_bot_usernames", v.trim()).apply()

    /**
     * 信任白名单外 bot（默认开）：群内带 t.me 链接且 replyTo 指向人类文本消息的 sender，
     * 经 GetUser 确认为 UserTypeBot 后也按应答 bot 采信（结果进程内缓存，每 sender 一次请求）。
     * 关闭 = 严格白名单模式，名单外 sender 一律不采（可能收不到任何应答对）。
     */
    var harvestTrustAllBots: Boolean
        get() = sp.getBoolean("harvest_trust_all_bots", true)
        set(v) = sp.edit().putBoolean("harvest_trust_all_bots", v).apply()

    /**
     * 收割轮询间隔（分钟，默认 15）：每个周期对每个目标群做增量拉取（1~3 页）。
     * 实际节奏同时受 [floodHarvestPerHour] 预算硬闸约束——预算耗尽时本轮直接收工。
     */
    var groupHarvestPollMinutes: Int
        get() = sp.getInt("group_harvest_poll_minutes", 15)
        set(v) = sp.edit().putInt("group_harvest_poll_minutes", v.coerceIn(5, 120)).apply()

    /**
     * flood 预算·收割车道（次/小时，默认 60）：GetChatHistory 页拉取与 GetUser 实证
     * 共用同一滑动窗口记账，耗尽即停（静默降级，下小时自动恢复）。
     * 稳态估算：1 群 × 15min 轮询 ≈ 4 页/小时，60 的默认值够 ~10 个群用。
     */
    var floodHarvestPerHour: Int
        get() = sp.getInt("flood_harvest_per_hour", 60)
        set(v) = sp.edit().putInt("flood_harvest_per_hour", v.coerceIn(6, 300)).apply()

    /**
     * flood 预算·查询车道（次/小时，默认 30）：searchViaBot 每个候选机器人一次查询记 1 次
     * （含发送关键词 + 轮询收割 + 翻页的整个会话）。耗尽时查询静默降级为空结果——
     * 防风控优先于单次搜索成功率。
     */
    var floodQueryPerHour: Int
        get() = sp.getInt("flood_query_per_hour", 30)
        set(v) = sp.edit().putInt("flood_query_per_hour", v.coerceIn(3, 120)).apply()

    /**
     * flood 预算·深度索引爬取车道（次/小时，R46 默认 2000，**R255 提默认 6000**）：
     * 清扫页（SearchChatMessages）/锚点请求按 1 次实记，旧路逻辑页按 [DeepSearchIndex]
     * 内估值常数记账；滑动窗口 1 小时。
     *
     * R255 提默认的依据（用户令「给索引数据提速，手段不限」）：
     *  - 突发耗尽死链（R158 ③ 实测）：8 车道突发 ~16 页/s → 预算分钟级耗尽 → 干涸
     *    55min → 队列空 FGS 摘牌 → 进程冻结 → 滑窗恢复无人消费。R255 起配速闸
     *    （DeepSearchIndex.SmoothRateGate）把预算**摊满整小时平滑消费**（6000/h →
     *    600ms/请求），吞吐=预算上限本身；
     *  - 429 安全性**上升**：瞬时请求率从 ~16 页/s 降到 1.67 页/s（峰值低一个数量级
     *    低于旧突发形态）；AIMD pacer（撞 429 ×2 降速+5min 惩罚窗）/熔断表照旧是
     *    反应层。旧口径「1500~3000 ≈ 0.4~0.8 req/s 水位」针对的是无配速的突发形态，
     *    平滑消费下同预算的瞬时压力大幅降低。
     * coerce 域 600..6000 不变；用户已显式设置过旧值（2000）的设备保持用户值。
     */
    var floodCrawlPerHour: Int
        get() = sp.getInt("flood_crawl_per_hour", 6000)
        set(v) = sp.edit().putInt("flood_crawl_per_hour", v.coerceIn(600, 6000)).apply()

    /**
     * R184 代理验证模式（默认关）：开启时全进程搜索请求一律按 CRAWLER 车道计费
     * （账号级搜索账本的最低优先级、最小份额、最先被拒——基本被拒）。
     * 背景：测试机/模拟器上跑的全是**用户主力账号**——设备端验证与用户真实使用
     * 共烧同一个账号搜索桶，是烧配额事故的真凶（R184 归因①②）。代理跑真机
     * 验证前应开启本键保护账号，验证完关闭（用户面功能验证时关闭——否则搜索
     * 功能会因预算拒而失真）。
     * 开关方式：`adb shell run-as dev.tgfeed` 写 shared_prefs 后 force-stop 重启生效
     * （代理既有工具链，零 UI 新增面）。写入 SearchQuotaLedger 生效链：
     * TdLibGateway.effectiveSearchLane。
     */
    var agentVerifySearchQuota: Boolean
        get() = sp.getBoolean("agent_verify_search_quota", false)
        set(v) = sp.edit().putBoolean("agent_verify_search_quota", v).apply()

    /**
     * R220 账号写保护（默认关）：开启时频道礼包等**对真实 TG 账号的写操作**一律在
     * 发送前拦截（建夹/转可分享/导出链接/整包收下；纯读不受影响）。
     * 背景：R130 频道礼包测试在用户主力账号里留下了一堆「R130」开头的测试文件夹
     * （无查重+无测试闸的复合事故，R220 清理+立规）。测试机/模拟器跑的全是用户
     * 真实账号——代理跑真机验证前开本键，验证完关闭（礼包功能用户面验收时关，
     * 否则分享/收下功能失真）。用户显式确认的清理工具不受本键影响。
     * 开关方式：`adb shell run-as dev.tgfeed` 写 shared_prefs 键 agent_no_account_write
     * 为 true 后 force-stop 重启生效（与 R184 agentVerifySearchQuota 同工具链）。
     * 生效链：TdLibGateway.accountWriteBlocked。
     */
    var agentNoAccountWrite: Boolean
        get() = sp.getBoolean("agent_no_account_write", false)
        set(v) = sp.edit().putBoolean("agent_no_account_write", v).apply()

    /**
     * R292 封面 OCR 词位并入检索开关（**默认关**）：试点期识别文本只进 item_ocr 表
     * 不并词位（生产检索零行为变化）；用户终审试点报告后置开，apply Job 把已识别
     * 条目的 OCR 词位并入 fts/LIKE tokens（只并低信息 caption 条目，防 BM25 稀释）。
     * 开关方式：`adb shell run-as dev.tgfeed` 写 shared_prefs 键 ocr_search_enabled
     * 为 true 后 force-stop 重启生效（与 R184 同工具链）。
     */
    var ocrSearchEnabled: Boolean
        get() = sp.getBoolean("ocr_search_enabled", true) // 用户 2026-10-21 终审放量：默认开
        set(v) = sp.edit().putBoolean("ocr_search_enabled", v).apply()

    /**
     * R292 封面 OCR 放量闸（**默认关**）：关=试点模式——回填处理满 1 万封面
     * （DeepSearchIndex.COVER_OCR_PILOT_LIMIT）即收工写试点报告待终审；
     * 开=放量模式——试点上限失效，全量低信息视频封面续跑到底。
     * 开关方式同 ocr_search_enabled（run-as 写 ocr_backfill_full 后重启生效）。
     */
    var ocrBackfillFull: Boolean
        get() = sp.getBoolean("ocr_backfill_full", true) // 用户 2026-10-21 终审放量：默认开（试点 1 万上限失效）
        set(v) = sp.edit().putBoolean("ocr_backfill_full", v).apply()

    /**
     * 自动发现搜索相关实体开关（默认开）：收割周期启动时扫描全会话（主列表+归档+分组），
     * 把"跟资源搜索沾边"的实体自动并入收割清单——
     * 群组/频道：名字含 搜/资源/找片/寻片 等关键词，或简介含"发关键词"类玩法说明；
     * bot 私聊：UserTypeBot 实证且名字/用户名含 搜/soso/jisou 等，外加 searchViaBot
     * 既有配置（searchBotUsername + searchBotFallbacks）里的 bot 无条件并入。
     * 手动配置的 [searchGroupChatIds] 永远并入（手动优先，不受本开关影响）。
     * 清单每小时重扫一次（用户新加的群最迟 1 小时自动纳入），并持久化到 [harvestDiscoveredJson]。
     * 关闭 = 只收手动清单（恢复升级前的旧行为）。
     */
    var harvestAutoDiscover: Boolean
        get() = sp.getBoolean("harvest_auto_discover", true)
        set(v) = sp.edit().putBoolean("harvest_auto_discover", v).apply()

    /**
     * 自动发现的搜索相关实体持久缓存（JSON 数组字符串，GroupHarvester 唯一写入方）：
     * [{"chatId":…,"kind":0|1,"title":"…","src":"name|desc|botname|botconfig"}]，
     * kind 0=群组/频道 1=bot 私聊，src 为发现来源（诊断用）。
     * 跨进程重启保留：冷启动首个收割周期直接用存量清单开工，
     * 避免每次重启都把"简介/ bot 身份"特征实证的请求开销重付一遍；
     * 每小时重扫后整体覆写。内容损坏时读取侧按空表重建（全 runCatching，不崩）。
     *
     * R432 启动性能：该字段是主 SP 文件里的最大 JSON（MB 级），单文件全量重写模型
     * 下拖累其他所有小键的写放大 + 冷启动装载。已迁 filesDir/harvest_discovered.json
     * 独立文件（[dev.tgfeed.core.store.HarvestDiscoveredStore]：懒加载 + tmp+rename
     * 原子写 + 首次访问自动从旧 SP 键迁移），读写口仍收在本属性内，调用方无感。
     */
    var harvestDiscoveredJson: String
        get() = filesDir?.let { HarvestDiscoveredStore.read(it, sp) }
            ?: sp.getString(HarvestDiscoveredStore.LEGACY_SP_KEY, "") ?: "" // 兜底：无 filesDir（纯 JVM 单测）走旧主 SP 键
        set(v) {
            val dir = filesDir
            if (dir != null) HarvestDiscoveredStore.write(dir, sp, v)
            else sp.edit().putString(HarvestDiscoveredStore.LEGACY_SP_KEY, v).apply()
        }

    /**
     * 自动发现清单的最近重扫时刻（毫秒时间戳，GroupHarvester 随 harvestDiscoveredJson 一起写）。
     * 用途：让"每小时重扫一次"的节奏跨进程重启仍然准确——没有它，每次冷启动都会在
     * 首个收割周期立即重扫（全量枚举的 JNI 开销集中在授权 Ready 时刻，没必要）。
     */
    var harvestDiscoveredAtMs: Long
        get() = sp.getLong("harvest_discovered_at_ms", 0L)
        set(v) = sp.edit().putLong("harvest_discovered_at_ms", v).apply()

    /**
     * R183 宝藏二·用户勾选纳入收割的已加入搜索群（知情可控，用户亲自拍板的设计）：
     * JSON 数组 `[{"chatId":…,"title":"…","src":"name|desc|manual"}]`，设置页「我的搜索群」
     * 勾选卡为唯一写入方（GroupHarvester 读侧并入收割清单——与手动清单同优先级）。
     * 勾选语义独立于 [harvestAutoDiscover]：勾选的群无条件并入（用户显式确认过的
     * 自己的群）；自动发现照旧按它自己的开关跑——用户可用「勾选+关自动发现」
     * 达成「只收我自己的群」的精确模式。内容损坏按空表重建（不崩）。
     */
    var harvestUserGroupsJson: String
        get() = sp.getString("harvest_user_groups_json", "") ?: ""
        set(v) = sp.edit().putString("harvest_user_groups_json", v).apply()

    // ---------------- TG 广场 ----------------

    /**
     * TG 广场总开关（默认关——隐私敏感：开启后「在看 / 点赞 / 弹幕」会以本人 TG 账号
     * 公开发送到广场频道，其他用户可见发送者身份）。由设置页"广场"卡片显式打开；
     * 关闭后 PlazaService 读、写两侧全部停摆。
     */
    var plazaEnabled: Boolean
        get() = sp.getBoolean("plaza_enabled", false)
        set(v) = sp.edit().putBoolean("plaza_enabled", v).apply()

    /**
     * 广场频道用户名（不含 @，默认 [PLAZA_CHANNEL_OFFICIAL] = tgfeed_stl）。
     * 全站用户填同一个名字才能互通；空白输入读取侧回落默认值，避免清空输入框后功能失效。
     *
     * R361 开源收口裁决（用户 2026-10-23 拍板，架构沿用）：广场名锁死官方默认名——
     *  1. 默认值收敛到唯一出处 [PLAZA_CHANNEL_OFFICIAL]（前代默认名沿革存档于 r361
     *     笔记，历史名不在代码面复写）；
     *  2. **release 构建读取侧恒返回官方名**（锁死名覆盖：旧值用户本机已存的
     *     自定义值在正式版不再生效——全站互通以唯一官方广场为前提；
     *     配套 UI 闸：设置页/广场管理面板在 release 隐藏频道名输入框、只读展示
     *     「官方广场 @官方名」）。debug 构建（作者自用）仍可自定义，语义不变；
     *  3. 备用广场 [plazaChannelBackup] 不受本锁影响（主广场被炸时的逃生口，只找不建）。
     *
     * R369 官方名换轨裁决（用户 2026-10-24 拍板「就这个吧」）：官方广场由 R361 锁定的
     * 历史占位名（现实里从未建成，真名只存档于 r361 笔记不改写）换轨为用户在 TG 手动
     * 创建的官方广场真群 **tgfeed_stl**（t.me/tgfeed_stl）。锁死架构判词（单源唯一
     * 出处+release 锁名）原样沿用；ensurePlaza 走 plazaFindChat(SearchPublicChat)
     * 按名找群→入群，换轨后开关一开即找到用户真群。
     */
    var plazaChannel: String
        get() = if (BuildConfig.DEBUG) {
            sp.getString("plaza_channel", null).takeUnless { it.isNullOrBlank() } ?: PLAZA_CHANNEL_OFFICIAL
        } else {
            PLAZA_CHANNEL_OFFICIAL // R361：正式版锁死官方广场名（全站互通前提）
        }
        set(v) = sp.edit().putString("plaza_channel", v.trim().removePrefix("@")).apply()

    /**
     * 备用广场频道用户名（不含 @，默认空 = 不启用）。
     * 主广场找不到/创建失败时 PlazaService.ensurePlaza 自动回落尝试本备群（只找不建，
     * 备群默认已由别人建好）。与 plazaChannel 不同：空白是合法值，读取侧不回落默认值。
     */
    var plazaChannelBackup: String
        get() = sp.getString("plaza_channel_backup", null)?.trim() ?: ""
        set(v) = sp.edit().putString("plaza_channel_backup", v.trim().removePrefix("@")).apply()

    /**
     * 广场创建者标记（默认 false）：true 时 PlazaService.ensurePlaza 找不到广场频道
     * 会以本人账号创建它并绑定 plazaChannel 用户名。仅第一个使用者需要开启。
     */
    var plazaOwner: Boolean
        get() = sp.getBoolean("plaza_owner", false)
        set(v) = sp.edit().putBoolean("plaza_owner", v).apply()

    /** 广场群已建好（找到/创建成功过一次）：设置页据此隐藏"我是广场创建者"开关 */
    var plazaCreated: Boolean
        get() = sp.getBoolean("plaza_created", false)
        set(v) = sp.edit().putBoolean("plaza_created", v).apply()

    /**
     * R70 闸二：广场 chatId 落盘（0 = 未创建过/未知；创建成功或找到主广场都会写）。
     * ensurePlaza 最前面优先读它 + GetChat 验证可访问即直接复用，完全绕过
     * SearchPublicChat——该接口被 429 熔断期「查询失败」与「不存在」都返回 null，
     * 曾导致 owner 设备每次启动都新建一个广场群（无限建群 bug）。落盘后熔断期也畅通。
     */
    var plazaCreatedChatId: Long
        get() = sp.getLong("plaza_created_chat_id", 0L)
        set(v) = sp.edit().putLong("plaza_created_chat_id", v).apply()

    /**
     * R70 闸三：最近一次广场创建尝试的毫秒时间戳（成功/失败都先落戳再创建）。
     * 创建是不可逆高危副作用（每建一次用户账号就多一个垃圾群），即便真"不存在"
     * 也 24h 内最多尝试一次；落盘保证跨进程重启冷却仍然有效。
     */
    var plazaCreateAttemptAtMs: Long
        get() = sp.getLong("plaza_create_attempt_at_ms", 0L)
        set(v) = sp.edit().putLong("plaza_create_attempt_at_ms", v).apply()

    // ---------------- 点赞回写原频道 Reaction（R128） ----------------

    /**
     * 点赞回写总开关（R128，默认关）：开启后双击点赞时，除本地飘心+点赞外，
     * 顺手以本人 TG 账号给原频道该消息补一个 👍 Reaction（TdLibGateway.sendLikeReaction），
     * 让点赞回到内容生态。
     * 默认关的理由：回写是写操作——Reaction 计数与名单对频道主/管理员可见，
     * 属"对内容生态有影响"的外发行为，必须由用户显式知情开启，不应默认替用户向外表态。
     * R138 起任何一方（设置页/场景引导）写本开关都会顺手置 [likeWritebackPromptDecided]：
     * 动过总闸 = 已知情，首次双击点赞的轻引导不再打扰（设置页仍保留做总闸，不再是唯一触点）。
     */
    var likeReactionWriteback: Boolean
        get() = sp.getBoolean("like_reaction_writeback", false)
        set(v) {
            sp.edit().putBoolean("like_reaction_writeback", v).apply()
            likeWritebackPromptDecided = true
        }

    /**
     * 回写决策位（R138 场景化引导，默认 false = 尚未决定）：首次双击点赞时弹一次性轻引导
     * "让 👍 回到频道？"（可开启 / 仅此点赞）。两个选项、或设置页翻转总闸都会置 true——
     * 一次性引导此后不再出现；总闸状态本身仍由 [likeReactionWriteback] 单一持有。
     */
    var likeWritebackPromptDecided: Boolean
        get() = sp.getBoolean("like_writeback_prompt_decided", false)
        set(v) = sp.edit().putBoolean("like_writeback_prompt_decided", v).apply()

    // ---------------- 跨设备续播云端同步 ----------------

    /**
     * 跨设备续播总开关（默认开——接力是默认想要的）：开启后观看位置会以一条 JSON 文本消息的形式
     * 存入本人 Telegram「收藏夹」（Saved Messages，仅自己可见、云端多设备同步），
     * 本账号其他设备拉取合并后即"手机刷一半平板接着放"；更新方式是编辑同一条消息，
     * 不会刷屏。隐私说明：数据只发往自己的收藏夹，不对任何第三方可见；
     * 关闭后推（FeedViewModel.syncProgressPush / saveProgress 自动节流推）
     * 拉（FeedViewModel init 自动拉 / 开关打开即时拉 / syncProgressPull 手动拉）两侧全停。
     */
    var progressSyncEnabled: Boolean
        get() = sp.getBoolean("progress_sync_enabled", true)
        set(v) = sp.edit().putBoolean("progress_sync_enabled", v).apply()

    /**
     * 监听 [progressSyncEnabled] 开关变化（设置页直写 SharedPreferences、不经 ViewModel，
     * VM 借此在开关由关转开时立即自动拉一次云端进度，接力即开即用）。
     * 返回注销函数（调用方持有并在销毁时调用）；SharedPreferences 对监听是弱引用，
     * 本实现把监听器闭包连同强引用一起包进返回的注销函数，注销前不会被 GC 提前回收。
     */
    fun registerProgressSyncEnabledListener(onChange: (Boolean) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "progress_sync_enabled") onChange(progressSyncEnabled)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // ---------------- R300 多设备状态云端同步（收藏/稍后再看/追更订阅+已读水位/屏蔽三件套） ----------------

    /**
     * 多设备状态云端同步总开关（默认开，与 [progressSyncEnabled] 同族同默认）：
     * 开启后收藏/稍后再看/追更订阅与已读水位/屏蔽三件套经 Telegram 收藏夹
     * （Saved Messages，仅本人可见）按协议 V3 多域消息同步（每域一条 marker 消息，
     * 原地编辑不刷屏，载荷只搬 key+ts 不搬条目本体——design-multi-device-sync.md 定稿）。
     * 隐私说明同进度域：明文 JSON 只发往自己的收藏夹，不对任何第三方可见；
     * 同步数据存放于你的 TG 收藏夹（设置页「跨设备续播」卡有明示）。
     * 关闭后四域推（标脏节流推/手动推）拉（启动拉/事件驱动拉/周期拉）两侧全停；
     * 进度域开关与本开关独立（进度域走自己的 progress_sync_enabled）。
     */
    var cloudSyncEnabled: Boolean
        get() = sp.getBoolean("cloud_sync_enabled", true)
        set(v) = sp.edit().putBoolean("cloud_sync_enabled", v).apply()

    /**
     * 监听 [cloudSyncEnabled] 开关变化（设置页直写 SharedPreferences、不经 ViewModel，
     * VM 借此在开关由关转开时立即自动拉一次云端四域——同 [registerProgressSyncEnabledListener] 先例）。
     */
    fun registerCloudSyncEnabledListener(onChange: (Boolean) -> Unit): () -> Unit {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "cloud_sync_enabled") onChange(cloudSyncEnabled)
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        return { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        /**
         * R361 开源收口裁决（用户 2026-10-23 拍板，架构沿用）：官方广场频道用户名唯一
         * 出处（不含 @）。正式版广场名锁死为本名——全站用户聚在同一个公开超级群才
         * 互通；release 构建零创建路径（见 PlazaService.ensurePlaza 的
         * BuildConfig.DEBUG 硬闸）。
         *
         * R369 换轨裁决（用户 2026-10-24 拍板「就这个吧」）：本名由 R361 历史占位名
         * （从未实际建成，存档于 r361 笔记不改写）换轨为用户在 TG 手动创建的官方广场
         * 真群 **tgfeed_stl**（t.me/tgfeed_stl）——真群已存在于服务端，ensurePlaza
         * 按名 SearchPublicChat 找到即入群。
         */
        const val PLAZA_CHANNEL_OFFICIAL = "tgfeed_stl"
    }
}
