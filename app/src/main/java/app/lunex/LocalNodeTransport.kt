package app.lunex

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import io.socket.client.IO
import io.socket.client.Socket as IoSocket
import io.socket.emitter.Emitter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val LUNEX_NODE_PORT = 45873
private const val MAX_REQUEST_FRAME_CHARS = 65_536
private const val MAX_RESPONSE_FRAME_CHARS = 24_000_000
private const val MAX_FILE_BYTES = 25L * 1024L * 1024L
private const val FILE_CHUNK_BYTES = 24 * 1024
private val CALL_EVENTS = setOf("call:invite", "call:accept", "call:signal", "call:reject", "call:end")

internal data class LanPeer(val name: String, val host: InetAddress, val port: Int, val serviceName: String = name)
internal data class LocalRoomPeer(val id: String, val name: String, val host: InetAddress, val port: Int)

internal class LocalNodeTransport(
    context: Context,
    private val store: LocalNodeStore,
    private val onPeersChanged: (List<LanPeer>) -> Unit,
    private val onMessage: (LocalNodeMessage) -> Unit,
    private val onFile: (LocalNodeFile) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var io = Executors.newCachedThreadPool()
    @Volatile private var maintenance: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val peers = ConcurrentHashMap<String, LanPeer>()
    private val seenCallSignals = ConcurrentHashMap<String, Long>()
    @Volatile private var callSignalListener: ((String, JSONObject) -> Unit)? = null
    @Volatile private var running = false
    @Volatile private var relayServerUrl = ""
    @Volatile private var relayAuthToken = ""
    @Volatile private var relaySocket: IoSocket? = null
    @Volatile private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var port = 0
    private val serviceType = "_lunexnode._tcp."

    @Synchronized
    fun start() {
        if (running) return
        if (io.isShutdown) io = Executors.newCachedThreadPool()
        if (maintenance.isShutdown) maintenance = Executors.newSingleThreadScheduledExecutor()
        running = true
        io.execute {
            var listening: ServerSocket? = null
            try {
                val socket = ServerSocket(LUNEX_NODE_PORT)
                listening = socket
                if (!running) {
                    socket.close()
                    return@execute
                }
                server = socket
                port = socket.localPort
                registerService()
                while (running) {
                    val client = socket.accept()
                    io.execute { handle(client) }
                }
            } catch (e: Exception) {
                if (running) main.post { onStatus("LAN-узел: ${e.message ?: "ошибка"}") }
            } finally {
                runCatching { listening?.close() }
            }
        }
        startDiscovery()
        startInternetRelay()
        io.execute { reconnectKnownPeers() }
        maintenance.scheduleWithFixedDelay({ if (running) reconnectKnownPeers() }, 30, 30, TimeUnit.SECONDS)
        main.post { onStatus("LAN-узел запущен. Поиск устройств в этой Wi-Fi сети…") }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { discovery?.let { nsd.stopServiceDiscovery(it) } }
        runCatching { registration?.let { nsd.unregisterService(it) } }
        runCatching { server?.close() }
        discovery = null
        registration = null
        relaySocket?.disconnect()
        relaySocket?.off()
        relaySocket = null
        peers.clear()
        main.post { onPeersChanged(emptyList()) }
        io.shutdownNow()
        maintenance.shutdownNow()
    }

    fun setCallSignalListener(listener: ((String, JSONObject) -> Unit)?) {
        callSignalListener = listener
    }

    fun roomPeers(roomId: String): List<LocalRoomPeer> =
        peers.entries.toList()
            .filter { store.isPeerInRoom(it.key, roomId) }
            .map { LocalRoomPeer(it.key, it.value.name, it.value.host, it.value.port) }

    fun sendCallSignal(roomId: String, targetNodeId: String, event: String, payload: JSONObject, senderName: String) {
        if (roomId.isBlank() || targetNodeId.isBlank() || event !in CALL_EVENTS) return
        io.execute {
            val signalId = java.util.UUID.randomUUID().toString()
            seenCallSignals[signalId] = System.currentTimeMillis()
            val localId = store.serverId()
            val recipients = peers.entries.toList().filter { entry ->
                store.isPeerInRoom(entry.key, roomId)
            }
            recipients.forEach { entry ->
                val source = JSONObject()
                    .put("op", "call-signal")
                    .put("roomId", roomId)
                    .put("nodeId", localId)
                    .put("fromNodeId", localId)
                    .put("fromName", senderName.take(80))
                    .put("toNodeId", targetNodeId)
                    .put("event", event)
                    .put("payload", payload)
                    .put("signalId", signalId)
                    .put("ttl", 16)
                for (token in store.inviteTokensForRoom(roomId)) {
                    val response = runCatching { request(entry.value, JSONObject(source.toString()).put("token", token)) }.getOrNull()
                    if (response?.optBoolean("ok") == true) break
                }
            }
        }
    }

    /**
     * Configure the authenticated Internet relay. The phone still owns its node
     * and local SQLite data; the server only forwards encrypted room envelopes.
     */
    @Synchronized
    fun configureInternetRelay(serverUrl: String, authToken: String) {
        val normalized = serverUrl.trim().trimEnd('/')
        if (relayServerUrl == normalized && relayAuthToken == authToken && relaySocket != null) return
        relayServerUrl = normalized
        relayAuthToken = authToken.trim()
        relaySocket?.disconnect()
        relaySocket?.off()
        relaySocket = null
        if (running) startInternetRelay()
    }

    private fun startInternetRelay() {
        val url = relayServerUrl
        val auth = relayAuthToken
        if (!running || url.isBlank() || auth.isBlank()) return
        runCatching {
            val options = IO.Options.builder()
                .setAuth(mapOf("token" to auth))
                .setReconnection(true)
                .setReconnectionAttempts(Int.MAX_VALUE)
                .build()
            val socket = IO.socket(url, options)
            socket.on(IoSocket.EVENT_CONNECT) {
                refreshInternetRelaySubscriptions(socket)
                main.post { onStatus("Интернет-ретранслятор подключён; узел Lunex доступен через сеть.") }
            }
            socket.on("node:message", Emitter.Listener { args ->
                val packet = args.firstOrNull() as? JSONObject ?: return@Listener
                receiveInternetRelay(packet)
            })
            socket.on(IoSocket.EVENT_DISCONNECT) {
                if (running) main.post { onStatus("Интернет-ретранслятор отключён; локальная сеть продолжает работать.") }
            }
            socket.on("node:error", Emitter.Listener { args ->
                val packet = args.firstOrNull() as? JSONObject ?: return@Listener
                main.post { onStatus(packet.optString("error", "Ошибка интернет-ретранслятора")) }
            })
            relaySocket = socket
            socket.connect()
        }.onFailure { error ->
            main.post { onStatus("Не удалось запустить интернет-ретранслятор: ${error.message ?: "ошибка"}") }
        }
    }

    fun refreshInternetRelaySubscriptions(socket: IoSocket? = relaySocket) {
        val active = socket ?: return
        if (!active.connected()) return
        val hashes = org.json.JSONArray()
        store.allInviteTokens().map { tokenHash(it) }.distinct().take(128).forEach { hashes.put(it) }
        active.emit("node:join", JSONObject().put("tokenHashes", hashes))
    }

    private fun relayViaInternet(message: LocalNodeMessage) {
        val socket = relaySocket ?: return
        if (!socket.connected()) return
        refreshInternetRelaySubscriptions(socket)
        val tokens = store.inviteTokensForRoom(message.roomId)
        for (token in tokens) {
            val payload = JSONObject()
                .put("op", "relay-message")
                .put("roomId", message.roomId)
                .put("message", message.toJson())
            val envelope = encryptEnvelope(payload, token)
            socket.emit("node:message", JSONObject().put("tokenHash", tokenHash(token)).put("envelope", envelope))
        }
    }

    private fun receiveInternetRelay(packet: JSONObject) {
        val envelope = packet.optJSONObject("envelope") ?: return
        val keyId = envelope.optString("keyId")
        val token = store.allInviteTokens().firstOrNull { tokenHash(it) == keyId } ?: return
        val payload = runCatching { decryptEnvelope(envelope, token) }.getOrNull() ?: return
        if (payload.optString("op") != "relay-message") return
        val roomId = payload.optString("roomId")
        if (roomId.isBlank() || store.roomForInvite(token) != roomId || store.room(roomId) == null) return
        val messageJson = payload.optJSONObject("message") ?: return
        val message = runCatching { LocalNodeMessage.fromJson(messageJson) }.getOrNull() ?: return
        if (message.roomId != roomId || message.body.length > 4000 ||
            message.id.length > 128 || message.senderName.length > 80) return
        if (store.addMessage(message)) {
            main.post { onMessage(message) }
            io.execute { relayToPeers(message, null); relayViaInternet(message) }
            main.post { onStatus("Сообщение доставлено через интернет-ретранслятор.") }
        }
    }

    fun peers(): List<LanPeer> = peers.values.toList()

    /** Current listening port; remote reachability still depends on the network/NAT. */
    fun listeningPort(): Int = port

    /** Reachable addresses on this device's active network interfaces. */
    fun localEndpoints(): List<String> {
        val result = mutableListOf<String>()
        val interfaces = runCatching { java.net.NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (runCatching { !networkInterface.isUp || networkInterface.isLoopback }.getOrDefault(true)) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val host = if (address is java.net.Inet6Address) {
                    "[" + address.hostAddress.substringBefore('%') + "]"
                } else address.hostAddress
                result += host + ":" + LUNEX_NODE_PORT
            }
        }
        return result.distinct()
    }

    fun join(peer: LanPeer, token: String, callback: (Boolean, String) -> Unit) {
        io.execute {
            try {
                val hello = request(peer, JSONObject().put("op", "hello"))
                if (!hello.optBoolean("ok") || hello.optString("nodeId").isBlank()) {
                    throw IllegalStateException("По адресу не найден узел Lunex.")
                }
                require(hello.optString("nodeId") != store.serverId()) { "Нельзя подключиться к самому себе." }
                val response = request(peer, JSONObject().put("op", "join").put("token", token).put("nodeId", store.serverId()))
                if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Приглашение не принято"))
                val roomId = response.getString("roomId")
                val roomName = response.optString("roomName", "Lunex room")
                val namedPeer = peer.copy(name = hello.optString("name").ifBlank { peer.name })
                peers[hello.getString("nodeId")] = namedPeer
                store.rememberPeer(hello.getString("nodeId"), namedPeer.serviceName, endpointOf(namedPeer))
                main.post { onPeersChanged(peers()) }
                store.joinRoom(roomId, roomName, token)
                refreshInternetRelaySubscriptions()
                store.rememberPeerRoom(hello.getString("nodeId"), roomId)
                main.post { onPeersChanged(peers()) }
                sync(peer, roomId)
                discoverRoomMesh(namedPeer, roomId)
                syncAll()
                main.post { callback(true, "Комната «$roomName» подключена; узлы сети обнаружены и синхронизированы.") }
            } catch (e: Exception) {
                main.post { callback(false, e.message ?: "Не удалось подключиться к узлу") }
            }
        }
    }

    fun send(roomId: String, senderName: String, body: String) {
        val clean = body.trim().take(4000)
        if (clean.isBlank()) return
        val message = LocalNodeMessage(
            java.util.UUID.randomUUID().toString(), roomId, store.serverId(), senderName,
            clean, System.currentTimeMillis()
        )
        store.addMessage(message)
        onMessage(message)
        io.execute { relayToPeers(message, null); relayViaInternet(message) }
    }

    fun shareFile(uri: Uri, roomId: String, senderName: String, callback: (Boolean, String) -> Unit) {
        io.execute {
            var created: File? = null
            var persisted = false
            try {
                require(store.inviteTokensForRoom(roomId).isNotEmpty()) { "Сначала создай приглашение для комнаты." }
                var fileName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) fileName = cursor.getString(index)?.takeIf { it.isNotBlank() } ?: fileName
                    }
                }
                fileName = fileName.substringAfterLast('/').substringAfterLast('\\').take(255).ifBlank { "file" }
                val mimeType = (app.contentResolver.getType(uri) ?: "application/octet-stream").take(128)
                val fileId = java.util.UUID.randomUUID().toString()
                val directory = roomFilesDirectory(roomId)
                if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Не удалось создать каталог файлов.")
                val destination = File(directory, fileId)
                created = destination
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val input = app.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Не удалось открыть выбранный файл.")
                input.use { source ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= MAX_FILE_BYTES) { "Файл слишком большой. Максимум 25 МБ." }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val metadata = LocalNodeFile(
                    fileId, roomId, store.serverId(), senderName.take(80), fileName, mimeType,
                    size, sha, ((size + FILE_CHUNK_BYTES - 1) / FILE_CHUNK_BYTES).toInt(),
                    System.currentTimeMillis(), destination.absolutePath, true
                )
                require(store.insertFile(metadata)) { "ID файла уже существует." }
                persisted = true
                onFile(metadata)
                main.post { onStatus("Файл «$fileName» сохранён локально; синхронизирую с узлами комнаты…") }
                runCatching { syncAll() }
                main.post { callback(true, "Файл «$fileName» добавлен. Передача узлам идёт в фоне, повторная синхронизация будет при их подключении.") }
            } catch (e: Exception) {
                if (!persisted) created?.let { runCatching { it.delete() } }
                main.post { callback(false, e.message ?: "Не удалось добавить файл.") }
            }
        }
    }

    fun syncAll() {
        io.execute {
            peers.entries.toList().forEach { entry ->
                val peerId = entry.key
                val peer = entry.value
                store.rooms().forEach { room ->
                    if (store.isPeerInRoom(peerId, room.id) && store.inviteTokensForRoom(room.id).isNotEmpty()) {
                        runCatching { syncInvites(peer, room.id) }
                        runCatching { pushHistoryToPeer(peer, room.id) }
                        runCatching { sync(peer, room.id) }
                        runCatching { pushFilesToPeer(peer, room.id) }
                    }
                }
            }
        }
    }

    private fun roomFilesDirectory(roomId: String): File {
        val canonicalId = java.util.UUID.fromString(roomId).toString()
        require(canonicalId.equals(roomId, ignoreCase = true)) { "Некорректный ID комнаты." }
        return File(app.filesDir, "lunex-files/$canonicalId")
    }

    private fun pushFilesToPeer(peer: LanPeer, roomId: String) {
        store.files(roomId).forEach { file -> runCatching { sendFileToPeer(peer, file) } }
    }

    private fun sendFileToPeer(peer: LanPeer, file: LocalNodeFile): Boolean {
        val localFile = File(file.localPath)
        if (!file.complete || !localFile.isFile || localFile.length() != file.sizeBytes) return false
        var selectedToken: String? = null
        for (token in store.inviteTokensForRoom(file.roomId)) {
            val manifest = JSONObject()
                .put("op", "file-manifest")
                .put("token", token)
                .put("nodeId", store.serverId())
                .put("fileId", file.id)
                .put("roomId", file.roomId)
                .put("senderNodeId", file.senderNodeId)
                .put("senderName", file.senderName)
                .put("fileName", file.fileName)
                .put("mimeType", file.mimeType)
                .put("sizeBytes", file.sizeBytes)
                .put("sha256", file.sha256)
                .put("chunkCount", file.chunkCount)
                .put("createdAt", file.createdAt)
            val response = runCatching { request(peer, manifest) }.getOrNull() ?: continue
            if (response.optBoolean("ok")) {
                if (response.optBoolean("already")) return true
                selectedToken = token
                break
            }
        }
        val token = selectedToken ?: return false
        FileInputStream(localFile).use { input ->
            for (index in 0 until file.chunkCount) {
                val expected = if (index < file.chunkCount - 1) FILE_CHUNK_BYTES
                    else (file.sizeBytes - index.toLong() * FILE_CHUNK_BYTES).toInt()
                val chunk = ByteArray(expected)
                var offset = 0
                while (offset < expected) {
                    val read = input.read(chunk, offset, expected - offset)
                    if (read < 0) throw IllegalStateException("Локальный файл изменился во время передачи.")
                    offset += read
                }
                val response = request(peer, JSONObject()
                    .put("op", "file-chunk")
                    .put("token", token)
                    .put("nodeId", store.serverId())
                    .put("roomId", file.roomId)
                    .put("fileId", file.id)
                    .put("index", index)
                    .put("data", Base64.getEncoder().encodeToString(chunk)))
                if (!response.optBoolean("ok")) throw IllegalStateException("Узел отклонил часть файла.")
            }
        }
        val completed = request(peer, JSONObject()
            .put("op", "file-complete")
            .put("token", token)
            .put("nodeId", store.serverId())
            .put("roomId", file.roomId)
            .put("fileId", file.id))
        return completed.optBoolean("ok")
    }

    private fun relayFileToPeers(file: LocalNodeFile, sourceAddress: InetAddress?) {
        peers.entries.toList().filter { entry ->
            store.isPeerInRoom(entry.key, file.roomId) &&
                (sourceAddress == null || !entry.value.host.address.contentEquals(sourceAddress.address))
        }.forEach { entry -> runCatching { sendFileToPeer(entry.value, file) } }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sync(peer: LanPeer, roomId: String) {
        // A room can have several still-valid invitation tokens. Try each one
        // because a destination may have joined using an older invitation.
        var response: JSONObject? = null
        for (token in store.inviteTokensForRoom(roomId)) {
            val candidate = runCatching {
                request(peer, JSONObject().put("op", "sync").put("roomId", roomId).put("token", token).put("nodeId", store.serverId()))
            }.getOrNull()
            if (candidate?.optBoolean("ok") == true) {
                response = candidate
                break
            }
        }
        val array = response?.optJSONArray("messages") ?: return
        val newlyInserted = mutableListOf<LocalNodeMessage>()
        for (i in 0 until array.length()) {
            val message = LocalNodeMessage.fromJson(array.getJSONObject(i))
            if (message.roomId != roomId || message.body.length > 4000 ||
                message.id.length > 128 || message.senderName.length > 80) continue
            // Sync is also a propagation path: a returning/offline node can bring
            // older messages back into the mesh. Relay only newly inserted IDs.
            if (store.addMessage(message)) {
                main.post { onMessage(message) }
                newlyInserted += message
            }
        }
        // Relay sequentially in this worker instead of scheduling one thread-pool
        // task per historical message when a large history is synchronized.
        newlyInserted.forEach { relayToPeers(it, peer.host); relayViaInternet(it) }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10000
            val line = readBoundedLine(BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)), MAX_REQUEST_FRAME_CHARS) ?: return
            val wireRequest = JSONObject(line)
            if (!wireRequest.optBoolean("encrypted") &&
                (wireRequest.optString("token").isNotBlank() || wireRequest.optString("authToken").isNotBlank())) {
                PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                    .println(JSONObject().put("ok", false).put("error", "Room-authenticated frames must be encrypted").toString())
                return
            }
            var responseToken: String? = null
            val request = if (wireRequest.optBoolean("encrypted")) {
                val keyId = wireRequest.optString("keyId")
                val token = store.allInviteTokens().firstOrNull { tokenHash(it) == keyId }
                if (token == null) {
                    PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                        .println(JSONObject().put("ok", false).put("error", "Encrypted frame rejected").toString())
                    return
                }
                try {
                    responseToken = token
                    decryptEnvelope(wireRequest, token)
                } catch (_: Exception) {
                    PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                        .println(JSONObject().put("ok", false).put("error", "Encrypted frame rejected").toString())
                    return
                }
            } else wireRequest
            val response = try {
                when (request.optString("op")) {
                    "hello" -> JSONObject().put("ok", true).put("nodeId", store.serverId()).put("name", android.os.Build.MODEL)
                    "join" -> {
                        val token = request.optString("token")
                        val roomId = store.roomForInvite(token)
                        val room = roomId?.let { store.room(it) }
                        if (room == null) JSONObject().put("ok", false).put("error", "Приглашение недействительно")
                        else {
                            rememberPeerRoomIfKnown(request.optString("nodeId"), room.id, socket.inetAddress)
                            JSONObject().put("ok", true).put("roomId", room.id).put("roomName", room.name)
                        }
                    }
                    "call-signal" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        val fromNodeId = request.optString("fromNodeId")
                        val fromName = request.optString("fromName", fromNodeId).take(80)
                        val toNodeId = request.optString("toNodeId")
                        val event = request.optString("event")
                        val signalId = request.optString("signalId")
                        val ttl = request.optInt("ttl", -1)
                        val payload = request.optJSONObject("payload") ?: JSONObject()
                        val valid = store.roomForInvite(token) == roomId && store.room(roomId) != null &&
                            fromNodeId.isNotBlank() && toNodeId.isNotBlank() && signalId.isNotBlank() &&
                            event in CALL_EVENTS && ttl in 0..16
                        if (!valid) {
                            JSONObject().put("ok", false).put("error", "Недопустимый сигнал звонка.")
                        } else {
                            rememberPeerRoomIfKnown(fromNodeId, roomId, socket.inetAddress)
                            val now = System.currentTimeMillis()
                            seenCallSignals.entries.removeIf { now - it.value > 120_000L }
                            val fresh = seenCallSignals.putIfAbsent(signalId, now) == null
                            if (seenCallSignals.size > 4096) seenCallSignals.entries.removeIf { now - it.value > 30_000L }
                            if (fresh) {
                                if (toNodeId == store.serverId()) {
                                    val localEvent = when (event) {
                                        "call:invite" -> "call:incoming"
                                        "call:accept" -> "call:accepted"
                                        "call:reject" -> "call:rejected"
                                        "call:end" -> "call:ended"
                                        else -> event
                                    }
                                    val delivered = JSONObject(payload.toString())
                                        .put("fromNodeId", fromNodeId)
                                        .put("fromName", fromName)
                                        .put("toNodeId", toNodeId)
                                    if (localEvent == "call:incoming") delivered.put("caller", JSONObject().put("displayName", fromName))
                                    main.post { callSignalListener?.invoke(localEvent, delivered) }
                                }
                                if (ttl > 0) {
                                    val sourceAddress = socket.inetAddress
                                    io.execute { relayCallSignal(roomId, fromNodeId, toNodeId, fromName, event, payload, signalId, ttl - 1, sourceAddress) }
                                }
                            }
                            JSONObject().put("ok", true)
                        }
                    }
                    "file-manifest" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        val rawId = request.optString("fileId")
                        val fileId = runCatching { java.util.UUID.fromString(rawId).toString() }.getOrNull()
                        val size = request.optLong("sizeBytes", -1L)
                        val sha = request.optString("sha256").lowercase()
                        val chunkCount = request.optInt("chunkCount", -1)
                        val expectedChunks = if (size in 0..MAX_FILE_BYTES) ((size + FILE_CHUNK_BYTES - 1) / FILE_CHUNK_BYTES).toInt() else -1
                        val fileName = request.optString("fileName").substringAfterLast('/').substringAfterLast('\\').take(255).ifBlank { "file" }
                        val mimeType = request.optString("mimeType", "application/octet-stream").take(128)
                        val senderName = request.optString("senderName", "Узел").take(80)
                        val senderId = request.optString("senderNodeId").take(128)
                        val valid = store.roomForInvite(token) == roomId && store.room(roomId) != null &&
                            fileId != null && fileId.equals(rawId, ignoreCase = true) &&
                            size in 0..MAX_FILE_BYTES && sha.matches(Regex("[0-9a-f]{64}")) &&
                            chunkCount == expectedChunks && senderId.isNotBlank()
                        if (!valid) {
                            JSONObject().put("ok", false).put("error", "Некорректный манифест файла.")
                        } else {
                            val validFileId = fileId ?: throw IllegalStateException("Некорректный ID файла.")
                            rememberPeerRoomIfKnown(request.optString("nodeId"), roomId, socket.inetAddress)
                            val existing = store.file(validFileId)
                            if (existing != null && (existing.roomId != roomId || existing.sizeBytes != size ||
                                existing.sha256 != sha || existing.chunkCount != chunkCount)) {
                                JSONObject().put("ok", false).put("error", "ID файла уже занят другими данными.")
                            } else if (existing?.complete == true) {
                                JSONObject().put("ok", true).put("already", true)
                            } else {
                                val directory = roomFilesDirectory(roomId)
                                if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Не удалось создать каталог файла.")
                                val destination = File(directory, validFileId)
                                store.insertFile(LocalNodeFile(
                                    validFileId, roomId, senderId, senderName, fileName, mimeType,
                                    size, sha, chunkCount, request.optLong("createdAt", System.currentTimeMillis()),
                                    destination.absolutePath, false
                                ))
                                val saved = store.file(validFileId) ?: throw IllegalStateException("Не удалось сохранить манифест файла.")
                                if (saved.roomId != roomId || saved.sha256 != sha || saved.sizeBytes != size) {
                                    JSONObject().put("ok", false).put("error", "Конфликт манифеста файла.")
                                } else JSONObject().put("ok", true)
                            }
                        }
                    }
                    "file-chunk" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        val fileId = request.optString("fileId")
                        val file = store.file(fileId)
                        val index = request.optInt("index", -1)
                        val bytes = Base64.getDecoder().decode(request.optString("data"))
                        val expected = if (file != null && index >= 0 && index < file.chunkCount) {
                            if (index < file.chunkCount - 1) FILE_CHUNK_BYTES
                            else (file.sizeBytes - index.toLong() * FILE_CHUNK_BYTES).toInt()
                        } else -1
                        if (store.roomForInvite(token) != roomId || file == null || file.roomId != roomId ||
                            file.complete || bytes.size != expected || bytes.size > FILE_CHUNK_BYTES) {
                            JSONObject().put("ok", false).put("error", "Недопустимая часть файла.")
                        } else {
                            rememberPeerRoomIfKnown(request.optString("nodeId"), roomId, socket.inetAddress)
                            val partial = File(file.localPath + ".part")
                            if (!partial.parentFile.exists() && !partial.parentFile.mkdirs()) throw IllegalStateException("Не удалось создать файл.")
                            RandomAccessFile(partial, "rw").use { output ->
                                output.seek(index.toLong() * FILE_CHUNK_BYTES)
                                output.write(bytes)
                            }
                            JSONObject().put("ok", true)
                        }
                    }
                    "file-complete" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        val fileId = request.optString("fileId")
                        val file = store.file(fileId)
                        if (store.roomForInvite(token) != roomId || file == null || file.roomId != roomId) {
                            JSONObject().put("ok", false).put("error", "Нет доступа к файлу.")
                        } else if (file.complete) {
                            JSONObject().put("ok", true).put("already", true)
                        } else {
                            rememberPeerRoomIfKnown(request.optString("nodeId"), roomId, socket.inetAddress)
                            val destination = File(file.localPath)
                            val partial = File(file.localPath + ".part")
                            if (file.sizeBytes == 0L && !partial.exists()) {
                                if (!partial.parentFile.exists() && !partial.parentFile.mkdirs()) throw IllegalStateException("Не удалось создать файл.")
                                partial.createNewFile()
                            }
                            if (!partial.isFile || partial.length() != file.sizeBytes || sha256File(partial) != file.sha256) {
                                JSONObject().put("ok", false).put("error", "Контрольная сумма файла не совпала.")
                            } else {
                                if (destination.exists()) destination.delete()
                                if (!partial.renameTo(destination)) {
                                    JSONObject().put("ok", false).put("error", "Не удалось завершить запись файла.")
                                } else {
                                    store.markFileComplete(fileId)
                                    val completed = store.file(fileId) ?: file.copy(complete = true)
                                    main.post { onFile(completed) }
                                    val sourceAddress = socket.inetAddress
                                    io.execute { relayFileToPeers(completed, sourceAddress) }
                                    JSONObject().put("ok", true)
                                }
                            }
                        }
                    }
                    "prove-room" -> {
                        val roomId = request.optString("roomId")
                        val challenge = request.optString("challenge")
                        val proof = hexToBytes(request.optString("proof"))
                        val valid = roomId.isNotBlank() && store.room(roomId) != null &&
                            challenge.length in 16..128 && proof != null &&
                            store.inviteTokensForRoom(roomId).any { token ->
                                MessageDigest.isEqual(roomProof(token, roomId, challenge), proof)
                            }
                        if (valid) rememberPeerRoomIfKnown(request.optString("nodeId"), roomId, socket.inetAddress)
                        JSONObject().put("ok", valid)
                    }
                    "peers" -> {
                        val roomId = request.optString("roomId")
                        val token = request.optString("token")
                        if (store.roomForInvite(token) != roomId) {
                            JSONObject().put("ok", false).put("error", "Нет доступа к комнате")
                        } else {
                            rememberPeerRoomIfKnown(request.optString("nodeId"), roomId, socket.inetAddress)
                            val array = org.json.JSONArray()
                            peers.forEach { (peerId, peer) ->
                                if (peerId != store.serverId() && store.isPeerInRoom(peerId, roomId)) {
                                    array.put(JSONObject()
                                        .put("nodeId", peerId)
                                        .put("name", peer.name)
                                        .put("endpoint", endpointOf(peer)))
                                }
                            }
                            JSONObject().put("ok", true).put("peers", array)
                        }
                    }
                    "invite" -> {
                        val roomId = request.optString("roomId")
                        val authToken = request.optString("authToken")
                        val inviteToken = request.optString("inviteToken")
                        if (store.roomForInvite(authToken) != roomId || store.room(roomId) == null) {
                            JSONObject().put("ok", false).put("error", "Нет доступа к комнате")
                        } else {
                            rememberPeerRoomIfKnown(request.optString("nodeId"), roomId, socket.inetAddress)
                            val inserted = store.rememberInviteToken(roomId, inviteToken)
                            if (inserted) {
                                val sourceAddress = socket.inetAddress
                                io.execute { relayInviteToPeers(roomId, inviteToken, sourceAddress) }
                            }
                            JSONObject().put("ok", true)
                        }
                    }
                    "message" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        val message = LocalNodeMessage.fromJson(request.getJSONObject("message"))
                        if (store.roomForInvite(token) != roomId || message.roomId != roomId ||
                            message.body.length > 4000 || message.id.length > 128 || message.senderName.length > 80) {
                            JSONObject().put("ok", false).put("error", "Нет доступа к комнате или сообщение некорректно")
                        } else {
                            rememberPeerRoomIfKnown(request.optString("fromNodeId"), roomId, socket.inetAddress)
                            // Deduplication is the loop guard: a node relays a message only
                            // the first time it stores that message ID.
                            val inserted = store.addMessage(message)
                            if (inserted) {
                                main.post { onMessage(message) }
                                val sourceAddress = client.inetAddress
                                io.execute { relayToPeers(message, sourceAddress); relayViaInternet(message) }
                            }
                            JSONObject().put("ok", true).put("ack", message.id)
                        }
                    }
                    "sync" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        if (store.roomForInvite(token) != roomId) JSONObject().put("ok", false).put("error", "Нет доступа к комнате")
                        else {
                            rememberPeerRoomIfKnown(request.optString("nodeId"), roomId, socket.inetAddress)
                            val array = org.json.JSONArray()
                            store.messages(roomId).forEach { array.put(it.toJson()) }
                            JSONObject().put("ok", true).put("messages", array)
                        }
                    }
                    else -> JSONObject().put("ok", false).put("error", "Неизвестная операция")
                }
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "Ошибка запроса")
            }
            val wireResponse = responseToken?.let { encryptEnvelope(response, it) } ?: response
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true).println(wireResponse.toString())
        }
    }

    /**
     * Forward newly accepted messages to the other discovered nodes.
     *
     * Every receiver checks the room invite and deduplicates by message ID before
     * forwarding, so messages can cross multiple phone nodes without a central
     * server and without an endless relay loop.
     */
    /** Propagate newly created room invite secrets using an already shared token. */
    private fun relayInviteToPeers(roomId: String, inviteToken: String, sourceAddress: InetAddress?) {
        peers.entries.toList().filter { entry ->
            store.isPeerInRoom(entry.key, roomId) &&
                (sourceAddress == null || !entry.value.host.address.contentEquals(sourceAddress.address))
        }.forEach { entry ->
            val peer = entry.value
            for (authToken in store.inviteTokensForRoom(roomId)) {
                val accepted = runCatching {
                    request(peer, JSONObject()
                        .put("op", "invite")
                        .put("roomId", roomId)
                        .put("authToken", authToken)
                        .put("inviteToken", inviteToken)
                        .put("nodeId", store.serverId()))
                }.getOrNull()?.optBoolean("ok") == true
                if (accepted) break
            }
        }
    }

    /** Bring all locally known invite tokens to a peer before message sync. */
    private fun syncInvites(peer: LanPeer, roomId: String) {
        val tokens = store.inviteTokensForRoom(roomId)
        for (inviteToken in tokens) {
            for (authToken in tokens) {
                val accepted = runCatching {
                    request(peer, JSONObject()
                        .put("op", "invite")
                        .put("roomId", roomId)
                        .put("authToken", authToken)
                        .put("inviteToken", inviteToken)
                        .put("nodeId", store.serverId()))
                }.getOrNull()?.optBoolean("ok") == true
                if (accepted) break
            }
        }
    }

    private fun relayCallSignal(
        roomId: String,
        fromNodeId: String,
        toNodeId: String,
        fromName: String,
        event: String,
        payload: JSONObject,
        signalId: String,
        ttl: Int,
        sourceAddress: InetAddress
    ) {
        peers.entries.toList().filter { entry ->
            store.isPeerInRoom(entry.key, roomId) &&
                !entry.value.host.address.contentEquals(sourceAddress.address)
        }.forEach { entry ->
            for (token in store.inviteTokensForRoom(roomId)) {
                val response = runCatching {
                    request(entry.value, JSONObject()
                        .put("op", "call-signal")
                        .put("token", token)
                        .put("roomId", roomId)
                        .put("nodeId", store.serverId())
                        .put("fromNodeId", fromNodeId)
                        .put("fromName", fromName)
                        .put("toNodeId", toNodeId)
                        .put("event", event)
                        .put("payload", payload)
                        .put("signalId", signalId)
                        .put("ttl", ttl))
                }.getOrNull()
                if (response?.optBoolean("ok") == true) break
            }
        }
    }

    private fun sendMessageToPeer(peer: LanPeer, message: LocalNodeMessage): Boolean {
        for (token in store.inviteTokensForRoom(message.roomId)) {
            val accepted = runCatching {
                request(peer, JSONObject()
                    .put("op", "message")
                    .put("token", token)
                    .put("roomId", message.roomId)
                    .put("fromNodeId", store.serverId())
                    .put("message", message.toJson()))
            }.getOrNull()?.optBoolean("ok") == true
            if (accepted) return true
        }
        return false
    }

    /** Push this node's saved history before pulling the peer's history. */
    private fun pushHistoryToPeer(peer: LanPeer, roomId: String) {
        store.messages(roomId).forEach { message ->
            if (message.roomId == roomId && message.body.length <= 4000) {
                sendMessageToPeer(peer, message)
            }
        }
    }

    private fun relayToPeers(message: LocalNodeMessage, sourceAddress: InetAddress?) {
        peers.entries.toList().filter { entry ->
            store.isPeerInRoom(entry.key, message.roomId) &&
                (sourceAddress == null || !entry.value.host.address.contentEquals(sourceAddress.address))
        }.forEach { entry -> sendMessageToPeer(entry.value, message) }
    }

    /**
     * Walk peer lists learned from room-authorized nodes. This gives the mesh
     * multi-hop discovery without a central directory; every candidate's endpoint
     * is verified against its advertised node ID before being persisted.
     */
    private fun discoverRoomMesh(seedPeer: LanPeer, roomId: String) {
        val roomTokens = store.inviteTokensForRoom(roomId)
        if (roomTokens.isEmpty()) return
        val queue = java.util.ArrayDeque<LanPeer>()
        val visited = mutableSetOf<String>()
        val queued = mutableSetOf<String>()
        queue.add(seedPeer)
        while (queue.isNotEmpty() && visited.size < 128) {
            val current = queue.removeFirst()
            val hello = runCatching { request(current, JSONObject().put("op", "hello")) }.getOrNull() ?: continue
            val currentId = hello.optString("nodeId")
            if (!hello.optBoolean("ok") || currentId.isBlank() || currentId == store.serverId() || !visited.add(currentId)) continue
            queued.add(currentId)

            var response: JSONObject? = null
            for (token in roomTokens) {
                val candidate = runCatching {
                    request(current, JSONObject().put("op", "peers").put("roomId", roomId).put("token", token).put("nodeId", store.serverId()))
                }.getOrNull()
                if (candidate?.optBoolean("ok") == true) {
                    store.rememberPeerRoom(currentId, roomId)
                    main.post { onPeersChanged(peers()) }
                    response = candidate
                    break
                }
            }
            val list = response?.optJSONArray("peers") ?: continue
            for (index in 0 until list.length()) {
                val advertised = list.optJSONObject(index) ?: continue
                val expectedId = advertised.optString("nodeId")
                val endpoint = advertised.optString("endpoint")
                if (expectedId.isBlank() || endpoint.isBlank() || expectedId == store.serverId() || expectedId in visited || !queued.add(expectedId)) continue
                val candidatePeer = runCatching {
                    parseEndpoint(endpoint, advertised.optString("name").ifBlank { endpoint })
                }.getOrNull() ?: continue
                val candidateHello = runCatching {
                    request(candidatePeer, JSONObject().put("op", "hello"))
                }.getOrNull() ?: continue
                if (!candidateHello.optBoolean("ok") || candidateHello.optString("nodeId") != expectedId) continue
                val isRoomMember = roomTokens.any { token ->
                    runCatching { proveRoomMembership(candidatePeer, roomId, token) }.getOrDefault(false)
                }
                if (!isRoomMember) continue
                val verified = candidatePeer.copy(name = candidateHello.optString("name").ifBlank { candidatePeer.name })
                peers[expectedId] = verified
                store.rememberPeer(expectedId, verified.serviceName, endpointOf(verified))
                store.rememberPeerRoom(expectedId, roomId)
                main.post { onPeersChanged(peers()) }
                queue.addLast(verified)
            }
        }
    }

    private fun reconnectKnownPeers() {
        store.knownPeers().forEach { saved ->
            try {
                val peer = parseEndpoint(saved.endpoint, saved.label)
                val hello = request(peer, JSONObject().put("op", "hello"))
                val actualId = hello.optString("nodeId")
                if (hello.optBoolean("ok") && actualId == saved.peerId && actualId != store.serverId()) {
                    val verified = peer.copy(name = hello.optString("name").ifBlank { saved.label })
                    val newlyConnected = peers.put(actualId, verified) == null
                    store.rememberPeer(actualId, verified.serviceName, endpointOf(verified))
                    if (newlyConnected) {
                        main.post { onPeersChanged(peers()) }
                        store.rooms().forEach { room ->
                            if (store.inviteTokensForRoom(room.id).isNotEmpty()) discoverRoomMesh(verified, room.id)
                        }
                    }
                } else if (peers.remove(saved.peerId) != null) {
                    main.post { onPeersChanged(peers()) }
                }
            } catch (_: Exception) {
                if (peers.remove(saved.peerId) != null) main.post { onPeersChanged(peers()) }
            }
        }
        // Poll reachable room peers as well as reconnecting stale ones. This is
        // required for one-way Internet links where the remote phone cannot dial back.
        syncAll()
    }

    private fun parseEndpoint(raw: String, label: String): LanPeer {
        val value = raw.trim()
        val host: String
        val endpointPort: Int
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            require(close > 1 && close + 2 < value.length && value[close + 1] == ':') {
                "Некорректный сохранённый IPv6 endpoint."
            }
            host = value.substring(1, close)
            endpointPort = value.substring(close + 2).toInt()
        } else {
            val split = value.lastIndexOf(':')
            require(split > 0 && value.indexOf(':') == split) { "Некорректный сохранённый endpoint." }
            host = value.substring(0, split)
            endpointPort = value.substring(split + 1).toInt()
        }
        require(endpointPort in 1..65535) { "Некорректный порт сохранённого узла." }
        return LanPeer(label, InetAddress.getByName(host), endpointPort)
    }

    /**
     * Only trust a node ID for membership tracking when it matches a peer we
     * already verified at the same source address. Direct Internet peers that
     * cannot be dialed back still sync through their own periodic pulls.
     */
    private fun proveRoomMembership(peer: LanPeer, roomId: String, token: String): Boolean {
        val challenge = java.util.UUID.randomUUID().toString()
        val proof = roomProof(token, roomId, challenge).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val response = request(peer, JSONObject()
            .put("op", "prove-room")
            .put("roomId", roomId)
            .put("challenge", challenge)
            .put("proof", proof)
            .put("nodeId", store.serverId()))
        return response.optBoolean("ok")
    }

    private fun roomProof(token: String, roomId: String, challenge: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal((roomId + ":" + challenge).toByteArray(Charsets.UTF_8))
    }

    private fun hexToBytes(value: String): ByteArray? {
        if (value.length != 64 || !value.matches(Regex("[0-9a-fA-F]{64}"))) return null
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun rememberPeerRoomIfKnown(peerId: String, roomId: String, sourceAddress: InetAddress) {
        if (peerId.isBlank() || peerId == store.serverId()) return
        val known = peers[peerId] ?: return
        if (known.host.address.contentEquals(sourceAddress.address) && !store.isPeerInRoom(peerId, roomId)) {
            store.rememberPeerRoom(peerId, roomId)
            main.post { onPeersChanged(peers()) }
        }
    }

    private fun endpointOf(peer: LanPeer): String {
        val host = if (peer.host is java.net.Inet6Address) {
            "[" + peer.host.hostAddress.substringBefore('%') + "]"
        } else peer.host.hostAddress
        return host + ":" + peer.port
    }

    private fun readBoundedLine(reader: BufferedReader, maxChars: Int): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value == -1) return if (line.length == 0) null else line.toString().removeSuffix("\r")
            if (value == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= maxChars) throw IllegalArgumentException("Слишком большой сетевой кадр.")
            line.append(value.toChar())
        }
    }

    private fun request(peer: LanPeer, payload: JSONObject): JSONObject {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(peer.host, peer.port), 4000)
            socket.soTimeout = 6000
            val tokenValue = payload.optString("token").ifBlank { payload.optString("authToken") }
            val secret = tokenValue.takeIf { it.isNotBlank() }
            val wirePayload = secret?.let { encryptEnvelope(payload, it) } ?: payload
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true).println(wirePayload.toString())
            val line = readBoundedLine(BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)), MAX_RESPONSE_FRAME_CHARS)
                ?: throw IllegalStateException("Узел не ответил")
            val wireResponse = JSONObject(line)
            return if (wireResponse.optBoolean("encrypted")) {
                val key = secret ?: throw IllegalStateException("Неожиданный зашифрованный ответ.")
                decryptEnvelope(wireResponse, key)
            } else wireResponse
        }
    }

    /** Encrypts every room-authenticated request/response with AES-GCM using the shared room invite secret. */
    private fun encryptEnvelope(payload: JSONObject, token: String): JSONObject {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("encrypted", true)
            .put("keyId", tokenHash(token))
            .put("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
            .put("ciphertext", Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted))
    }

    private fun decryptEnvelope(envelope: JSONObject, token: String): JSONObject {
        require(envelope.optString("keyId") == tokenHash(token)) { "Неверный ключ кадра." }
        val nonce = Base64.getUrlDecoder().decode(envelope.getString("nonce"))
        require(nonce.size == 12) { "Некорректный nonce." }
        val encrypted = Base64.getUrlDecoder().decode(envelope.getString("ciphertext"))
        val key = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
    }

    private fun tokenHash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun registerService() {
        val info = NsdServiceInfo().apply {
            serviceName = "Lunex-${store.serverId().takeLast(8)}"
            serviceType = this@LocalNodeTransport.serviceType
            port = this@LocalNodeTransport.port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { onStatus("Не удалось объявить LAN-узел (код $errorCode)") }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != serviceType.trimEnd('.') || serviceInfo.serviceName.startsWith("Lunex-${store.serverId().takeLast(8)}")) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host ?: return
                        val peer = LanPeer(info.serviceName, host, info.port)
                        io.execute {
                            val hello = runCatching { request(peer, JSONObject().put("op", "hello")) }.getOrNull()
                            if (hello?.optBoolean("ok") == true && hello.optString("nodeId") != store.serverId()) {
                                val peerId = hello.optString("nodeId")
                                val verifiedPeer = peer.copy(name = hello.optString("name").ifBlank { peer.name })
                                peers[peerId] = verifiedPeer
                                store.rememberPeer(peerId, verifiedPeer.serviceName, endpointOf(verifiedPeer))
                                main.post { onPeersChanged(peers()) }
                                store.rooms().forEach { room ->
                                    if (store.inviteTokensForRoom(room.id).isNotEmpty()) discoverRoomMesh(verifiedPeer, room.id)
                                }
                                syncAll()
                            }
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.entries.removeIf { it.value.serviceName == serviceInfo.serviceName }
                main.post { onPeersChanged(peers()) }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { onStatus("Поиск устройств не запущен (код $errorCode)") }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
    }
}

internal data class LocalNodeMessage(
    val id: String, val roomId: String, val senderNodeId: String,
    val senderName: String, val body: String, val createdAt: Long
) {
    fun toJson() = JSONObject().put("id", id).put("roomId", roomId).put("senderNodeId", senderNodeId)
        .put("senderName", senderName).put("body", body).put("createdAt", createdAt)
    companion object {
        fun fromJson(o: JSONObject) = LocalNodeMessage(
            o.getString("id"), o.getString("roomId"), o.getString("senderNodeId"),
            o.optString("senderName", "Узел"), o.getString("body"), o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
