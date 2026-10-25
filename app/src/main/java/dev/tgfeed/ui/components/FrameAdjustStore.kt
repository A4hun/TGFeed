package dev.tgfeed.ui.components

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import dev.tgfeed.core.store.LocalStore
import dev.tgfeed.core.store.StoredFrameAdjust
import dev.tgfeed.ui.frameAdjustNormalized
import dev.tgfeed.ui.rotateFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 画面姿态（旋转 + 镜像）的 UI 侧共享表（R218 建表 MirrorStore / R227 扩展改名
 * FrameAdjustStore，Compose 快照感知）：每条视频独立的旋转+镜像复合态，Feed 播放页
 * （VideoPage）与分屏格（GridVideoCell）两宿主读写同一真身——一处调整全局生效
 * （纪律§一：共享件收敛，不许每个宿主各写一份私有缓存）。
 *
 * - **数据源**：[LocalStore.mirrored.json] 键级缓存（跨会话保持：退出重进/重启后
 *   复合态照旧；R218 旧数组格式由存储层读侧自动迁移，零丢失）。
 * - **读**：[stateOf] 读快照 map，组合期读取即订阅——调整后画面/封面即时重绘；
 *   首读一次性同步装载（小文件一次盘读，AppPrefs 首读同口径），AppContainer init
 *   另有后台 IO 预读（[warmup]），常态下 UI 首读时已装好、零盘读。
 * - **写**：[rotate] / [toggleMirror] / [reset] 改快照 map（UI 即时生效）+ 异步落盘
 *   （Dispatchers.IO，串行 Mutex 保序：连点两次也不会让旧快照后落盖掉新状态；
 *   主线程零磁盘 IO）。
 * - 单写入方纪律：本表是 mirrored.json 的唯一写入方（VM 不碰该文件），
 *   进程内快照与磁盘不可能失配。
 * - 旋转口径（R227 变更，见 r227_notes）：R122 时代旋转是页内临时态（划走归零）；
 *   R227 起按任务书收编为条目级持久记忆（与镜像同链路；「恢复默认」一键归零）。
 *   频道级记忆仍然没有（R122 用户拍板口径不变）。
 */
object FrameAdjustStore {

    /** 内部可变表（快照感知）：key → 复合姿态（默认态不入表） */
    private val backing = mutableStateMapOf<String, StoredFrameAdjust>()

    /** 落盘用 IO 协程域（SupervisorJob：单次写失败不杀域） */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 落盘串行锁：写协程按入队序执行，执行时读最新快照（旧快照永不后落） */
    private val persistMutex = Mutex()

    /** 持久层实例（首用装载后常驻；@Volatile 双检 + synchronized 单飞） */
    @Volatile
    private var store: LocalStore? = null

    /**
     * 该条的复合姿态（rotationDeg 归一 [0,360) + mirrored）。组合期调用即订阅
     * （快照 map 读），调整后所有读位自动重组；表外 key = 默认姿态（0°/未镜像）。
     */
    fun stateOf(context: Context, key: String): StoredFrameAdjust {
        if (key.isEmpty()) return StoredFrameAdjust()
        ensureStore(context)
        return backing[key] ?: StoredFrameAdjust()
    }

    /**
     * 旋转该条（deltaDeg 取 +90=向右/顺时针 或 -90=向左/逆时针，结果归一 [0,360)）。
     * 快照 map 先动（UI 即时生效），落盘异步走 IO。
     */
    fun rotate(context: Context, key: String, deltaDeg: Int) {
        if (key.isEmpty()) return
        val s = ensureStore(context)
        val cur = backing[key]
        val next = rotateFrame(cur?.rotationDeg ?: 0, deltaDeg)
        write(context, key, StoredFrameAdjust(rotationDeg = next, mirrored = cur?.mirrored ?: false))
        if (s != null) schedulePersist(s)
    }

    /**
     * 翻转该条的镜像态并返回翻转后的值（true=已镜像）。
     * 快照 map 先动（UI 即时生效），落盘异步走 IO。
     */
    fun toggleMirror(context: Context, key: String): Boolean {
        if (key.isEmpty()) return false
        val s = ensureStore(context)
        val cur = backing[key]
        val next = !(cur?.mirrored ?: false)
        write(context, key, StoredFrameAdjust(rotationDeg = cur?.rotationDeg ?: 0, mirrored = next))
        if (s != null) schedulePersist(s)
        return next
    }

    /**
     * 该条姿态恢复默认（旋转归零 + 取消镜像）——枢纽菜单「恢复默认」行与
     * 分屏/播放页两宿主共用的归零口（表项删除即默认，落盘侧默认态不入盘）。
     */
    fun reset(context: Context, key: String) {
        if (key.isEmpty()) return
        val s = ensureStore(context)
        if (backing.containsKey(key)) {
            backing.remove(key)
            if (s != null) schedulePersist(s)
        }
    }

    /**
     * 进程级预读（AppContainer init 后台 IO 调）：把 mirrored.json 装进快照表，
     * 让 UI 侧首读零盘读。幂等（ensureStore 单飞）；预读与 UI 首读竞态时
     * 先到者装载、后到者纯内存返回，结论一致。
     */
    fun warmup(context: Context) {
        ensureStore(context)
    }

    /** 快照写入（默认态删表项，与存储层「默认态不入盘」同口径） */
    private fun write(context: Context, key: String, entry: StoredFrameAdjust) {
        if (entry.rotationDeg == 0 && !entry.mirrored) backing.remove(key) else backing[key] = entry
    }

    /** 懒装载：建 LocalStore + 把落盘复合态灌进快照表（单飞，双检快路径零锁开销） */
    private fun ensureStore(context: Context): LocalStore? {
        store?.let { return it }
        return synchronized(this) {
            store ?: run {
                val s = runCatching {
                    val local = LocalStore(context.applicationContext)
                    local.loadFrameAdjust().forEach { (k, e) ->
                        if (k.isNotEmpty()) {
                            // 读侧再归一一次（防御存储层版本混布/手改文件的角度脏值）
                            backing[k] = if (e.rotationDeg in 0..359) e
                            else e.copy(rotationDeg = frameAdjustNormalized(e.rotationDeg))
                        }
                    }
                    local
                }.getOrNull()
                store = s
                s
            }
        }
    }

    /** 异步落盘：锁内现读最新快照（连点两次时后写的协程执行时读到的就是终态） */
    private fun schedulePersist(s: LocalStore) {
        ioScope.launch {
            persistMutex.withLock {
                runCatching { s.saveFrameAdjust(backing.toMap()) }
            }
        }
    }
}
