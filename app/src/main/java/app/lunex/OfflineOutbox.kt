package app.lunex

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class PendingMessage(
    val clientId: String,
    val kind: String,
    val targetId: String,
    val text: String,
    val createdAt: Long
)

internal data class StoredMessage(
    val id: String,
    val kind: String,
    val targetId: String,
    val user: String,
    val userId: String,
    val text: String,
    val time: String
)

/**
 * Durable local message database. Outgoing messages are retained until acknowledged;
 * delivered messages are deduplicated by server message ID and remain readable offline.
 */
internal class OfflineOutbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lunex-local.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        createOutbox(db)
        createMessages(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMessages(db)
    }

    private fun createOutbox(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS outbox (
                client_id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                target_id TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    private fun createMessages(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                target_id TEXT NOT NULL,
                username TEXT NOT NULL,
                user_id TEXT NOT NULL,
                body TEXT NOT NULL,
                sent_at TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_room_time ON messages(kind, target_id, sent_at)")
    }

    @Synchronized
    fun enqueue(message: PendingMessage) {
        val values = ContentValues().apply {
            put("client_id", message.clientId)
            put("kind", message.kind)
            put("target_id", message.targetId)
            put("body", message.text)
            put("created_at", message.createdAt)
        }
        writableDatabase.insertWithOnConflict("outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun pending(): List<PendingMessage> {
        val result = mutableListOf<PendingMessage>()
        readableDatabase.query(
            "outbox", arrayOf("client_id", "kind", "target_id", "body", "created_at"),
            null, null, null, null, "created_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PendingMessage(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4)
                )
            }
        }
        return result
    }

    @Synchronized
    fun acknowledge(clientId: String) {
        writableDatabase.delete("outbox", "client_id = ?", arrayOf(clientId))
    }

    @Synchronized
    fun store(message: StoredMessage) {
        if (message.id.isBlank() || message.targetId.isBlank()) return
        val values = ContentValues().apply {
            put("id", message.id)
            put("kind", message.kind)
            put("target_id", message.targetId)
            put("username", message.user)
            put("user_id", message.userId)
            put("body", message.text)
            put("sent_at", message.time)
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun history(kind: String, targetId: String, limit: Int = 500): List<StoredMessage> {
        val result = mutableListOf<StoredMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "kind", "target_id", "username", "user_id", "body", "sent_at"),
            "kind = ? AND target_id = ?",
            arrayOf(kind, targetId),
            null, null, "sent_at DESC", limit.coerceIn(1, 1000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredMessage(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6)
                )
            }
        }
        return result.asReversed()
    }
}
