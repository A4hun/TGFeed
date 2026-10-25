package dev.tgfeed.core.td

import dev.tgfeed.core.prefs.AppPrefs
import kotlinx.coroutines.flow.first

// ---------------- 搜索文本匹配工具（SEARCH-QUALITY §2/§4：简繁折叠 + CJK bigram + ED≤1 模糊 + 同义词组） ----------------

/**
 * 搜索文本匹配工具（td 层 internal，core.feed 搜索区共用同一把尺子）。
 *
 * 解决的问题（诊断见 docs/SEARCH-QUALITY.md）：
 *  1. 简繁零归一：简体"电影"搜不到繁体 caption"電影"——中文资源圈简繁混杂，
 *     匹配前双侧折叠到简体（[fold]），索引原文侧用简→繁展开变体逼近（[analyze] 词位变体）；
 *  2. CJK 无分词：查询"宫崎骏合集"整串 contains 要求连续出现，"宫崎骏作品高清合集"漏掉——
 *     CJK 连续段切重叠 bigram 词位（不依赖任何分词器），按词位覆盖率给分；
 *  3. 错别字零容错：拉丁词位（番号/英文标题，长度 ≥4 才启用）对文本拉丁 token 做
 *     编辑距离 ≤1 模糊（[lev1]），命中按 0.6 降权；CJK 错别字由 bigram 覆盖率天然兜住
 *     （错一个字只丢相邻 2 个词位，其余照常命中），不做 CJK 编辑距离（2 字 ED1 误命中爆炸）；
 *  4. 全角字符不归一：全角空格/全角英数在 [fold] 里转半角（"电影　合集"不再产出烂词）。
 *
 * 简繁表取舍：人工高频子集（约 400 对，空格分隔两字对，运行时解析成 map；
 * 每对自足不存在错位风险）。繁→简多对一折叠（臺/檯/颱→台）对搜索匹配是正确方向；
 * 简→繁展开（[expandTrad]）每字只取表中第一候选，生僻异体写法可能漏——
 * 不在表内的字退化为原样匹配（不会比不做更差）。
 */
internal object SearchTextKit {

