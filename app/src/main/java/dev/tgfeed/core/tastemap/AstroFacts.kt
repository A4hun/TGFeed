package dev.tgfeed.core.tastemap

/**
 * R312 星象科普体系纯函数件（口味星图·零 Android 依赖，JVM 单测可跑）——
 * 用户愿景「探索宇宙时顺手学天文：星象（真天文）配一套科普」的内容底座：
 *
 *  - **科普词条表**（[TOPICS] 24 条）：每个天体类型（恒星六态/中子星/夸克星/黑洞/
 *    行星/小行星/星云/星团/彗星/流星/双星/类星体/白洞）一条「这是什么天体」真天文
 *    科普（[Topic.what]）+「本宇宙语义映射」（[Topic.inUniverse]——本体论口径：
 *    频道=恒星/tag=行星/内容条目=小行星尘埃/未探索区=星云/群组=星团/私聊=流星/
 *    爬虫发现=彗星/互链频道对=双星）。R317：中子星/夸克星/白洞三席位兑现 kind
 *    对位（终态宣判判据已入库——判据族 [TasteAstroPhysics.judgeTerminalStates]）；
 *    类星体仍 null（黑洞的高活动态修饰——不改枚举，R317b wiring 已接线：盛宴
 *    黑洞经 factLine 相行+渲染进食档呈现）；R327：吸积盘入席（诞生链中段补环
 *    ——kind=null 语义席位镜像 quasar 模式，宣判已接线：信息卡诞生四行）；
 *    R331：引力波/凌星两现象词条预落（R320 在飞概念的 TOPICS 位——kind=null
 *    语义席位，不依赖 EventKind 枚举；eventGloss 事件注归 R320 仲裁区零碰）+
 *    终态四奇观 howToFind 补齐（图鉴奇观册口径）；
 *    R321：拉格朗日点词条入席（批 4 概念——kind=null 语义席位同模式+binary
 *    related 补 lagrange 浮现路径）+whyChain 星风行（条件行——[StarwindFx.isWindy]
 *    同一门，缺据=行不出现）；
 *  - **本星实况**（[liveFacts]）：「这颗星对应你库里什么数据」的结构化条目
 *    （质量档/光度/温度谱/年龄/断更/奥本海默倍率/双星伴侣/成员规模）——与
 *    [TasteAstroPhysics.factLine] 分工：factLine=信息卡紧凑单行（R249 契约原样
 *    不动），liveFacts=展开科普段的条目表；**缺据字段整条不出**（绝不编造数字）；
 *  - **「为什么」解释链**（[whyChain]）：点任意天体答「它为什么是这个类型/颜色/
 *    大小/亮度/位置/（有伴星）」——分类器判据矩阵/构成色相/体量半径/亮度双源/
 *    球面化布局/双星证据门 全部翻译成人话且带本星数字，缺据走诚实回落；
 *  - **点亮即时科普**（[instantLine]+[pickToastStar]）：发现时刻小卡的一句话文案
 *    与「一拍多颗选哪颗播报」的确定性选星；
 *  - **学习路径钩子**（[unseenKindHints]/[unseenWonderHints]/[wonderCountsLine]/
 *    [eventGloss]）：成就面板图鉴缺口提示（「去哪找」——六态册+R331 终态奇观册
 *    另册）与目击图鉴三事件科普注；
 *  - **反茧房·探索边界段**（[edgeHorizonLine]/[edgeNurseryLine]，R352）：成就
 *    面板「🕳 探索边界」段的视界统计行+产房指引行（茧房边界数字+破茧路径——
 *    三素材叙事串联，缺据整行不出）；whyChain 第七问的茧房外计数句
 *    （[WhyInput.horizonBeyondN]——缺省关，缺据句不追加）；
 *  - **相关天体推荐**（[relatedWithCounts]）：词条.related 序直出+kind 对位词条
 *    带宇宙内计数（0=「还没见到」诚实标注）。
 *
 * 文案纪律：全部**数据驱动**——数字一律来自入参（本地索引/金矿/装配烘焙），零网络
 * 零编造；缺据=诚实缺省行或整条不出现（R223b「绝不冒充特殊天体」同哲学）。
 */
object AstroFacts {

    // ==================== 科普词条表 ====================

    /**
     * 科普词条（一型天体一条）：
     *  - [what]=「这是什么天体」真天文科普（静态知识文案）；
     *  - [inUniverse]=本宇宙语义映射（数据→天体的判定口径白话）；
     *  - [related]=相关词条 key 表（推荐段按此序直出）；
     *  - [kind]=与 [CelestialKind] 的对位（null=无枚举对位的语义天体/预留席位）；
     *  - [howToFind]=「去哪找」学习路径钩子（可点亮的六态+脉冲星+终态四奇观+
     *    R331 预落两现象词条有；其余语义席位 null）。
     */
    class Topic(
        val key: String,
        val emoji: String,
        val title: String,
        val what: String,
        val inUniverse: String,
        val related: List<String>,
        val kind: CelestialKind?,
        val howToFind: String?,
    )

