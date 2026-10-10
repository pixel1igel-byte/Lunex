package app.lunex

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val LUNEX_NODE_PORT = 45873
private const val MAX_REQUEST_FRAME_CHARS = 65_536
private const val MAX_RESPONSE_FRAME_CHARS = 16_777_216

internal data class LanPeer(val name: String, val host: InetAddress, val port: Int, val serviceName: String = name)

internal class LocalNodeTransport(
    context: Context,
    private val store: LocalNodeStore,
    private val onPeersChanged: (List<LanPeer>) -> Unit,
    private val onMessage: (LocalNodeMessage) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var io = Executors.newCachedThreadPool()
    @Volatile private var maintenance: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val peers = ConcurrentHashMap<String, LanPeer>()
    @Volatile private var running = false
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
        peers.clear()
        main.post { onPeersChanged(emptyList()) }
        io.shutdownNow()
        maintenance.shutdownNow()
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
                val response = request(peer, JSONObject().put("op", "join").put("token", token))
                if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Приглашение не принято"))
                val roomId = response.getString("roomId")
                val roomName = response.optString("roomName", "Lunex room")
                val namedPeer = peer.copy(name = hello.optString("name").ifBlank { peer.name })
                peers[hello.getString("nodeId")] = namedPeer
                store.rememberPeer(hello.getString("nodeId"), namedPeer.serviceName, endpointOf(namedPeer))
                main.post { onPeersChanged(peers()) }
                store.joinRoom(roomId, roomName, token)
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
        io.execute { relayToPeers(message, null) }
    }

    fun syncAll() {
        io.execute {
            peers().forEach { peer ->
                store.rooms().forEach { room ->
                    if (store.inviteTokensForRoom(room.id).isNotEmpty()) {
                        runCatching { syncInvites(peer, room.id) }
                        runCatching { pushHistoryToPeer(peer, room.id) }
                        runCatching { sync(peer, room.id) }
                    }
                }
            }
        }
    }

    private fun sync(peer: LanPeer, roomId: String) {
        // A room can have several still-valid invitation tokens. Try each one
        // because a destination may have joined using an older invitation.
        var response: JSONObject? = null
        for (token in store.inviteTokensForRoom(roomId)) {
            val candidate = runCatching {
                request(peer, JSONObject().put("op", "sync").put("roomId", roomId).put("token", token))
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
        newlyInserted.forEach { relayToPeers(it, peer.host) }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10000
            val line = readBoundedLine(BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)), MAX_REQUEST_FRAME_CHARS) ?: return
            val request = JSONObject(line)
            val response = try {
                when (request.optString("op")) {
                    "hello" -> JSONObject().put("ok", true).put("nodeId", store.serverId()).put("name", android.os.Build.MODEL)
                    "join" -> {
                        val token = request.optString("token")
                        val roomId = store.roomForInvite(token)
                        val room = roomId?.let { store.room(it) }
                        if (room == null) JSONObject().put("ok", false).put("error", "Приглашение недействительно")
                        else JSONObject().put("ok", true).put("roomId", room.id).put("roomName", room.name)
                    }
                    "peers" -> {
                        val roomId = request.optString("roomId")
                        val token = request.optString("token")
                        if (store.roomForInvite(token) != roomId) {
                            JSONObject().put("ok", false).put("error", "Нет доступа к комнате")
                        } else {
                            val array = org.json.JSONArray()
                            peers.forEach { (peerId, peer) ->
                                if (peerId != store.serverId()) {
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
                            // Deduplication is the loop guard: a node relays a message only
                            // the first time it stores that message ID.
                            val inserted = store.addMessage(message)
                            if (inserted) {
                                main.post { onMessage(message) }
                                val sourceAddress = client.inetAddress
                                io.execute { relayToPeers(message, sourceAddress) }
                            }
                            JSONObject().put("ok", true).put("ack", message.id)
                        }
                    }
                    "sync" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        if (store.roomForInvite(token) != roomId) JSONObject().put("ok", false).put("error", "Нет доступа к комнате")
                        else {
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
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true).println(response.toString())
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
        peers().filterNot { peer ->
            sourceAddress != null && peer.host.address.contentEquals(sourceAddress.address)
        }.forEach { peer ->
            for (authToken in store.inviteTokensForRoom(roomId)) {
                val accepted = runCatching {
                    request(peer, JSONObject()
                        .put("op", "invite")
                        .put("roomId", roomId)
                        .put("authToken", authToken)
                        .put("inviteToken", inviteToken))
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
                        .put("inviteToken", inviteToken))
                }.getOrNull()?.optBoolean("ok") == true
                if (accepted) break
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
        peers().filterNot { peer ->
            sourceAddress != null && peer.host.address.contentEquals(sourceAddress.address)
        }.forEach { peer -> sendMessageToPeer(peer, message) }
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
                    request(current, JSONObject().put("op", "peers").put("roomId", roomId).put("token", token))
                }.getOrNull()
                if (candidate?.optBoolean("ok") == true) {
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
                val verified = candidatePeer.copy(name = candidateHello.optString("name").ifBlank { candidatePeer.name })
                peers[expectedId] = verified
                store.rememberPeer(expectedId, verified.serviceName, endpointOf(verified))
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
                        syncAll()
                    }
                } else if (peers.remove(saved.peerId) != null) {
                    main.post { onPeersChanged(peers()) }
                }
            } catch (_: Exception) {
                if (peers.remove(saved.peerId) != null) main.post { onPeersChanged(peers()) }
            }
        }
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
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true).println(payload.toString())
            val line = readBoundedLine(BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)), MAX_RESPONSE_FRAME_CHARS)
                ?: throw IllegalStateException("Узел не ответил")
            return JSONObject(line)
        }
    }

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
