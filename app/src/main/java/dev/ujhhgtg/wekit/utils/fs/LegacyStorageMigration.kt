package dev.ujhhgtg.wekit.utils.fs

import android.content.Context
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** One-shot copy/verify migration from the pre-private-root module directories. */
object LegacyStorageMigration {
    private const val TAG = "LegacyStorageMigration"
    private const val MARKER = ".legacy-storage-v1.done"

    fun run(context: Context) {
        val root = KnownPaths.moduleRoot.toFile()
        root.mkdirs()
        val marker = File(root, MARKER)
        if (marker.isFile) return
        FileChannel.open(
            root.toPath().resolve(".legacy-storage.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                if (marker.isFile) return
                runCatching {
                    migrateExternal()
                    migratePrivate(LegacyPaths.privateExtensionRoot.toFile(), File(root, "extensions"))
                    migratePrivate(
                        LegacyPaths.privateAgentRoot.toFile(),
                        File(root, "agent"),
                        excluded = setOf("weagent.db", "weagent.db-wal", "weagent.db-shm", "weagent.db-journal"),
                    )
                    marker.writeText("1")
                }.onFailure { WeLogger.e(TAG, "legacy storage migration failed; sources were retained", it) }
            }
        }
    }

    private fun migrateExternal() {
        val source = LegacyPaths.externalModuleRoot.toFile()
        if (!source.isDirectory) return
        val root = KnownPaths.moduleRoot.toFile()
        val directories = listOf(
            "assets", "scripts_java", "scripts_python", "python", "agent/skills", "themes",
            "sticker_panel", "voice_panel", "extensions/script-deps",
        )
        directories.forEach { relative ->
            migratePrivate(File(source, relative), File(root, relative))
        }
        val files = listOf(
            "conversation_groups.json", "chat_folders.json", "moments_custom_bottom_details.json",
            "feature_flag_overrides.json", "red_packet_settings.json", "red_packet_group_members.json",
            "auto_accept_transfer_settings.json", "custom_avatars_map.json",
        )
        files.forEach { name -> migratePrivate(File(source, name), File(root, name)) }
        migratePrivate(
            File(source, "virtual_voip_video.mp4"),
            File(root, "assets/virtual_voip_video.mp4"),
        )
    }

    private fun migratePrivate(source: File, destination: File, excluded: Set<String> = emptySet()) {
        if (!source.exists()) return
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { child ->
                if (child.name in excluded) return@forEach
                migratePrivate(child, File(destination, child.name), excluded)
            }
            return
        }
        if (destination.exists()) return
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.migrating")
        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() }
        }
        check(temporary.length() == source.length()) { "legacy file copy was truncated: $source" }
        check(sha256(temporary) == sha256(source)) { "legacy file copy failed verification: $source" }
        check(temporary.renameTo(destination)) { "cannot publish migrated file: $destination" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
