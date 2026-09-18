package dev.ujhhgtg.wekit.agent.data

import kotlin.io.path.moveTo
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

data class PreparedDatabaseLocation(
    val file: File,
    val migratedNow: Boolean,
    val externalFallback: Boolean,
    val failure: Throwable? = null,
)

/**
 * Copies a legacy WeAgent database to the unified private database in two phases. The source is
 * deliberately retained: migration cleanup is a separate user-confirmed operation.
 */
class WeAgentDatabaseRelocator(
    private val source: File,
    private val destination: File,
    private val recoverSource: (File) -> Unit,
) {
    private val temp: File = File(destination.parentFile, destination.name + ".migrating")

    fun prepare(): PreparedDatabaseLocation {
        if (destination.isFile) {
            return PreparedDatabaseLocation(destination, migratedNow = false, externalFallback = false)
        }
        if (!source.isFile) {
            // Fresh install: no external copy to migrate and recoverSource() would throw
            // on the missing file, so go straight to a brand-new private database.
            destination.parentFile!!.mkdirs()
            return PreparedDatabaseLocation(destination, migratedNow = false, externalFallback = false)
        }
        return try {
            destination.parentFile!!.mkdirs()
            recoverSource(source)
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            temp.toPath().moveTo(destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            PreparedDatabaseLocation(destination, migratedNow = true, externalFallback = false)
        } catch (t: Throwable) {
            temp.delete()
            destination.delete()
            destination.deleteSidecars()
            PreparedDatabaseLocation(source, migratedNow = false, externalFallback = true, failure = t)
        }
    }

    fun commit(prepared: PreparedDatabaseLocation) {
        // Keep the legacy source and sidecars until the user explicitly clears migrated copies.
    }

    fun rollback(prepared: PreparedDatabaseLocation) {
        if (!prepared.migratedNow) return
        temp.delete()
        destination.delete()
        destination.deleteSidecars()
    }

    private fun File.deleteSidecars() {
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            File(path + suffix).delete()
        }
    }
}
