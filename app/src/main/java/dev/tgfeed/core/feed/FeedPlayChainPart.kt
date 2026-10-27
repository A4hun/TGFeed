package dev.tgfeed.core.feed

import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dev.tgfeed.core.playback.PlayerPool
import dev.tgfeed.core.td.VideoItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ---------------- R392 播放编排三期:#5 自动连播链段尽消费 + #6 睡眠播完即停 ----------------
//
// 定位(r392b_notes §2.3):「队列段尽=供给尽 ∧ 消费尽」两闸串联的 VM 侧消费层
// ——供给尽=PlayQueueFeedSource.supplyExhausted 只读访问器(段走本体零改);
// 消费尽=分媒体三路 VM 层既有信号(播放链 UI 零碰判词,红线):
//  - 图/音:recordMediaViewImpl 播完上抛点(FeedWatchPart 首行一刀挂点);
//  - 视频:观看心跳读主位播放器 STATE_ENDED(FeedViewModel init 心跳循环一行
//    挂点;视频 ENDED 无 VM 上抛——VideoPage onEnded 纯 UI 回调,此为唯一
//    VM 侧可观测位;时效≤2s 心跳粒度,电台衔接语义可受纳,诚实注记)。
//
// 消费单口 [advancePlayChainAtQueueEnd](主线程模型纪律):
//  1. 单发门(playChainSettledSource 源实例身份):同源只结算一次——睡眠停/
//     自然停/静默待命/接棒均算已结算,防心跳每 2s 重复消费与日志刷屏;
//  2. **睡眠旗优先**(#6②,判词:睡眠旗>接棒——用户设了定时=要睡的意图最新
//     且明确):清睡眠旗(setSleepTimerImpl(null)=cancel+置 null)+复用
//     _sleepNow 既有信号(FeedScreen/VideoPage 既有消费面:暂停+「定时关闭
//     已暂停」中央胶囊=通知如实)+探针行→不接棒;睡眠停不受 autoAdvance 门
//     (它是停不是进);
//  3. 接棒解析 resolvePlayChain(纯函数):null=自然停(播完即停语义保留,
//     一期语义零变化);
//  4. **autoAdvance 门**(判词:接=推进动作,跟全局推进意愿):关=播完即停
//     在末条,接棒静默待命(本次段尽按自然停结算,不重挂不欠账);
//  5. 目标→openPlayStationImpl(target.id, fromStart=false):一期开台链
//     全继承(切台 flush/断点续播/置题=台名/_mediaTypeTab=-1/空单守卫)。
//
// 环路判词:接棒只在真实播完消费尽后触发(每环需真实播放贯穿),A→B→A
// 双向接棒=用户显式配置的循环电台语义,非失控环(无播放不推进);防御面=
// 单发门(同源一次)+空台死端(开单链空单守卫)+取数错误死端(限流/断连
// 错误上抛口径,队列停在该段待用户处置——一期既有口径)。
//
// 探针行口径:R392 tag,计数+台尾 6 位,零台名零频道名(脱敏铁律)。

/**
 * 图/音播完上抛点的段尽挂点(recordMediaViewImpl 首行一刀调用):
 * 在播源是队列源 ∧ 供给尽 ∧ 上抛条目=列表末条 ∧ 当前页=末位 → 段尽消费单口。
 * 图(PhotoCard 幻灯片走完一轮~4s 只抛一次)/音(AudioCard STATE_ENDED 上抛)
 * 的播完事件都在这里——记录画像与段尽消费互不干扰(先后顺序无耦合)。
 */
internal fun FeedViewModel.maybeAdvancePlayChainOnMediaEnd(item: VideoItem) {
    val src = source as? PlayQueueFeedSource ?: return // 非队列源:与本件无关
    if (!src.supplyExhausted) return // 供给未尽:还有后续页,播完的不是末条
    val items = _items.value
    if (items.isEmpty() || item.key != items.lastOrNull()?.key) return // 非末条
    if (_currentIndex.value != items.lastIndex) return // 落定页非末位(图集翻页中)
    advancePlayChainAtQueueEnd(src)
}

