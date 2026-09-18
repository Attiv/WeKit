package dev.ujhhgtg.wekit.agent.data

import java.io.File
import dev.ujhhgtg.wekit.utils.HostInfo

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
        get() = File(HostInfo.application.filesDir, "wekit/$FILE_NAME")
}
