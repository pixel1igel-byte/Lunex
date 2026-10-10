package app.lunex

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable local outbox. Messages remain on-device until the server confirms
 * that they were persisted; reconnects can safely retry the same client ID.
 */
internal data class PendingMessage(
    val clientId: String,
    val kind: String,
    val targetId: String,
    val text: String,
    val createdAt: Long
)

internal class OfflineOutbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lunex-local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE outbox (
                client_id TEXT PRIMARY KEY NOT NULL,
                kind TEXT NOT NULL,
                target_id TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep pending messages when future schema migrations are added.
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
}