/**
 * 视频路的段尽挂点(观看心跳每 [WATCH_HEARTBEAT_MS]=2s 主线程调用,FeedViewModel
 * init 心跳循环一行一刀):供给尽 ∧ 末位视频 ∧ 当前计量条目=末条(播过才可能
 * 播完)∧ 主位播放器 STATE_ENDED(真播完,非暂停/缓冲)∧ 归属判式
 * (watchHeartbeatTick 同款:mediaId==key/uri==playUri/帧提供者在场——防
 * 悬浮窗/分屏的主位播放器误判)→ 段尽消费单口。
 * 媒体类型=视频门:图/音走各自播完上抛点(recordMediaView 挂点),防陈旧
 * 播放器状态误触发两路双计。
 */
internal fun FeedViewModel.maybeAdvancePlayChainOnTick() {
    val src = source as? PlayQueueFeedSource ?: return
    if (!src.supplyExhausted) return
    val items = _items.value
    val idx = _currentIndex.value
    if (items.isEmpty() || idx != items.lastIndex) return
    val last = items[idx]
    if (last.mediaType != 0) return // 视频路专属:图/音走上抛点
    val w = watchingAccum ?: return // 计量归属:当前计量条目=末条(settleWatchEvent onPage 起表)
    if (w.first.key != last.key) return
    val player = PlayerPool.lastActivePlayer ?: return
    val ended = runCatching { player.playbackState == Player.STATE_ENDED }
        .getOrDefault(false)
    if (!ended) return // 暂停/缓冲/在播都不是「播完」
    // 归属判式(watchHeartbeatTick 同款同暴露面,既有口径继承):主位播放器播的
    // 必须是末条——音频共享播放器 mediaId 约定/直链条目对地址/流式条目 Feed
    // 页帧提供者在场旁证
    val container = (app as? dev.tgfeed.App)?.container
    val owned = runCatching {
        val mediaId = player.currentMediaItem?.mediaId
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        when {
            !mediaId.isNullOrEmpty() -> mediaId == last.key
            !last.playUri.isNullOrEmpty() && uri != null -> uri == last.playUri
            else -> container?.currentFrameProvider != null
        }
    }.getOrDefault(false)
    if (!owned) return
    advancePlayChainAtQueueEnd(src)
}

/**
 * 段尽消费单口(主线程;两挂点殊途同归)。四步结算,一切分支都置单发门
 * (playChainSettledSource=src)防重复消费:
 * 睡眠旗优先停播 → 接棒解析(不接=自然停)→ autoAdvance 门(关=静默待命
 * 结算)→ 一期开台链开接棒台。
 */
internal fun FeedViewModel.advancePlayChainAtQueueEnd(src: PlayQueueFeedSource) {
    if (playChainSettledSource === src) return // 单发门:同源只结算一次
    val queue = playQueueModel.queue
    // ① 睡眠播完即停(#6②):队列耗尽且睡眠旗在→停播+清旗+既有胶囊通知如实;
    // 判词:睡眠旗>接棒(要睡的意图最新且明确,不接棒;接棒配置保留明日照常)
    if (sleepJob != null) {
        playChainSettledSource = src
        setSleepTimerImpl(null) // 清睡眠旗:cancel 计时+置 null(既有口)
        _sleepNow.tryEmit(Unit) // 复用既有到点信号:暂停+「定时关闭已暂停」中央胶囊
        android.util.Log.i(
            "R392",
            "睡眠播完即停:队列耗尽 清睡眠旗 台尾=${queue.id.takeLast(6)} 余条目=${queue.entries.size}",
        )
        return
    }
    // ② 接棒解析(纯函数):不接/无下家/失联/自指→null=自然停
    val target = resolvePlayChain(queue, playStations)
    if (target == null) {
        playChainSettledSource = src
        android.util.Log.i(
            "R392",
            "段尽自然停:不接棒 台尾=${queue.id.takeLast(6)} 接棒=${queue.chainTo ?: "无"}",
        )
        return
    }
    // ③ autoAdvance 门:接=推进动作,跟全局推进意愿;关=播完即停在末条,
    // 接棒静默待命(本次段尽按自然停结算,不欠账不重挂)
    if (!_autoAdvance.value) {
        playChainSettledSource = src
        android.util.Log.i(
            "R392",
            "段尽静默待命:自动连播关 台尾=${queue.id.takeLast(6)} 接棒台尾=${target.id.takeLast(6)}",
        )
        return
    }
    // ④ 接棒:一期开台链全继承(切台 flush/断点续播 fromStart=false/置题=台名)
    playChainSettledSource = src
    android.util.Log.i(
        "R392",
        "段尽接棒:台尾=${queue.id.takeLast(6)} → 接棒台尾=${target.id.takeLast(6)} " +
            "模式=${if (queue.chainTo == PlayQueue.CHAIN_NEXT) "下一台" else "指定"} 台数=${playStations.size}",
    )
    openPlayStationImpl(target.id, fromStart = false)
}

