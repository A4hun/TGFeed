package dev.tgfeed.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据迁移（导出/导入数据包）：解决 debug 包与 release 包签名不同、无法覆盖安装的问题——
 * 换包时「导出数据包 → 卸载旧包装新包 → 导入数据包」即可把本机数据整体搬家，
 * 不管装的包来自哪（debug/release），数据都能迁移/共享。
 *
 * 数据包 = 单个 zip（用户经系统文件选择器 SAF 自选位置，默认落在"下载"目录），布局：
 *  - manifest.json           数据包元信息（魔数/版本/创建时间/App 版本/文件清单与字节数），
 *                            恒为 zip 第一个条目：导入侧第一遍流式扫到它即完成校验
 *  - prefs/<name>.xml        shared_prefs 全量 xml（tgfeed.xml = AppPrefs 全部设置项；
 *                            interaction_boost.xml = 互动加权底账等；将来新增的偏好文件自动纳入）
 *  - store/<name>.json       filesDir/store/ 整目录快照（LocalStore 全部：收藏/点赞/观看历史/
 *                            订阅追更/稍后再看/屏蔽频道/不感兴趣与永久屏蔽/观看事件/续播进度/
 *                            搜索历史/下载记录/片单及条目/分屏画布/口味词表/发现候选与臂统计/
 *                            last_seen 基线/taste_radar 等；将来新增落盘文件自动纳入）
 *  - deepindex/search.db     深度搜索索引库（SQLite FTS5，可达数百 MB，全程流式读写不进内存）。
 *                            导出前先做 WAL TRUNCATE 检查点：单拷主库文件即一致快照；
 *                            检查点被在飞爬取阻塞时连 search.db-wal/-shm 一并打包，
 *                            恢复后由 SQLite 自动重放 WAL，不丢已提交页
 *  - discovery_graph.json    filesDir 根下的资源嗅探图谱（DiscoveryGraph）
 *
 * 刻意不打包：
 *  - filesDir/tdlib/（TDLib 登录会话与媒体缓存）：会话绑定设备/签名，迁过去反而有
 *    "同号两地登录态错乱"的风险；导入后扫码重登即可（很快）。媒体缓存本来就可重新下载。
 *  - filesDir/covers/（封面磁盘缓存）：可由 TDLib 重新获取，打包只会白白撑大包体。
 *
 * 导入语义 = 校验 → 全量解压到暂存区 → 分段"清旧换新" → 提示重启生效：
 *  - 暂存区在 filesDir/backup_staging_tmp（与正式区同文件系统，rename 瞬时可换入）；
 *    解压中途任何失败只需清掉暂存区，本机原数据分毫不动；
 *  - 换入按区段进行且只动包内存在的区段：store 整目录清旧换新（备份 = 回到当时状态），
 *    deepindex 先删旧 search.db/-wal/-shm 三件套再移入（旧 WAL 残留会被当新库日志重放，必删），
 *    prefs 按包内清单逐文件覆盖（不删包外文件），discovery_graph.json 单文件覆盖；
 *  - 运行中的进程不会感知新数据（SharedPreferences 内存副本 / LocalStore 各 @Volatile 缓存 /
 *    DeepSearchIndex 已打开的句柄全是启动时建立），因此导入完成后必须冷启动才生效——
 *    UI 侧导入成功即弹"立即退出/稍后手动重启"对话框。
 *
 * R287 起导入分两路（设置页三选弹窗）：
 *  - **覆盖恢复**（[importBackup]）：上文整包回滚语义，换包搬家场景，需重启；
 *  - **合并导入**（[importBackupMergeIndex]，多设备同账号汇总场景）：只取包内
 *    deepindex 区段解到 deepindex/import_staging/，合并本体由 IndexImportMerge
 *    后台批迁执行（key+内容指纹去重/字段非空胜/meta 爬取状态随迁/断点续传），
 *    合并进活库**免重启**；prefs/store/图谱一概不碰（本机数据不被对端洗掉）。
 *
 * 线程约定：exportBackup/importBackup 均为同步阻塞 IO，调用方（SettingsScreen）负责切
 * Dispatchers.IO；[onProgress] 回调跑在本类工作线程上（UI 侧直接写 Compose snapshot 状态即可，
 * 跨线程写是安全的）。
 */
object BackupManager {

    /** 数据包格式版本（写进 manifest.json）；导入侧拒绝更高版本（防新版格式被旧版误读成乱数据） */
    private const val BACKUP_VERSION = 1

