package dev.ujhhgtg.wekit.dexkit.cache

import dev.ujhhgtg.wekit.agent.data.WeKitDatabase
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.unreachable
import dev.ujhhgtg.wekit.agent.data.entity.DexCacheDescriptorEntity
import dev.ujhhgtg.wekit.agent.data.entity.DexCacheEntryEntity

/** Host-versioned Dex descriptors stored inside the shared Room database. */
object DexCacheManager {
    private const val TAG = "DexCacheManager"
    private lateinit var hostVersion: String

    fun init(currentVer: String) {
        hostVersion = currentVer
        WeLogger.i(TAG, "using Room Dex cache version: $currentVer")
    }

    fun isItemCacheValid(item: IResolveDex): Boolean {
        if (item !is BaseFeature) unreachable()
        val record = read(item.technicalId) ?: return false
        if (record.methodHash != methodHash(item)) return false
        val missing = item.dexDelegates.filter { record.descriptors[it.key].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            WeLogger.d(TAG, "cache incomplete for ${item.technicalPath}: ${missing.map { it.key }}")
            return false
        }
        return true
    }

    fun saveItemCache(item: IResolveDex) {
        if (item !is BaseFeature) error("item is not BaseFeature")
        val descriptors = item.collectDescriptors()
        database().beginTransaction()
        try {
            database().execSQL("DELETE FROM dex_cache_descriptors WHERE hostVersion = ? AND technicalId = ?", arrayOf<Any?>(hostVersion, item.technicalId))
            database().execSQL("INSERT OR REPLACE INTO dex_cache_entries(hostVersion, technicalId, methodHash, timestamp) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, item.technicalId, methodHash(item), System.currentTimeMillis()))
            descriptors.forEach { (key, value) ->
                database().execSQL("INSERT OR REPLACE INTO dex_cache_descriptors(hostVersion, technicalId, descriptorKey, descriptorValue) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, item.technicalId, key, value))
            }
            database().setTransactionSuccessful()
        } finally {
            database().endTransaction()
        }
    }

    fun loadItemCache(item: IResolveDex): Map<String, Any>? {
        if (item !is BaseFeature) error("item is not BaseFeature")
        return read(item.technicalId)?.descriptors?.mapValues { it.value as Any }
    }

    fun deleteCache(technicalId: String) {
        database().execSQL("DELETE FROM dex_cache_entries WHERE hostVersion = ? AND technicalId = ?", arrayOf<Any?>(hostVersion, technicalId))
    }

    /** Clears only the current host-version partition; older versions remain available. */
    fun clearAllCache() {
        database().execSQL("DELETE FROM dex_cache_entries WHERE hostVersion = ?", arrayOf<Any?>(hostVersion))
        database().execSQL("DELETE FROM dex_cache_descriptors WHERE hostVersion = ?", arrayOf<Any?>(hostVersion))
        WeLogger.i(TAG, "cleared Room Dex cache for $hostVersion")
    }

    fun getOutdatedItems(items: List<IResolveDex>): List<IResolveDex> =
        items.filter { !isItemCacheValid(it) }

    fun importCloudCaches(entries: List<CloudDexCacheEntry>) {
        entries.forEach { entry ->
            database().beginTransaction()
            try {
                database().execSQL("DELETE FROM dex_cache_descriptors WHERE hostVersion = ? AND technicalId = ?", arrayOf<Any?>(hostVersion, entry.technicalId))
                database().execSQL("INSERT OR REPLACE INTO dex_cache_entries(hostVersion, technicalId, methodHash, timestamp) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, entry.technicalId, entry.methodHash, System.currentTimeMillis()))
                entry.descriptors.forEach { (key, value) ->
                    database().execSQL("INSERT OR REPLACE INTO dex_cache_descriptors(hostVersion, technicalId, descriptorKey, descriptorValue) VALUES (?, ?, ?, ?)", arrayOf<Any?>(hostVersion, entry.technicalId, key, value))
                }
                database().setTransactionSuccessful()
            } finally {
                database().endTransaction()
            }
        }
    }

    fun methodHash(item: IResolveDex): String {
        val hash = GeneratedMethodHashes.HASHES[(item as BaseFeature).technicalId]
        if (hash.isNullOrBlank()) error("failed to retrieve method hash for item ${item.technicalId}")
        return hash
    }

    private data class CacheRecord(val methodHash: String, val descriptors: Map<String, String>)

    private fun read(technicalId: String): CacheRecord? = runCatching {
        val entry = database().query(
            "SELECT methodHash FROM dex_cache_entries WHERE hostVersion = ? AND technicalId = ?",
            arrayOf(hostVersion, technicalId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)
        } ?: return@runCatching null
        val descriptors = buildMap {
            database().query("SELECT descriptorKey, descriptorValue FROM dex_cache_descriptors WHERE hostVersion = ? AND technicalId = ?", arrayOf(hostVersion, technicalId)).use { cursor ->
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }
        CacheRecord(entry, descriptors)
    }.onFailure { WeLogger.e(TAG, "failed to read Room Dex cache for $technicalId", it) }.getOrNull()

    private fun database() = WeKitDatabase.instance.openHelper.writableDatabase
}
