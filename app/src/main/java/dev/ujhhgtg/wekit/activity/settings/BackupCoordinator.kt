package dev.ujhhgtg.wekit.activity.settings

import android.content.Context
import android.os.Build
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.restartHost
import dev.ujhhgtg.wekit.utils.fs.LegacyPaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson

/**
 * Creates and restores the user-facing WeKit backup format.
 *
 * The archive deliberately has a small, explicit allow-list. Runtime artifacts, caches,
 * diagnostics and host data are never discovered by walking the whole module directory.
 */
object BackupCoordinator {
    private const val FORMAT_VERSION = 1
    private const val MANIFEST = "manifest.json"
    private const val DATABASE = "wekit.sqlite"
    private const val LIVE_DATABASE = "wekit.db"

    private val managedDirectories = listOf(
        "scripts_java",
        "scripts_python",
        "agent/skills",
        "python/data",
    )

    data class Result(val output: File, val fileCount: Int)

    data class ImportResult(val restoredFiles: Int)

    private data class ManifestFile(val path: String, val size: Long, val sha256: String)

    private data class ExtensionRequirement(val id: String, val version: String, val sha256: String)

    private data class Manifest(
        val formatVersion: Int,
        val packageName: String,
        val moduleVersion: String,
        val abis: List<String>,
        val extensions: List<ExtensionRequirement>,
        val files: List<ManifestFile>,
    )