    /** 繁→简折叠表（"繁简"两字对，空格分隔）。常见高频字子集；运行时解析，坏对（长度≠2）跳过 */
    private const val TRAD_SIMP_PAIRS =
        "愛爱 礙碍 奧奥 罷罢 擺摆 敗败 辦办 絆绊 幫帮 綁绑 寶宝 報报 貝贝 備备 筆笔 幣币 " +
        "閉闭 編编 變变 標标 賓宾 並并 撥拨 補补 財财 參参 殘残 倉仓 層层 產产 長长 場场 " +
        "車车 徹彻 塵尘 陳陈 稱称 遲迟 齒齿 蟲虫 處处 傳传 創创 純纯 詞词 聰聪 從从 錯错 " +
        "達达 帶带 擔担 單单 當当 導导 燈灯 敵敌 點点 電电 釣钓 丟丢 東东 動动 讀读 獨独 " +
        "對对 噸吨 額额 惡恶 兒儿 爾尔 發发 罰罚 範范 飛飞 費费 墳坟 風风 縫缝 輔辅 負负 " +
        "復复 婦妇 該该 蓋盖 幹干 乾干 趕赶 剛刚 個个 給给 夠够 購购 顧顾 觀观 廣广 規规 " +
        "貴贵 國国 過过 還还 漢汉 號号 華华 畫画 話话 懷怀 壞坏 歡欢 環环 黃黄 會会 貨货 " +
        "獲获 機机 雞鸡 積积 級级 極极 幾几 計计 紀纪 際际 劑剂 價价 間间 檢检 簡简 見见 " +
        "將将 講讲 節节 結结 緊紧 進进 經经 舊旧 舉举 據据 覺觉 決决 軍军 開开 課课 寬宽 " +
        "況况 來来 藍蓝 樂乐 類类 離离 裏里 麗丽 歷历 連连 聯联 臉脸 練练 兩两 領领 錄录 " +
        "論论 羅罗 媽妈 馬马 買买 賣卖 滿满 貿贸 麼么 沒没 門门 們们 夢梦 鳥鸟 寧宁 農农 " +
        "歐欧 評评 氣气 錢钱 強强 橋桥 親亲 輕轻 請请 區区 權权 卻却 確确 讓让 熱热 認认 " +
        "軟软 殺杀 設设 聲声 勝胜 師师 詩诗 時时 實实 視视 書书 術术 數数 雙双 誰谁 順顺 " +
        "說说 訴诉 雖虽 隨随 歲岁 孫孙 臺台 颱台 檯台 談谈 題题 體体 條条 調调 鐵铁 聽听 " +
        "統统 頭头 圖图 團团 萬万 網网 為为 圍围 維维 偉伟 聞闻 問问 臥卧 無无 務务 誤误 " +
        "習习 戲戏 係系 繫系 細细 鮮鲜 顯显 現现 線线 寫写 謝谢 續续 學学 壓压 陽阳 養养 " +
        "樣样 藥药 業业 醫医 議议 藝艺 銀银 應应 優优 遊游 語语 園园 員员 願愿 約约 雲云 " +
        "則则 戰战 張张 這这 職职 隻只 紙纸 製制 緻致 終终 種种 眾众 專专 轉转 裝装 狀状 " +
        "準准 資资 總总 組组 訊讯 討讨 託托 謠谣 謙谦 謹谨 譜谱 豐丰 豔艳 貞贞 貢贡 貧贫 " +
        "販贩 貪贪 貫贯 責责 貯贮 貸贷 貼贴 賀贺 賈贾 賄贿 賃赁 賂赂 贓赃 賑赈 賒赊 賜赐 " +
        "賞赏 賠赔 賢贤 賤贱 賦赋 質质 賬账 賭赌 賴赖 賺赚 賽赛 贈赠 贏赢 躍跃 軀躯 軌轨 " +
        "軒轩 軸轴 較较 載载 輛辆 輝辉 輩辈 輪轮 輯辑 輸输 轄辖 轟轰 辭辞 遷迁 選选 遺遗 " +
        "遼辽 邊边 邏逻 遠远 運运 違违 遙遥 遜逊 適适 邁迈 識识 記记 許许 諾诺 護护 證证 " +
        "贊赞 譽誉 戀恋 蠻蛮 覽览 觸触 訓训 諒谅 諸诸 謊谎 謎谜 關关 於于 灣湾 薦荐 碼码 " +
        "換换 後后 餘余 裡里 複复 髮发 麵面 鬆松 佈布 佔占 帳账 徵征 徑径 慶庆 憂忧 戶户 " +
        "捨舍 掃扫 損损 攝摄 斷断 曆历 榮荣 樓楼 樹树 欄栏 淚泪 淨净 減减 湯汤 溫温 滅灭 " +
        "潔洁 澤泽 濃浓 濕湿 濟济 災灾 烏乌 煙烟 燒烧 營营 爺爷 牽牵 猶犹 獎奖 畢毕 異异 " +
        "疊叠 療疗 盜盗 盡尽 監监 禮礼 禍祸 穩稳 竊窃 窩窝 籃篮 紅红 絲丝 綠绿 縣县 縱纵 " +
        "纖纤 羨羡 義义 聖圣 腦脑 腳脚 脫脱 膚肤 臟脏 與与 蘇苏 虛虚 蝦虾 蟻蚁 衝冲 擾扰 " +
        "擁拥 擇择 擊击 攜携 齊齐 齋斋 龍龙 龜龟"

    /** 同义词组（fold 后的简体形；查询整段落在组内时，该段扩展为一个"组内任一词命中即中"的词位）。
     *  保守高置信小组：误扩比不扩更伤信任（SEARCH-QUALITY §4） */
    private val SYNONYM_GROUPS: List<Set<String>> = listOf(
        setOf("合集", "合辑", "全集"),
        setOf("电影", "影片"),
        setOf("电视剧", "剧集"),
        setOf("教程", "教学", "课程"),
        setOf("音乐", "歌曲"),
        setOf("动画", "动漫"),
        setOf("写真", "图集"),
    )

    /** 词位数上限：超长查询截断（防 bigram 过多导致索引多路召回成本爆炸） */
    private const val MAX_SLOTS = 8

