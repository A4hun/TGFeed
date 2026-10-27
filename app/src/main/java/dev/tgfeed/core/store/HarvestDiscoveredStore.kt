package dev.tgfeed.core.store

import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * R432 启动性能：收割自动发现清单大 JSON 的独立文件存取口。
 *
 * 背景：该清单（[{"chatId":…,"kind":…,"title":…,"src":…}] 数组，量级随会话数线性涨，
 * 可到 MB 级）原先作为 `harvest_discovered_json` 键挤在 "tgfeed" 主 SP 文件里——
 * SP 是单文件全量重写模型：主文件被这个大字段撑胖后，任何一个小开关的写都会
 * 触发整个文件（含大 JSON）重写；冷启动装载 SP 时也要把大 JSON 先解析进内存。
 *
 * 本类把它迁到 filesDir/harvest_discovered.json 独立小文件：
 *  - **懒加载**：首次 read/write 才碰盘（调用点在 GroupHarvester 收割协程，IO 线程），
 *    AppPrefs 构造与主线程启动路径零成本；
 *  - **原子写**：tmp+rename（与 LocalStore.writeText / ChannelExposureLedger 同口径）；
 *  - **一次性迁移**：旧 SP 键还在（老用户升级路径）则首次访问时搬过来并清掉旧键，
 *    幂等收敛——文件已存在且旧键已清即零开销直读。
 *
 * 形态为 object 进程级单例：AppPrefs 在仓内被各处按需 new（共享同一 "tgfeed" SP
 * 实例），若每个实例各持一份内存缓存会互相看不到对方的写；单例 + 内存快照保证
 * 全进程读写一致。读写都假定 IO 线程（收割链路），object 锁串行化读写本体的
 * 读-改-写窗口；故障全 runCatching 静默降级（清单是缓存性质数据，坏了按空表
 * 重建，重扫会重新填充，不影响正确性）。
 */
object HarvestDiscoveredStore {

    private const val TAG = "HarvestDiscStore"

    /** 旧存储位：主 "tgfeed" SP 里的键名（迁移源，写口 AppPrefs.harvestDiscoveredJson） */
    internal const val LEGACY_SP_KEY = "harvest_discovered_json"

    private const val FILE_NAME = "harvest_discovered.json"

    private val lock = Any()

    /** 内存快照（进程级唯一缓存；null = 尚未装载） */
    @Volatile
    private var cached: String? = null

    /** 读清单 JSON；首次访问才读盘 + 顺带完成旧 SP 键迁移 */
    fun read(filesDir: File, legacySp: SharedPreferences): String {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val v = loadLocked(filesDir, legacySp)
            cached = v
            return v
        }
    }

    /** 整体覆写清单 JSON（tmp+rename 原子替换）；顺带幂等清掉旧 SP 键 */
    fun write(filesDir: File, legacySp: SharedPreferences, json: String) {
        synchronized(lock) {
            runCatching {
                val file = file(filesDir)
                val dir = file.parentFile
                if (dir != null) dir.mkdirs()
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    // rename 失败（极少见）时退化为直接覆盖写（LocalStore.writeText 同口径）
                    runCatching { file.writeText(json) }
                    tmp.delete()
                }
                cached = json
                clearLegacyKeyIfNeededLocked(legacySp)
            }.onFailure { Log.w(TAG, "发现清单写盘失败（下轮重写）: ${it.message}") }
        }
    }

    // ---------------- 内部（持 [lock] 调用） ----------------

    private fun loadLocked(filesDir: File, legacySp: SharedPreferences): String {
        return runCatching {
            val file = file(filesDir)
            if (file.isFile) {
                file.readText()
            } else {
                // 首次迁移：文件还没有 → 看旧 SP 键（老用户升级；新装为空串直落空文件语义）
                val legacy = legacySp.getString(LEGACY_SP_KEY, "") ?: ""
                if (legacy.isNotEmpty()) {
                    val dir = file.parentFile
                    if (dir != null) dir.mkdirs()
                    val tmp = File(dir, file.name + ".tmp")
                    tmp.writeText(legacy)
                    if (!tmp.renameTo(file)) runCatching { file.writeText(legacy) }
                }
                legacy
            }
        }.getOrElse {
            Log.w(TAG, "发现清单读盘失败（按空表重建，重扫会重新填充）: ${it.message}")
            ""
        }.also {
            clearLegacyKeyIfNeededLocked(legacySp)
        }
    }

    /** 旧键存在才 remove——避免对不存在键的无谓 remove 触发整个主 SP 文件重写 */
    private fun clearLegacyKeyIfNeededLocked(legacySp: SharedPreferences) {
        runCatching {
            if (legacySp.contains(LEGACY_SP_KEY)) {
                legacySp.edit().remove(LEGACY_SP_KEY).apply()
            }
        }.onFailure { Log.w(TAG, "旧 SP 键清理失败（不影响新文件读写，下次再试）: ${it.message}") }
    }

    private fun file(filesDir: File) = File(filesDir, FILE_NAME)
}
