package dev.ujhhgtg.wekit.agent.data

import android.content.Context
import android.net.Uri
import dev.ujhhgtg.wekit.agent.data.entity.AssetBindingEntity
import dev.ujhhgtg.wekit.agent.data.entity.AssetChunkEntity
import dev.ujhhgtg.wekit.agent.data.entity.AssetEntity
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
    private const val MIGRATION_KEY = "legacy-document-v1"
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
        "custom_avatars_map.json",
    )

    private data class AssetSource(
        val owner: String,
        val slot: String,
        val mimeType: String,
        val metadataJson: String?,
        val open: () -> InputStream,
    )

    private data class AssetDigest(val sha256: String, val sizeBytes: Long)

    fun run(context: Context) {
        val state = UnifiedDocumentStore.get(MIGRATION_NAMESPACE, MIGRATION_KEY)
        if (state?.content?.contains("\"status\":\"completed\"") == true) return

        val startedAt = System.currentTimeMillis()
        putState("running", startedAt, null)
        try {
            val root = KnownPaths.moduleRoot.toFile()
            var documentCount = 0
            documentFiles.forEach { relative ->
                val file = File(root, relative)
                if (!file.isFile) return@forEach
                UnifiedDocumentStore.put(
                    DOCUMENT_NAMESPACE,
                    relative,
                    file.readText(Charsets.UTF_8),
                    updatedAt = file.lastModified().takeIf { it > 0 } ?: startedAt,
                )
                documentCount++
            }

            val assetCount = AtomicInteger()
            migrateDirectoryAssets(root, "assets/user", "legacy-assets-user", assetCount)
            migrateDirectoryAssets(root, "themes", "legacy-themes", assetCount)
            migrateDirectoryAssets(root, "sticker_panel", "legacy-sticker-panel", assetCount)
            migrateDirectoryAssets(root, "voice_panel", "legacy-voice-panel", assetCount)
            migrateCustomAvatars(context, File(root, "custom_avatars_map.json"), assetCount)

            putState(
                "completed",
                startedAt,
                mapOf(
                    "documents" to documentCount,
                    "assets" to assetCount.get(),
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
        }
    }

    private fun importAsset(source: AssetSource) = runBlocking(Dispatchers.IO) {
        val digest = digest(source.open)
        val assetId = "legacy-${digest.sha256}"
        val dao = WeKitDatabase.instance.assetDao()
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
        UnifiedDocumentStore.put(MIGRATION_NAMESPACE, MIGRATION_KEY, content, exportable = true)
    }

    private fun metadata(vararg values: Pair<String, String>): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            values.forEach { (key, value) -> put(key, value) }
        })

    private fun mimeType(name: String): String =
        URLConnection.guessContentTypeFromName(name)?.lowercase(Locale.ROOT)
            ?: "application/octet-stream"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
