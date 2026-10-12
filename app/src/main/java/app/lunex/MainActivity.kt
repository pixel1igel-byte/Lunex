package app.lunex

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.view.WindowCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter

private val Night = Color(0xFF0D0B16)
private val Panel = Color(0xFF191426)
private val Panel2 = Color(0xFF241B35)
private val Purple = Color(0xFFB56BFF)
private val Muted = Color(0xFFAAA0BC)
private val White = Color(0xFFF8F3FF)
private const val DEFAULT_SERVER = "https://lunex-messenger.onrender.com"

private data class Channel(val id: String, val name: String, val type: String)
private data class Community(val id: String, val name: String, val channels: List<Channel>)
private data class ChatMessage(val id: String, val user: String, val text: String, val time: String, val userId: String = "")
private data class DirectChat(val id: String, val userId: String, val username: String, val displayName: String, val lastMessage: String)
private data class FoundUser(val id: String, val username: String, val displayName: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { LunexApp() }
    }

    override fun onStart() {
        super.onStart()
        // A node exists while the app is in use; its room/message data remains in
        // SQLite when the app is backgrounded and the network sockets are paused.
        LocalNodeRuntime.ensure(this)
    }

    override fun onStop() {
        LocalNodeRuntime.pause()
        super.onStop()
    }
}

private suspend fun request(base: String, path: String, method: String = "GET", token: String? = null, body: JSONObject? = null): JSONObject {
    return withContext(Dispatchers.IO) {
        val url = URL(base.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("error", text.trim().take(500).ifBlank { "Сервер вернул ответ в неожиданном формате." })
            }
            if (status !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "Ошибка сервера ($status)" })
            if (text.isNotBlank() && !text.trimStart().startsWith("{")) {
                throw IllegalStateException("Сервер вернул не JSON (HTTP $status): " + text.trim().take(300))
            }
            json
        } finally { conn.disconnect() }
    }
}

private fun responseDisplayName(response: JSONObject, fallback: String): String {
    val userValue = response.opt("user")
    return when (userValue) {
        is JSONObject -> userValue.optString("displayName").ifBlank { userValue.optString("username").ifBlank { fallback } }
        is String -> userValue.ifBlank { fallback }
        else -> response.optString("displayName").ifBlank { response.optString("username").ifBlank { fallback } }
    }
}

private suspend fun requestArray(base: String, path: String, token: String): JSONArray {
    return withContext(Dispatchers.IO) {
        val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(runCatching { JSONObject(text).optString("error") }.getOrDefault("Ошибка сервера ($status)"))
            JSONArray(text)
        } finally { conn.disconnect() }
    }
}

