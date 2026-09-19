package dev.ujhhgtg.wekit.data

import android.content.Context
import android.net.Uri
import android.os.Build
import dev.ujhhgtg.wekit.data.entity.AssetBindingEntity
import dev.ujhhgtg.wekit.data.entity.AssetChunkEntity
import dev.ujhhgtg.wekit.data.entity.AssetEntity
import dev.ujhhgtg.wekit.data.entity.ExtensionInstallEntity
import dev.ujhhgtg.wekit.data.entity.ManagedDataEntryEntity
import dev.ujhhgtg.wekit.data.entity.ScriptCatalogEntity
import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.extensions.PackFs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Imports the first set of legacy JSON documents and user-owned media into the unified Room
 * catalog.  Files remain in place until each runtime repository is converted to read the new
 * tables, so this migration is safe to retry and does not create a second runtime tree.
 */
object LegacyDocumentMigration {
    private const val TAG = "LegacyDocumentMigration"
    private const val MIGRATION_NAMESPACE = "migration"
    /**
     * v3 deliberately keeps file-backed media in the file tree.  v2 accidentally imported the
     * complete themes/sticker/voice trees (and the complete assets tree) into asset_chunks, which
     * made the database grow with every media file and left the feature readers unable to import
     * their original directories.  Bump the marker so an installation that already completed v2
     * runs the cleanup below exactly once.
     */
    private const val MIGRATION_KEY = "legacy-document-v3"
    private const val DOCUMENT_NAMESPACE = "legacy-json"
    private const val CHUNK_SIZE = 256 * 1024

    private val documentFiles = listOf(
        "conversation_groups.json",
        "chat_folders.json",
        "moments_custom_bottom_details.json",
        "feature_flag_overrides.json",
        "red_packet_settings.json",
        "red_packet_group_members.json",
        "auto_accept_transfer_settings.json",
        "auto_like_moments_settings.json",
        "auto_repost_moments_settings.json",
        "custom_avatars_map.json",
    )

    private data class AssetSource(
        val owner: String,
        val slot: String,
        val mimeType: String,
        val metadataJson: String?,
        val sizeHint: Long? = null,
        val open: () -> InputStream,
    )

    private data class AssetDigest(val sha256: String, val sizeBytes: Long)

    fun run(context: Context) {
        val state = DocumentStore.get(MIGRATION_NAMESPACE, MIGRATION_KEY)
        if (state?.content?.contains("\"status\":\"completed\"") == true) return

        val startedAt = System.currentTimeMillis()
        putState("running", startedAt, null)
        try {
            val root = KnownPaths.moduleRoot.toFile()
            var documentCount = 0
            documentFiles.forEach { relative ->
                val file = File(root, relative)
                if (!file.isFile) return@forEach
                DocumentStore.put(
                    DOCUMENT_NAMESPACE,
                    relative,
                    file.readText(Charsets.UTF_8),
                    updatedAt = file.lastModified().takeIf { it > 0 } ?: startedAt,
                )
                documentCount++
            }

            val assetCount = AtomicInteger()
            // Themes, sticker packs, voice packs and other media are file-backed runtime data.
            // Keep their original directory layout intact: importing them into Room would both
            // duplicate potentially gigabytes of data and break the existing import/read paths.
            // Only the explicitly catalogued user asset directory and custom avatar URIs belong
            // in the asset catalog.
            migrateDirectoryAssets(root, "assets/user", "legacy-assets-user", assetCount)
            migrateCustomAvatars(context, File(root, "custom_avatars_map.json"), assetCount)
            cleanupV2FileBackedAssets()
            val indexedFiles = scanScriptCatalog(root, startedAt) +
                    scanManagedData(root, startedAt) +
                    scanExtensionCatalog(root)

            putState(
                "completed",
                startedAt,
                mapOf(
                    "documents" to documentCount,
                    "assets" to assetCount.get(),
                    "indexedFiles" to indexedFiles,
                ),
            )
        } catch (error: Throwable) {
            runCatching {
                putState("failed", startedAt, mapOf("error" to (error.message ?: error.javaClass.name)))
            }.onFailure { metadataError ->
                WeLogger.e(TAG, "failed to persist legacy migration error state", metadataError)
            }
            WeLogger.e(TAG, "legacy document and asset migration failed; source files were retained", error)
        }
    }