    /** 拉丁模糊命中的降权系数（编辑距离 ≤1 命中按此权重计入词位覆盖） */
    const val FUZZY_HIT_WEIGHT = 0.6

    /** 繁→简折叠 map（lazy 首用解析） */
    private val foldMap: Map<Char, Char> by lazy {
        val m = HashMap<Char, Char>(512)
        for (p in TRAD_SIMP_PAIRS.split(' ')) if (p.length == 2) m[p[0]] = p[1]
        m
    }

    /** 简→繁展开 map：每字取表中第一候选（多繁体折叠同一简体时先到先得；索引召回逼近用） */
    private val expandMap: Map<Char, Char> by lazy {
        val m = LinkedHashMap<Char, Char>()
        for (p in TRAD_SIMP_PAIRS.split(' ')) {
            if (p.length != 2) continue
            if (!m.containsKey(p[1])) m[p[1]] = p[0]
        }
        m
    }

    /**
     * 文本归一化折叠：繁→简 + 全角空格/英数转半角 + 小写化。
     * 匹配前对查询与文本两侧都调用，简繁/全角/大小写差异一并抹平。
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val f = foldMap[c]
            when {
                f != null -> sb.append(f) // 繁→简折叠
                c == '　' -> sb.append(' ') // 全角空格归一（\s+ 切词的前提）
                c in 'Ａ'..'Ｚ' -> sb.append('a' + (c - 'Ａ')) // 全角大写→半角小写
                c in 'ａ'..'ｚ' -> sb.append('a' + (c - 'ａ')) // 全角小写→半角
                c in '０'..'９' -> sb.append('0' + (c - '０')) // 全角数字→半角
                else -> sb.append(c.lowercaseChar()) // 其余小写化
            }
        }
        return sb.toString()
    }

    /** 简→繁展开：逐字替换为第一候选繁体；无候选字保留原样。全部无候选时返回原串（供变体去重） */
    fun expandTrad(s: String): String {
        val sb = StringBuilder(s.length)
        var changed = false
        for (c in s) {
            val t = expandMap[c]
            if (t != null) {
                sb.append(t)
                changed = true
            } else {
                sb.append(c)
            }
        }
        return if (changed) sb.toString() else s
    }

    /**
     * 复滤谓词（网关本地过滤用）：折叠后 contains——简繁/全角/大小写差异不再误杀。
     * 查询去 #/＃ 前缀；空查询恒 true（不过滤）。
     */
    fun foldsContains(text: String, query: String): Boolean {
        val q = query.trim().trimStart('#', '＃')
        if (q.isEmpty()) return true
        return fold(text).contains(fold(q))
    }

    /**
     * 精确命中判定（模糊搜索开关关闭时的口径，AppPrefs.fuzzySearch=false）：
     * [phrase] 须为 fold 后的查询整串，按空白切段，**每段**都必须作为连续子串出现在
     * textFold 中——AND 语义（"三体 小说" = 同时含"三体"和"小说"；只含"三体"的
     * "三体电视剧"不算中）。无空格查询 = 单段 = 连续字符串精确匹配。
     * 简繁/全角/大小写归一由 fold 承担（调用前两侧都已 fold），不属于模糊。
     */
    fun exactHits(textFold: String, phrase: String): Boolean {
        val segs = phrase.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (segs.isEmpty()) return false
        return segs.all { textFold.contains(it) }
    }

    // ---------------- R167 #话题精确词匹配（hashtag 点击/索引查询/源内复滤共用同一把尺子） ----------------

    /**
     * hashtag 查询判定（R167）：trim 后以 # 或全角 ＃ 开头，且标记后到串尾无任何空白
     * （话题实体不含空白——带空白的 "#tag 合集" 走普通段落匹配）。
     * 调用方据它分路：true → hashtag 精确词模式（[hashtagHits]），false → 既有口径。
     * 词身字符集与 [hashtagHits] 的边界判定、UI 可点片段提取正则
     * （BottomInfoPanel `#([\p{L}\p{N}_]+)`）三方同口径。
     */
    fun isHashtagQuery(query: String): Boolean {
        val t = query.trim()
        if (!t.startsWith("#") && !t.startsWith("＃")) return false
        val body = t.trimStart('#', '＃')
        return body.isNotEmpty() && body.none { it.isWhitespace() }
    }