    /** manifest.json 魔数：辨认"是不是本 App 数据包"的第一道闸 */
    private const val MAGIC = "tgfeed-backup"

    private const val TAG = "BackupManager"

    /** 流式复制缓冲区：search.db 可达数百 MB，全程流式，任何时刻内存占用 ≤ 本缓冲 */
    private const val IO_BUFFER = 64 * 1024

    /** manifest.json 读取上限（正常清单只有几十条记录；防伪造清单撑爆内存） */
    private const val MANIFEST_MAX_BYTES = 256 * 1024

    /** 索引库目录/主库文件名（与 FeedViewModel 的 DeepSearchIndex(File(filesDir,"deepindex"))、
     *  DeepSearchIndex.DB_NAME="search.db" 同源；那两个符号是 private，这里按约定用字面量） */
    private const val DEEPINDEX_DIR = "deepindex"
    private const val SEARCH_DB = "search.db"

    /** 建议数据包文件名：tgfeed-backup-20240101-1330.zip（SAF 新建文档的预填名） */
    fun suggestedFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "tgfeed-backup-$ts.zip"
    }

    // ---------------- 导出 ----------------

    /** 一个待打包文件：zip 内相对路径 + 源文件 */
    private class BackupItem(val relPath: String, val file: File)

    /**
     * 导出数据包到 [target]（SAF ACTION_CREATE_DOCUMENT 返回的 Uri）。
     * 必须在 IO 线程调用；失败抛异常（调用方 runCatching）。返回人类可读的结果摘要。
     */
    fun exportBackup(context: Context, target: Uri, onProgress: (String) -> Unit): String {
        val items = collectBackupItems(context, onProgress)
        if (items.isEmpty()) throw IOException("没有可导出的数据（请先使用一段时间再备份）")

        // 清单先行：manifest.json 恒为 zip 第一个条目（导入侧第一遍流式扫描到此即止）
        val manifest = JSONObject().apply {
            put("magic", MAGIC)
            put("version", BACKUP_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("app", appVersionName(context))
            put("files", JSONArray().apply {
                items.forEach {
                    put(JSONObject().apply {
                        put("path", it.relPath)
                        put("bytes", it.file.length())
                    })
                }
            })
        }

        val raw = context.contentResolver.openOutputStream(target, "w")
            ?: throw IOException("无法写入所选位置")
        var totalBytes = 0L
        ZipOutputStream(raw.buffered(IO_BUFFER)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            items.forEachIndexed { index, item ->
                onProgress("正在导出 ${index + 1}/${items.size}：${item.relPath}")
                zos.putNextEntry(ZipEntry(item.relPath))
                item.file.inputStream().buffered(IO_BUFFER).use { input ->
                    val buf = ByteArray(IO_BUFFER)
                    var sinceReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zos.write(buf, 0, n)
                        totalBytes += n
                        sinceReport += n
                        // 大文件（索引库）每 32MB 报一次进度，避免长时间无反馈
                        if (sinceReport >= 32L * 1024 * 1024) {
                            sinceReport = 0
                            onProgress("正在导出 ${item.relPath}（累计 ${formatSize(totalBytes)}）")
                        }
                    }
                }
                zos.closeEntry()
            }
        }
        Log.d(TAG, "导出完成：${items.size} 个文件，解压前共 ${formatSize(totalBytes)}")
        return "导出完成：${items.size} 个文件（解压前共 ${formatSize(totalBytes)}）"
    }

    /**
     * 收集待打包文件，按大小升序（小文件先走、进度反馈快；索引库大文件最后）。
     * 目录一律"整目录快照"：未来新增的 json/xml 自动纳入导出，无需改备份代码。
     */
    private fun collectBackupItems(context: Context, onProgress: (String) -> Unit): List<BackupItem> {
        val filesDir = context.filesDir
        val out = ArrayList<BackupItem>()

        // 1) shared_prefs 全量 xml（tgfeed.xml=AppPrefs；interaction_boost.xml=互动加权等）
        val prefsDir = File(filesDir.parentFile, "shared_prefs")
        prefsDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".xml")) out += BackupItem("prefs/${f.name}", f)
        }

        // 2) filesDir/store/ 整目录（LocalStore 全部 JSON；跳过原子写残留的 .tmp）
        File(filesDir, "store").listFiles()?.forEach { f ->
            if (f.isFile && !f.name.endsWith(".tmp")) out += BackupItem("store/${f.name}", f)
        }

        // 3) filesDir/discovery_graph.json（资源嗅探图谱，单文件）
        File(filesDir, "discovery_graph.json").takeIf { it.isFile }
            ?.let { out += BackupItem("discovery_graph.json", it) }

        // 4) 深度索引库：先做 WAL 检查点，尽量只拷主库；被在飞爬取阻塞时连 WAL 旁文件一起打包
        val indexDir = File(filesDir, DEEPINDEX_DIR)
        val dbFile = File(indexDir, SEARCH_DB)
        if (dbFile.isFile) {
            onProgress("正在整理索引库…")
            val checkpointed = checkpointWal(dbFile)
            out += BackupItem("$DEEPINDEX_DIR/$SEARCH_DB", dbFile)
            if (!checkpointed) {
                // WAL 未截断：-wal 里还有已提交页，必须随包带走（恢复时 SQLite 自动重放）；
                // -shm 是共享内存索引，恢复时会自动重建，带上只为一致性兜底
                File(indexDir, "$SEARCH_DB-wal").takeIf { it.isFile && it.length() > 0 }
                    ?.let { out += BackupItem("$DEEPINDEX_DIR/$SEARCH_DB-wal", it) }
                File(indexDir, "$SEARCH_DB-shm").takeIf { it.isFile && it.length() > 0 }
                    ?.let { out += BackupItem("$DEEPINDEX_DIR/$SEARCH_DB-shm", it) }
                Log.w(TAG, "索引库正被写入、WAL 未截断：已连 -wal/-shm 一并打包")
            }
        }

        return out.sortedBy { it.file.length() }
    }

    /**
     * 导出前对索引库做 WAL 检查点（TRUNCATE）：把 -wal 里的已提交页合并回主库文件，
     * 使单拷 search.db 即是一致快照。WAL 模式允许多连接：另开一条连接做 checkpoint
     * 不干扰 DeepSearchIndex 已持有的句柄；busy_timeout 3s 等在飞写事务收尾。
     * 返回 true = 截断成功（-wal 已清空，只拷主库即可）；false = 仍有活跃写入或打开失败，
     * 调用方把 -wal/-shm 一并打包（SQLite 恢复时自动重放 WAL，不丢已提交数据）。
     */
    private fun checkpointWal(dbFile: File): Boolean {
        if (!dbFile.isFile) return true
        return runCatching {
            var truncated = false
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // PRAGMA 返回结果行，必须走 rawQuery（execSQL 不允许带返回值的语句）
                db.rawQuery("PRAGMA busy_timeout=3000", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    // 结果行第一列：0=不再忙碌（全部帧已回写且 -wal 截断），1=仍有活跃读写阻塞
                    if (c.moveToFirst()) truncated = c.getInt(0) == 0
                }
            }
            truncated
        }.getOrElse {
            Log.w(TAG, "索引库 WAL 检查点失败，将连 -wal/-shm 一并打包", it)
            false
        }
    }

    // ---------------- 导入 ----------------

    /**
     * 从 [source]（SAF ACTION_OPEN_DOCUMENT 返回的 Uri）导入数据包。
     * 必须在 IO 线程调用；校验/解压/换入任何一步失败都抛异常且不动本机原数据
     * （解压全程在暂存区，成功后才分段换入）。返回人类可读的结果摘要。
     */
    fun importBackup(context: Context, source: Uri, onProgress: (String) -> Unit): String {
        // —— 第一遍：流式扫到 manifest.json 即止，校验魔数/版本（不是本 App 数据包直接拒） ——
        val files = readManifest(context, source)
        val manifestBytes = files.sumOf { it.second }

        // 空间预检：暂存区与正式区同分区，峰值 ≈ 解压后一份 + 原数据一份（清单缺失字节数时跳过）
        if (manifestBytes > 0) {
            val usable = context.filesDir.usableSpace
            if (manifestBytes * 2 > usable) {
                throw IOException("存储空间不足：恢复约需 ${formatSize(manifestBytes * 2)} 空闲空间")
            }
        }

        // —— 第二遍：全量解压到暂存区（成功后才换入；中途失败清掉暂存区即可，原数据分毫不动） ——
        val staging = File(context.filesDir, "backup_staging_tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        var restored = 0
        var restoredBytes = 0L
        try {
            val raw = context.contentResolver.openInputStream(source)
                ?: throw IOException("无法读取所选文件")
            ZipInputStream(raw.buffered(IO_BUFFER)).use { zis ->
                val buf = ByteArray(IO_BUFFER)
                var entry = zis.nextEntry
                while (entry != null) {
                    val target = resolveEntryTarget(staging, entry.name)
                    if (target != null) {
                        onProgress("正在恢复 ${entry.name}")
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered(IO_BUFFER).use { out ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                restoredBytes += n
                            }
                        }
                        restored++
                    } // 不认识的条目：跳过（前向兼容未来版本新增区段/防路径穿越）
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (restored == 0) throw IOException("数据包内没有可恢复的内容")

            // —— 换入正式区：分段"清旧换新"，只动包内存在的区段 ——
            onProgress("正在写回数据…")
            applyStoreSnapshot(staging, context.filesDir)
            applyDeepIndexSnapshot(staging, context.filesDir)
            applyPrefsSnapshot(staging, context.filesDir)
            applyDiscoveryGraphSnapshot(staging, context.filesDir)
        } finally {
            staging.deleteRecursively()
        }
        Log.d(TAG, "导入完成：$restored 个文件，共 ${formatSize(restoredBytes)}")
        return "已恢复 $restored 个文件（共 ${formatSize(restoredBytes)}）"
    }

    /**
     * 第一遍流式扫描：读到 manifest.json 即止（导出侧把它写成第一条目，正常几 KB 内完成校验；
     * 扫完整个 zip 都找不到 = 不是本 App 数据包）。返回文件清单（相对路径 → 字节数）供空间预检。
     */
    private fun readManifest(context: Context, source: Uri): List<Pair<String, Long>> {
        val raw = context.contentResolver.openInputStream(source)
            ?: throw IOException("无法读取所选文件")
        ZipInputStream(raw.buffered(IO_BUFFER)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    val bytes = zis.readEntryBytesCapped(MANIFEST_MAX_BYTES)
                    val obj = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse {
                        throw IOException("数据包清单损坏")
                    }
                    if (obj.optString("magic") != MAGIC) throw IOException("所选文件不是 tgfeed 数据包")
                    val ver = obj.optInt("version", 0)
                    if (ver <= 0 || ver > BACKUP_VERSION) {
                        throw IOException("数据包版本不兼容（v$ver，本机支持 ≤v$BACKUP_VERSION）")
                    }
                    val arr = obj.optJSONArray("files") ?: return emptyList()
                    val out = ArrayList<Pair<String, Long>>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val p = o.optString("path")
                        if (p.isNotEmpty()) out += p to o.optLong("bytes", 0L)
                    }
                    return out
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        throw IOException("所选文件不是 tgfeed 数据包（缺少 manifest.json）")
    }

    /**
     * 条目名 → 暂存区内目标文件；返回 null = 跳过（manifest/目录项/不认识的路径）。
     * 安全闸（防恶意 zip 路径穿越写出暂存区）：
     * 只允许 prefs//store//deepindex/ 三类目录前缀 + 根下固定一个 discovery_graph.json；
     * 文件名必须是"简单名"（无 ../反斜杠/嵌套路径）；deepindex 只收索引库三件套；
     * 最后再以 canonicalPath 必须仍在暂存区内做双保险。
     */
    private fun resolveEntryTarget(staging: File, name: String): File? {
        if (name == "manifest.json") return null
        val inScope = name == "discovery_graph.json" ||
            name.startsWith("prefs/") || name.startsWith("store/") || name.startsWith("deepindex/")
        if (!inScope) return null
        val fileName = name.substringAfterLast('/')
        if (fileName.isEmpty() || name.contains("..") || name.contains('\\')) return null
        if (name.startsWith("deepindex/") &&
            fileName != SEARCH_DB && fileName != "$SEARCH_DB-wal" && fileName != "$SEARCH_DB-shm"
        ) {
            return null // 索引区只收三件套，防包内夹带无关大文件
        }
        val target = File(staging, name)
        if (!target.canonicalPath.startsWith(staging.canonicalPath)) return null
        return target
    }

    /** store/ 整目录换入：先清现有（备份语义 = 回到当时状态，备份之后产生的记录一并撤掉），再移入暂存文件 */
    private fun applyStoreSnapshot(staging: File, filesDir: File) {
        val src = File(staging, "store")
        if (!src.isDirectory) return // 包内无此区段：不动现有数据
        val dst = File(filesDir, "store")
        dst.mkdirs()
        dst.listFiles()?.forEach { if (it.isFile) it.delete() }
        src.listFiles()?.forEach { f -> if (f.isFile) moveInto(f, File(dst, f.name)) }
    }

    /**
     * 索引库换入：旧 search.db/-wal/-shm 三件套一律先删——哪怕数据包只带主库：
     * 旧 -wal 若残留，会被 SQLite 当成新库的待重放日志（帧校验不过即报错/数据错乱）。
     * R287：覆盖语义=「回到包内那一刻」——挂起的合并导入（暂存区+断点）一并清场，
     * 防旧合并打进新换的库。
     */
    private fun applyDeepIndexSnapshot(staging: File, filesDir: File) {
        val src = File(staging, DEEPINDEX_DIR)
        if (!src.isDirectory) return
        val dst = File(filesDir, DEEPINDEX_DIR)
        dst.mkdirs()
        listOf(SEARCH_DB, "$SEARCH_DB-wal", "$SEARCH_DB-shm").forEach { File(dst, it).delete() }
        src.listFiles()?.forEach { f -> if (f.isFile) moveInto(f, File(dst, f.name)) }
        runCatching { File(dst, dev.tgfeed.core.feed.IndexImportMerge.STAGING_DIR_NAME).deleteRecursively() }
        runCatching { File(dst, dev.tgfeed.core.feed.IndexImportMerge.STATE_FILE_NAME).delete() }
        runCatching { File(dst, dev.tgfeed.core.feed.IndexImportMerge.STATE_FILE_NAME + ".tmp").delete() }
    }

    // ---------------- R287：多设备索引合并导入（合并非覆盖；只动 deepindex 区段） ----------------

    /**
     * 合并导入·解包相位：把包内 deepindex/search.db（+ -wal/-shm 若随包）解到
     * filesDir/deepindex/[IndexImportMerge.STAGING_DIR_NAME]/src.db，并落
     * [IndexImportMerge.STATE_FILE_NAME] 断点状态（相位=ITEMS 起步）。
     *
     * 与覆盖式 [importBackup] 的分工：本函数**只解索引区段**（prefs/store/
     * discovery_graph 一律不动——多设备汇入语义，本机收藏/设置不被对端洗掉），
     * 且**不解完即换库**——合并本体（数 GB 级 SQL 归并）由 IndexImportMerge 后台
     * 批迁执行（断点续传/与爬虫错峰），调用方拿到返回后应调
     * FeedViewModel.requestImportMerge() 点火；进程死亡则由下次 openDb 自动续跑。
     *
     * 断点口径（诚实）：SAF zip 流不可续解——解包中断=重解（extract.ok 完成标记+
     * 清单字节数核对，已解完的包重导零重解）；合并本体全相位可续。
     *
     * 并发闸：已有挂起合并（状态文件在=上次合并未收尾）且包内容不同 → 拒绝
     * （防在跑合并读到被重写的暂存库）；同包重导=幂等（断点重置、合并器重扫零效果）。
     */
    fun importBackupMergeIndex(context: Context, source: Uri, onProgress: (String) -> Unit): String {
        val files = readManifest(context, source) // 校验魔数/版本（非本 App 包直接拒）
        val dbBytes = files.firstOrNull { it.first == "$DEEPINDEX_DIR/$SEARCH_DB" }?.second
            ?: throw IOException("数据包内没有索引库（无可合并内容）")
        val sideBytes = files.filter {
            it.first == "$DEEPINDEX_DIR/$SEARCH_DB-wal" || it.first == "$DEEPINDEX_DIR/$SEARCH_DB-shm"
        }.sumOf { it.second }
        val need = dbBytes + sideBytes

        // 空间预检：暂存一份源库 + 合并期本机库增长余量（增长 ≤ 源库规模量级）
        val required = need + need / 2 + 256L * 1024 * 1024
        val usable = context.filesDir.usableSpace
        if (required > usable) {
            throw IOException("存储空间不足：合并导入约需 ${formatSize(required)} 空闲空间")
        }

        val indexDir = File(context.filesDir, DEEPINDEX_DIR)
        val staging = File(indexDir, dev.tgfeed.core.feed.IndexImportMerge.STAGING_DIR_NAME)
        val srcFile = File(staging, dev.tgfeed.core.feed.IndexImportMerge.SRC_DB_NAME)
        val okFile = File(staging, dev.tgfeed.core.feed.IndexImportMerge.EXTRACT_OK_NAME)
        val stateFile = File(indexDir, dev.tgfeed.core.feed.IndexImportMerge.STATE_FILE_NAME)

        if (okFile.isFile && srcFile.isFile && srcFile.length() == dbBytes) {
            onProgress("索引包已在暂存区，跳过重复解包")
        } else {
            if (stateFile.isFile && okFile.isFile) {
                // 上次合并未收尾（断点在）且包内容不同 → 在跑/挂起的合并正读着暂存库，拒绝
                throw IOException("上一次合并尚未完成，请等它跑完再导新包（或重启应用让它续跑）")
            }
            onProgress("正在解包索引库（${formatSize(need)}）…")
            staging.deleteRecursively()
            staging.mkdirs()
            val raw = context.contentResolver.openInputStream(source)
                ?: throw IOException("无法读取所选文件")
            var done = 0L
            ZipInputStream(raw.buffered(IO_BUFFER)).use { zis ->
                val buf = ByteArray(IO_BUFFER)
                var entry = zis.nextEntry
                while (entry != null) {
                    val target = when (entry.name) {
                        "$DEEPINDEX_DIR/$SEARCH_DB" -> srcFile
                        "$DEEPINDEX_DIR/$SEARCH_DB-wal" -> File(staging, "${dev.tgfeed.core.feed.IndexImportMerge.SRC_DB_NAME}-wal")
                        "$DEEPINDEX_DIR/$SEARCH_DB-shm" -> File(staging, "${dev.tgfeed.core.feed.IndexImportMerge.SRC_DB_NAME}-shm")
                        else -> null
                    }
                    if (target != null) {
                        target.outputStream().buffered(IO_BUFFER).use { out ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done >= 32L * 1024 * 1024 && done % (32L * 1024 * 1024) < IO_BUFFER) {
                                    onProgress("正在解包索引库（${formatSize(done)}/${formatSize(need)}）")
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (!srcFile.isFile) throw IOException("数据包内没有索引库（无可合并内容）")
            okFile.writeText(dbBytes.toString()) // 完成标记最后写（半途死亡无标记=重解）
        }

        // 落断点状态（相位=ITEMS 起步；原子 tmp+rename，R262b 范式）——合并器据此点火
        val init = "{\"v\":1,\"phase\":\"ITEMS\",\"itemsCursor\":0,\"refsCursor\":0,\"metaCursor\":${Long.MIN_VALUE}}"
        val tmp = File(indexDir, dev.tgfeed.core.feed.IndexImportMerge.STATE_FILE_NAME + ".tmp")
        tmp.writeText(init)
        if (!tmp.renameTo(stateFile)) {
            tmp.delete()
            stateFile.writeText(init)
        }
        Log.d(TAG, "R287 合并导入解包就绪：${formatSize(need)}，断点已落盘等合并器点火")
        return "索引包已就绪（${formatSize(need)}），合并在后台进行"
    }

    /** shared_prefs 按包内清单逐文件覆盖（不删包外文件：避免误伤将来新增的偏好文件） */
    private fun applyPrefsSnapshot(staging: File, filesDir: File) {
        val src = File(staging, "prefs")
        if (!src.isDirectory) return
        val dst = File(filesDir.parentFile, "shared_prefs")
        dst.mkdirs()
        src.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".xml")) moveInto(f, File(dst, f.name))
        }
    }

    /** discovery_graph.json 单文件覆盖（包内没有则不动现有） */
    private fun applyDiscoveryGraphSnapshot(staging: File, filesDir: File) {
        val src = File(staging, "discovery_graph.json")
        if (!src.isFile) return
        moveInto(src, File(filesDir, "discovery_graph.json"))
    }

    // ---------------- 工具 ----------------

    /** 同文件系统优先 rename（瞬时原子）；失败退化为流式复制后删源 */
    private fun moveInto(src: File, dst: File) {
        if (src.renameTo(dst)) return
        src.inputStream().buffered(IO_BUFFER).use { input ->
            dst.outputStream().buffered(IO_BUFFER).use { output -> input.copyTo(output, IO_BUFFER) }
        }
        src.delete()
    }

    /** 读当前 zip 条目的全部字节（上限 [cap]，超限按损坏处理，防伪造清单撑爆内存） */
    private fun ZipInputStream.readEntryBytesCapped(cap: Int): ByteArray {
        val out = ByteArrayOutputStream(8192)
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw IOException("数据包清单异常过大")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 当前 App 版本名（仅写进清单作诊断信息；读取失败不影响备份） */
    @Suppress("DEPRECATION")
    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 人类可读大小：≥1MB 一位小数 MB，否则 KB（与设置页 formatIndexSize 同口径） */
    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1.0) "%.1f MB".format(mb) else "${bytes / 1024} KB"
    }
}
