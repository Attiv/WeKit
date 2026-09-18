package dev.ujhhgtg.wekit.agent.data

import java.io.File
import dev.ujhhgtg.wekit.activity.settings.BackupCoordinator
import dev.ujhhgtg.wekit.utils.fs.KnownPaths

/**
 * Unified storage entry point.
 *
 * The Room implementation keeps the historical [WeAgentDatabase] name so existing repositories
 * and generated schema history remain source/binary compatible. New module storage code should
 * obtain the same instance through this facade; there is only one active SQLite file.
 */
object WeKitDatabase {
    const val FILE_NAME = "wekit.db"

    val instance: WeAgentDatabase
        get() = WeAgentDatabase.instance

    /** Location used by the unified database after the path migration has completed. */
    val file: File
        get() = KnownPaths.moduleRoot.resolve(FILE_NAME).toFile()

    init {
        BackupCoordinator.beforeDatabaseReplace = { WeAgentDatabase.close() }
    }
}