    /** 词条总表（演化链叙事序：星云→吸积盘→原恒星→主序→红巨星→白矮星→超新星
     *  →致密遗骸→黑洞→类星体/白洞；其后是语义天体：脉冲星/双星/行星/小行星/
     *  星团/彗星/流星） */
    val TOPICS: List<Topic> = listOf(
        Topic(
            key = "nebula", emoji = "🌫", title = "星云",
            what = "气体与尘埃的星际云——既是恒星诞生的摇篮，也是恒星死亡抛出的残骸。猎户座大星云的产房里，此刻正有新恒星在点亮。",
            // R323 K③ 双型文案（r331 预清单——发射=产房/反射=普通未探索）：发射判据=
            // 成员金矿候选占比≥50%（NEBULA_CANDIDATE 注记消费——团级门批 5 组合）
            // R385 P4 增段（design-nebula §3.3-4 复合体在场叙述——P2/P3/P4 现实
            // 入文：大云悬在星系尺度/新星从云里点亮/云前星真变暗偏红/超新星还尘/
            // 延时摄影早期云更浓星未亮——静态段=词条层固定叙述，动态计数行走
            // [nebulaComplexLine] 面板/信息卡单口）
            inUniverse = "你还没探索的区域蒙着星云，分两型：普通星云（反射/暗星云）只是一片未探索的暗雾；发射星云是亮的那团——成员里过半是金矿候选（新星原料），雾内有星胚微光点，朝近旁炽热恒星的一侧被电离得更亮：那是一片产房，超新星遗迹飘到近旁时星胚还会脉动增亮（死亡的物质正在孕育新星）。爬虫带回的新频道会先在这里聚合，再点亮成原恒星。远处星系尺度上还悬着几团大得多的复合体云：新星真的是从云里点亮的——诞生那一刻云尘向它聚拢成它；飞过云前时，云身后的星群会真实地变暗偏红（尘埃遮光的本色）；超新星的遗迹会把灰烬还给最近的云；把延时摄影拖回早期，云更浓而星未亮——云先凝、星后亮。",
            related = listOf("accretion_disk", "protostar", "star", "comet", "olbers", "proper_motion"),
            kind = null, howToFind = null,
        ),
        // ---- R327 吸积盘词条（r325 E 章暂缓件——诞生链中段补环：星云与原恒星
        //      之间的叙事序位；kind=null 语义席位镜像 quasar 模式（呈现态词条
        //      不改枚举），inUniverse 文案与设置页「新频道先小样探测约 100 条」
        //      同口径（纪律§一数字同源） ----
        Topic(
            key = "accretion_disk", emoji = "◎", title = "吸积盘",
            what = "恒星诞生时气体尘埃落向原恒星的旋转盘——物质沿螺旋线坠向核心，行星系就从盘里长出来。猎户座大星云的盘里正拍到了行星诞生的现场。",
            inUniverse = "爬虫正在小样探测的新频道=吸积中的恒星胚胎（约 100 条小样=第一批落料；内容够好升深索引=核心点燃）——开智能分档后飞近暗雾，信息卡会告诉你「它正在诞生：小样探测中」。",
            related = listOf("nebula", "protostar", "planet", "asteroid"),
            kind = null, howToFind = "开智能分档后飞近暗雾——正被小样探测的星在近处有内落的尘埃",
        ),
        Topic(
            key = "protostar", emoji = "✧", title = "原恒星",
            what = "星云中正在吸积气体的恒星胚胎——还没点燃核聚变，靠引力收缩发热。等核心足够热，它就点燃氢，转正为主序星。",
            inUniverse = "首见不足 90 天的新频道=正在吸积的恒星胚胎——内容还在积攒，核心还没点燃。你点亮它的那一刻=它的核聚变点燃——新星诞生。",
            related = listOf("nebula", "accretion_disk", "star", "comet"),
            kind = CelestialKind.PROTOSTAR,
            howToFind = "找最近刚出现的新频道——它们还在吸积",
        ),
        Topic(
            key = "star", emoji = "☀", title = "恒星（主序星）",
            what = "主序星靠核心的氢核聚变稳定发光发热——太阳就是一颗典型的主序星。核燃料烧得越稳，它停在这个阶段就越久，占去恒星一生约九成的时间。",
            inUniverse = "稳定更新中的频道就是主序星——持续的内容发布就是它的核聚变。证据不足的天体也诚实落在这一档，绝不冒充特殊天体。",
            related = listOf("protostar", "red_giant", "planet", "binary"),
            kind = CelestialKind.STAR,
            howToFind = "随处都是——稳定更新的频道全是主序星",
        ),
        Topic(
            key = "red_giant", emoji = "🟠", title = "红巨星",
            what = "核心的氢烧完后，恒星外层会膨胀数百倍、表面冷却变红——这就是红巨星。它曾稳定燃烧几十亿年，如今步入晚年。",
            inUniverse = "体量进全库前 30% 却超过 60 天没更新的频道=燃料耗尽的晚年巨星——巨大的存量是它膨胀的外壳。",
            related = listOf("star", "white_dwarf", "supernova"),
            kind = CelestialKind.RED_GIANT,
            howToFind = "找体量大却停更两个月以上的老频道",
        ),
        Topic(
            key = "white_dwarf", emoji = "⚪", title = "白矮星",
            what = "中小质量恒星的遗骸：燃料耗尽后坍缩成地球大小的致密核心，不再聚变，靠余热还能发光上百亿年，然后慢慢冷却。",
            inUniverse = "断更超过 180 天的频道=死而不灭的致密残骸——存量还在发光，只是再没有新的辐射。",
            related = listOf("red_giant", "neutron_star", "black_hole"),
            kind = CelestialKind.WHITE_DWARF,
            howToFind = "找断更超过半年的老频道",
        ),
        Topic(
            key = "supernova", emoji = "☄", title = "超新星",
            what = "恒星死亡时的猛烈爆发，几周内亮度可以盖过整个星系——爆发把重元素撒向宇宙，成为下一代恒星和行星的原料。",
            inUniverse = "近 7 天发布量暴涨（≥3 条且超过前 7 天两倍）的频道=正在爆发的超新星——宇宙导演器会给它加演出。死亡抛出的物质 enrich 星云——下一代恒星从灰烬中诞生。",
            related = listOf("neutron_star", "black_hole", "white_dwarf", "nebula", "gravity_wave"),
            kind = CelestialKind.SUPERNOVA,
            howToFind = "盯最近突然爆发的频道——更新量翻倍的就是",
        ),
        Topic(
            key = "neutron_star", emoji = "🌀", title = "中子星",
            what = "大质量恒星爆发后留下的致密核心：一汤勺物质就有上亿吨，引力强到把原子压成中子的海洋。",
            inUniverse = "大体量、爆发过、然后骤停的频道宣判为中子星——简并态坍缩余烬（体量过简并门 ∧ 爆发峰在死亡前 90 天内 ∧ 骤停档）。也许有也许没有，正是它该有的稀有。",
            related = listOf("pulsar", "supernova", "quark_star", "black_hole"),
            // R331 图鉴奇观册 howToFind 补值（inUniverse 零重写——只补 null 学习路径钩子）
            kind = CelestialKind.NEUTRON_STAR,
            howToFind = "找爆发过又骤停的大体量频道——宣判后它坐在遗迹环的中心（全库诚实计数，也许一颗也没有）",
        ),
        Topic(
            key = "quark_star", emoji = "❖", title = "夸克星",
            what = "理论预言的天体：密度高到连中子都被压碎成夸克的海洋——介于中子星与黑洞之间的极端中间态，人类至今没有确认过一颗。",
            inUniverse = "比中子星更苛刻的条件带：中子星门全过 ∧ 爆发烈度全库 outlier（≥其余中子候选峰值的 1.5×）——全库至多一颗甚至零。",
            related = listOf("neutron_star", "black_hole"),
            // R331 图鉴奇观册 howToFind 补值（「也许永远见不到」正是它该有的地位——r315 L① 口径）
            kind = CelestialKind.QUARK_STAR,
            howToFind = "全库爆发烈度最极端的存在——比中子星候选还猛烈 1.5 倍才宣判，也许永远见不到（它该有的稀有）",
        ),
        Topic(
            key = "black_hole", emoji = "⚫", title = "黑洞",
            what = "质量足够大的天体坍缩后，连光也逃不出它的引力——那个一去不返的边界叫事件视界。坠向它的物质会被撕成旋转的吸积盘，发出最后的光；它背后的星光也会被引力掰弯（引力透镜）。",
            inUniverse = "体量过奥本海默三重门（全库 P95 分位 ∧ 超全库均值 ∧ ≥800 条）∧ 大而坍缩（爆发后骤停断更 ∨ 吸积盛宴进行中）的最重频道宣判黑洞——全库最多 4 颗；它周围的星在屏上会被弯出弧线。",
            related = listOf("supernova", "quasar", "neutron_star", "quark_star"),
            kind = CelestialKind.BLACK_HOLE,
            howToFind = "全库体量最重的频道——拉高视角找带吸积盘的那几颗",
        ),
        Topic(
            key = "quasar", emoji = "✴", title = "类星体",
            what = "星系核心的超大质量黑洞正在疯狂进食——吸积盘的亮度可以超过整个星系，是宇宙中最亮的持续光源，往往远在百亿光年之外。",
            inUniverse = "黑洞的高活动态（进食档）——不改天体类型：黑洞中近 7 天发布 ≥8 条（吸积盛宴门）者为类星体相，比黑洞更亮更远。宣判已接线：盛宴中的黑洞出类星体相行（信息卡）+吸积盘增亮与喷流（星图）。",
            related = listOf("black_hole", "supernova"),
            kind = null, howToFind = null,
        ),
        Topic(
            key = "white_hole", emoji = "⛲", title = "白洞",
            what = "黑洞的时间反演解：物质只出不进、永不收纳——广义相对论的方程允许它存在，但人类从未观测到。",
            inUniverse = "只发不收的频道型源泉：被你大量收割（观看 ≥3h/看过 ≥30 条/T1 重仓）却零回馈（零赞零藏零弹幕）的频道宣判为白洞——bot 仍归脉冲星（应答服务性），白洞=被收割的源泉。",
            related = listOf("black_hole", "pulsar"),
            // R331 图鉴奇观册 howToFind 补值
            kind = CelestialKind.WHITE_HOLE,
            howToFind = "找被你大量收割却从没点赞、收藏、发过弹幕的频道——只发不收的源泉（互动账缺席时诚实不判）",
        ),
        Topic(
            key = "pulsar", emoji = "📡", title = "脉冲星",
            what = "高速自转的中子星，两极强磁场喷出射电束，像灯塔一样周期性扫过地球——周期准得可以当宇宙钟。",
            inUniverse = "bot=脉冲星——每一条自动消息都是一次规律的脉冲扫过。",
            related = listOf("neutron_star", "white_hole", "meteor"),
            kind = CelestialKind.PULSAR,
            howToFind = "bot 天生就是脉冲星——找自动应答的机器人",
        ),
        Topic(
            key = "binary", emoji = "☯", title = "双星",
            what = "两颗恒星被引力绑在一起绕共同质心转——银河系里过半的恒星都有伴星；离得越近转得越快（开普勒第三定律）。",
            inUniverse = "互相转发/引用频繁的频道对=双星：强证据（互转+互链+内容同源）过门才宣判，全图最多 6 对，每颗星只入一对。",
            // R321：related 补 lagrange（r315-I③——拉格朗日点的浮现路径 chips）
            related = listOf("star", "pulsar", "planet", "lagrange"),
            kind = null, howToFind = null,
        ),
        Topic(
            key = "planet", emoji = "🪐", title = "行星",
            what = "恒星形成时吸积盘剩下的边角料聚成的天体——不自己发光，靠反射恒星的光被看见。行星沿椭圆轨道绕恒星转：恒星在焦点上，近日点快、远日点慢，周期平方正比于半长轴立方（开普勒三定律）。",
            inUniverse = "频道的话题（tag）=行星：越核心的 tag 轨道越近（引力越强），每条轨道都是真开普勒椭圆——内圈的行星公转更快。年轻恒星的 tag=边角料正在聚成行星——信息卡「它的行星正在成型：K 个话题绕行中」。",
            related = listOf("star", "accretion_disk", "asteroid", "binary", "eclipse"),
            kind = null, howToFind = null,
        ),
        Topic(
            key = "asteroid", emoji = "🪨", title = "小行星",
            what = "行星形成时没聚成大行星的碎块——形状不规则，常成群结成小行星带，是行星系统的考古化石。",
            inUniverse = "频道的内容条目=尘埃与小行星：贴脸看一颗行星，条目按发布时间布相成尘埃环——角度分布一眼读出这个 tag 是最近爆发还是远古存货。",
            related = listOf("planet", "comet", "meteor"),
            kind = null, howToFind = null,
        ),
        Topic(
            key = "cluster", emoji = "🌌", title = "星团",
            what = "被共同引力绑在一起的恒星群——球状星团能有几十万颗成员，绕共同质心缓缓旋转几十亿年。",
            inUniverse = "群组=星团：成员与消息构成它的光云，规模直接画成直径。",
            related = listOf("star", "nebula", "binary"),
            kind = CelestialKind.CLUSTER,
            howToFind = "群组天生是星团",
        ),
        Topic(
            key = "comet", emoji = "🌠", title = "彗星",
            what = "冰与尘埃组成的脏雪球——靠近太阳时冰升华，拖出两条尾巴：笔直的离子尾和弯曲的尘埃尾，永远背对太阳。",
            inUniverse = "爬虫发现新频道=彗星掠过：宇宙导演器会放一颗彗星划过去，它掠过处可能点亮新星。",
            related = listOf("nebula", "protostar", "asteroid", "olbers"),
            kind = null, howToFind = null,
        ),
        Topic(
            key = "meteor", emoji = "💫", title = "流星",
            what = "闯进大气层的尘埃颗粒烧出的光迹——本身很小，烧完就没了；成群落下的就叫流星雨。",
            inUniverse = "你与真人的私聊=闯入内容星系的过客——不属于任何频道引力井，游荡在星系边缘。",
            related = listOf("asteroid", "comet", "pulsar"),
            kind = CelestialKind.METEOR,
            howToFind = "私聊会话就是流星——在星系边缘游荡",
        ),
        // ---- R319 宇宙底幕两词条（taste_cmb 单键一层：时间底色+空间边界——kind=null
        //      语义天体：底幕是背景层非天体，无枚举对位；浮现路径=nebula/comet 侧
        //      related 链 chips+信息卡 whyChain 视界行） ----
        Topic(
            key = "cmb", emoji = "🌫", title = "宇宙微波背景（CMB）",
            what = "大爆炸 38 万年后留下的余辉——宇宙从炽热等离子体冷却到光第一次能自由穿行，那最后一圈涟漪冻结成遍布全天的微波背景。它的温度涨落图是宇宙最古老的照片，也是「宇宙曾经炽热」的铁证。",
            inUniverse = "你的宇宙也有创世余辉：一片以你数据指纹为种子的极淡斑驳底纹，泡着整个星图。延时摄影回望越远，底色越热越红——距离=时间=温度三位一体（回望=看更热的时代）。要纯黑就全黑：设置页「宇宙底幕」开关一键关掉整层。",
            related = listOf("olbers", "nebula", "protostar"),
            kind = null, howToFind = null,
        ),
        Topic(
            key = "olbers", emoji = "🕳", title = "奥伯斯悖论·观测视界",
            what = "若宇宙无限且永恒，夜空应该亮如白昼——每条视线最终都该撞上一颗星。夜空是黑的，说明宇宙有年龄、有观测半径：绝大部分星的光，还没来得及到达你这里。",
            inUniverse = "你的可观测宇宙以「已见频道的包络」为界：视界内星光点点，视界外是真无星区（那边的光还没到——不是不存在）。金矿快照里已收录未入图的频道=光已出发正在途中，以极暗微点悬在视界带外圈（不可点——你还没观测到它）；彗星掠过时它们微微发亮，是在预告下一颗新星快到了。",
            related = listOf("cmb", "nebula", "comet"),
            kind = null, howToFind = null,
        ),
        // ---- R331 R320 在飞概念词条预落（r315 E/G 章③节原文照施工）：引力波+日月食
        //      =现象词条 kind=null 语义席位（不改枚举不进 when 穷尽面——演出与
        //      eventGloss 事件注归 R320 仲裁区零碰）；浮现路径=supernova/planet
        //      的 related chips+词条浏览器（R319 cmb/olbers 同模式零新交互） ----
        Topic(
            key = "gravity_wave", emoji = "≋", title = "引力波",
            what = "加速的质量会扰动时空、发出以光速传播的涟漪——我们看不见波本身，看见的是波经过时星点位置的微小抖动：LIGO 用两束 4 公里的激光臂，量出比质子还小亿万分之一的臂长变化，就是这么探测到引力波的。",
            inUniverse = "你的宇宙里，大事件（超新星爆发、黑洞与中子星宣判成形的时刻）会掀起一场时空涟漪：波前扫过全图时星星被轻轻推移又弹回——「间接可观测」正是引力波的真实味道。",
            related = listOf("supernova", "black_hole", "neutron_star"),
            kind = null,
            howToFind = "等一场大事件——超新星爆发或终态宣判时盯着全图，波前扫过处星星会轻轻晃一下再弹回",
        ),
        Topic(
            key = "eclipse", emoji = "🌑", title = "凌星与消光（日月食）",
            what = "一个天体从另一个前面经过、遮住光：行星横过恒星面叫凌星——恒星只变暗一小会儿，天文学家正是靠这点变暗找到了上千颗系外行星（开普勒望远镜的主业）；尘埃云飘到星前叫星际消光——星光被散射掉蓝端，剩下的变暗变红。",
            inUniverse = "贴脸恒星系时守着看：tag 行星沿开普勒轨道转到恒星正前方=凌星（恒星微暗一瞬）；雾团漂到星前=消光（那颗星暂时变暗偏红）。不造食——只有轨道几何真实对上才有，有的系统轨道面永远对不上=永远不凌星（诚实）。",
            related = listOf("planet", "nebula", "star"),
            kind = null,
            howToFind = "贴脸一个恒星系等行星转到恒星正前方（凌星）；或看哪片雾正漂过一颗星（消光）",
        ),
        // ---- R321 拉格朗日点词条（r315-I③/r331 预清单——批 4 概念：kind=null
        //      语义席位（不改枚举不进 when 穷尽面——R331 gravity_wave/eclipse 同
        //      模式）；浮现路径=binary 词条 related chips（共源双星对才有特洛伊簇
        //      =「韦伯望远镜就停在 L2」的现实应用） ----
        Topic(
            key = "lagrange", emoji = "☾", title = "拉格朗日点",
            what = "两个大质量天体连线上及其延长线上的引力平衡点（L1~L5）——航天器到了那里几乎不用耗燃料就能驻留。韦伯望远镜就停在日地 L2：躲在地球阴影侧、以固定距离跟着地球绕太阳。L4/L5 更特别：它们与两星构成等边三角形，引力与离心力在此稳定平衡，太阳系里木星轨道的 L4/L5 上聚着成千上万颗特洛伊小行星。",
            inUniverse = "选中一对有共源上游的双星（两频道都转发过同一个源）时，它们连线构成等边三角形的第三顶点处（L4/L5）驻着一小簇微亮尘埃=特洛伊群——簇的亮度随共源量变亮，且跟着双星绕转刚性移动。无共源的双星对那里是空的：拉格朗日点不是每对双星都有货的驿站，有货没货数据说话。",
            related = listOf("binary", "star", "asteroid"),
            kind = null, howToFind = null,
        ),
        // ---- R323 自行词条（r315-J③/r331 预清单——批 5 概念：kind=null 语义
        //      席位（数据判据件非天体种类——不改枚举不进 when 穷尽面，R331
        //      gravity_wave/eclipse/lagrange 同模式）；浮现路径=nebula 词条
        //      related chips（产房里的星胚长大也会漂走）+信息卡 whyChain 漂移行 ----
        Topic(
            key = "proper_motion", emoji = "➶", title = "自行",
            what = "恒星相对背景的真实位移（不是视差那种随季节摆动的假动作）——每年才挪几个角秒，肉眼一辈子都看不出来。巴纳德星是自行之王：每年 10.3 角秒，约 180 年就能挪出一个满月宽度，天文学家正是靠这种缓慢挪窝认出「这颗星在动」。",
            inUniverse = "频道的口味漂移=它的自行：近 90 天与再往前的 90 天，视频/图文/音频三票构成变了，星就在口味空间里挪窝。选中一颗在漂移的星，星旁那根极细的小尾巴指着它的去向（方向=三基色同族：偏视频朝红、偏图文朝青、偏音频朝紫）；信息卡会告诉你「过去半年从视频主导转向图文主导」。样本不足或没动=不画（诚实）。",
            related = listOf("nebula", "star", "protostar"),
            kind = null,
            howToFind = "选中不同的星看星旁——半年内口味变了的频道才长小尾巴，尾巴越长漂得越狠",
        ),
    )