// ---------------- R392 #6① 晨间定时(每日 HH:mm→生成今日单→开台播单) ----------------
//
// 判词(r392b_notes §1.4 实勘定案):DailyDigest(R63)骨架平移到 VM 域——
// 进程内协程闸点循环(启动补查+到点自查),**零新后台常驻**(delay 协程不持锁
// 不占线程)/零新权限面(WorkManager 零依赖=项目「不加新依赖」边界;Alarm
// Manager 全仓零先例不引);已知局限如实注记(DailyDigest 同款):app 不开/
// 进程被杀→当日闸点不触发,下次启动补查兜底——补查仅迟到窗口
// ([MorningStationSchedule.LATE_WINDOW_MS])内补开,窗外=晨间已过明日再开。
//
// 消费链:**generateTodayStationImpl() 直调(R391 链零改只调用**——生成→覆盖/
// 新增今日台→设活跃→一期开单链开台,全继承);错峰:启动补查先过
// ColdStartQuietGate(WarmupCoordinator 同款冷启动让路闸——不与首拉抢
// TDLib/IO,R158 爬虫风暴教训)。
//
// 幂等纪律(digest「发出才落」同款):lastMorningStationDate 只在今日台真正
// 生成并开台后落盘;**当日已有今日台(用户已手动生成)→只补记日期不重生成**
// (晨间承诺已被手动兑现,覆盖重生成=churn 不可取);原料缺(空态 nonce)与
// 超时未知不落——补报机会留给下次触发。

/**
 * 晨间循环挂起(启动补查+到点自查;init 与 setMorningStationAlarmImpl 共用,
 * 重挂=cancel 旧 Job 再起新循环节奏——sleepJob 重挂同款纪律)。
 * 主线程起跑(checkMorningOnce 主线程模型纪律:读 playStations 底账+调
 * generateTodayStationImpl 主线程入口)。
 */
internal fun FeedViewModel.startMorningAlarmLoop() {
    morningAlarmJob?.cancel()
    morningAlarmJob = viewModelScope.launch {
        // 启动补查:错峰——先过冷启动让路闸(20s 宽限+风暴闸并入,既有口径),
        // 不与首拉抢;闸后判定「今天该开没开」
        runCatching {
            dev.tgfeed.core.td.ColdStartQuietGate.awaitQuiet()
        }
        checkMorningOnce("启动补查")
        while (isActive) {
            delay(
                MorningStationSchedule.msUntilNextGate(
                    prefs.morningStationMinuteOfDay,
                    System.currentTimeMillis(),
                    java.time.ZoneId.systemDefault(),
                ),
            )
            checkMorningOnce("到点")
        }
    }
}

/**
 * 晨间单次判定(DailyDigest checkOnce 同口径:判定行进 logcat 供验证)。
 * 五闸全过才开火:开关/当日已开/到点(1s 早醒容差)/迟到窗/当日已有今日台。
 * 主线程(prefs SP 读+playStations 底账读;SP 首读已在缓存,纳秒级)。
 */
