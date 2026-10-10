package app.lunex

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class LocalIdentity(
    val serverId: String,
    val username: String,
    val displayName: String,
    val createdAt: Long
)

internal data class LocalRoom(
    val id: String,
    val name: String,
    val createdAt: Long
)

/**
 * The device's durable node identity and local account database.
 *
 * This is deliberately independent from the remote API: a local account can be
 * created and authenticated without contacting a central server. The node ID is
 * stable for this installation and is not the same as any room ID or invite token.
 */
internal class LocalNodeStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "lunex-node.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE node (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                server_id TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE account (
                username TEXT PRIMARY KEY COLLATE NOCASE,
                display_name TEXT NOT NULL,
                salt TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE rooms (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE invites (
                token_hash TEXT PRIMARY KEY NOT NULL,
                room_id TEXT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE peers (
                peer_id TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL,
                endpoint TEXT NOT NULL DEFAULT '',
                last_seen INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE node_messages (
                message_id TEXT PRIMARY KEY NOT NULL,
                room_id TEXT NOT NULL,
                sender_node_id TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_node_messages_room_time ON node_messages(room_id, created_at)")
        db.execSQL("CREATE TABLE room_tokens (room_id TEXT NOT NULL, token TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(room_id, token))")
        ensureNode(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS room_tokens (room_id TEXT NOT NULL, token TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(room_id, token))")
    }

    @Synchronized
    fun serverId(): String {
        readableDatabase.rawQuery("SELECT server_id FROM node WHERE singleton=1", null).use {
            if (it.moveToFirst()) return it.getString(0)
        }
        writableDatabase.beginTransaction()
        try {
            ensureNode(writableDatabase)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return serverId()
    }

    private fun ensureNode(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT OR IGNORE INTO node(singleton, server_id, created_at) VALUES(1, ?, ?)",
            arrayOf("lnx-" + UUID.randomUUID().toString().replace("-", ""), System.currentTimeMillis())
        )
    }

    @Synchronized
    fun register(usernameRaw: String, displayNameRaw: String, password: CharArray): LocalIdentity {
        val username = usernameRaw.trim().lowercase()
        val displayName = displayNameRaw.trim()
        require(username.matches(Regex("[a-z0-9_]{3,24}"))) {
            "Логин: 3–24 символа, только a–z, цифры и _."
        }
        require(displayName.isNotBlank() && displayName.length <= 32) { "Укажи имя длиной до 32 символов." }
        require(password.size in 10..128) { "Пароль должен содержать от 10 до 128 символов." }
        readableDatabase.rawQuery("SELECT 1 FROM account WHERE username = ?", arrayOf(username)).use {
            require(!it.moveToFirst()) { "Этот локальный логин уже существует." }
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = passwordHash(password, salt)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("username", username)
            put("display_name", displayName)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("password_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            put("created_at", now)
        }
        writableDatabase.insertOrThrow("account", null, values)
        password.fill('\u0000')
        return LocalIdentity(serverId(), username, displayName, now)
    }

    @Synchronized
    fun login(usernameRaw: String, password: CharArray): LocalIdentity {
        val username = usernameRaw.trim().lowercase()
        readableDatabase.rawQuery(
            "SELECT display_name, salt, password_hash, created_at FROM account WHERE username = ?",
            arrayOf(username)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                password.fill('\u0000')
                throw IllegalArgumentException("На этом сервере такой аккаунт не найден.")
            }
            val salt = Base64.decode(cursor.getString(1), Base64.NO_WRAP)
            val expected = Base64.decode(cursor.getString(2), Base64.NO_WRAP)
            val actual = passwordHash(password, salt)
            password.fill('\u0000')
            require(MessageDigest.isEqual(expected, actual)) { "Неверный логин или пароль." }
            return LocalIdentity(serverId(), username, cursor.getString(0), cursor.getLong(3))
        }
    }

    private fun passwordHash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    @Synchronized
    fun createRoom(nameRaw: String): LocalRoom {
        val name = nameRaw.trim()
        require(name.isNotBlank() && name.length <= 80) { "Название комнаты должно быть от 1 до 80 символов." }
        val room = LocalRoom(UUID.randomUUID().toString(), name, System.currentTimeMillis())
        writableDatabase.insertOrThrow("rooms", null, ContentValues().apply {
            put("id", room.id); put("name", room.name); put("created_at", room.createdAt)
        })
        return room
    }

    @Synchronized
    fun rooms(): List<LocalRoom> {
        val result = mutableListOf<LocalRoom>()
        readableDatabase.rawQuery("SELECT id, name, created_at FROM rooms ORDER BY created_at", null).use { c ->
            while (c.moveToNext()) result += LocalRoom(c.getString(0), c.getString(1), c.getLong(2))
        }
        return result
    }

    /** Returns a one-time random invite secret. Only its SHA-256 hash is persisted. */
    @Synchronized
    fun createInvite(roomId: String): String {
        readableDatabase.rawQuery("SELECT 1 FROM rooms WHERE id = ?", arrayOf(roomId)).use {
            require(it.moveToFirst()) { "Комната не найдена на этом устройстве." }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }
        writableDatabase.insertOrThrow("invites", null, ContentValues().apply {
            put("token_hash", hash); put("room_id", roomId); put("created_at", System.currentTimeMillis())
        })
        writableDatabase.insertWithOnConflict("room_tokens", null, ContentValues().apply {
            put("room_id", roomId); put("token", token); put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return token
    }


    @Synchronized
    fun room(roomId: String): LocalRoom? {
        readableDatabase.rawQuery("SELECT id, name, created_at FROM rooms WHERE id = ?", arrayOf(roomId)).use { c ->
            return if (c.moveToFirst()) LocalRoom(c.getString(0), c.getString(1), c.getLong(2)) else null
        }
    }

    @Synchronized
    fun joinRoom(roomId: String, name: String, token: String) {
        require(roomId.isNotBlank() && token.length in 32..128) { "Приглашение повреждено." }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict("rooms", null, ContentValues().apply {
                put("id", roomId); put("name", name.take(80).ifBlank { "Комната" }); put("created_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            val hash = digest.joinToString("") { "%02x".format(it) }
            writableDatabase.insertWithOnConflict("invites", null, ContentValues().apply {
                put("token_hash", hash); put("room_id", roomId); put("created_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.insertWithOnConflict("room_tokens", null, ContentValues().apply {
                put("room_id", roomId); put("token", token); put("created_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    @Synchronized
    fun inviteTokenForRoom(roomId: String): String? {
        readableDatabase.rawQuery("SELECT token FROM room_tokens WHERE room_id = ? ORDER BY created_at DESC LIMIT 1", arrayOf(roomId)).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** All locally held invite secrets for a room, newest first. Different peers
     * may have joined through different invitations, so mesh operations must try
     * a token that the destination actually recognizes. */
    @Synchronized
    fun inviteTokensForRoom(roomId: String): List<String> {
        val tokens = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT token FROM room_tokens WHERE room_id = ? ORDER BY created_at DESC",
            arrayOf(roomId)
        ).use { cursor ->
            while (cursor.moveToNext()) tokens += cursor.getString(0)
        }
        return tokens.distinct()
    }

    @Synchronized
    fun addMessage(message: LocalNodeMessage): Boolean {
        val values = ContentValues().apply {
            put("message_id", message.id); put("room_id", message.roomId)
            put("sender_node_id", message.senderNodeId); put("sender_name", message.senderName)
            put("body", message.body); put("created_at", message.createdAt); put("delivered", 1)
        }
        return writableDatabase.insertWithOnConflict("node_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized
    fun messages(roomId: String): List<LocalNodeMessage> {
        val result = mutableListOf<LocalNodeMessage>()
        readableDatabase.rawQuery("SELECT message_id, room_id, sender_node_id, sender_name, body, created_at FROM node_messages WHERE room_id = ? ORDER BY created_at LIMIT 1000", arrayOf(roomId)).use { c ->
            while (c.moveToNext()) result += LocalNodeMessage(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))
        }
        return result
    }

    @Synchronized
    fun roomForInvite(token: String): String? {
        if (token.length !in 32..128) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }
        readableDatabase.rawQuery(
            "SELECT room_id FROM invites WHERE token_hash = ? AND revoked = 0", arrayOf(hash)
        ).use { return if (it.moveToFirst()) it.getString(0) else null }
    }
}