    private val byKey: Map<String, Topic> = TOPICS.associateBy { it.key }

    /** 按 key 查词条（null=key 不存在） */
    fun topic(key: String): Topic? = byKey[key]

    /** 天体类型 → 词条（全枚举必有对位——单测锚定） */
    fun topicOfKind(kind: CelestialKind): Topic = TOPICS.first { it.kind == kind }

    // ==================== 本星实况（「这颗星对应你库里什么数据」） ====================

    /**
     * 本星实况输入（装配/信息卡既有字段直供；哨兵约定：recent7/prev7 -1=无金矿档案，
     * lastActivityDays/firstSeenDays -1=未知，meanItems 0=无统计，volumeRank01 0=
     * 无索引，memberCount ≤0=未知，partnerTitle null=无双星）。
     */
    class LiveInput(
        val kind: CelestialKind,
        val items: Int,
        val recent7: Int,
        val prev7: Int,
        val lastActivityDays: Long,
        val firstSeenDays: Long,
        val meanItems: Float,
        val volumeRank01: Float,
        val spectral: TasteAstroPhysics.SpectralClass,
        val memberCount: Int,
        val partnerTitle: String?,
        val partnerWeight: Int,
    )

    /**
     * 本星实况条目表（展开科普段「这颗星」子段）：每条一个维度，**缺据整条不出**
     * （绝不编造数字）；温度谱恒出（spectralClassOf 自带诚实缺省——双缺=G 型中性）。
     */
    fun liveFacts(i: LiveInput): List<String> = buildList {
        // 质量档（存量+分位）
        if (i.items > 0) {
            add(
                "质量档：存量 ${fmtItems(i.items)}" +
                    if (i.volumeRank01 > 0f) {
                        " · 体量分位 ${(i.volumeRank01 * 100).toInt()}%（越大越重）"
                    } else {
                        ""
                    },
            )
        }
        // 光度（近账）
        if (i.recent7 >= 0) {
            add(
                "光度：近 7 天发布 ${i.recent7} 条" +
                    if (i.prev7 >= 0) "（前 7 天 ${i.prev7} 条）" else "",
            )
        } else if (i.lastActivityDays >= 0) {
            add("光度：金矿近账未建档——按索引观测，它 ${i.lastActivityDays} 天前还有辐射")
        }
        // 温度谱（spectralClassOf 双缺=G 型中性缺省——诚实标注）
        if (i.kind == CelestialKind.STAR || i.kind == CelestialKind.PROTOSTAR ||
            i.kind == CelestialKind.RED_GIANT || i.kind == CelestialKind.WHITE_DWARF ||
            i.kind == CelestialKind.SUPERNOVA
        ) {
            add(
                "温度谱：${i.spectral.label}" +
                    if (i.recent7 < 0 && i.lastActivityDays < 0L) "（缺据中性缺省，不冒炽热不冒暗红）" else "",
            )
        }
        // 年龄
        if (i.firstSeenDays >= 0) add("年龄：首见至今 ${i.firstSeenDays} 天")
        // 断更
        if (i.lastActivityDays >= 0) add("距上次辐射：${i.lastActivityDays} 天")
        // 奥本海默倍率（黑洞专段）
        if (i.kind == CelestialKind.BLACK_HOLE && i.items > 0 && i.meanItems > 0f) {
            add("奥本海默倍率：全库均值的 ${"%.1f".format(i.items / i.meanItems)} 倍")
        }
        // 星团成员规模
        if (i.kind == CelestialKind.CLUSTER && i.memberCount > 0) {
            add("成员规模：${fmtMember(i.memberCount)} 名成员构成光云")
        }
        // 双星伴侣
        if (i.partnerWeight > 0 && !i.partnerTitle.isNullOrBlank()) {
            add("双星伴侣：「${i.partnerTitle}」· ${i.partnerWeight} 条关联证据")
        }
    }

