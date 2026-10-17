package id.zoomtranscriber.huawei

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionRecord(
    val id: Long,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val itemCount: Int,
    val wordCount: Int
)

data class CaptionItem(
    val id: Long,
    val sessionId: Long,
    var speaker: String,
    var initials: String,
    var text: String,
    val timestamp: String,
    var createdAt: Double
)

data class CaptureRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

/**
 * Port 1:1 dari class `HistoryDatabase` pada `app_desktop.py`.
 * Menyimpan seluruh sesi meeting, item caption, dan koordinat kotak caption di SQLite lokal tablet.
 */
class HistoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "caption_history.db", null, 1) {

    private val lock = Any()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val titleFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS caption_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                speaker TEXT NOT NULL,
                initials TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                created_at REAL NOT NULL,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun saveCaptureRect(rect: CaptureRect) {
        synchronized(lock) {
            val json = JSONObject().apply {
                put("x", rect.x)
                put("y", rect.y)
                put("w", rect.w)
                put("h", rect.h)
            }.toString()
            val cv = ContentValues().apply {
                put("key", "capture_rect")
                put("value", json)
            }
            writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun getCaptureRect(defaultRect: CaptureRect): CaptureRect {
        synchronized(lock) {
            readableDatabase.rawQuery(
                "SELECT value FROM settings WHERE key = ?",
                arrayOf("capture_rect")
            ).use { cur ->
                if (cur.moveToFirst()) {
                    return try {
                        val obj = JSONObject(cur.getString(0))
                        CaptureRect(
                            x = obj.optInt("x", defaultRect.x),
                            y = obj.optInt("y", defaultRect.y),
                            w = obj.optInt("w", defaultRect.w),
                            h = obj.optInt("h", defaultRect.h)
                        )
                    } catch (_: Exception) {
                        defaultRect
                    }
                }
            }
            return defaultRect
        }
    }

    fun createSession(customTitle: String? = null): Long {
        synchronized(lock) {
            val nowStr = dateFmt.format(Date())
            val title = if (customTitle.isNullOrBlank()) {
                "Sesi Meeting — ${titleFmt.format(Date())}"
            } else {
                customTitle.trim()
            }
            val cv = ContentValues().apply {
                put("title", title)
                put("created_at", nowStr)
                put("updated_at", nowStr)
            }
            return writableDatabase.insert("sessions", null, cv)
        }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        synchronized(lock) {
            val nowStr = dateFmt.format(Date())
            val cv = ContentValues().apply {
                put("title", newTitle.trim())
                put("updated_at", nowStr)
            }
            writableDatabase.update("sessions", cv, "id = ?", arrayOf(sessionId.toString()))
        }
    }

    fun deleteSession(sessionId: Long) {
        synchronized(lock) {
            writableDatabase.delete("caption_items", "session_id = ?", arrayOf(sessionId.toString()))
            writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId.toString()))
        }
    }

    fun clearSessionItems(sessionId: Long) {
        synchronized(lock) {
            val nowStr = dateFmt.format(Date())
            writableDatabase.delete("caption_items", "session_id = ?", arrayOf(sessionId.toString()))
            val cv = ContentValues().apply { put("updated_at", nowStr) }
            writableDatabase.update("sessions", cv, "id = ?", arrayOf(sessionId.toString()))
        }
    }