    /** hashtag 词身字符（TG 话题实体口径，= [\p{L}\p{N}_]）：字母（含 CJK）/数字/下划线 */
    private fun isTagBodyChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * hashtag 命中判定（R167 纯函数）：textFold 与 tagFold 均须先经 [fold]
     * （简繁/全角/大小写归一由调用方完成，与全仓匹配口径一致）。文本含 "#tag"
     * （# 或全角 ＃ 起）且 tag 后是词边界（下一字符非 hashtag 词身字符，或已到
     * 串尾）才算命中：
     *  - "看 #电影 合集" 命中 "电影"；"看 #电影推荐 合集" 不命中 "电影"
     *    （词身延续——那是另一个标签，防把长标签误归给短标签）
     *  - "今日电影合集"（无标记）不命中：裸词不算——防裸子串误伤（R167 语义根因）
     *  - "#TAG"/"#電影" 经 fold 双侧归一后命中；"＃tag" 全角标记同认
     *  - 标记前置字符不查（"abc#tag" 也命中）——召回优先宁多勿漏，前置误命中面
     *    （"url#tag" 形态）实测罕见，与既有段级复核的宽松口径一致
     * 返回 false 的其他情形：tagFold 为空。
     */
    fun hashtagHits(textFold: String, tagFold: String): Boolean {
        if (tagFold.isEmpty()) return false
        val n = textFold.length
        val tn = tagFold.length
        var i = 0
        while (i < n) {
            val c = textFold[i]
            if (c == '#' || c == '＃') {
                val s = i + 1
                val after = s + tn
                if (after <= n && textFold.regionMatches(s, tagFold, 0, tn) &&
                    (after == n || !isTagBodyChar(textFold[after]))
                ) {
                    return true
                }
            }
            i++
        }
        return false
    }

    /**
     * 分析后的查询（建搜索会话时一次性完成，打分/召回共享）：
     *  - [phrase]：fold 后整串（完整短语命中仍是最高分信号）；
     *  - [slots]：词位列表，每个词位 = 变体集合（首元素为打分主变体；
     *    变体含 fold 原形 + 简→繁展开形 + 同义词组扩展）。
     * （SEARCH-INVERTED：旧"逐词位多路 contains 召回"的 recallQueries/exactRecallQueries
     *  已随 DeepSearchIndex 倒排索引落地移除——索引侧自己分词查表，不再需要调用侧凑召回串。）
     */
    internal class AQuery(
        val phrase: String,
        val slots: List<List<String>>,
    )

    /**
     * 查询分析：去 # 前缀 → fold → 按空白切段 → 每段成词位：
     *  1. 整段命中同义词组 → 一个词位（变体 = 组内全部词 + 各自繁体展开）；
     *  2. 段内按 ASCII/非 ASCII 切运行：拉丁运行整词成位（ED≤1 模糊在 [slotHit] 内启用）；
     *     CJK 运行单字成位、多字切重叠 bigram（"宫崎骏合集"→宫崎/崎骏/骏合/合集，不依赖分词）。
     */
    fun analyze(query: String): AQuery {
        val raw = query.trim().trimStart('#', '＃')
        val phrase = fold(raw)
        if (phrase.isEmpty()) return AQuery("", emptyList())
        val slots = ArrayList<List<String>>()
        for (seg in phrase.split(Regex("\\s+"))) {
            if (seg.isBlank()) continue
            if (slots.size >= MAX_SLOTS) break
            // 同义词组扩展：整段（fold 形）在组内 → 组内任一词命中即算该词位命中
            val syn = SYNONYM_GROUPS.firstOrNull { seg in it }
            if (syn != null) {
                slots += syn.flatMap { w -> listOf(w, expandTrad(w)) }
                    .distinct()
                    .filter { it.isNotEmpty() }
                continue
            }
            // 段内按 ASCII/非 ASCII 切运行分别处理（"1080p合集"→拉丁"1080p"+bigram"合集"）
            var i = 0
            while (i < seg.length && slots.size < MAX_SLOTS) {
                val ascii = seg[i].code < 128
                var j = i + 1
                while (j < seg.length && (seg[j].code < 128) == ascii) j++
                val run = seg.substring(i, j)
                when {
                    ascii -> slots += listOf(run)
                    run.length == 1 -> slots += listOf(run, expandTrad(run)).distinct()
                    else -> for (k in 0 until run.length - 1) {
                        if (slots.size >= MAX_SLOTS) break
                        val bg = run.substring(k, k + 2)
                        slots += listOf(bg, expandTrad(bg)).distinct()
                    }
                }
                i = j
            }
        }
        return AQuery(phrase, slots)
    }