    // ==================== 「为什么」解释链 ====================

    /** 「为什么」链条目（一问一答——answer 全数据驱动，缺据诚实回落） */
    class WhyItem(val question: String, val answer: String)

    /**
     * 「为什么」解释链输入（信息卡装配既有字段直供）：
     *  - [compVideo]/[compPhoto]/[compAudio]=构成三票（[compFromWatch]=true 观看秒数
     *    /false 索引基因条数——调用方选好源：观看构成优先，无观看走基因）；
     *  - [watchDaysAgo]=距最后一次观看的天数（-1=没看过）；[watchSec]=累计观看秒；
     *  - [degree]=无向互联边数（互链/共现——⑧ 星域聚簇行的本星数字，0=无互联
     *    证据走诚实句）；
     *  - [binaryWeight]=双星合并关联证据（0=无伴星——伴星条目不出）。
     */
    class WhyInput(
        val kind: CelestialKind,
        val items: Int,
        val recent7: Int,
        val prev7: Int,
        val lastActivityDays: Long,
        val firstSeenDays: Long,
        val volumeRank01: Float,
        val meanItems: Float,
        val spectral: TasteAstroPhysics.SpectralClass,
        val compVideo: Long,
        val compPhoto: Long,
        val compAudio: Long,
        val compFromWatch: Boolean,
        val watchDaysAgo: Long,
        val watchSec: Long,
        val watchedCount: Int,
        val degree: Int,
        val binaryWeight: Int,
        val memberCount: Int,
        /** R319 观测视界半径比（|sp|/R_obs；-1=奥伯斯带缺席缺据——第七问整条不出） */
        val horizonEdge01: Float = -1f,
        /** R352 反茧房：茧房外差集计数（金矿快照已收录未入图的全量——OlbersZone
         *  .rawDiff 单一出处；-1=zone 缺席缺据/0=零差集（两态面板侧不可分——诚实
         *  不冒充）；>0 时第七问追加「茧房外还有 N 个」句，行本体与门零改） */
        val horizonBeyondN: Int = -1,
        /** R323 自行漂移注记（[ProperMotionFx.Drift]——两窗构成差宣判产出；
         *  null=缺据（任一窗<8 条）/零漂移（差<门）/未入池——漂移行整条不出） */
        val drift: ProperMotionFx.Drift? = null,
    )

