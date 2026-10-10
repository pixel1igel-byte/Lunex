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

internal data class LanPeer(val name: String, val host: InetAddress, val port: Int)

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
    private val io = Executors.newCachedThreadPool()
    private val peers = ConcurrentHashMap<String, LanPeer>()
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var port = 0
    private val serviceType = "_lunexnode._tcp."

    fun start() {
        if (running) return
        running = true
        io.execute {
            try {
                server = ServerSocket(0)
                port = server!!.localPort
                registerService()
                while (running) {
                    val client = server!!.accept()
                    io.execute { handle(client) }
                }
            } catch (e: Exception) {
                if (running) main.post { onStatus("LAN-узел: ${e.message ?: "ошибка"}") }
            }
        }
        startDiscovery()
        main.post { onStatus("LAN-узел запущен. Поиск устройств в этой Wi-Fi сети…") }
    }

    fun stop() {
        running = false
        runCatching { discovery?.let { nsd.stopServiceDiscovery(it) } }
        runCatching { registration?.let { nsd.unregisterService(it) } }
        runCatching { server?.close() }
        discovery = null
        registration = null
        peers.clear()
        main.post { onPeersChanged(emptyList()) }
        io.shutdownNow()
    }

    fun peers(): List<LanPeer> = peers.values.toList()

    /** Current port; internet reachability may require router port forwarding. */
    fun listeningPort(): Int = port

    fun join(peer: LanPeer, token: String, callback: (Boolean, String) -> Unit) {
        io.execute {
            try {
                val response = request(peer, JSONObject().put("op", "join").put("token", token))
                if (!response.optBoolean("ok")) throw IllegalStateException(response.optString("error", "Приглашение не принято"))
                val roomId = response.getString("roomId")
                val roomName = response.optString("roomName", "Lunex room")
                store.joinRoom(roomId, roomName, token)
                sync(peer, roomId, token)
                main.post { callback(true, "Комната «$roomName» подключена") }
            } catch (e: Exception) {
                main.post { callback(false, e.message ?: "Не удалось подключиться к узлу") }
            }
        }
    }

    fun send(roomId: String, token: String, senderName: String, body: String) {
        val clean = body.trim().take(4000)
        if (clean.isBlank()) return
        val message = LocalNodeMessage(
            java.util.UUID.randomUUID().toString(), roomId, store.serverId(), senderName,
            clean, System.currentTimeMillis()
        )
        store.addMessage(message)
        onMessage(message)
        io.execute {
            peers().forEach { peer ->
                runCatching {
                    request(peer, JSONObject().put("op", "message").put("token", token)
                        .put("roomId", roomId).put("message", message.toJson()))
                }
            }
        }
    }

    fun syncAll() {
        io.execute {
            peers().forEach { peer ->
                store.rooms().forEach { room ->
                    val token = store.inviteTokenForRoom(room.id) ?: return@forEach
                    runCatching { sync(peer, room.id, token) }
                }
            }
        }
    }

    private fun sync(peer: LanPeer, roomId: String, token: String) {
        val response = request(peer, JSONObject().put("op", "sync").put("roomId", roomId).put("token", token))
        val array = response.optJSONArray("messages") ?: return
        for (i in 0 until array.length()) {
            val message = LocalNodeMessage.fromJson(array.getJSONObject(i))
            if (store.addMessage(message)) main.post { onMessage(message) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10000
            val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine() ?: return
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
                    "message" -> {
                        val token = request.optString("token")
                        val roomId = request.optString("roomId")
                        val message = LocalNodeMessage.fromJson(request.getJSONObject("message"))
                        if (store.roomForInvite(token) != roomId || message.roomId != roomId) {
                            JSONObject().put("ok", false).put("error", "Нет доступа к комнате")
                        } else {
                            if (store.addMessage(message)) main.post { onMessage(message) }
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

    private fun request(peer: LanPeer, payload: JSONObject): JSONObject {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(peer.host, peer.port), 4000)
            socket.soTimeout = 6000
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true).println(payload.toString())
            val line = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
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
                                peers[hello.optString("nodeId")] = peer
                                main.post { onPeersChanged(peers()) }
                                syncAll()
                            }
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peers.entries.removeIf { it.value.name == serviceInfo.serviceName }
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
