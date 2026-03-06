// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor

/**
 * Data access object for VionBoard personal shortcuts.
 * A shortcut maps a short trigger word to a full expansion (may contain spaces/punctuation).
 * e.g. "omw" → "On my way!"
 * e.g. "addr" → "123 Main Street, Springfield"
 */
class ShortcutsDao private constructor(context: Context) {

    private val db = Database.getInstance(context)

    // In-memory cache for fast lookup during typing — rebuilt on any write
    private var cache: Map<String, ShortcutEntry> = emptyMap()
    private var cacheValid = false

    data class ShortcutEntry(
        val id: Long,
        val trigger: String,
        val expansion: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** Returns the expansion for an exact trigger match, or null. Fast — uses cache. */
    fun findExpansion(trigger: String): String? {
        if (!cacheValid) rebuildCache()
        return cache[trigger.lowercase()]?.expansion
    }

    /** Returns all shortcuts, sorted by trigger. */
    fun getAll(): List<ShortcutEntry> {
        val result = mutableListOf<ShortcutEntry>()
        db.readableDatabase.query(
            TABLE, arrayOf(COL_ID, COL_TRIGGER, COL_EXPANSION, COL_CREATED_AT),
            null, null, null, null, "$COL_TRIGGER ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toEntry())
        }
        return result
    }

    /** Adds or updates a shortcut. Trigger is stored lowercase. Returns row id. */
    fun upsert(trigger: String, expansion: String): Long {
        val values = ContentValues().apply {
            put(COL_TRIGGER, trigger.trim().lowercase())
            put(COL_EXPANSION, expansion.trim())
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        val rowId = db.writableDatabase.insertWithOnConflict(
            TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        cacheValid = false
        return rowId
    }

    /** Deletes a shortcut by trigger. */
    fun delete(trigger: String) {
        db.writableDatabase.delete(TABLE, "$COL_TRIGGER = ?", arrayOf(trigger.lowercase()))
        cacheValid = false
    }

    /** Deletes a shortcut by id. */
    fun deleteById(id: Long) {
        db.writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        cacheValid = false
    }

    /** Clears all shortcuts. */
    fun clear() {
        db.writableDatabase.delete(TABLE, null, null)
        cacheValid = false
    }

    private fun rebuildCache() {
        val map = mutableMapOf<String, ShortcutEntry>()
        db.readableDatabase.query(
            TABLE, arrayOf(COL_ID, COL_TRIGGER, COL_EXPANSION, COL_CREATED_AT),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = cursor.toEntry()
                map[entry.trigger] = entry
            }
        }
        cache = map
        cacheValid = true
    }

    private fun Cursor.toEntry() = ShortcutEntry(
        id = getLong(0),
        trigger = getString(1),
        expansion = getString(2),
        createdAt = getLong(3)
    )

    companion object {
        const val TABLE = "shortcuts"
        const val COL_ID = "id"
        const val COL_TRIGGER = "trigger"
        const val COL_EXPANSION = "expansion"
        const val COL_CREATED_AT = "created_at"

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TRIGGER TEXT NOT NULL UNIQUE,
                $COL_EXPANSION TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0
            )
        """

        @Volatile private var instance: ShortcutsDao? = null

        fun getInstance(context: Context): ShortcutsDao {
            return instance ?: synchronized(this) {
                instance ?: ShortcutsDao(context.applicationContext).also { instance = it }
            }
        }
    }
}