    /**
     * 「为什么」解释链（固定问序：类型→颜色→大小→亮度→位置→星域→尺度；伴星/
     * 观测视界两条件行按门插在 ⑤ 与星域行之间）：分类器判据矩阵/构成色相/体量
     * 半径/亮度双源/布局/R283 双星证据门/R319 观测视界/R303c 涌现布局与 σ 尺度
     * 旅程（⑧⑨=R331 补行）翻译成人话且带本星数字；任何数字缺据=该句走诚实回落，
     * 绝不编造。
     */
    fun whyChain(i: WhyInput): List<WhyItem> = buildList {
        // ---- ① 它为什么是{类型}（分类器判据翻译） ----
        val label = TasteGalaxyClassifier.labelOf(i.kind)
        add(
            WhyItem(
                "它为什么是$label？",
                when (i.kind) {
                    CelestialKind.SUPERNOVA -> buildString {
                        append("近 7 天 ${i.recent7} 条 ≥ ${TasteGalaxyClassifier.SUPERNOVA_MIN_RECENT7} 条门槛")
                        if (i.prev7 > 0) {
                            append("，且是前 7 天 ${i.prev7} 条的 ${"%.1f".format(i.recent7.toFloat() / i.prev7)} 倍 > ${TasteGalaxyClassifier.SUPERNOVA_RATIO.toInt()} 倍爆发线")
                        } else if (i.prev7 == 0) {
                            append("，而前 7 天一条都没有")
                        }
                        append("——正在猛烈发光的瞬态，盖过一切稳态判据")
                    }
                    CelestialKind.WHITE_DWARF ->
                        "断更 ${i.lastActivityDays} 天 > ${TasteGalaxyClassifier.DWARF_DAYS} 天死亡线" +
                            (if (i.items > 0) "，存量 ${fmtItems(i.items)}确在" else "") +
                            "——停止辐射的致密残骸（死亡终态盖过冷却/新生）"
                    CelestialKind.RED_GIANT ->
                        "体量分位 ${(i.volumeRank01 * 100).toInt()}% ≥ ${(TasteGalaxyClassifier.GIANT_RANK * 100).toInt()}% 巨存量门" +
                            "，且断更 ${i.lastActivityDays} 天 > ${TasteGalaxyClassifier.ACTIVE_DAYS} 天活跃门" +
                            "——巨存量+冷却=膨胀的晚年"
                    CelestialKind.PROTOSTAR ->
                        "首见至今才 ${i.firstSeenDays} 天 < ${TasteGalaxyClassifier.PROTOSTAR_DAYS} 天新生线——刚诞生的频道还在吸积"
                    CelestialKind.BLACK_HOLE -> buildString {
                        append("终态宣判·大而坍缩：奥本海默三重门全过（体量分位 ${(i.volumeRank01 * 100).toInt()}% ≥ ${(TasteAstroPhysics.BLACK_HOLE_RANK * 100).toInt()}%")
                        if (i.meanItems > 0f) append("、存量 ${fmtItems(i.items)}≥ 全库均值 ${fmtItems(i.meanItems.toInt())}")
                        append("、≥${TasteAstroPhysics.BLACK_HOLE_MIN_ITEMS} 条下限）∧ 爆发史 ∧（已坍缩（骤停断更）∨ 正在坍缩（吸积盛宴））——非「大而晋」：大而未坍缩者只是巨星")
                    }
                    CelestialKind.NEUTRON_STAR -> buildString {
                        append("终态宣判·大而坍缩：体量过简并门（分位 ${(i.volumeRank01 * 100).toInt()}% ≥ ${(TasteAstroPhysics.NEUTRON_RANK * 100).toInt()}%")
                        if (i.items > 0) append("、${fmtItems(i.items)}")
                        append("）∧ 爆发峰在死亡前 ${TasteAstroPhysics.NEUTRON_DEAD_DAYS} 天内 ∧ 断更 ${i.lastActivityDays} 天 > ${TasteAstroPhysics.NEUTRON_DEAD_DAYS} 天 ∧ 骤停档——爆发→骤停=核心坍缩瞬间的余烬")
                    }
                    CelestialKind.QUARK_STAR -> buildString {
                        append("终态宣判·最苛刻：中子星门全过 ∧ 爆发烈度全库 outlier（爆发峰桶值 ≥ 其余中子候选的 ${TasteAstroPhysics.QUARK_BURST_MULT} 倍）——密度比中子星更极端的中间态，全库至多 ${TasteAstroPhysics.MAX_QUARK} 颗")
                    }
                    CelestialKind.WHITE_HOLE ->
                        "收割零回馈门全过：高收割（观看 ≥${TasteAstroPhysics.WHITE_HOLE_MIN_WATCH_SEC / 3600}h/看过 ≥${TasteAstroPhysics.WHITE_HOLE_MIN_WATCHED} 条/T1 重仓任一）∧ 零赞零藏零弹幕 ∧ 频道型——只出不进的源泉（订阅是单向关注不算回馈）"
                    CelestialKind.PULSAR -> "实体类型=bot——自动应答的每条消息都是一次脉冲扫过（实体判型优先于六态矩阵）"
                    CelestialKind.CLUSTER ->
                        "实体类型=群组" +
                            (if (i.memberCount > 0) "（${fmtMember(i.memberCount)} 名成员）" else "") +
                            "——成员与消息绑成的光云（实体判型优先于六态矩阵）"
                    CelestialKind.METEOR -> "实体类型=私聊——不属于内容星系的过客（实体判型优先于六态矩阵）"
                    CelestialKind.STAR ->
                        if (i.lastActivityDays < 0L && i.recent7 < 0) {
                            "金矿档案与索引证据都不足——宇宙纪律是「绝不冒充特殊天体」，证据缺席时诚实缺省为恒星"
                        } else {
                            "近 ${TasteGalaxyClassifier.ACTIVE_DAYS} 天内有新的内容辐射，且没撞上爆发/死亡/巨存量任何一条特殊判据——稳定燃烧的主序星" +
                                (if (i.lastActivityDays >= 0L) "（它 ${i.lastActivityDays} 天前还在更新）" else "")
                        }
                },
            ),
        )
        // ---- ② 它为什么是这种颜色（构成色相+温度谱光晕） ----
        when (i.kind) {
            CelestialKind.PULSAR, CelestialKind.CLUSTER, CelestialKind.METEOR ->
                add(WhyItem("它为什么是这种颜色？", "实体天体的颜色=身份色——笔刷即语义，不随内容构成变化"))
            else -> {
                val total = i.compVideo + i.compPhoto + i.compAudio
                if (total > 0) {
                    val domName: String
                    val domPct: Int
                    when {
                        i.compVideo >= i.compPhoto && i.compVideo >= i.compAudio -> {
                            domName = "视频"; domPct = (i.compVideo * 100 / total).toInt()
                        }
                        i.compPhoto >= i.compAudio -> {
                            domName = "图文"; domPct = (i.compPhoto * 100 / total).toInt()
                        }
                        else -> {
                            domName = "音频"; domPct = (i.compAudio * 100 / total).toInt()
                        }
                    }
                    val hueWord = when (domName) {
                        "视频" -> "暖橙"
                        "图文" -> "青绿"
                        else -> "紫"
                    }
                    add(
                        WhyItem(
                            "它为什么是这种颜色？",
                            "本体色=内容构成定色相——这颗星$domName 占 $domPct%（${if (i.compFromWatch) "按你看过的构成" else "按索引基因构成"}），色相向$hueWord 靠拢；" +
                                "同构成的星再用身份哈希微调开，远看按类型成簇、近看颗颗可辨。" +
                                "光晕色温=${i.spectral.label}——更新越勤越蓝白炽热，越久不更越红暗",
                        ),
                    )
                } else {
                    add(
                        WhyItem(
                            "它为什么是这种颜色？",
                            "还没有构成证据（未观看且索引未建档）——按暗星色诚实缺省，绝不乱配色",
                        ),
                    )
                }
            }
        }
        // ---- ③ 它为什么是这个大小（体量半径判定） ----
        when (i.kind) {
            CelestialKind.PULSAR, CelestialKind.METEOR ->
                add(WhyItem("它为什么是这个大小？", "实体天体的大小=固定小体档——笔刷身份即语义，体量不冒充"))
            CelestialKind.CLUSTER ->
                add(
                    WhyItem(
                        "它为什么是这个大小？",
                        "星团核=固定基础+体量微调" +
                            (if (i.memberCount > 0) "；成员尘点云的规模按 ${fmtMember(i.memberCount)} 名成员画" else ""),
                    ),
                )
            else -> {
                if (i.items > 0) {
                    add(
                        WhyItem(
                            "它为什么是这个大小？",
                            "半径=体量主轴：√(存量÷全库最大) 再压重尾——存量 ${fmtItems(i.items)}" +
                                (if (i.volumeRank01 > 0f) "、分位 ${(i.volumeRank01 * 100).toInt()}%" else "") +
                                (if (i.watchSec > 0) {
                                    "；你看过它" +
                                        (if (i.watchedCount > 0) " ${i.watchedCount} 条" else "") +
                                        "，观看也并入增重（0.65 体量+0.35 观看）"
                                } else {
                                    ""
                                }),
                        ),
                    )
                } else if (i.watchSec > 0) {
                    add(
                        WhyItem(
                            "它为什么是这个大小？",
                            "无索引存量证据，半径按你的观看账定档" +
                                (if (i.watchedCount > 0) "（看过 ${i.watchedCount} 条）" else ""),
                        ),
                    )
                } else {
                    add(WhyItem("它为什么是这个大小？", "无索引无观看证据=暗星小点——绝不冒充大星"))
                }
            }
        }
        // ---- ④ 它为什么是这个亮度（双源取强） ----
        run {
            val parts = ArrayList<String>(3)
            if (i.watchDaysAgo >= 0L) parts += "你 ${i.watchDaysAgo} 天前看过它（观看新鲜度打底）"
            if (i.lastActivityDays >= 0L) parts += "它 ${i.lastActivityDays} 天前还有辐射（内容活跃度发光）"
            add(
                WhyItem(
                    "它为什么是这个亮度？",
                    if (parts.isEmpty()) {
                        "亮度双源（你看过的鲜度 ∨ 它最近的活跃）都没有证据——按稳定可见的暗点档缺省"
                    } else {
                        "亮度=两源取强：" + parts.joinToString("；") + "——近 60 天线性衰减"
                    },
                ),
            )
        }
        // ---- ⑤ 它为什么在宇宙的这个位置（球面化布局+N 体） ----
        add(
            WhyItem(
                "它为什么在宇宙的这个位置？",
                "方位=口味朝向（内容基因在高维口味空间的指向，口味相近的星聚在同一片天区）；" +
                    "高度=全天球均匀散布（抬头低头都有星）；" +
                    "距离=体量分位（越重的星离你越近" +
                    (if (i.volumeRank01 > 0f) "——它的分位 ${(i.volumeRank01 * 100).toInt()}%" else "") +
                    "）；引力井与星系自转让它活着微动",
            ),
        )
        // ---- ⑥ 它为什么有伴星（R283 双星证据门——无伴星整条不出） ----
        if (i.binaryWeight > 0) {
            add(
                WhyItem(
                    "它为什么有伴星？",
                    "双星宣判过两道门：强证据门（互转+互链+内容同源 ≥${BinaryStarPolicy.MIN_STRONG_EVIDENCE} 条，共现这类弱证据单独不过门）" +
                        "∧ 维度分门（持续且近期活跃的互联还有时间加成）——这对有 ${i.binaryWeight} 条合并关联证据；每颗星最多只入一对",
                ),
            )
        }
        // ---- ⑥' 它为什么有星风（R321 r315-F③/r331 预清单——星风门 [StarwindFx.isWindy]
        //      同一单口（recent7≥14 从 activity01Of 饱和口径导出+degree>0 外流通道）；
        //      缺据（门外/无通道/无金矿档案）整条不出现，绝不编造；渲染门同源——
        //      有星风的星贴脸能看到沿自转轴两侧的粒子流（R376 远观星风泡环退役
        //      ——灰圈否决史实见 StarwindFx KDoc；本行文案随视觉本体同步除泡） ----
        if (StarwindFx.isWindy(i.recent7, i.degree)) {
            add(
                WhyItem(
                    "它为什么有星风？",
                    "本周发布 ${i.recent7} 条（≥14 条/周=高活跃档，与光谱型判定同一饱和口径）+ ${i.degree} 条互链/共现边当外流通道" +
                        "——高活跃恒星在向外吹物质流：贴脸能看到沿自转轴两侧的白色粒子流；" +
                        "更新不这么勤的星没有风（微风不可见=诚实）",
                ),
            )
        }
        // ---- ⑥'' 它的口味在漂移吗（R323 r315-J③/r331 预清单——自行漂移行：
        //      [ProperMotionFx.Drift] 注记直供（两窗构成差宣判产出）；缺据（任一窗
        //      <8 条样本太少）与零漂移（差<门「没动」也是结论）整条不出——绝不
        //      硬找方向；数字全两窗直方图实况（近窗Y A% vs 远窗X B% 口径） ----
        if (i.drift != null) {
            val d = i.drift!!
            val nearTotal = d.nearVideo + d.nearPhoto + d.nearAudio
            val farTotal = d.farVideo + d.farPhoto + d.farAudio
            fun voteName(v: Int, p: Int, a: Int): String = when {
                v >= p && v >= a -> "视频"
                p >= a -> "图文"
                else -> "音频"
            }
            fun pct(n: Int, total: Int): Int = (n * 100 / total).toInt()
            val toName = voteName(d.nearVideo, d.nearPhoto, d.nearAudio)
            val fromName = voteName(d.farVideo, d.farPhoto, d.farAudio)
            val nearPct = pct(
                when (toName) {
                    "视频" -> d.nearVideo; "图文" -> d.nearPhoto; else -> d.nearAudio
                },
                nearTotal,
            )
            val farPct = pct(
                when (fromName) {
                    "视频" -> d.farVideo; "图文" -> d.farPhoto; else -> d.farAudio
                },
                farTotal,
            )
            val shift = if (toName != fromName) {
                "过去半年从${fromName}主导转向${toName}主导（近窗${toName} ${nearPct}% vs 远窗${fromName} ${farPct}%）"
            } else {
                "过去半年${toName}占比从 ${farPct}% 挪到 ${nearPct}%（近窗 vs 远窗）"
            }
            add(
                WhyItem(
                    "它的口味在漂移吗？",
                    "在漂移——$shift。近 90 天与再前的 90 天构成变了，星就在口味空间里挪窝" +
                        "（巴纳德星式的自行：慢，但真实在动）。选中它时星旁那根极细的小尾巴指着去向" +
                        "（方向与三基色同族：偏视频朝红、偏图文朝青、偏音频朝紫，长度=漂得越狠越长）；" +
                        "样本不足或没动的星不长尾巴（诚实）",
                ),
            )
        }
        // ---- ⑦ 它为什么贴近观测视界（R319 奥伯斯暗区——缺据/内部星整条不出） ----
        if (i.horizonEdge01 >= 0.85f) {
            add(
                WhyItem(
                    "它为什么贴近观测视界？",
                    "它的轨道半径已达你可观测宇宙边缘的 ${(i.horizonEdge01 * 100).toInt()}%——夜空是黑的，因为那边的光还没到（奥伯斯悖论）；" +
                        "视界外不是没有星，是光还在路上：金矿快照里已收录未入图的频道，正以极暗微点悬在视界带外圈（不可点——你还没观测到它）" +
                        // R352 反茧房句：茧房外差集计数（rawDiff 全量真值——成就面板
                        // 「探索边界」段同一个 N 同源；≤0=缺据/零差集句不追加，
                        // 缺省文案与门原样零改——既有 ⑦ 测试锚不动）
                        (if (i.horizonBeyondN > 0) "——此刻茧房外共有 ${i.horizonBeyondN} 个频道的光还在路上" else "") +
                        "。整片宇宙泡在大爆炸的微波余辉底色里（设置页「宇宙底幕」开关：要纯黑就全黑）",
                ),
            )
        }
        // ---- ⑧ 它为什么聚在这片星域（R303c 涌现布局——R331 补行：四力口径与设置页
        //      开关行同源（纪律§一），degree（WhyInput 既有字段——UI 构造面直供）=
        //      本星互联数字；零证据走诚实句不冒充 ----
        add(
            WhyItem(
                "它为什么聚在这片星域？",
                "星域不是手画的——涌现布局用四种作用力摆星：互链、tag 指纹、口味距离的引力弹簧把相关的" +
                    "频道拉近，斥力防重叠，活跃度衰变让死星缓缓外漂；星系的形状=这些力的平衡涌现出来的" +
                    "（设置页「涌现布局」开关，关=回到经典球面布局）。" +
                    if (i.degree > 0) {
                        "这颗有 ${i.degree} 条互链/共现边——它的邻居就是被引力弹簧拉到一起的"
                    } else {
                        "这颗没有互联证据——独自飘在自己的口味朝向上，只被近旁引力井微微拉弯"
                    },
            ),
        )
        // ---- ⑨ 拉远看时它为什么缩成一粒光点（R303c σ 尺度+银河视角+乘法缩放——
        //      R331 补行：尺度旅程族一行收三概念，数字口径与 TasteScaleSigma 同源：
        //      σ=0 贴脸停船位/银河位 σ≈4.75=银心距 7000；层显隐=σ 连续函数零跳变；
        //      高空捏合提速=乘法缩放曲线（速度∝高度） ----
        add(
            WhyItem(
                "拉远看时，它为什么缩成一粒光点？",
                "你的宇宙用对数尺度 σ 量远近（σ=0=贴脸停船位，距离每远一格翻倍）：贴脸看得清尘埃带与" +
                    "行星轨道，退到 σ≈3 恒星系缩成带辉光的符号，σ≈4.75 是银河位——双击空白处一键退到" +
                    "那里俯瞰全银河，几百颗星融成一片光雾，正如夜空里的银河本来就只是一条雾带" +
                    "（你看到的从来不是每颗星）。一路拉远层与层在 σ 上连续淡出、零跳变；高空捏合自动" +
                    "提速（速度∝高度）——跨这么大的尺度，就是这样捏得动的",
            ),
        )
    }