@Composable
private fun LunexApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("lunex", android.content.Context.MODE_PRIVATE) }
    val outbox = remember { OfflineOutbox(context) }
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(true) }
    var localMode by remember { mutableStateOf(false) }
    var currentName by remember { mutableStateOf("") }
    var communities by remember { mutableStateOf(emptyList<Community>()) }
    var activeCommunity by remember { mutableStateOf("") }
    var activeChannel by remember { mutableStateOf("") }
    var activeChannelName by remember { mutableStateOf("общение") }
    var messages by remember { mutableStateOf(emptyList<ChatMessage>()) }
    var dms by remember { mutableStateOf(emptyList<DirectChat>()) }
    var activeDm by remember { mutableStateOf<DirectChat?>(null) }
    var dmUsername by remember { mutableStateOf("") }
    var userSearchResults by remember { mutableStateOf(emptyList<FoundUser>()) }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var showServer by remember { mutableStateOf(false) }
    var showNewDm by remember { mutableStateOf(false) }
    var socket by remember { mutableStateOf<Socket?>(null) }
    val activeChannelRef = remember { java.util.concurrent.atomic.AtomicReference("") }
    val activeDmRef = remember { java.util.concurrent.atomic.AtomicReference("") }
    LaunchedEffect(activeChannel, activeDm) { activeChannelRef.set(activeChannel); activeDmRef.set(activeDm?.id.orEmpty()) }
    LaunchedEffect(dmUsername, token, server) {
        val query = dmUsername.trim().removePrefix("@").trim()
        if (token.isBlank() || query.length < 2) {
            userSearchResults = emptyList()
        } else {
            delay(300)
            runCatching {
                requestArray(server, "/api/users/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}", token)
            }.onSuccess { result ->
                userSearchResults = (0 until result.length()).map { index ->
                    val user = result.getJSONObject(index)
                    FoundUser(user.optString("id"), user.optString("username"), user.optString("displayName"))
                }
            }.onFailure { userSearchResults = emptyList() }
        }
    }
    var communityDialog by remember { mutableStateOf(false) }
    var communityName by remember { mutableStateOf("") }
    var callState by remember { mutableStateOf("idle") }
    var callMode by remember { mutableStateOf("audio") }
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var incomingCall by remember { mutableStateOf<JSONObject?>(null) }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var iceServers by remember { mutableStateOf(listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val action = pendingCallAction; pendingCallAction = null
        if (results.values.all { it }) action?.invoke() else notice = "Для звонков разреши доступ к микрофону и камере."
    }
    fun withCallPermissions(action: () -> Unit) {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (callMode == "video" && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) action() else { pendingCallAction = action; permissionLauncher.launch(needed.toTypedArray()) }
    }
    val callManager = remember(context, token, iceServers) {
        if (token.isBlank()) null else AndroidCallManager(context, iceServers,
            onIncoming = { event -> android.os.Handler(android.os.Looper.getMainLooper()).post { incomingCall = event; callMode = event.optString("mode", "audio") } },
            onState = { state -> android.os.Handler(android.os.Looper.getMainLooper()).post {
                callState = if (state in setOf("ended", "rejected", "failed")) "idle" else state
                if (state in setOf("connecting", "connected", "ended", "rejected", "failed", "idle")) incomingCall = null
                if (state == "ended") notice = "Собеседник завершил звонок."
                if (state == "rejected") notice = "Собеседник отклонил звонок."
            } },
            onLocalVideo = { track -> android.os.Handler(android.os.Looper.getMainLooper()).post { localVideoTrack = track } },
            onRemoteVideo = { track -> android.os.Handler(android.os.Looper.getMainLooper()).post { remoteVideoTrack = track } },
            onError = { message -> android.os.Handler(android.os.Looper.getMainLooper()).post { notice = message } }
        )
    }
    LaunchedEffect(token, server) {
        if (token.isNotBlank()) runCatching {
            val config = request(server, "/api/call-config", token = token)
            val array = config.optJSONArray("iceServers") ?: JSONArray()
            val parsed = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val urls = item.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?: listOf(item.optString("urls")).filter { it.isNotBlank() }
                for (uri in urls) {
                    val builder = PeerConnection.IceServer.builder(uri)
                    if (item.has("username")) builder.setUsername(item.optString("username"))
                    if (item.has("credential")) builder.setPassword(item.optString("credential"))
                    parsed.add(builder.createIceServer())
                }
            }
            if (parsed.isNotEmpty()) iceServers = parsed
        }
    }
    LaunchedEffect(socket, callManager) { if (socket != null && callManager != null) callManager.bind(socket!!) }
    DisposableEffect(callManager) { onDispose { callManager?.dispose() } }

    suspend fun loadDms(base: String, authToken: String): List<DirectChat> {
        val arr = requestArray(base, "/api/dms", authToken)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val other = o.optJSONObject("other") ?: JSONObject()
            DirectChat(o.optString("id"), other.optString("id"), other.optString("username"), other.optString("displayName"), o.optString("lastMessage"))
        }
    }


    fun disconnect() { socket?.disconnect(); socket?.off(); socket = null }
    fun replayPending(s: Socket, kind: String, targetId: String) {
        outbox.pending().filter { it.kind == kind && it.targetId == targetId }.forEach { pending ->
            val payload = JSONObject().put("clientMessageId", pending.clientId).put("text", pending.text)
            if (kind == "dm") s.emit("dm:send", payload.put("conversationId", targetId))
            else s.emit("message:send", payload.put("channelId", targetId))
        }
    }
    fun connectSocket(authToken: String) {
        disconnect()
        try {
            val opts = IO.Options.builder().setAuth(mapOf("token" to authToken)).setReconnection(true).build()
            val s = IO.socket(server.trimEnd('/'), opts)
            s.on(Socket.EVENT_CONNECT) {
                val ch = activeChannel
                if (ch.isNotBlank() && activeDm == null) s.emit("channel:join", ch)
                activeDm?.let { s.emit("dm:join", it.id) }
                // Only replay messages for a room this socket has joined.
                activeDm?.let { replayPending(s, "dm", it.id) }
                    ?: activeChannel.takeIf { it.isNotBlank() }?.let { replayPending(s, "channel", it) }
            }
            s.on("message:ack", Emitter.Listener { args ->
                val event = args.firstOrNull() as? JSONObject ?: return@Listener
                val clientId = event.optString("clientMessageId")
                if (clientId.isNotBlank()) outbox.acknowledge(clientId)
            })
            s.on("message:new", Emitter.Listener { args ->
                val event = args.firstOrNull() as? JSONObject ?: return@Listener
                val msg = event.optJSONObject("message") ?: return@Listener
                val channelId = event.optString("channelId")
                val item = ChatMessage(msg.optString("id"), msg.optString("user"), msg.optString("text"), msg.optString("time"), msg.optString("userId"))
                outbox.store(StoredMessage(item.id, "channel", channelId, item.user, item.userId, item.text, item.time))
                if (channelId == activeChannelRef.get() && activeDmRef.get().isBlank()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (messages.none { it.id == item.id }) messages = (messages + item).takeLast(500)
                    }
                }
            })
            s.on("dm:new", Emitter.Listener { args ->
                val event = args.firstOrNull() as? JSONObject ?: return@Listener
                val msg = event.optJSONObject("message") ?: return@Listener
                val id = event.optString("conversationId")
                val item = ChatMessage(msg.optString("id"), msg.optString("user"), msg.optString("text"), msg.optString("time"), msg.optString("userId"))
                outbox.store(StoredMessage(item.id, "dm", id, item.user, item.userId, item.text, item.time))
                if (activeDmRef.get() == id) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (messages.none { it.id == item.id }) messages = (messages + item).takeLast(500)
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { scope.launch { runCatching { loadDms(server, authToken) }.onSuccess { dms = it } } }
            })
            s.on("message:error", Emitter.Listener { args ->
                val err = (args.firstOrNull() as? JSONObject)?.optString("error") ?: "Не удалось отправить сообщение"
                android.os.Handler(android.os.Looper.getMainLooper()).post { notice = err }
            })
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { notice = "Не удалось подключиться к серверу сообщений. Проверь адрес сервера." }
            }
            s.connect()
            socket = s
        } catch (e: Exception) { notice = "Ошибка подключения: ${e.message ?: "unknown"}" }
    }


    suspend fun refreshChannel(channel: String, authToken: String) {
        val arr = try {
            requestArray(server, "/api/messages/${java.net.URLEncoder.encode(channel, "UTF-8")}", authToken)
        } catch (_: Exception) {
            messages = outbox.history("channel", channel).map { ChatMessage(it.id, it.user, it.text, it.time, it.userId) }
            return
        }
        val loaded = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatMessage(o.optString("id"), o.optString("user"), o.optString("text"), o.optString("time"), o.optString("userId"))
        }
        loaded.forEach { outbox.store(StoredMessage(it.id, "channel", channel, it.user, it.userId, it.text, it.time)) }
        messages = loaded.ifEmpty { outbox.history("channel", channel).map { ChatMessage(it.id, it.user, it.text, it.time, it.userId) } }.takeLast(500)
    }

    suspend fun refreshDm(chat: DirectChat, authToken: String) {
        val arr = try {
            requestArray(server, "/api/dms/${chat.id}/messages", authToken)
        } catch (_: Exception) {
            messages = outbox.history("dm", chat.id).map { ChatMessage(it.id, it.user, it.text, it.time, it.userId) }
            return
        }
        val loaded = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatMessage(o.optString("id"), o.optString("user"), o.optString("text"), o.optString("time"), o.optString("userId"))
        }
        loaded.forEach { outbox.store(StoredMessage(it.id, "dm", chat.id, it.user, it.userId, it.text, it.time)) }
        messages = loaded.ifEmpty { outbox.history("dm", chat.id).map { ChatMessage(it.id, it.user, it.text, it.time, it.userId) } }.takeLast(500)
    }

    LaunchedEffect(Unit) {
        if (token.isNotBlank()) {
            loading = true
            try {
                val me = request(server, "/api/me", token = token)
                currentName = me.getJSONObject("user").optString("displayName")
                val boot = request(server, "/api/bootstrap", token = token)
                val arr = boot.optJSONArray("communities") ?: JSONArray()
                communities = (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i); val ch = c.optJSONArray("channels") ?: JSONArray()
                    Community(c.optString("id"), c.optString("name"), (0 until ch.length()).map { j ->
                        val v = ch.getJSONObject(j); Channel(v.optString("id"), v.optString("name"), v.optString("type"))
                    })
                }
                communities.firstOrNull()?.let { c -> activeCommunity = c.id; c.channels.firstOrNull { it.type == "text" }?.let { activeChannel = it.id; activeChannelName = it.name; refreshChannel(it.id, token) } }
                dms = loadDms(server, token)
                connectSocket(token)
            } catch (e: Exception) {
                token = ""; prefs.edit().remove("token").apply()
                notice = "Не удалось восстановить сессию: ${e.message ?: "сервер недоступен"}"
            } finally { loading = false }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Color(0xFFFF73D1), background = Night, surface = Panel, onBackground = White, onSurface = White)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = Night) {
            if (localMode) {
                LocalNodeScreen(onSwitchToCloud = { localMode = false })
            } else if (token.isBlank()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DarkMode, null, tint = Purple, modifier = Modifier.size(42.dp))
                    Text("LUNEX", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text("Реальные чаты, аккаунт и история на сервере", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(22.dp))
                    TextButton(onClick = { showServer = !showServer }) { Text(if (showServer) "Скрыть настройки подключения" else "Настройки подключения") }
                    if (showServer) OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server-url"))
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Ник") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("auth-username"))
                    if (register) OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Отображаемое имя") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("auth-display-name"))
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль (не менее 10 символов)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("auth-password"))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        scope.launch {
                            loading = true; notice = ""
                            try {
                                val base = server.trim().trimEnd('/')
                                require(base.startsWith("https://") || base.startsWith("http://")) { "Укажи адрес сервера с https:// или http://" }
                                prefs.edit().putString("server", base).apply()
                                val payload = JSONObject().put("username", username.trim())
                                    .put("password", password)
                                if (register) payload.put("displayName", displayName.trim())
                                val response = request(base, "/api/auth/${if (register) "register" else "login"}", "POST", body = payload)
                                token = response.getString("token"); prefs.edit().putString("token", token).apply()
                                currentName = responseDisplayName(response, username.trim())
                                val boot = request(base, "/api/bootstrap", token = token)
                                val arr = boot.optJSONArray("communities") ?: JSONArray()
                                communities = (0 until arr.length()).map { i ->
                                    val c = arr.getJSONObject(i); val ch = c.optJSONArray("channels") ?: JSONArray()
                                    Community(c.optString("id"), c.optString("name"), (0 until ch.length()).map { j ->
                                        val v = ch.getJSONObject(j); Channel(v.optString("id"), v.optString("name"), v.optString("type"))
                                    })
                                }
                                communities.firstOrNull()?.let { c -> activeCommunity = c.id; c.channels.firstOrNull { it.type == "text" }?.let { activeChannel = it.id; activeChannelName = it.name; refreshChannel(it.id, token) } }
                                dms = loadDms(base, token)
                                connectSocket(token); password = ""
                            } catch (e: Exception) { notice = e.message ?: "Сервер недоступен" }
                            finally { loading = false }
                        }
                    }, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("auth-submit")) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (register) "Создать аккаунт" else "Войти")
                    }
                    TextButton(onClick = { register = !register; notice = "" }, modifier = Modifier.testTag("toggle-register")) { Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Создать") }
                    if (notice.isNotBlank()) Text(notice, color = Color(0xFFFF8D9B), fontSize = 12.sp)
                    Text("Регистрация: логин 3–24 символа (a–z, цифры, _), пароль от 10 символов.", color = Muted, fontSize = 11.sp)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().background(Panel).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("LUNEX", fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("Вошли как $currentName", color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { scope.launch {
                            loading = true
                            try {
                                val boot = request(server, "/api/bootstrap", token = token)
                                val arr = boot.optJSONArray("communities") ?: JSONArray()
                                communities = (0 until arr.length()).map { i ->
                                    val c = arr.getJSONObject(i); val ch = c.optJSONArray("channels") ?: JSONArray()
                                    Community(c.optString("id"), c.optString("name"), (0 until ch.length()).map { j -> val v=ch.getJSONObject(j); Channel(v.optString("id"),v.optString("name"),v.optString("type")) })
                                }
                                dms = loadDms(server, token)
                                if (activeDm != null) refreshDm(activeDm!!, token) else if (activeChannel.isNotBlank()) refreshChannel(activeChannel, token)
                            } catch(e:Exception) { notice=e.message ?: "Ошибка обновления" } finally { loading=false }
                        } }) { Icon(Icons.Default.Refresh, "Обновить", tint = Purple) }
                        TextButton(onClick = { scope.launch { runCatching { request(server, "/api/auth/logout", "POST", token) }; disconnect(); token=""; prefs.edit().remove("token").apply(); messages= emptyList(); dms= emptyList(); activeDm=null } }) { Text("Выйти") }
                    }
                    if (notice.isNotBlank()) Text(notice, color = Color(0xFFFF8D9B), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp)
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(communities) { c ->
                            FilterChip(selected = activeCommunity == c.id && activeDm == null, onClick = {
                                activeCommunity = c.id; activeDm = null
                                val ch = c.channels.firstOrNull { it.type == "text" }
                                if (ch != null) { activeChannel = ch.id; activeChannelName = ch.name; messages = emptyList(); socket?.emit("channel:join", ch.id); socket?.let { replayPending(it, "channel", ch.id) }; scope.launch { runCatching { refreshChannel(ch.id, token) }.onFailure { notice = it.message ?: "Ошибка загрузки" } } }
                            }, label = { Text(c.name) })
                        }
                    }
                    val community = communities.firstOrNull { it.id == activeCommunity }
                    if (community != null && activeDm == null) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(community.channels.filter { it.type == "text" }) { ch ->
                                AssistChip(onClick = { activeChannel = ch.id; activeChannelName = ch.name; activeDm = null; messages = emptyList(); socket?.emit("channel:join", ch.id); scope.launch { runCatching { refreshChannel(ch.id, token) }.onFailure { notice = it.message ?: "Ошибка загрузки" } } }, label = { Text("# ${ch.name}") })
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { activeDm = null; showNewDm = false; messages = emptyList(); community?.channels?.firstOrNull { it.type == "text" }?.let { activeChannel=it.id; activeChannelName=it.name; socket?.emit("channel:join",it.id); scope.launch { runCatching { refreshChannel(it.id,token) } } } }) { Text("Каналы") }
                        OutlinedButton(onClick = { showNewDm = !showNewDm }, modifier = Modifier.testTag("open-dms")) { Text("＋ Добавить друга") }
                    }
                    if (showNewDm) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = dmUsername, onValueChange = { dmUsername = it.removePrefix("@") }, label = { Text("@ник друга") }, singleLine = true, modifier = Modifier.weight(1f).testTag("dm-username"))
                            Button(onClick = { scope.launch {
                                try {
                                    val res = request(server, "/api/dms", "POST", token, JSONObject().put("username", dmUsername.trim().removePrefix("@")))
                                    val other = res.optJSONObject("other") ?: JSONObject()
                                    val chat = DirectChat(res.optString("id"), other.optString("id"), other.optString("username"), other.optString("displayName"), res.optString("lastMessage"))
                                    dms = loadDms(server, token).let { listOf(chat) + it.filter { d -> d.id != chat.id } }
                                    activeDm = chat; activeChannel = ""; messages = emptyList(); socket?.emit("dm:join", chat.id); socket?.let { replayPending(it, "dm", chat.id) }; refreshDm(chat, token); dmUsername = ""; showNewDm = false
                                } catch(e:Exception) { notice=e.message ?: "Не удалось создать чат" }
                            } }, modifier = Modifier.testTag("open-dm-submit")) { Text("Добавить") }
                        }
                        if (userSearchResults.isNotEmpty()) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(userSearchResults, key = { it.id }) { user ->
                                    AssistChip(
                                        onClick = { dmUsername = user.username },
                                        label = { Text("${user.displayName} · @${user.username}") }
                                    )
                                }
                            }
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(dms) { d -> AssistChip(onClick = { activeDm=d; activeChannel=""; messages=emptyList(); socket?.emit("dm:join", d.id); socket?.let { replayPending(it, "dm", d.id) }; scope.launch { runCatching { refreshDm(d,token) }.onFailure { notice=it.message ?: "Ошибка загрузки" } } }, label = { Text(d.displayName.ifBlank { d.username }) }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Panel2).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(activeDm?.displayName ?: "# $activeChannelName", fontWeight = FontWeight.Bold)
                            Text(if (activeDm != null) "@${activeDm!!.username}" else "История загружается с сервера", color = Muted, fontSize = 11.sp)
                        }
                        if (activeDm != null) {
                            IconButton(onClick = {
                                val chat = activeDm
                                if (chat == null || chat.userId.isBlank()) notice = "Не удалось определить пользователя для звонка."
                                else { callMode = "audio"; withCallPermissions { callManager?.startOutgoing(chat.id, chat.userId, "audio") } }
                            }, modifier = Modifier.testTag("call-audio")) { Icon(Icons.Default.Call, "Голосовой звонок", tint = Purple) }
                            IconButton(onClick = {
                                val chat = activeDm
                                if (chat == null || chat.userId.isBlank()) notice = "Не удалось определить пользователя для звонка."
                                else { callMode = "video"; withCallPermissions { callManager?.startOutgoing(chat.id, chat.userId, "video") } }
                            }, modifier = Modifier.testTag("call-video")) { Icon(Icons.Default.Videocam, "Видеозвонок", tint = Purple) }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(messages, key = { it.id }) { msg ->
                            val own = msg.user == currentName
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                                Text(msg.user, color = if (own) Purple else Color(0xFF79D8E8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Surface(color = if (own) Purple.copy(alpha = .22f) else Panel, shape = RoundedCornerShape(16.dp)) {
                                    Text(msg.text, Modifier.padding(horizontal = 13.dp, vertical = 9.dp), color = White)
                                }
                                Text(msg.time.replace("T", " ").take(16), color = Muted, fontSize = 9.sp)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Сообщение…") }, singleLine = true)
                        IconButton(onClick = {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                val s = socket
                                val clientId = java.util.UUID.randomUUID().toString()
                                val targetDm = activeDm
                                val kind: String
                                val targetId: String
                                if (targetDm != null) {
                                    kind = "dm"
                                    targetId = targetDm.id
                                } else if (activeChannel.isNotBlank()) {
                                    kind = "channel"
                                    targetId = activeChannel
                                } else {
                                    notice = "Сначала открой чат или канал."
                                    return@IconButton
                                }
                                // Persist before network send so process death/offline periods cannot lose it.
                                outbox.enqueue(PendingMessage(clientId, kind, targetId, text, System.currentTimeMillis()))
                                if (s != null && s.connected()) {
                                    val payload = JSONObject().put("clientMessageId", clientId).put("text", text)
                                    if (kind == "dm") s.emit("dm:send", payload.put("conversationId", targetId))
                                    else s.emit("message:send", payload.put("channelId", targetId))
                                    notice = "Сообщение сохранено на устройстве, ожидает подтверждения доставки."
                                } else {
                                    notice = "Нет сети. Сообщение сохранено на устройстве и будет отправлено при подключении."
                                }
                                draft = ""
                            }
                        }) { Icon(Icons.Default.Send, "Отправить", tint = Purple) }
                    }
                }
            }
            if (token.isNotBlank() && callState !in setOf("idle", "ended", "rejected", "failed")) {
                val isIncoming = incomingCall != null
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(if (isIncoming) "Входящий ${if (callMode == "video") "видеозвонок" else "звонок"}" else if (callMode == "video") "Видеозвонок" else "Голосовой звонок") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(incomingCall?.optJSONObject("caller")?.optString("displayName").orEmpty().ifBlank { activeDm?.displayName ?: "Собеседник" }, color = Muted)
                            Text(when (callState) { "calling" -> "Вызов…"; "incoming" -> "Входящий вызов"; "connecting" -> "Подключаемся…"; "connected" -> "Соединение установлено"; else -> "Подготовка звонка…" })
                            if (callMode == "video") {
                                localVideoTrack?.let { Text("Твоё видео", fontWeight = FontWeight.SemiBold); CallVideoPreview(it, callManager!!, true) }
                                remoteVideoTrack?.let { Text("Видео собеседника", fontWeight = FontWeight.SemiBold); CallVideoPreview(it, callManager!!, false) }
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isIncoming) {
                                TextButton(onClick = { withCallPermissions { incomingCall = null; callState = "connecting"; callManager?.acceptIncoming() } }) { Text("Принять") }
                                TextButton(onClick = { callManager?.rejectIncoming() }) { Text("Отклонить", color = Color(0xFFFF7188)) }
                            } else {
                                if (callState == "connected") {
                                    TextButton(onClick = { micEnabled = !micEnabled; callManager?.setMicrophoneEnabled(micEnabled) }) { Icon(if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff, null); Text("Микрофон") }
                                    if (callMode == "video") TextButton(onClick = { cameraEnabled = !cameraEnabled; callManager?.setCameraEnabled(cameraEnabled) }) { Icon(if (cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff, null); Text("Камера") }
                                }
                                TextButton(onClick = { callManager?.endCall() }) { Icon(Icons.Default.CallEnd, null, tint = Color(0xFFFF7188)); Text("Завершить", color = Color(0xFFFF7188)) }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun CallVideoPreview(track: VideoTrack, manager: AndroidCallManager, mirror: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember(manager) {
        SurfaceViewRenderer(context).apply {
            init(manager.eglContext, null)
            setEnableHardwareScaler(true)
            setMirror(mirror)
        }
    }
    DisposableEffect(renderer, track) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer) }
    }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    AndroidView(factory = { renderer }, modifier = Modifier.fillMaxWidth().height(180.dp))
}
