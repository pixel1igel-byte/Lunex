package app.lunex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
private data class DirectChat(val id: String, val username: String, val displayName: String, val lastMessage: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LunexApp() }
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
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "Ошибка сервера ($status)" })
            json
        } finally { conn.disconnect() }
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
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER) }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var currentName by remember { mutableStateOf("") }
    var communities by remember { mutableStateOf(emptyList<Community>()) }
    var activeCommunity by remember { mutableStateOf("") }
    var activeChannel by remember { mutableStateOf("") }
    var activeChannelName by remember { mutableStateOf("общение") }
    var messages by remember { mutableStateOf(emptyList<ChatMessage>()) }
    var dms by remember { mutableStateOf(emptyList<DirectChat>()) }
    var activeDm by remember { mutableStateOf<DirectChat?>(null) }
    var dmUsername by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var showServer by remember { mutableStateOf(false) }
    var showNewDm by remember { mutableStateOf(false) }
    var socket by remember { mutableStateOf<Socket?>(null) }\n    val activeChannelRef = remember { java.util.concurrent.atomic.AtomicReference("") }\n    val activeDmRef = remember { java.util.concurrent.atomic.AtomicReference("") }\n    LaunchedEffect(activeChannel, activeDm) { activeChannelRef.set(activeChannel); activeDmRef.set(activeDm?.id.orEmpty()) }
    var communityDialog by remember { mutableStateOf(false) }
    var communityName by remember { mutableStateOf("") }

    fun disconnect() { socket?.disconnect(); socket?.off(); socket = null }
    fun connectSocket(authToken: String) {
        disconnect()
        try {
            val opts = IO.Options.builder().setAuth(mapOf("token" to authToken)).setReconnection(true).build()
            val s = IO.socket(server.trimEnd('/'), opts)
            s.on(Socket.EVENT_CONNECT) {
                val ch = activeChannel
                if (ch.isNotBlank() && activeDm == null) s.emit("channel:join", ch)
                activeDm?.let { s.emit("dm:join", it.id) }
            }
            s.on("message:new", Emitter.Listener { args ->
                val event = args.firstOrNull() as? JSONObject ?: return@Listener
                val msg = event.optJSONObject("message") ?: return@Listener
                val channelId = event.optString("channelId")
                val item = ChatMessage(msg.optString("id"), msg.optString("user"), msg.optString("text"), msg.optString("time"), msg.optString("userId"))
                if (channelId == activeChannelRef.get() && activeDmRef.get().isBlank()) {
                    android.os.Handler(mainLooper).post { messages = (messages + item).takeLast(500) }
                }
            })
            s.on("dm:new", Emitter.Listener { args ->
                val event = args.firstOrNull() as? JSONObject ?: return@Listener
                val msg = event.optJSONObject("message") ?: return@Listener
                val id = event.optString("conversationId")
                if (activeDmRef.get() == id) {
                    val item = ChatMessage(msg.optString("id"), msg.optString("user"), msg.optString("text"), msg.optString("time"), msg.optString("userId"))
                    android.os.Handler(mainLooper).post { messages = (messages + item).takeLast(500) }
                }
                android.os.Handler(mainLooper).post { scope.launch { runCatching { loadDms(server, authToken) }.onSuccess { dms = it } } }
            })
            s.on("message:error", Emitter.Listener { args ->
                val err = (args.firstOrNull() as? JSONObject)?.optString("error") ?: "Не удалось отправить сообщение"
                android.os.Handler(mainLooper).post { notice = err }
            })
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                android.os.Handler(mainLooper).post { notice = "Не удалось подключиться к серверу сообщений. Проверь адрес сервера." }
            }
            s.connect()
            socket = s
        } catch (e: Exception) { notice = "Ошибка подключения: ${e.message ?: "unknown"}" }
    }

    suspend fun loadDms(base: String, authToken: String): List<DirectChat> {
        val arr = requestArray(base, "/api/dms", authToken)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val other = o.optJSONObject("other") ?: JSONObject()
            DirectChat(o.optString("id"), other.optString("username"), other.optString("displayName"), o.optString("lastMessage"))
        }
    }

    suspend fun refreshChannel(channel: String, authToken: String) {
        val arr = requestArray(server, "/api/messages/${java.net.URLEncoder.encode(channel, "UTF-8")}", authToken)
        messages = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); ChatMessage(o.optString("id"), o.optString("user"), o.optString("text"), o.optString("time"), o.optString("userId"))
        }
    }

    suspend fun refreshDm(chat: DirectChat, authToken: String) {
        val arr = requestArray(server, "/api/dms/${chat.id}/messages", authToken)
        messages = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); ChatMessage(o.optString("id"), o.optString("user"), o.optString("text"), o.optString("time"), o.optString("userId"))
        }
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
        Surface(Modifier.fillMaxSize(), color = Night) {
            if (token.isBlank()) {
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DarkMode, null, tint = Purple, modifier = Modifier.size(42.dp))
                    Text("LUNEX", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                    Text("Реальные чаты, аккаунт и история на сервере", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(22.dp))
                    OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("Адрес сервера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (register) OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Отображаемое имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль (не менее 10 символов)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
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
                                currentName = response.getJSONObject("user").optString("displayName")
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
                    }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (register) "Создать аккаунт" else "Войти")
                    }
                    TextButton(onClick = { register = !register; notice = "" }) { Text(if (register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Создать") }
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
                                if (ch != null) { activeChannel = ch.id; activeChannelName = ch.name; messages = emptyList(); socket?.emit("channel:join", ch.id); scope.launch { runCatching { refreshChannel(ch.id, token) }.onFailure { notice = it.message ?: "Ошибка загрузки" } } }
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
                        OutlinedButton(onClick = { showNewDm = !showNewDm }) { Text("Личные сообщения") }
                    }
                    if (showNewDm) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = dmUsername, onValueChange = { dmUsername = it }, label = { Text("Логин собеседника") }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { scope.launch {
                                try {
                                    val res = request(server, "/api/dms", "POST", token, JSONObject().put("username", dmUsername.trim().removePrefix("@")))
                                    val other = res.optJSONObject("other") ?: JSONObject()
                                    val chat = DirectChat(res.optString("id"), other.optString("username"), other.optString("displayName"), res.optString("lastMessage"))
                                    dms = loadDms(server, token).let { listOf(chat) + it.filter { d -> d.id != chat.id } }
                                    activeDm = chat; activeChannel = ""; messages = emptyList(); socket?.emit("dm:join", chat.id); refreshDm(chat, token); dmUsername = ""; showNewDm = false
                                } catch(e:Exception) { notice=e.message ?: "Не удалось создать чат" }
                            } }) { Text("Открыть") }
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(dms) { d -> AssistChip(onClick = { activeDm=d; activeChannel=""; messages=emptyList(); socket?.emit("dm:join", d.id); scope.launch { runCatching { refreshDm(d,token) }.onFailure { notice=it.message ?: "Ошибка загрузки" } } }, label = { Text(d.displayName.ifBlank { d.username }) }) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Panel2).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(activeDm?.displayName ?: "# $activeChannelName", fontWeight = FontWeight.Bold)
                            Text(if (activeDm != null) "@${activeDm!!.username}" else "История загружается с сервера", color = Muted, fontSize = 11.sp)
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
                                if (s == null || !s.connected()) notice = "Нет соединения с сервером"
                                else if (activeDm != null) s.emit("dm:send", JSONObject().put("conversationId", activeDm!!.id).put("text", text))
                                else if (activeChannel.isNotBlank()) s.emit("message:send", JSONObject().put("channelId", activeChannel).put("text", text))
                                draft = ""
                            }
                        }) { Icon(Icons.Default.Send, "Отправить", tint = Purple) }
                    }
                }
            }
        }
    }
}