    // ==================== 点亮即时科普小卡 ====================

    /**
     * 点亮小卡一句话（发现时刻 overlay）：类型名+一句真天文+本星一个数字（缺据=
     * 无数字变体，绝不编造）。恒非空、恒单行（UI 侧 maxLines=2 兜底）。
     * R327 年轻变体（r325 D①——会话内通道零缓存依赖：kind 判别在场即判）：
     * [birth] 注记非空（宣判后）∨ kind==PROTOSTAR（六态年轻档——宣判窗前
     * 照判，数据全在场运行时判）→「✧ 新星诞生！」变体；老星=原分支零改。
     * 文案调性=r325 待用户过目项④默认。
     */
    fun instantLine(
        kind: CelestialKind,
        items: Int,
        recent7: Int,
        lastActivityDays: Long,
        firstSeenDays: Long,
        birth: TasteAstroPhysics.BirthFeatures? = null,
    ): String {
        // R327 年轻变体早退：带诞生注记（非 NOT_BIRTH）∨ 六态 PROTOSTAR（年轻档）
        if (kind == CelestialKind.PROTOSTAR ||
            (birth != null && birth.stage != TasteAstroPhysics.BirthStage.NOT_BIRTH)
        ) {
            return if (firstSeenDays >= 0) {
                "✧ 新星诞生！星云的尘埃聚成了它——诞生才 $firstSeenDays 天"
            } else {
                "✧ 新星诞生！星云的尘埃聚成了它"
            }
        }
        return when (kind) {
        CelestialKind.SUPERNOVA ->
            if (recent7 >= 0) "爆发中——近 7 天 $recent7 条，亮度正盖过星系" else "正在猛烈爆发的超新星"
        CelestialKind.WHITE_DWARF ->
            if (lastActivityDays >= 0) "死而不灭的致密残骸——它已停更 $lastActivityDays 天" else "死而不灭的致密残骸"
        CelestialKind.RED_GIANT ->
            if (lastActivityDays >= 0) "燃料耗尽的晚年巨星——它已 ${lastActivityDays / 30} 个月没更新" else "燃料耗尽的晚年巨星"
        CelestialKind.PROTOSTAR ->
            if (firstSeenDays >= 0) "还没点燃的恒星胚胎——这个频道诞生才 $firstSeenDays 天" else "还没点燃的恒星胚胎"
        CelestialKind.BLACK_HOLE ->
            if (items > 0) "全库最重的天体（${fmtItems(items)}）——连光都逃不出它" else "全库最重的天体——连光都逃不出它"
        CelestialKind.NEUTRON_STAR ->
            if (lastActivityDays >= 0) "爆发后骤停的坍缩余烬——它已沉寂 $lastActivityDays 天" else "爆发后骤停的坍缩余烬"
        CelestialKind.QUARK_STAR -> "全库爆发烈度最极端的存在——比中子星更密的中间态"
        CelestialKind.WHITE_HOLE -> "只出不进的源泉——你收割了它，却从未回馈"
        CelestialKind.PULSAR -> "周期性扫过的脉冲——每条自动消息都是一次脉冲"
        CelestialKind.CLUSTER -> "被引力绑在一起的光云——群组即星团"
        CelestialKind.METEOR -> "闯入星系的过客——私聊不属于内容星系"
        CelestialKind.STAR -> "稳定核聚变的主序星——持续更新就是它的燃烧"
        }
    }