    fun listSessions(searchQuery: String = ""): List<SessionRecord> {
        synchronized(lock) {
            val q = searchQuery.trim().lowercase(Locale.getDefault())
            val sql = if (q.isNotEmpty()) {
                """
                SELECT s.id, s.title, s.created_at, s.updated_at,
                       COUNT(c.id) AS item_count,
                       COALESCE(GROUP_CONCAT(c.text, ' '), '') AS all_text
                FROM sessions s
                LEFT JOIN caption_items c ON c.session_id = s.id
                GROUP BY s.id
                HAVING LOWER(s.title) LIKE ? OR LOWER(all_text) LIKE ?
                ORDER BY s.id DESC
                """.trimIndent()
            } else {
                """
                SELECT s.id, s.title, s.created_at, s.updated_at,
                       COUNT(c.id) AS item_count,
                       COALESCE(GROUP_CONCAT(c.text, ' '), '') AS all_text
                FROM sessions s
                LEFT JOIN caption_items c ON c.session_id = s.id
                GROUP BY s.id
                ORDER BY s.id DESC
                """.trimIndent()
            }
            val args = if (q.isNotEmpty()) arrayOf("%$q%", "%$q%") else emptyArray()
            val list = mutableListOf<SessionRecord>()
            readableDatabase.rawQuery(sql, args).use { cur ->
                while (cur.moveToNext()) {
                    val allTxt = cur.getString(5) ?: ""
                    val words = if (allTxt.isBlank()) 0 else allTxt.trim().split(Regex("\\s+")).size
                    list.add(
                        SessionRecord(
                            id = cur.getLong(0),
                            title = cur.getString(1),
                            createdAt = cur.getString(2),
                            updatedAt = cur.getString(3),
                            itemCount = cur.getInt(4),
                            wordCount = words
                        )
                    )
                }
            }
            return list
        }
    }

    fun getSessionItems(sessionId: Long): MutableList<CaptionItem> {
        synchronized(lock) {
            val items = mutableListOf<CaptionItem>()
            readableDatabase.rawQuery(
                """
                SELECT id, speaker, initials, text, timestamp, created_at
                FROM caption_items
                WHERE session_id = ?
                ORDER BY id ASC
                """.trimIndent(),
                arrayOf(sessionId.toString())
            ).use { cur ->
                while (cur.moveToNext()) {
                    items.add(
                        CaptionItem(
                            id = cur.getLong(0),
                            sessionId = sessionId,
                            speaker = cur.getString(1),
                            initials = cur.getString(2),
                            text = cur.getString(3),
                            timestamp = cur.getString(4),
                            createdAt = cur.getDouble(5)
                        )
                    )
                }
            }
            return items
        }
    }

    fun addItem(sessionId: Long, speaker: String, initials: String, text: String, timestamp: String): Long {
        synchronized(lock) {
            val nowTs = System.currentTimeMillis() / 1000.0
            val nowStr = dateFmt.format(Date())
            val cv = ContentValues().apply {
                put("session_id", sessionId)
                put("speaker", speaker)
                put("initials", initials)
                put("text", text)
                put("timestamp", timestamp)
                put("created_at", nowTs)
            }
            val id = writableDatabase.insert("caption_items", null, cv)
            val sesCv = ContentValues().apply { put("updated_at", nowStr) }
            writableDatabase.update("sessions", sesCv, "id = ?", arrayOf(sessionId.toString()))
            return id
        }
    }

    fun updateItem(itemId: Long, sessionId: Long, speaker: String, initials: String, newText: String) {
        synchronized(lock) {
            val nowTs = System.currentTimeMillis() / 1000.0
            val nowStr = dateFmt.format(Date())
            val cv = ContentValues().apply {
                put("speaker", speaker)
                put("initials", initials)
                put("text", newText)
                put("created_at", nowTs)
            }
            writableDatabase.update("caption_items", cv, "id = ?", arrayOf(itemId.toString()))
            val sesCv = ContentValues().apply { put("updated_at", nowStr) }
            writableDatabase.update("sessions", sesCv, "id = ?", arrayOf(sessionId.toString()))
        }
    }

    fun renameSpeakerInSession(sessionId: Long, oldSpeaker: String, newSpeaker: String, newInitials: String) {
        synchronized(lock) {
            val nowStr = dateFmt.format(Date())
            val cv = ContentValues().apply {
                put("speaker", newSpeaker)
                put("initials", newInitials)
            }
            writableDatabase.update(
                "caption_items",
                cv,
                "session_id = ? AND speaker = ?",
                arrayOf(sessionId.toString(), oldSpeaker)
            )
            val sesCv = ContentValues().apply { put("updated_at", nowStr) }
            writableDatabase.update("sessions", sesCv, "id = ?", arrayOf(sessionId.toString()))
        }
    }
}