    private fun migrateDirectoryAssets(
        root: File,
        relativeRoot: String,
        owner: String,
        assetCount: AtomicInteger,
    ) {
        val sourceRoot = File(root, relativeRoot)
        if (!sourceRoot.isDirectory) return
        val canonicalRoot = sourceRoot.canonicalPath + File.separator
        sourceRoot.walkTopDown()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                check(file.canonicalPath.startsWith(canonicalRoot)) { "asset escapes managed root: $file" }
                val slot = file.relativeTo(sourceRoot).invariantSeparatorsPath
                importAsset(
                    AssetSource(
                        owner = owner,
                        slot = slot,
                        mimeType = mimeType(file.name),
                        metadataJson = metadata("path" to "$relativeRoot/$slot"),
                        sizeHint = file.length(),
                        open = { file.inputStream() },
                    ),
                )
                assetCount.incrementAndGet()
            }
    }

    private fun migrateCustomAvatars(context: Context, mapFile: File, assetCount: AtomicInteger) {
        if (!mapFile.isFile) return
        val entries = Json.decodeFromString<Map<String, String>>(mapFile.readText(Charsets.UTF_8))
        entries.forEach { (username, rawUri) ->
            runCatching {
                val uri = Uri.parse(rawUri)
                val source = AssetSource(
                    owner = "custom-avatar",
                    slot = username,
                    mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                    metadataJson = metadata("uri" to rawUri),
                    open = {
                        context.contentResolver.openInputStream(uri)
                            ?: error("cannot read custom avatar URI: $rawUri")
                    },
                )
                importAsset(source)
                assetCount.incrementAndGet()
            }.onFailure { WeLogger.w(TAG, "failed to migrate custom avatar for $username", it) }
        }
    }

    private fun scanScriptCatalog(root: File, updatedAt: Long): Int = runBlocking(Dispatchers.IO) {
        val dao = WeKitDatabase.instance.scriptCatalogDao()
        var count = 0
        listOf("scripts_java" to "java", "scripts_python" to "python").forEach { (directory, kind) ->
            val sourceRoot = File(root, directory)
            if (!sourceRoot.isDirectory) return@forEach
            sourceRoot.listFiles().orEmpty().filter(File::isDirectory).forEach { scriptRoot ->
                if (java.nio.file.Files.isSymbolicLink(scriptRoot.toPath())) return@forEach
                val relative = scriptRoot.relativeTo(root).invariantSeparatorsPath
                val manifest = File(scriptRoot, "plugin.json")
                    .takeIf { kind == "python" && it.isFile }
                    ?.readText(Charsets.UTF_8)
                val updated = scriptRoot.lastModified().takeIf { it > 0 } ?: updatedAt
                dao.upsert(
                    ScriptCatalogEntity(
                        scriptId = "$kind:${scriptRoot.name}",
                        kind = kind,
                        relativePath = relative,
                        manifestJson = manifest,
                        contentHash = hashTree(scriptRoot),
                        enabled = !File(scriptRoot, "disabled.flag").exists(),
                        updatedAt = updated,
                    ),
                )
                count++
            }
        }
        count
    }

    private fun scanManagedData(root: File, updatedAt: Long): Int = runBlocking(Dispatchers.IO) {
        val sourceRoot = File(root, "python/data")
        if (!sourceRoot.isDirectory) return@runBlocking 0
        val dao = WeKitDatabase.instance.managedDataDao()
        var count = 0
        sourceRoot.walkTopDown()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .forEach { file ->
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
                val pluginId = relative.substringBefore('/').ifBlank { "python" }
                dao.upsert(
                    ManagedDataEntryEntity(
                        pluginId = pluginId,
                        relativePath = relative,
                        sizeBytes = file.length(),
                        contentHash = sha256(file),
                        modifiedAt = file.lastModified().takeIf { it > 0 } ?: updatedAt,
                    ),
                )
                count++
            }
        count
    }

    private fun scanExtensionCatalog(root: File): Int = runBlocking(Dispatchers.IO) {
        val dao = WeKitDatabase.instance.extensionInstallDao()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        var count = 0
        ExtensionPacks.packs.forEach { pack ->
            val installRoot = pack.installDir()
            if (!installRoot.isDirectory) return@forEach
            val selectedVersion = pack.installedManifest()?.version
            installRoot.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .forEach { versionDir ->
                    val manifest = runCatching { PackFs.readManifest(versionDir) }.getOrNull() ?: return@forEach
                    dao.upsert(
                        ExtensionInstallEntity(
                            extensionId = pack.id,
                            version = manifest.version,
                            abi = abi,
                            manifestJson = versionDir.resolve("manifest.json").takeIf { it.isFile }?.readText(),
                            contentHash = manifest.sha256,
                            relativePath = versionDir.relativeTo(root).invariantSeparatorsPath,
                            selected = manifest.version == selectedVersion,
                            mounted = manifest.version == selectedVersion && pack.isInUse(),
                            updatedAt = manifest.installedAtEpochMs,
                        ),
                    )
                    count++
                }
        }
        count
    }

    /**
     * Remove the media rows produced by the v2 migration.  The source files are deliberately
     * untouched; they remain the canonical representation used by theme, sticker and voice
     * importers.  Chunks are deleted only after their bindings are gone and only when no remaining
     * binding (for example a custom avatar with the same digest) references the asset.
     */
    private fun cleanupV2FileBackedAssets() = runBlocking(Dispatchers.IO) {
        val dao = WeKitDatabase.instance.assetDao()
        val obsolete = buildList {
            addAll(dao.getBindings("legacy-themes"))
            addAll(dao.getBindings("legacy-sticker-panel"))
            addAll(dao.getBindings("legacy-voice-panel"))
            // v2 changed the owner from legacy-assets-user to legacy-assets and therefore also
            // captured assets/virtual_voip_video.mp4. Retain the user/ subtree, but remove every
            // other legacy-assets slot now that the file is canonical on disk.
            addAll(dao.getBindings("legacy-assets").filterNot { it.slot.startsWith("user/") })
        }
        obsolete.forEach { binding -> dao.unbind(binding.owner, binding.slot) }
        val orphanedAssets = mutableListOf<String>()
        obsolete.asSequence().map { it.assetId }.distinct().forEach { assetId ->
            if (dao.countBindings(assetId) == 0) orphanedAssets += assetId
        }
        if (orphanedAssets.isNotEmpty()) {
            // One DELETE per asset would create a transaction for every imported media file and
            // make the corrective migration as slow as the original import. Delete all orphaned
            // rows in two statements instead.
            orphanedAssets.chunked(500).forEach { assetIds ->
                dao.deleteChunksForAssets(assetIds)
                dao.deleteAssets(assetIds)
            }
        }
    }

    private fun importAsset(source: AssetSource) = runBlocking(Dispatchers.IO) {
        val dao = WeKitDatabase.instance.assetDao()
        val legacyBinding = if (source.owner == "legacy-assets-user") {
            // v1 used legacy-assets-user/<slot>; v2 used legacy-assets/user/<slot>. Reuse either
            // existing binding so retrying this migration does not duplicate its chunks.
            dao.getBinding("legacy-assets-user", source.slot)
                ?: dao.getBinding("legacy-assets", "user/${source.slot}")
        } else {
            null
        }
        val existingBinding = dao.getBinding(source.owner, source.slot) ?: legacyBinding
        val existingAsset = existingBinding?.let { dao.get(it.assetId) }
        val expectedExistingChunks = source.sizeHint?.let { (it + CHUNK_SIZE - 1) / CHUNK_SIZE }?.toInt()
        if (source.sizeHint != null && existingAsset != null &&
            existingAsset.sizeBytes == source.sizeHint && existingAsset.metadataJson == source.metadataJson &&
            expectedExistingChunks == dao.countChunks(existingAsset.assetId)
        ) {
            if (existingBinding.owner != source.owner || existingBinding.slot != source.slot) {
                dao.bind(AssetBindingEntity(source.owner, source.slot, existingBinding.assetId))
                dao.unbind(existingBinding.owner, existingBinding.slot)
            }
            return@runBlocking
        }

        val digest = digest(source.open)
        val assetId = "legacy-${digest.sha256}"
        val existing = dao.get(assetId)
        val expectedChunks = ((digest.sizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val currentChunks = if (existing == null) -1 else dao.countChunks(assetId)
        if (existing == null || existing.sizeBytes != digest.sizeBytes || existing.sha256 != digest.sha256 || currentChunks != expectedChunks) {
            dao.deleteChunks(assetId)
            dao.upsert(
                AssetEntity(
                    assetId = assetId,
                    mimeType = source.mimeType,
                    sizeBytes = digest.sizeBytes,
                    sha256 = digest.sha256,
                    createdAt = System.currentTimeMillis(),
                    metadataJson = source.metadataJson,
                ),
            )
            source.open().use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                var ordinal = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    dao.insertChunk(AssetChunkEntity(assetId, ordinal++, buffer.copyOf(read)))
                }
            }
        }
        dao.bind(AssetBindingEntity(source.owner, source.slot, assetId))
        if (existingBinding != null &&
            (existingBinding.owner != source.owner || existingBinding.slot != source.slot)
        ) {
            dao.unbind(existingBinding.owner, existingBinding.slot)
        }
    }

    private fun digest(open: () -> InputStream): AssetDigest {
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        open().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sha.update(buffer, 0, read)
                size += read
            }
        }
        return AssetDigest(sha.digest().toHex(), size)
    }

    private fun putState(status: String, startedAt: Long, details: Map<String, Any?>?) {
        val content = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("status", status)
            put("startedAt", startedAt)
            put("updatedAt", System.currentTimeMillis())
            details?.forEach { (key, value) ->
                when (value) {
                    null -> put(key, JsonNull)
                    is Number -> put(key, value.toLong())
                    else -> put(key, value.toString())
                }
            }
        })
        DocumentStore.put(MIGRATION_NAMESPACE, MIGRATION_KEY, content, exportable = true)
    }

    private fun metadata(vararg values: Pair<String, String>): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        })

    private fun mimeType(name: String): String =
        URLConnection.guessContentTypeFromName(name)?.lowercase(Locale.ROOT)
            ?: "application/octet-stream"

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (value in this@toHex) {
            val byte = value.toInt() and 0xff
            append(HEX_DIGITS[byte ushr 4])
            append(HEX_DIGITS[byte and 0x0f])
        }
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun hashTree(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                file.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().toHex()
    }
}