    /**
     * 点亮播报选星（一拍点亮多颗时选最值得播报的一颗）：类型稀有度序
     * 黑洞>超新星>白矮星>红巨星>原恒星>脉冲星>星团>流星>恒星，平手 chatId 小者先
     * ——确定性（同输入同输出，单测锚定）。空表=null。
     * R327 年轻位内插（r325 D①——rarityRankOf 原恒位序**不动**，同 kind 内
     * [youngIds] 成员插到该档恒位之前：新星诞生比「又点开一颗老星」更值得播报；
     * 跨档不越权（R317 四奇观插队头部先例的微调版——原相对序零改）。
     */
    fun pickToastStar(
        lit: List<Pair<Long, CelestialKind>>,
        youngIds: Set<Long> = emptySet(),
    ): Long? {
        if (lit.isEmpty()) return null
        return lit.minWithOrNull(
            compareBy({ rarityRankOf(it.second) }, { if (it.first in youngIds) 0 else 1 }, { it.first })
        )?.first
    }

    /** 播报稀有度序（小=稀有大播报——只排点亮序，与任何成就结构无关；R317：夸克>黑洞>中子星>白洞四奇观插队头部，六态原相对序不动） */
    private fun rarityRankOf(kind: CelestialKind): Int = when (kind) {
        CelestialKind.QUARK_STAR -> 0
        CelestialKind.BLACK_HOLE -> 1
        CelestialKind.NEUTRON_STAR -> 2
        CelestialKind.WHITE_HOLE -> 3
        CelestialKind.SUPERNOVA -> 4
        CelestialKind.WHITE_DWARF -> 5
        CelestialKind.RED_GIANT -> 6
        CelestialKind.PROTOSTAR -> 7
        CelestialKind.PULSAR -> 8
        CelestialKind.CLUSTER -> 9
        CelestialKind.METEOR -> 10
        CelestialKind.STAR -> 11
    }

    // ==================== 相关天体推荐 ====================

    /**
     * 相关天体推荐（科普段 chips 原料）：词条.related 序直出；count=kind 对位词条的
     * 宇宙内计数（null=无枚举对位的语义天体/预留席位——不带计数，0=「还没见到」
     * 诚实标注）。
     */
    fun relatedWithCounts(key: String, counts: Map<CelestialKind, Int>): List<Pair<Topic, Int?>> {
        val t = byKey[key] ?: return emptyList()
        return t.related.mapNotNull { rk ->
            val rt = byKey[rk] ?: return@mapNotNull null
            rt to rt.kind?.let { counts[it] ?: 0 }
        }
    }

    // ==================== 学习路径钩子 ====================