    /**
     * 单词位命中判定：textFold（fold 后的待匹配文本）含任一变体 → 1.0（精确）；
     * 否则主变体为拉丁词且长度 ≥4 时，与文本拉丁 token 比编辑距离 ≤1 → [FUZZY_HIT_WEIGHT]；
     * 都不中 → 0.0。[latinTokens] 由调用方对 textFold 提取一次、多词位复用。
     */
    fun slotHit(textFold: String, latinTokens: List<String>, slot: List<String>): Double {
        for (v in slot) if (textFold.contains(v)) return 1.0
        val main = slot.first()
        if (main.length >= 4 && main.all { it.code < 128 }) {
            for (tok in latinTokens) {
                if (kotlin.math.abs(tok.length - main.length) > 1) continue
                if (lev1(main, tok)) return FUZZY_HIT_WEIGHT
            }
        }
        return 0.0
    }

    /** 提取文本（已 fold）中的拉丁 token（[a-z0-9]+），供拉丁词位模糊匹配比对 */
    fun latinTokens(textFold: String): List<String> =
        LATIN_TOKEN_REGEX.findAll(textFold).map { it.value }.toList()

    /**
     * 词位提取（倒排索引专用，DeepSearchIndex 建表/查询共用同一口径，
     * 保证"查询词位一定能命中建表词位"）。输入须已 fold（简繁/全角/大小写归一由调用方完成）。
     *  - ASCII 运行按 [a-z0-9]+ 切整词成位（与 [latinTokens] 同口径）；
     *  - CJK（非 ASCII）运行：[forIndex]=true（建表）逐字 + 重叠 bigram 全收——
     *    单字位供单字查询召回，bigram 位是"两字符连续出现"的充要条件；
     *    [forIndex]=false（查询）单字运行取单字、多字运行只取 bigram——
     *    单字位被 bigram 蕴含（bigram 命中列表 ⊆ 单字命中列表），交集结果不变、查询更省。
     */
    fun tokenize(folded: String, forIndex: Boolean): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < folded.length) {
            val ascii = folded[i].code < 128
            var j = i + 1
            while (j < folded.length && (folded[j].code < 128) == ascii) j++
            val run = folded.substring(i, j)
            if (ascii) {
                LATIN_TOKEN_REGEX.findAll(run).forEach { out += it.value }
            } else {
                if (forIndex || run.length == 1) {
                    for (k in run.indices) out += run[k].toString() // 单字位
                }
                for (k in 0 until run.length - 1) {
                    out += run.substring(k, k + 2) // 重叠 bigram 位
                }
            }
            i = j
        }
        return out
    }

    /**
     * 同义词组扩展（fold 后整段落在组内返回组内全部词，否则空集）。
     * 倒排索引模糊召回复用 [analyze] 的同义词位口径（组内任一词命中即算该段命中）。
     */
    fun synonymVariants(segFold: String): Set<String> =
        SYNONYM_GROUPS.firstOrNull { segFold in it } ?: emptySet()

    /**
     * 编辑距离 ≤1 判定（早期退出）：长度差 >1 直接 false；
     * 等长允许一个替换位；长度差 1 允许长串跳过一个字符（一次插入/删除）。
     */
    fun lev1(a: String, b: String): Boolean {
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        if (la == lb) {
            var diff = 0
            for (i in 0 until la) if (a[i] != b[i] && ++diff > 1) return false
            return true
        }
        val longer = if (la > lb) a else b
        val shorter = if (la > lb) b else a
        var i = 0
        var j = 0
        var skipped = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
            } else if (!skipped) {
                skipped = true
                j++
            } else {
                return false
            }
        }
        return true
    }

    private val LATIN_TOKEN_REGEX = Regex("[a-z0-9]+")
}