    fun create(context: Context, output: File): Result {
        val root = storageRoot(context)
        require(root.isDirectory) { "WeKit 数据目录不存在" }
        val database = File(root, LIVE_DATABASE)
        require(database.isFile) { "统一数据库不存在，无法创建完整备份" }

        val scratch = File(context.cacheDir, ".wekit-backup-${UUID.randomUUID()}.sqlite")
        val files = ArrayList<ManifestFile>()
        val extensions = installedExtensions()
        try {
            snapshotDatabase(database, scratch)
            files += ManifestFile(DATABASE, scratch.length(), sha256(scratch))
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
                writeFile(zip, MANIFEST, manifestJson(context, files, extensions).toByteArray(Charsets.UTF_8))
                writeFile(zip, DATABASE, scratch)
                for (directory in managedDirectories) {
                    val source = File(root, directory)
                    if (!source.isDirectory) continue
                    source.walkTopDown()
                        .filter { it.isFile && !it.isSymbolicLink() }
                        .forEach { file ->
                            val relative = file.relativeTo(source).invariantSeparatorsPath
                            val archivePath = "$directory/$relative"
                            files += ManifestFile(archivePath, file.length(), sha256(file))
                            writeFile(zip, archivePath, file)
                        }
                }
            }
            // The manifest must describe all entries, including the files added after the DB.
            rewriteManifest(output, context, files, extensions)
            return Result(output, files.size)
        } finally {
            scratch.delete()
        }
    }

    /** Writes a package to the app-private backup directory before an import replaces data. */
    fun createPreImportBackup(context: Context): File {
        val directory = File(storageRoot(context), "backups").apply { mkdirs() }
        val output = File(directory, "pre-import-${System.currentTimeMillis()}.wekitbackup")
        create(context, output)
        return output
    }

    fun import(context: Context, input: File): ImportResult {
        require(input.isFile) { "备份文件不存在" }
        val root = storageRoot(context)
        val staging = File(root.parentFile, ".wekit-import-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            val manifest = extractAndValidate(input, staging)
            check(manifest.packageName == context.packageName) { "备份属于其他应用" }
            check(manifest.formatVersion == FORMAT_VERSION) { "不支持的备份格式" }
            check(manifest.abis.any(Build.SUPPORTED_ABIS::contains)) {
                "当前 ABI 与备份不匹配，请先安装匹配的运行制品"
            }
            checkExtensions(manifest.extensions)
            val database = File(staging, DATABASE)
            check(database.isFile) { "备份缺少统一数据库" }
            validateDatabase(database)
            checkHashes(staging, manifest.files)

            // This callback point is intentionally kept in one place: the database owner can close
            // its Room instance before the file swap without making import know its implementation.
            beforeDatabaseReplace?.invoke()
            val oldRoot = File(root.parentFile, ".wekit-before-import-${UUID.randomUUID()}")
            replaceManagedData(root, staging, oldRoot)
            oldRoot.deleteRecursively()
            return ImportResult(manifest.files.size)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Set by the unified Room owner once it exposes its process-wide close hook. */
    var beforeDatabaseReplace: (() -> Unit)? = null

    fun restartAfterImport() = restartHost()

    /**
     * Clears every module-owned path under the managed root. Host files and public Downloads are
     * intentionally outside this root and are never touched.
     */
    fun clearAll(context: Context) {
        beforeDatabaseReplace?.invoke()
        val root = storageRoot(context)
        root.deleteRecursively()
        // Remove old module-owned roots and artifacts, but never the host's shared MMKV directory
        // wholesale. The old external root is module-specific and is safe to remove only here,
        // after the user explicitly chose destructive clearing.
        LegacyPaths.externalModuleRoot.toFile().deleteRecursively()
        LegacyPaths.privateExtensionRoot.toFile().deleteRecursively()
        LegacyPaths.privateAgentRoot.toFile().deleteRecursively()
        File(HostInfo.application.filesDir, "wekit-python").deleteRecursively()
        File(HostInfo.application.filesDir, "wekit_skip_rewarded_js").deleteRecursively()
        File(HostInfo.application.filesDir, ".wekit-native").deleteRecursively()
        File(HostInfo.application.codeCacheDir, "generated_proxy_classes").deleteRecursively()
        val mmkv = File(HostInfo.application.filesDir, "mmkv")
        listOf("wekit_prefs", "wekit_prefs.crc").forEach { File(mmkv, it).delete() }
        mmkv.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(".wekit-bootstrap-") }
            .forEach(File::delete)
    }

    fun storageRoot(context: Context): File = File(context.filesDir, "wekit")

    private fun manifestJson(
        context: Context,
        files: List<ManifestFile>,
        extensions: List<ExtensionRequirement>,
    ): String =
        DefaultJson.encodeToString(buildJsonObject {
            put("formatVersion", FORMAT_VERSION)
            put("packageName", context.packageName)
            put("moduleVersion", BuildConfig.VERSION_NAME)
            put("abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(JsonPrimitive(it)) } })
            put("extensions", buildJsonArray {
                extensions.forEach { extension ->
                    add(buildJsonObject {
                        put("id", extension.id)
                        put("version", extension.version)
                        put("sha256", extension.sha256)
                    })
                }
            })
            put("files", buildJsonArray {
                files.forEach { file ->
                    add(buildJsonObject {
                        put("path", file.path)
                        put("size", file.size)
                        put("sha256", file.sha256)
                    })
                }
            })
        })

    private fun rewriteManifest(
        output: File,
        context: Context,
        files: List<ManifestFile>,
        extensions: List<ExtensionRequirement>,
    ) {
        val temp = File(output.parentFile, ".${output.name}.rewrite")
        ZipInputStream(BufferedInputStream(FileInputStream(output))).use { input ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zip ->
                var entry = input.nextEntry
                while (entry != null) {
                    if (entry.name != MANIFEST) {
                        zip.putNextEntry(ZipEntry(entry.name))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                    entry = input.nextEntry
                }
                writeFile(zip, MANIFEST, manifestJson(context, files, extensions).toByteArray(Charsets.UTF_8))
            }
        }
        check(temp.renameTo(output)) { "无法写入备份清单" }
    }

    private fun writeFile(zip: ZipOutputStream, path: String, file: File) {
        FileInputStream(file).use { writeFile(zip, path, it) }
    }

    private fun writeFile(zip: ZipOutputStream, path: String, content: ByteArray) =
        writeFile(zip, path, content.inputStream())

    private fun writeFile(zip: ZipOutputStream, path: String, input: java.io.InputStream) {
        zip.putNextEntry(ZipEntry(path))
        input.use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun extractAndValidate(input: File, staging: File): Manifest {
        var manifestJson: String? = null
        val entryNames = HashSet<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(input))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                check(!entry.isDirectory) { "备份包含目录条目" }
                val path = safeArchivePath(entry.name)
                check(entryNames.add(path)) { "备份包含重复文件：$path" }
                check(path == MANIFEST || path == DATABASE || managedDirectories.any { path.startsWith("$it/") }) {
                    "备份包含不允许的文件：$path"
                }
                val destination = File(staging, path)
                destination.parentFile?.mkdirs()
                if (path == MANIFEST) {
                    check(manifestJson == null) { "备份包含重复清单" }
                    manifestJson = zip.readBytes().toString(Charsets.UTF_8)
                } else {
                    FileOutputStream(destination).use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
        val raw = checkNotNull(manifestJson) { "备份缺少清单" }
        val manifest = parseManifest(raw)
        val listed = manifest.files.map { safeArchivePath(it.path) }.toSet()
        check(entryNames == listed + MANIFEST) { "备份清单与文件内容不一致" }
        return manifest
    }

    private fun parseManifest(raw: String): Manifest {
        val json = Json.parseToJsonElement(raw).jsonObject
        val files = json["files"]!!.jsonArray.map { item ->
            val obj = item.jsonObject
            ManifestFile(
                obj["path"]!!.jsonPrimitive.content,
                obj["size"]!!.jsonPrimitive.content.toLong(),
                obj["sha256"]!!.jsonPrimitive.content,
            )
        }
        return Manifest(
            json["formatVersion"]!!.jsonPrimitive.content.toInt(),
            json["packageName"]!!.jsonPrimitive.content,
            json["moduleVersion"]!!.jsonPrimitive.content,
            json["abis"]!!.jsonArray.map { it.jsonPrimitive.content },
            json["extensions"]!!.jsonArray.map { item ->
                val obj = item.jsonObject
                ExtensionRequirement(
                    obj["id"]!!.jsonPrimitive.content,
                    obj["version"]!!.jsonPrimitive.content,
                    obj["sha256"]!!.jsonPrimitive.content,
                )
            },
            files,
        )
    }

    private fun installedExtensions(): List<ExtensionRequirement> =
        ExtensionPacks.packs.mapNotNull { pack ->
            pack.installedManifest()?.let { manifest ->
                ExtensionRequirement(manifest.id, manifest.version, manifest.sha256)
            }
        }

    private fun checkExtensions(requirements: List<ExtensionRequirement>) {
        requirements.forEach { requirement ->
            val pack = ExtensionPacks.byId(requirement.id)
                ?: error("备份需要未安装的扩展包：${requirement.id}")
            val installed = pack.installedManifest()
                ?: error("备份需要扩展包：${requirement.id} ${requirement.version}")
            check(installed.version == requirement.version && installed.sha256.equals(requirement.sha256, true)) {
                "扩展包不匹配：${requirement.id}，请先安装 ${requirement.version}"
            }
        }
    }

    private fun checkHashes(staging: File, files: List<ManifestFile>) {
        val allowed = files.map { it.path }.toSet()
        check(DATABASE in allowed) { "备份清单缺少数据库" }
        for (file in files) {
            val target = File(staging, safeArchivePath(file.path))
            check(target.isFile) { "备份文件缺失：${file.path}" }
            check(target.length() == file.size) { "备份文件大小校验失败：${file.path}" }
            check(sha256(target).equals(file.sha256, ignoreCase = true)) {
                "备份文件校验失败：${file.path}"
            }
        }
    }

    private fun validateDatabase(database: File) {
        val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        sqlite.use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "数据库完整性校验失败" }
            }
        }
    }

    private fun snapshotDatabase(source: File, destination: File) {
        // A checkpoint makes the main file self-contained before it is copied. Room keeps WAL
        // enabled for the live database; the temporary snapshot is never opened by Room.
        android.database.sqlite.SQLiteDatabase.openDatabase(
            source.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        }
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
        }
        sanitizeSnapshot(destination)
    }

    private fun sanitizeSnapshot(database: File) {
        val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        sqlite.use { db ->
            for (table in listOf(
                "preference_entries",
                "preference_set_members",
                "documents",
                "settings",
                "legacy_mmkv_entries",
            )) {
                if (!tableExists(db, table)) continue
                val columns = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
                    buildList {
                        val nameIndex = cursor.getColumnIndex("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
                if ("key" in columns) {
                    db.delete(table, "key = ? OR key LIKE ?", arrayOf("payment_pswd_encdata", "%payment_pswd_encdata%"))
                }
            }
            // Rebuild the export copy after filtering device-local payment data so deleted
            // ciphertext cannot remain in free pages of the backup database.
            db.execSQL("VACUUM")
        }
    }

    private fun tableExists(db: android.database.sqlite.SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun replaceManagedData(root: File, staging: File, oldRoot: File) {
        oldRoot.mkdirs()
        root.mkdirs()
        try {
            val database = File(root, LIVE_DATABASE)
            val stagedDatabase = File(staging, DATABASE)
            moveIfPresent(database, File(oldRoot, LIVE_DATABASE))
            moveIfPresent(stagedDatabase, database)
            for (directory in managedDirectories) {
                val current = File(root, directory)
                val replacement = File(staging, directory)
                val old = File(oldRoot, directory)
                if (current.exists()) {
                    old.parentFile?.mkdirs()
                    check(current.renameTo(old)) { "无法暂存旧目录：$directory" }
                }
                replacement.parentFile?.mkdirs()
                if (replacement.isDirectory) check(replacement.renameTo(current)) { "无法恢复目录：$directory" }
                else current.mkdirs()
            }
        } catch (t: Throwable) {
            // Restore each moved item before surfacing the error. Import is replacement semantics:
            // a failed swap must leave the current installation usable.
            if (File(oldRoot, LIVE_DATABASE).exists()) {
                File(root, LIVE_DATABASE).delete()
                moveIfPresent(File(oldRoot, LIVE_DATABASE), File(root, LIVE_DATABASE))
            }
            for (directory in managedDirectories) {
                val old = File(oldRoot, directory)
                if (old.exists()) {
                    File(root, directory).deleteRecursively()
                    moveIfPresent(old, File(root, directory))
                }
            }
            throw t
        }
    }

    private fun moveIfPresent(source: File, destination: File) {
        if (!source.exists()) return
        destination.parentFile?.mkdirs()
        check(source.renameTo(destination)) { "无法替换数据库" }
    }

    private fun safeArchivePath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotEmpty() && !normalized.startsWith('/') && !normalized.contains("../")) {
            "备份路径越界"
        }
        require(normalized == normalized.trimStart('/')) { "备份路径越界" }
        return normalized
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.isSymbolicLink(): Boolean =
        java.nio.file.Files.isSymbolicLink(toPath())
}