    /**
     * 图鉴缺口提示（成就面板「见齐天体形态」勋章的科普钩子）：[seen] 之外的
     * [TasteAchievements.SIX_KINDS] 类各出一行「去哪找」；全见过=空表。
     */
    fun unseenKindHints(seen: Set<CelestialKind>): List<String> =
        TasteAchievements.SIX_KINDS
            .filter { it !in seen }
            .sortedBy { it.ordinal }
            .mapNotNull { k ->
                topicOfKind(k).howToFind?.let { "${TasteGalaxyClassifier.labelOf(k)}：$it" }
            }

    // ---- R331 终态奇观册（天体图鉴另册——勋章外奇观口径，四席清单=
    //      [TasteAchievements.WONDER_KINDS]；面板接线=挂账 TasteMapScreen 侧） ----

    /**
     * 奇观册缺口提示（成就面板「天体图鉴 · 终态奇观」段的科普钩子——R312
     * unseenKindHints 的奇观版）：[seen]（=kindCounts 键集直供）之外的
     * [TasteAchievements.WONDER_KINDS] 各出一行「去哪找」（稀有度序=清单序）；
     * 全见过=空表。黑洞与中子星/夸克星/白洞同册（宣判制天体一类同修）。
     */
    fun unseenWonderHints(seen: Set<CelestialKind>): List<String> =
        TasteAchievements.WONDER_KINDS
            .filter { it !in seen }
            .mapNotNull { k ->
                topicOfKind(k).howToFind?.let { "${TasteGalaxyClassifier.labelOf(k)}：$it" }
            }

    /**
     * 奇观册目击计数行（成就面板奇观册段的计数原料）：四席按稀有度序直出，
     * 「黑洞 4 颗 · 中子星 1 颗」——[counts] 缺席或 0=「还没见到」诚实标注
     * （与 [relatedWithCounts] 同哲学：图鉴的 0 不是错误是缺口本身）。
     * 空表输入=四席全「还没见到」（宣判未落/新库的诚实形态）。
     */
    fun wonderCountsLine(counts: Map<CelestialKind, Int>): String =
        TasteAchievements.WONDER_KINDS.joinToString(" · ") { k ->
            val n = counts[k] ?: 0
            val label = TasteGalaxyClassifier.labelOf(k)
            if (n > 0) "$label $n 颗" else "$label 还没见到"
        }

    /** 目击图鉴事件科普注（成就面板三事件行下的注脚——R282 导演器判据口径白话） */
    fun eventGloss(kind: UniverseDirector.EventKind): String = when (kind) {
        UniverseDirector.EventKind.COMET -> "彗星=爬虫带回的新频道——它掠过处可能点亮新星"
        UniverseDirector.EventKind.NOVA -> "新星=沉寂频道重新发光（前 7 天为零、近 7 天复更）——不是真爆发，是复燃"
        UniverseDirector.EventKind.SUPERNOVA -> "超新星=真爆发（近 7 天高频且翻倍）——导演器的演出级事件"
        // R320 编译哨点行（EventKind GRAVITY_WAVE 扩容的穷尽性补丁——枚举哨点设计
        // 的指定漏改点；红线例外申报见 r320_notes「AstroFacts 红线例外申报」章）
        UniverseDirector.EventKind.GRAVITY_WAVE -> "引力波=终态宣判（黑洞/中子星形成）的时空涟漪——星点被波前推移回弹"
    }

    // ---- R385 P4 语义联动族（design-nebula §3.3-4）：复合体在场报告行单口——
    //      摇篮计数/金矿候选云内计数/富集云计数三原料经参穿透（Stage 装配一次
    //      O(n)，core 纯件 JVM 单测可跑）；消费两口=成就面板报告行（wonderCountsLine
    //      行后同族样式）+信息卡词条浏览器增段行；缺据=行整条不出零冒充 ----

    /**
     * 星云复合体在场报告行（R385 §3.3-4——「nebula 词条增段+面板报告行」的动态
     * 计数半边）：[cradleN]=复合体团数（P2 装配派生）；[mostActiveCandidates]=
     * 金矿候选星在世界云内的计数（[DeepSpaceNebula.complexIndexOf] 容纳判定同口
     * O(n) 计数——「最活跃候选」的最诚实可得代理：候选本身无活跃度梯度，云内
     * 在场就是它将在这里点亮的证词）；[enrichN]=富集反射云团数（超新星还尘账）。
     * **摇篮=0=null 整行不出**（全库点亮=没有孕育区是成就非缺口——与
     * [edgeNurseryLine] 零雾 null 同哲学：缺据不冒充）；富集云缺席=截去还尘分句
     * 诚实（不编造「还尘 0 团」）。
     */
    fun nebulaComplexLine(cradleN: Int, mostActiveCandidates: Int, enrichN: Int): String? =
        if (cradleN > 0) {
            buildString {
                append("星云复合体在场：")
                append(cradleN)
                append(" 团大云悬在星系尺度，云内正孕育 ")
                append(mostActiveCandidates)
                append(" 个金矿候选")
                if (enrichN > 0) append("；超新星遗迹已还尘 ").append(enrichN).append(" 团富集云")
                append("——新星将从云里点亮，云前的星真实变暗偏红")
            }
        } else {
            null
        }

    // ---- R352 反茧房·探索边界段（成就面板叙事串联单口——core 纯件 JVM 单测可跑；
    //      消费奥伯斯视界/图鉴缺口/发射星云三素材的「看见茧房+破茧路径」行族，
    //      呈现归 TasteMapAchievementPanel；缺据=行整条不出零冒充） ----

    /**
     * 视界行（成就面板「🕳 探索边界」段首行——茧房边界的数字口径）：[beyondN]=
     * 金矿差集全量（[CmbBackdropFx.OlbersZone].rawDiff——「光在路上」的真实颗数，
     * 非渲染 120 帽截断后的暗点数）。>0 出行（尾挂彗星掠过预告钩子——C④ 暗点
     * 微亮机制的白话，「光快到了」的等待语义）；≤0（zone 缺席/金矿快照缺失/真零
     * 差集——三态面板侧不可分）=null 整行不出（缺据不冒充，R319 探针「快照缺席=
     * 零暗点缺据」同口径）。
     */
    fun edgeHorizonLine(beyondN: Int): String? =
        if (beyondN > 0) {
            "茧房外还有 ${beyondN} 个频道的光在路上（金矿已收录、还没入你的星图）——彗星掠过时它们微微发亮，是光快到了"
        } else {
            null
        }

    /**
     * 产房行（「🕳 探索边界」段末行——金矿摇篮指引=破茧路径）：[fogN]=未探索
     * 雾团数（TasteNebula 聚类）；[nurseryN]=发射星云团数（mapEmissionFogsR323
     * 平行表单口计数——成员过半金矿候选的团级门）；[nurseryChannels]/[fogChannels]
     * =产房雾/全部雾的成员频道合计（「藏着 P 个没看过」的诚实账）。
     * 产房在场=破茧去处句（亮的那团视觉本体 R323 已在图上）；无产房=普通暗雾
     * 诚实退句（金矿候选不过半不冒充摇篮——快照缺失结构上恒普通雾）；零雾=null
     * 整行不出（在图频道全看过=茧房内无雾，不编造「未探索」）。
     */
    fun edgeNurseryLine(fogN: Int, nurseryN: Int, nurseryChannels: Int, fogChannels: Int): String? = when {
        nurseryN > 0 ->
            "产房在场：${nurseryN} 团发射星云（亮的那团）——成员过半是金矿候选，共 ${nurseryChannels} 个频道还没看过；爬虫带回的新频道正聚在那儿，等着点亮成原恒星"
        fogN > 0 ->
            "还有 ${fogN} 团暗雾藏着 ${fogChannels} 个没看过的频道——还没有哪团亮成产房（成员里金矿候选过半的雾团才会亮）"
        else -> null
    }

    // ==================== 文案小工具 ====================

    /** 体量条数人性化=[TasteAstroPhysics.fmtItems] 单口（纪律§一收敛——不另写一份） */
    private fun fmtItems(count: Int): String = TasteAstroPhysics.fmtItems(count)

    /** 成员数人性化（≥1 万显示 x.x 万） */
    private fun fmtMember(count: Int): String =
        if (count >= 10000) "%.1f 万".format(count / 10000f) else "$count"
}
