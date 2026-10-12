package app.lunex

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlin.coroutines.resume
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

@Composable
internal fun LocalNodeScreen(
    onSwitchToCloud: () -> Unit,
    linkedIdentity: LocalIdentity? = null,
    initialRoomId: String? = null,
    initialInvite: String? = null,
    onInviteHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { LocalNodeRuntime.store(context) }
    val scope = rememberCoroutineScope()
    val nodeState by LocalNodeRuntime.state.collectAsState()
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(linkedIdentity != null) }
    var activeName by remember { mutableStateOf(linkedIdentity?.displayName.orEmpty()) }
    var rooms by remember { mutableStateOf(store.rooms()) }
    var roomName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LocalRoom?>(null) }
    var invite by remember { mutableStateOf("") }
    var inviteToJoin by remember { mutableStateOf("") }
    var inviteRetry by remember { mutableIntStateOf(0) }
    var peerEndpoint by remember { mutableStateOf("") }
    var chatDraft by remember { mutableStateOf("") }
    var localMessages by remember { mutableStateOf(emptyList<LocalNodeMessage>()) }
    var roomFiles by remember { mutableStateOf(emptyList<LocalNodeFile>()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var connectPanelOpen by remember { mutableStateOf(false) }
    var roomPeers by remember { mutableStateOf(emptyList<LocalRoomPeer>()) }
    var callState by remember { mutableStateOf("idle") }
    var callMode by remember { mutableStateOf("audio") }
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var incomingCall by remember { mutableStateOf<JSONObject?>(null) }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(linkedIdentity) {
        if (linkedIdentity != null) {
            username = linkedIdentity.username
            displayName = linkedIdentity.displayName
            activeName = linkedIdentity.displayName
            loggedIn = true
            rooms = store.rooms()
            status = "Единый аккаунт Lunex: локальная сеть активна на этом устройстве."
        }
    }
    val peers = nodeState.peers
    val transport = remember { LocalNodeRuntime.ensure(context) }
    LaunchedEffect(initialRoomId) {
        val roomId = initialRoomId ?: return@LaunchedEffect
        val room = store.room(roomId) ?: return@LaunchedEffect
        selected = room
        rooms = store.rooms()
        localMessages = store.messages(room.id)
        roomFiles = store.files(room.id)
    }
    LaunchedEffect(initialInvite, loggedIn, inviteRetry) {
        val rawInvite = initialInvite?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val inviteUri = runCatching { Uri.parse(rawInvite) }.getOrNull()
        val token = inviteUri?.getQueryParameter("token").orEmpty()
        val targetRoomId = inviteUri?.pathSegments?.firstOrNull().orEmpty()
        if (inviteUri?.scheme != "lunex" || inviteUri.host != "join" || token.isBlank() || targetRoomId.isBlank()) {
            status = "Ссылка приглашения Lunex некорректна."
            return@LaunchedEffect
        }
        if (!loggedIn) {
            status = "Войди или создай профиль — затем Lunex подключится к приглашению автоматически."
            return@LaunchedEffect
        }
        store.room(targetRoomId)?.let { existing ->
            selected = existing
            rooms = store.rooms()
            localMessages = store.messages(existing.id)
            roomFiles = store.files(existing.id)
            status = "Комната уже есть на этом устройстве."
            onInviteHandled()
            return@LaunchedEffect
        }
        status = "Ищу устройство с этой комнатой в локальной сети…"
        val availablePeers = withTimeoutOrNull(30_000L) {
            snapshotFlow { nodeState.peers }.first { it.isNotEmpty() }
        }
        if (availablePeers.isNullOrEmpty()) {
            status = "Устройство не найдено. Откройте ссылку, когда оба телефона будут в одной Wi-Fi сети."
            return@LaunchedEffect
        }
        var lastError = "У найденных устройств нет этой комнаты."
        for (peer in availablePeers) {
            val result = withTimeoutOrNull(10_000L) {
                suspendCancellableCoroutine<Pair<Boolean, String>> { continuation ->
                    transport.join(peer, token) { ok, message ->
                        if (continuation.isActive) continuation.resume(ok to message)
                    }
                }
            }
            if (result?.first == true) {
                val room = store.room(targetRoomId) ?: store.rooms().lastOrNull()
                if (room != null) {
                    selected = room
                    rooms = store.rooms()
                    localMessages = store.messages(room.id)
                    roomFiles = store.files(room.id)
                }
                status = result.second
                onInviteHandled()
                return@LaunchedEffect
            }
            lastError = result?.second ?: "Устройство не ответило вовремя."
        }
        status = "$lastError Проверь, что отправитель сейчас в сети и ссылка актуальна."
    }
    val callManager = remember(context, transport) {
        AndroidCallManager(
            context,
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
            onIncoming = { event -> Handler(Looper.getMainLooper()).post { incomingCall = event; callMode = event.optString("mode", "audio") } },
            onState = { state -> Handler(Looper.getMainLooper()).post {
                callState = if (state in setOf("ended", "rejected", "failed")) "idle" else state
                if (state in setOf("connecting", "connected", "ended", "rejected", "failed", "idle")) incomingCall = null
                if (state == "ended") status = "Собеседник завершил звонок."
                if (state == "rejected") status = "Собеседник отклонил звонок."
            } },
            onLocalVideo = { track -> Handler(Looper.getMainLooper()).post { localVideoTrack = track } },
            onRemoteVideo = { track -> Handler(Looper.getMainLooper()).post { remoteVideoTrack = track } },
            onError = { message -> Handler(Looper.getMainLooper()).post { status = message } }
        )
    }
    LaunchedEffect(selected?.id, loggedIn, activeName, callState) {
        val room = selected
        if (loggedIn && room != null) {
            if (callState == "idle") callManager.bindLocal(transport, room.id, store.serverId(), activeName)
        } else callManager.unbindLocal()
    }
    DisposableEffect(callManager) { onDispose { callManager.dispose() } }
    LaunchedEffect(selected?.id, nodeState.peers) {
        val room = selected
        roomPeers = if (room == null) emptyList() else transport.roomPeers(room.id)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val action = pendingCallAction
        pendingCallAction = null
        if (results.values.all { it }) action?.invoke() else status = "Для звонков разреши доступ к микрофону и камере."
    }
    fun withCallPermissions(action: () -> Unit) {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (callMode == "video" && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
        }
        if (needed.isEmpty()) action() else {
            pendingCallAction = action
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val room = selected
        if (uri != null && room != null) {
            status = "Добавляю файл в комнату…"
            transport.shareFile(uri, room.id, activeName) { _, message ->
                status = message
                roomFiles = store.files(room.id)
            }
        }
    }
    LaunchedEffect(nodeState.lastFile?.id, selected?.id) {
        val room = selected
        roomFiles = if (room == null) emptyList() else store.files(room.id)
    }
    LaunchedEffect(nodeState.lastMessage?.id, selected?.id) {
        val room = selected
        if (room != null && nodeState.lastMessage?.roomId == room.id) {
            localMessages = store.messages(room.id)
        }
    }
    val localEndpoints = remember(transport) { transport.localEndpoints() }
    val shownStatus = status
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0B16))) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF191426)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFFB56BFF), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text("L", color = Color(0xFF0D0B16), fontSize = 24.sp, fontWeight = FontWeight.Black) }
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text("Lunex", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(if (loggedIn) activeName else "Личные сообщения", color = Color(0xFFAAA0BC), fontSize = 12.sp)
            }
            IconButton(onClick = onSwitchToCloud) { Icon(Icons.Default.ArrowBack, "Вернуться к основным чатам", tint = Color(0xFFCB91FF)) }
            if (loggedIn) {
                IconButton(onClick = { connectPanelOpen = !connectPanelOpen }) { Icon(Icons.Default.Add, "Новая локальная комната", tint = Color(0xFFCB91FF)) }
                IconButton(onClick = { settingsOpen = !settingsOpen }) { Icon(Icons.Default.Settings, "Настройки", tint = Color(0xFFCB91FF)) }
            }
        }
        if (!loggedIn) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.Center) {
                Text(if (isRegister) "Создать аккаунт" else "С возвращением", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Твои чаты. Твои устройства. Твоя сеть.", color = Color(0xFFAAA0BC), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
                OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (isRegister) OutlinedTextField(displayName, { displayName = it }, label = { Text("Имя в Lunex") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Пароль (10+ символов)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Button(onClick = { scope.launch { try {
                    val id = withContext(Dispatchers.IO) { if (isRegister) store.register(username, displayName, password.toCharArray()) else store.login(username, password.toCharArray()) }
                    activeName = id.displayName; loggedIn = true; password = ""; rooms = store.rooms(); status = ""
                } catch (e: Exception) { status = e.message ?: "Ошибка локального аккаунта" } } }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (isRegister) "Создать аккаунт" else "Войти") }
                TextButton(onClick = { isRegister = !isRegister; status = "" }, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)) { Text(if (isRegister) "Уже есть аккаунт? Войти" else "Создать аккаунт") }
                Text("Без облачного аккаунта локальный профиль хранится на этом устройстве.", color = Color(0xFFAAA0BC), fontSize = 12.sp)
            }
        } else {
            if (settingsOpen) {
                Surface(Modifier.fillMaxWidth().heightIn(max = 390.dp).padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF191426), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Настройки устройства", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("ID узла: " + store.serverId(), color = Color(0xFFAAA0BC), fontSize = 11.sp)
                        Text("TCP-порт: 45873", color = Color(0xFFAAA0BC), fontSize = 11.sp)
                        Text(localEndpoints.joinToString("\n").ifBlank { "Адреса устройства пока недоступны" }, color = Color.White, fontSize = 11.sp)
                        if (localEndpoints.isNotEmpty()) TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Lunex node endpoints", localEndpoints.joinToString("\n")))
                            status = "Адреса устройства скопированы."
                        }) { Icon(Icons.Default.ContentCopy, null); Text("Скопировать адреса") }
                        Text("Подключённых устройств: ${peers.size}", color = Color(0xFFAAA0BC), fontSize = 12.sp)
                        TextButton(onClick = { settingsOpen = false }) { Text("Готово") }
                        TextButton(onClick = { loggedIn = false; isRegister = false; selected = null; invite = ""; settingsOpen = false }) { Text("Выйти из аккаунта", color = Color(0xFFFF8295)) }
                    }
                }
            }
            if (connectPanelOpen) {
                Surface(Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF191426), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Новое подключение", color = Color.White, fontWeight = FontWeight.Bold)
                        OutlinedTextField(peerEndpoint, { peerEndpoint = it }, label = { Text("Адрес устройства (IP:порт)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(inviteToJoin, { inviteToJoin = it }, label = { Text("Код или ссылка приглашения") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            try {
                                val endpoint = peerEndpoint.trim()
                                val split = endpoint.lastIndexOf(':')
                                require(split > 0 && split < endpoint.lastIndex) { "Введи адрес в формате IP:порт." }
                                val host = endpoint.substring(0, split).removePrefix("[").removeSuffix("]")
                                val port = endpoint.substring(split + 1).toInt()
                                require(port in 1..65535) { "Порт должен быть от 1 до 65535." }
                                val peer = LanPeer("Direct $endpoint", java.net.InetAddress.getByName(host), port)
                                val raw = inviteToJoin.trim()
                                val token = Regex("[?&]token=([^&]+)").find(raw)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: raw
                                status = "Подключаюсь…"
                                transport.join(peer, token) { ok, message ->
                                    status = message
                                    if (ok) { rooms = store.rooms(); selected = rooms.lastOrNull(); selected?.let { localMessages = store.messages(it.id); roomFiles = store.files(it.id) }; peerEndpoint = ""; inviteToJoin = ""; connectPanelOpen = false }
                                }
                            } catch (e: Exception) { status = e.message ?: "Неверный адрес." }
                        }, enabled = peerEndpoint.isNotBlank() && inviteToJoin.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Подключиться напрямую") }
                        HorizontalDivider(color = Color(0xFF393044))
                        OutlinedTextField(roomName, { roomName = it }, label = { Text("Название новой комнаты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            try { selected = store.createRoom(roomName); rooms = store.rooms(); roomName = ""; invite = ""; status = ""; connectPanelOpen = false; localMessages = emptyList() }
                            catch (e: Exception) { status = e.message ?: "Ошибка" }
                        }, enabled = roomName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Создать комнату") }
                        TextButton(onClick = { connectPanelOpen = false }) { Text("Отмена") }
                    }
                }
            }
            if (selected == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сообщения", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("Твои комнаты и групповые чаты", color = Color(0xFFAAA0BC), fontSize = 12.sp)
                    }
                    Surface(color = Color(0xFF241B35), shape = androidx.compose.foundation.shape.CircleShape) {
                        Text("${rooms.size}", color = Color(0xFFCB91FF), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
                    }
                }
                if (rooms.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFF241B35), modifier = Modifier.size(82.dp)) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(Icons.Default.ChatBubbleOutline, null, tint = Color(0xFFB56BFF), modifier = Modifier.size(36.dp)) }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Пока нет чатов", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Создай комнату или присоединись по приглашению.", color = Color(0xFFAAA0BC), fontSize = 13.sp)
                        Button(onClick = { connectPanelOpen = true }, modifier = Modifier.padding(top = 14.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Новый чат") }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(rooms, key = { it.id }) { room ->
                            val last = store.messages(room.id).lastOrNull()
                            Surface(Modifier.fillMaxWidth().clickable { selected = room; invite = ""; localMessages = store.messages(room.id); roomFiles = store.files(room.id); roomPeers = transport.roomPeers(room.id) }, color = Color(0xFF191622), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFF38234A), modifier = Modifier.size(52.dp)) {
                                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text(room.name.take(1).uppercase(), color = Color(0xFFCB91FF), fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                                    }
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(room.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                        Text(last?.body ?: "Комната Lunex", color = Color(0xFFAAA0BC), fontSize = 13.sp, maxLines = 1)
                                    }
                                    TextButton(onClick = { try { selected = room; invite = store.createInvite(room.id); transport.syncAll(); localMessages = store.messages(room.id); roomPeers = transport.roomPeers(room.id) } catch (e: Exception) { status = e.message ?: "Ошибка" } }) { Text("Пригласить", color = Color(0xFFCB91FF)) }
                                }
                            }
                        }
                    }
                }
                NavigationBar(containerColor = Color(0xFF191426), contentColor = Color(0xFFCB91FF), modifier = Modifier.height(62.dp)) {
                    NavigationBarItem(selected = true, onClick = { selected = null }, icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("Чаты") })
                    NavigationBarItem(selected = false, onClick = { connectPanelOpen = true }, icon = { Icon(Icons.Default.Add, null) }, label = { Text("Новый чат") })
                    NavigationBarItem(selected = false, onClick = { settingsOpen = true }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
                }
            } else {
                val room = selected!!
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF191426)).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { selected = null; invite = "" }) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color(0xFFCB91FF)) }
                        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFF38234A), modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text(room.name.take(1).uppercase(), color = Color(0xFFCB91FF), fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(room.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(if (roomPeers.isEmpty()) "Комната · ${localMessages.size} сообщений" else "${roomPeers.size} участников", color = Color(0xFFAAA0BC), fontSize = 11.sp)
                        }
                        IconButton(onClick = { try { invite = store.createInvite(room.id); transport.syncAll(); status = "Ссылка приглашения создана." } catch (e: Exception) { status = e.message ?: "Ошибка" } }) { Icon(Icons.Default.Add, "Пригласить", tint = Color(0xFFCB91FF)) }
                    }
                    if (invite.isNotBlank()) {
                        Surface(Modifier.fillMaxWidth().padding(8.dp), color = Color(0xFF241B35), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Приглашение в комнату", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Ссылка готова — нажми копировать", color = Color(0xFFAAA0BC), fontSize = 11.sp)
                                }
                                IconButton(onClick = {
                                    val uri = "lunex://join/${room.id}?token=${invite}"
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Lunex invitation", uri))
                                    status = "Приглашение скопировано."
                                }) { Icon(Icons.Default.ContentCopy, "Копировать приглашение", tint = Color(0xFFCB91FF)) }
                                IconButton(onClick = {
                                    val uri = "lunex://join/${room.id}?token=${invite}"
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, uri)
                                    }
                                    context.startActivity(Intent.createChooser(share, "Пригласить в Lunex"))
                                }) { Icon(Icons.Default.Share, "Отправить приглашение", tint = Color(0xFFCB91FF)) }
                                IconButton(onClick = { invite = "" }) { Icon(Icons.Default.Close, "Скрыть приглашение", tint = Color(0xFFAAA0BC)) }
                            }
                        }
                    }
                    if (roomPeers.isNotEmpty()) {
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), color = Color(0xFF15121D), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
                                Text("Участники", color = Color(0xFFAAA0BC), fontSize = 10.sp)
                                roomPeers.take(4).forEach { peer ->
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(peer.name, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { callMode = "audio"; withCallPermissions { callManager.startOutgoing(room.id, peer.id, "audio") } }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Call, "Позвонить", tint = Color(0xFFCB91FF)) }
                                        IconButton(onClick = { callMode = "video"; withCallPermissions { callManager.startOutgoing(room.id, peer.id, "video") } }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Videocam, "Видеозвонок", tint = Color(0xFFCB91FF)) }
                                    }
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(localMessages, key = { it.id }) { msg ->
                            val own = msg.senderName == activeName
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
                                Column(Modifier.widthIn(max = 310.dp).background(if (own) Color(0xFF57317B) else Color(0xFF211E2B), shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp, bottomStart = if (own) 17.dp else 5.dp, bottomEnd = if (own) 5.dp else 17.dp)).padding(horizontal = 13.dp, vertical = 9.dp)) {
                                    if (!own) Text(msg.senderName, color = Color(0xFFCB91FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text(msg.body, color = Color.White, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Color(0xFF191426)).padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = store.inviteTokenForRoom(room.id) != null, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.AttachFile, "Прикрепить файл", tint = Color(0xFFCB91FF)) }
                        OutlinedTextField(value = chatDraft, onValueChange = { chatDraft = it }, placeholder = { Text("Сообщение…", color = Color(0xFFAAA0BC)) }, modifier = Modifier.weight(1f), maxLines = 4, shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                        IconButton(onClick = {
                            val text = chatDraft.trim()
                            if (text.isNotEmpty()) {
                                val roomToken = store.inviteTokenForRoom(room.id)
                                if (roomToken == null) status = "Сначала создай приглашение в эту комнату."
                                else { transport.send(room.id, activeName, text); chatDraft = ""; localMessages = store.messages(room.id) }
                            }
                        }, enabled = chatDraft.isNotBlank(), modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Send, "Отправить", tint = if (chatDraft.isNotBlank()) Color(0xFFCB91FF) else Color(0xFF62596E)) }
                    }
                    if (roomFiles.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 92.dp).background(Color(0xFF15121D)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            items(roomFiles.takeLast(3), key = { it.id }) { file ->
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachFile, null, tint = Color(0xFFCB91FF), modifier = Modifier.size(16.dp))
                                    Text(file.fileName, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                    TextButton(onClick = { runCatching {
                                        val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.files", java.io.File(file.localPath))
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, file.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Открыть файл"))
                                    }.onFailure { status = it.message ?: "Не удалось открыть файл." } }) { Text("Открыть") }
                                }
                            }
                        }
                    }
                }
            }
            if (status.isNotBlank()) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = Color(0xFF2B2138), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(status, color = Color(0xFFE7DDF2), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (!initialInvite.isNullOrBlank() && loggedIn && selected == null) {
                            TextButton(onClick = { inviteRetry++ }) { Text("Повторить", color = Color(0xFFCB91FF), fontSize = 11.sp) }
                        }
                        IconButton(onClick = { status = "" }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Закрыть сообщение", tint = Color(0xFFAAA0BC)) }
                    }
                }
            }
        }
        if (shownStatus.isNotBlank()) Text(shownStatus, color = Color(0xFFFF8D9B), fontSize = 12.sp)
    }
    if (loggedIn && callState !in setOf("idle", "ended", "rejected", "failed")) {
        val isIncoming = incomingCall != null
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (isIncoming) "Входящий ${if (callMode == "video") "видеозвонок" else "звонок"}" else if (callMode == "video") "Видеозвонок" else "Голосовой звонок") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        incomingCall?.optJSONObject("caller")?.optString("displayName").orEmpty()
                            .ifBlank { roomPeers.firstOrNull { it.id == callManager.currentPeerId() }?.name ?: "Собеседник" },
                        color = Color(0xFFAAA0BC)
                    )
                    Text(when (callState) {
                        "calling" -> "Вызов…"
                        "incoming" -> "Входящий вызов"
                        "connecting" -> "Подключаемся…"
                        "connected" -> "Соединение установлено"
                        else -> "Подготовка звонка…"
                    })
                    Text("Сигнализация идёт через узлы комнаты; участники комнаты могут видеть данные установления соединения. Связь зависит от NAT и доступности STUN/TURN.", color = Color(0xFFAAA0BC), fontSize = 10.sp)
                    if (callMode == "video") {
                        localVideoTrack?.let { Text("Твоё видео", fontWeight = FontWeight.SemiBold); CallVideoPreview(it, callManager, true) }
                        remoteVideoTrack?.let { Text("Видео собеседника", fontWeight = FontWeight.SemiBold); CallVideoPreview(it, callManager, false) }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isIncoming) {
                        TextButton(onClick = {
                            withCallPermissions {
                                incomingCall = null
                                callState = "connecting"
                                callManager.acceptIncoming()
                            }
                        }) { Text("Принять") }
                        TextButton(onClick = { callManager.rejectIncoming() }) { Text("Отклонить", color = Color(0xFFFF7188)) }
                    } else {
                        if (callState == "connected") {
                            TextButton(onClick = { micEnabled = !micEnabled; callManager.setMicrophoneEnabled(micEnabled) }) {
                                Icon(if (micEnabled) Icons.Default.Mic else Icons.Default.MicOff, null)
                                Text("Микрофон")
                            }
                            if (callMode == "video") {
                                TextButton(onClick = { cameraEnabled = !cameraEnabled; callManager.setCameraEnabled(cameraEnabled) }) {
                                    Icon(if (cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff, null)
                                    Text("Камера")
                                }
                            }
                        }
                        TextButton(onClick = { callManager.endCall() }) {
                            Icon(Icons.Default.CallEnd, null, tint = Color(0xFFFF7188))
                            Text("Завершить", color = Color(0xFFFF7188))
                        }
                    }
                }
            }
        )
    }
}
