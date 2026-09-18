package dev.ujhhgtg.wekit.preferences

import android.content.SharedPreferences
import dev.ujhhgtg.wekit.agent.data.WeKitDatabase
import dev.ujhhgtg.wekit.agent.data.entity.PreferenceEntryEntity
import dev.ujhhgtg.wekit.agent.data.entity.PreferenceSetMemberEntity
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Synchronous compatibility facade over the unified Room database.
 *
 * WePrefs is used from host hooks where the old MMKV facade was synchronous. The SQL work is
 * deliberately kept small and indexed; larger documents and assets use their own repositories.
 */
class RoomPrefsImpl(name: String) : WePrefs() {
    private val database = WeKitDatabase.instance
    private val sql get() = database.openHelper.writableDatabase
    private val namespace = name

    init {
        migrateLegacyIfNeeded()
    }

    override fun getAll(): Map<String, *> {
        val result = LinkedHashMap<String, Any?>()
        sql.query("SELECT `key`, valueType, valueText, valueLong, valueDouble, valueBlob FROM preference_entries WHERE namespace = ? ORDER BY `key`", arrayOf(namespace)).use { cursor ->
            val key = cursor.getColumnIndexOrThrow("key")
            val type = cursor.getColumnIndexOrThrow("valueType")
            val text = cursor.getColumnIndexOrThrow("valueText")
            val long = cursor.getColumnIndexOrThrow("valueLong")
            val double = cursor.getColumnIndexOrThrow("valueDouble")
            val blob = cursor.getColumnIndexOrThrow("valueBlob")
            while (cursor.moveToNext()) {
                val name = cursor.getString(key)
                decode(type = cursor.getString(type), text = cursor.getStringOrNull(text), long = cursor.getLongOrNull(long), double = cursor.getDoubleOrNull(double), blob = cursor.getBlobOrNull(blob), key = name)?.let { result[name] = it }
            }
        }
        return result
    }

