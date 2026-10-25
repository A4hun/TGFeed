package dev.tgfeed.core.tastemap

import java.io.File

/**
 * R235 航行账本（口味星图四期·RPG 层·纯 java.io，单测可跑）。
 *
 * 成就体系里**没有既有本地账本**的两个量（「分享次数」）的持久计数：本地小文件
 * key=value 行格式（手写解析——core 零 Android 依赖纪律，org.json 在 JVM 单测
 * 不可用）；原子写（临时文件+rename——半写不损坏旧账）。
 *
 * 计数 funnel（诚实口径，KDoc 成文）：
 *  - feed 分享枢纽「分享到其他应用」（ACTION_SEND chooser 发出）+「分享卡片」
 *    （ShareCard.share 落地相册）——FeedScreen 两行 additive 挂钩（R235 广播）；
 *  - 星图「导出海报」+「航行日志」两导出（TasteMapScreen 自持）。
 *
 * 文件位置由 ui 调用方解析（filesDir/galaxy/voyage.txt——LocalStore 零改动，
 * 星图自持账本不进共享层）。
 */
object TasteVoyageLog {

    /** 账本文件名（ui 侧 File(filesDir/galaxy, 此名)） */
    const val FILE_NAME = "voyage.txt"

    /** 账本快照（不可变值对象——面板/装配读侧零锁） */
    class VoyageCounters(
        val shareCount: Int,
        val wormholeJumps: Int,
    )

    /** 读账本（文件缺失/损坏/半写=零账诚实缺省，绝不抛） */
    fun load(file: File): VoyageCounters {
        if (!file.isFile) return VoyageCounters(0, 0)
        return runCatching {
            var share = 0
            var jumps = 0
            file.useLines { lines ->
                for (line in lines) {
                    val idx = line.indexOf('=')
                    if (idx <= 0) continue
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim().toIntOrNull() ?: continue
                    when (k) {
                        "share" -> share = v.coerceAtLeast(0)
                        "jump" -> jumps = v.coerceAtLeast(0)
                    }
                }
            }
            VoyageCounters(share, jumps)
        }.getOrDefault(VoyageCounters(0, 0))
    }

    /** 原子写（临时文件+rename；失败静默——计数账本丢一次可容忍，绝不抛）。
     *  R248 类问题同修：父目录自建（filesDir/galaxy/ 此前全仓无创建方——首写
     *  必静默失败的真机实锤；与 TasteDiscoveryLog.save 同一类「写前不建父目录」）。 */
    private fun save(file: File, c: VoyageCounters) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText("share=${c.shareCount}\njump=${c.wormholeJumps}\n")
            if (!tmp.renameTo(file)) {
                // rename 跨设备失败兜底（同目录 rename 正常直达；直写保底）
                file.writeText("share=${c.shareCount}\njump=${c.wormholeJumps}\n")
                tmp.delete()
            }
        }
    }

    /** 分享计数 +1（funnel 各处调用；返回新账——调用方可直接用） */
    fun bumpShare(file: File): VoyageCounters {
        val next = load(file).let { VoyageCounters(it.shareCount + 1, it.wormholeJumps) }
        save(file, next)
        return next
    }

    /** 虫洞跳跃计数 +1（虫洞 transit 到达时调用——「航行足迹」的账） */
    fun bumpWormhole(file: File): VoyageCounters {
        val next = load(file).let { VoyageCounters(it.shareCount, it.wormholeJumps + 1) }
        save(file, next)
        return next
    }
}