internal fun FeedViewModel.checkMorningOnce(reason: String) {
    if (!prefs.morningStationEnabled) {
        android.util.Log.i("R392", "晨间判定[$reason]:开关关,跳过")
        return
    }
    val zone = java.time.ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = MorningStationSchedule.todayString(now, zone)
    if (prefs.lastMorningStationDate == today) {
        android.util.Log.i("R392", "晨间判定[$reason]:当日已开,跳过")
        return
    }
    val gate = MorningStationSchedule.gateTodayMs(prefs.morningStationMinuteOfDay, zone)
    // 1s 早醒容差:delay 精度内早醒仍算到点(否则重挂 60s 兜底晚一轮,判词同
    // msUntilNextGate 的 1 分钟下限)
    if (now < gate - 1_000L) {
        android.util.Log.i("R392", "晨间判定[$reason]:未到点(闸点=${prefs.morningStationMinuteOfDay}分位),跳过")
        return
    }
    // 迟到窗(启动补查与到点醒晚共用同窗):过闸超窗=晨间已过,明日再开
    if (!MorningStationSchedule.withinLateWindow(gate, now)) {
        android.util.Log.i("R392", "晨间判定[$reason]:过闸超窗晨间已过,跳过(明日照常)")
        return
    }
    // 当日已有今日台(用户已手动生成):只补记日期不重生成——幂等判词:晨间承诺
    // 已被手动兑现,覆盖重生成=churn 不可取(r392b_notes §3.2)
    val todayStart = MorningStationSchedule.todayStartMs(now, zone)
    if (playStations.any {
            it.name == TodayQueueRecipe.STATION_NAME && it.updatedAt >= todayStart
        }
    ) {
        prefs.lastMorningStationDate = today
        android.util.Log.i("R392", "晨间判定[$reason]:今日台已在场,补记日期不重生成")
        return
    }
    fireMorningStation(reason, today, todayStart)
}

/**
 * 晨间开火:**generateTodayStationImpl 直调(R391 链零改只调用**——生成→
 * 覆盖/新增今日台→设活跃→一期开单链开台播单)+ 成功判定有界守望。
 * 后台态判词(诚实注记):到点开台=源切换+列表装载+播放意图;进程活着而
 * app 在后台时——音频可后台续播(共享播放器 MediaSession 既有机制),视频
 * 由既有后台闸管(回前台起播),零新逻辑。
 */
private fun FeedViewModel.fireMorningStation(reason: String, today: String, todayStart: Long) {
    android.util.Log.i(
        "R392",
        "晨间开台触发[$reason]:闸点=${prefs.morningStationMinuteOfDay}分位 台数=${playStations.size}(计数口径)",
    )
    generateTodayStationImpl() // R391 今日单链零改只调用(红线)
    // 成功守望(有界 15s 轮询):今日台 fresh=成功落日期;空态 nonce 递增=原料缺
    // 不落;超时=未知不落——补报机会留给下次触发(digest「发出才落」同纪律)
    viewModelScope.launch {
        val hint0 = _todayQueueEmptyHint.value
        // 红修6a:守望循环改为旗变量收口——while(true) 作块尾语句类型为 Unit,
        // 显式泛型锚 Boolean 会类型红(首稿 withTimeoutOrNull<Boolean> + 尾 while
        // 真型不匹配;旗变量形无不可达代码告警,块尾表达式 freshSeen 天然 Boolean)
        var freshSeen = false
        val ok = withTimeoutOrNull(15_000L) {
            while (!freshSeen && _todayQueueEmptyHint.value <= hint0) {
                freshSeen = _playQueueStations.value.any {
                    it.name == TodayQueueRecipe.STATION_NAME && it.updatedAt >= todayStart
                }
                if (!freshSeen && _todayQueueEmptyHint.value <= hint0) delay(300)
            }
            freshSeen
        }
        if (ok == true) {
            prefs.lastMorningStationDate = today
            android.util.Log.i("R392", "晨间开台成功:落幂等日期闸")
        } else {
            android.util.Log.i(
                "R392",
                "晨间开台未成(${if (ok == false) "原料缺空态,诚实不编造" else "守望超时未知"}):不落日期,补报留下次",
            )
        }
    }
}

/**
 * 晨间定时设置口(播放台列表「晨间定时」对话框保存钮):落 prefs+即时重挂
 * 循环(新闸点即刻生效——sleepJob 重挂同款纪律)+设置探针行(计数口径)。
 */
internal fun FeedViewModel.setMorningStationAlarmImpl(enabled: Boolean, minuteOfDay: Int) {
    prefs.morningStationEnabled = enabled
    prefs.morningStationMinuteOfDay = minuteOfDay
    startMorningAlarmLoop()
    android.util.Log.i(
        "R392",
        "晨间定时设置:开关=$enabled 闸点=${minuteOfDay / 60}时${minuteOfDay % 60}分(计数口径)",
    )
}