    override fun getString(key: String?, defValue: String?): String? = read(key ?: return defValue)?.valueText ?: defValue

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val entry = read(key) ?: return defValues
        if (entry.valueType != TYPE_STRING_SET) return defValues
        val members = mutableSetOf<String>()
        sql.query("SELECT member FROM preference_set_members WHERE namespace = ? AND `key` = ? ORDER BY member", arrayOf(namespace, key)).use { cursor ->
            while (cursor.moveToNext()) members += cursor.getString(0)
        }
        return members
    }

    override fun getInt(key: String, defValue: Int): Int = read(key)?.valueLong?.toIntOrNull(read(key)?.valueType) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = read(key)?.valueLong ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = read(key)?.valueDouble?.toFloat() ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key)?.valueLong?.let { it != 0L } ?: defValue
    override fun contains(key: String): Boolean = read(key) != null

    override fun getObject(key: String): Any? {
        val entry = read(key) ?: return null
        return decode(entry.valueType, entry.valueText, entry.valueLong, entry.valueDouble, entry.valueBlob, key)
    }

    override fun putObject(key: String, obj: Any): WePrefs {
        when (obj) {
            is Boolean -> putBoolean(key, obj)
            is Int -> putInt(key, obj)
            is Long -> putLong(key, obj)
            is Float, is Double -> putFloat(key, (obj as Number).toFloat())
            is String -> putString(key, obj)
            is Set<*> -> putStringSet(key, obj.filterIsInstance<String>().toSet())
            is ByteArray -> putBytes(key, obj)
            is java.io.Serializable -> {
                val bytes = ByteArrayOutputStream().also { output -> ObjectOutputStream(output).use { it.writeObject(obj) } }.toByteArray()
                write(key, TYPE_SERIALIZABLE, blob = bytes)
            }
            else -> error("unsupported preference type ${obj::class}")
        }
        return this
    }

    override fun putString(key: String, value: String?): WePrefs {
        if (value == null) return remove(key)
        write(key, TYPE_STRING, text = value)
        return this
    }

    override fun putStringSet(key: String, values: Set<String>?): WePrefs {
        if (values == null) return remove(key)
        write(key, TYPE_STRING_SET)
        sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ? AND `key` = ?", arrayOf(namespace, key))
        values.forEach { sql.execSQL("INSERT OR REPLACE INTO preference_set_members(namespace, `key`, member) VALUES (?, ?, ?)", arrayOf(namespace, key, it)) }
        return this
    }

    override fun putInt(key: String, value: Int): WePrefs { write(key, TYPE_INT, long = value.toLong()); return this }
    override fun putLong(key: String, value: Long): WePrefs { write(key, TYPE_LONG, long = value); return this }
    override fun putFloat(key: String, value: Float): WePrefs { write(key, TYPE_FLOAT, double = value.toDouble()); return this }
    override fun putBoolean(key: String, value: Boolean): WePrefs { write(key, TYPE_BOOL, long = if (value) 1L else 0L); return this }
    override fun getBytesOrDefault(key: String, defValue: ByteArray): ByteArray = getBytes(key, null) ?: defValue
    override fun getBytes(key: String, defValue: ByteArray?): ByteArray? = read(key)?.valueBlob ?: defValue
    override fun putBytes(key: String, value: ByteArray) { write(key, TYPE_BYTES, blob = value) }

    override fun remove(key: String): WePrefs {
        sql.execSQL("DELETE FROM preference_entries WHERE namespace = ? AND `key` = ?", arrayOf(namespace, key))
        sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ? AND `key` = ?", arrayOf(namespace, key))
        return this
    }

    override fun clear(): WePrefs {
        sql.execSQL("DELETE FROM preference_entries WHERE namespace = ?", arrayOf(namespace))
        sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ?", arrayOf(namespace))
        return this
    }

    override fun save() { commit() }
    override fun commit(): Boolean = true
    override fun apply() = Unit
    override val isReadOnly = false
    override val isPersistent = true
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private fun read(key: String): PreferenceEntryEntity? {
        sql.query("SELECT namespace, `key`, valueType, valueText, valueLong, valueDouble, valueBlob, encodingVersion, revision, updatedAt, exportable FROM preference_entries WHERE namespace = ? AND `key` = ?", arrayOf(namespace, key)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return PreferenceEntryEntity(
                namespace = cursor.getString(0), key = cursor.getString(1), valueType = cursor.getString(2),
                valueText = cursor.getStringOrNull(3), valueLong = cursor.getLongOrNull(4), valueDouble = cursor.getDoubleOrNull(5),
                valueBlob = cursor.getBlobOrNull(6), encodingVersion = cursor.getInt(7), revision = cursor.getLong(8), updatedAt = cursor.getLong(9), exportable = cursor.getInt(10) != 0,
            )
        }
    }

    private fun write(key: String, type: String, text: String? = null, long: Long? = null, double: Double? = null, blob: ByteArray? = null) {
        sql.execSQL("INSERT OR REPLACE INTO preference_entries(namespace, `key`, valueType, valueText, valueLong, valueDouble, valueBlob, encodingVersion, revision, updatedAt, exportable) VALUES (?, ?, ?, ?, ?, ?, ?, 1, COALESCE((SELECT revision + 1 FROM preference_entries WHERE namespace = ? AND `key` = ?), 1), ?, ?)", arrayOf(namespace, key, type, text, long, double, blob, namespace, key, System.currentTimeMillis(), if (key == "payment_pswd_encdata") 0 else 1))
    }

    private fun migrateLegacyIfNeeded() {
        val legacy = runCatching { MmkvPrefsImpl(PREFS_NAME) }.getOrNull() ?: return
        val hasRows = sql.query("SELECT 1 FROM preference_entries WHERE namespace = ? LIMIT 1", arrayOf(namespace)).use { it.moveToFirst() }
        if (hasRows) return
        runCatching {
            sql.beginTransaction()
            legacy.getAll().forEach { (key, value) -> putObject(key, value ?: return@forEach) }
            sql.setTransactionSuccessful()
        }.onFailure { WeLogger.e("RoomPrefsImpl", "failed to migrate legacy MMKV", it) }
            .also { sql.endTransaction() }
    }

    private fun decode(type: String, text: String?, long: Long?, double: Double?, blob: ByteArray?, key: String): Any? = when (type) {
        TYPE_BOOL -> long?.let { it != 0L }
        TYPE_INT -> long?.toInt()
        TYPE_LONG -> long
        TYPE_FLOAT -> double?.toFloat()
        TYPE_STRING -> text
        TYPE_STRING_SET -> getStringSet(key, emptySet())
        TYPE_BYTES -> blob
        TYPE_SERIALIZABLE -> runCatching { ObjectInputStream(ByteArrayInputStream(blob ?: return null)).use { it.readObject() } }.getOrNull()
        else -> null
    }

    companion object {
        private const val PREFS_NAME = WePrefs.PREFS_NAME
        private const val TYPE_BOOL = "bool"
        private const val TYPE_INT = "int"
        private const val TYPE_LONG = "long"
        private const val TYPE_FLOAT = "float"
        private const val TYPE_STRING = "string"
        private const val TYPE_STRING_SET = "string_set"
        private const val TYPE_BYTES = "bytes"
        private const val TYPE_SERIALIZABLE = "serializable"
    }
}

private fun Long.toIntOrNull(type: String?): Int? = if (type == "int") toInt() else null
private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun android.database.Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun android.database.Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
private fun android.database.Cursor.getBlobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)
