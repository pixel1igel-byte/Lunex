package app.lunex

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Wifi
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

@Composable
internal fun LocalNodeScreen(onSwitchToCloud: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LocalNodeRuntime.store(context) }
    val scope = rememberCoroutineScope()
    val nodeState by LocalNodeRuntime.state.collectAsState()
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(false) }
    var activeName by remember { mutableStateOf("") }
    var rooms by remember { mutableStateOf(store.rooms()) }
    var roomName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LocalRoom?>(null) }
    var invite by remember { mutableStateOf("") }
    var inviteToJoin by remember { mutableStateOf("") }
    var peerEndpoint by remember { mutableStateOf("") }
    var chatDraft by remember { mutableStateOf("") }
    var localMessages by remember { mutableStateOf(emptyList<LocalNodeMessage>()) }
    var roomFiles by remember { mutableStateOf(emptyList<LocalNodeFile>()) }
    var settingsOpen by remember { mutableStateOf(false) }
    var connectPanelOpen by remember { mutableStateOf(false) }
    val peers = nodeState.peers
    val transport = remember { LocalNodeRuntime.ensure(context) }
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
    val shownStatus = status.ifBlank { nodeState.status }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0B16))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF191426)).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFFB56BFF), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("L", color = Color(0xFF0D0B16), fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Lunex", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(if (loggedIn) "Вы вошли как $activeName" else "Личные сообщения и комнаты", color = Color(0xFFAAA0BC), fontSize = 12.sp)
            }
            if (loggedIn) {
                IconButton(onClick = { connectPanelOpen = !connectPanelOpen }) {
                    Icon(Icons.Default.Add, contentDescription = "Подключить комнату", tint = Color(0xFFB56BFF))
                }
                IconButton(onClick = { settingsOpen = !settingsOpen }) {
                    Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color(0xFFB56BFF))
                }
            }
        }
        if (settingsOpen && loggedIn) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), color = Color(0xFF191426), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Настройки устройства", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Узел Lunex работает автоматически", color = Color(0xFFAAA0BC), fontSize = 13.sp)
                    Text("ID: " + store.serverId(), color = Color(0xFFAAA0BC), fontSize = 11.sp)
                    Text("TCP-порт: 45873", color = Color(0xFFAAA0BC), fontSize = 11.sp)
                    Text(localEndpoints.joinToString("\n").ifBlank { "Адреса устройства пока недоступны" }, color = Color.White, fontSize = 11.sp)
                    if (localEndpoints.isNotEmpty()) TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Lunex node endpoints", localEndpoints.joinToString("\n")))
                        status = "Адреса устройства скопированы."
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = null); Text("Скопировать адреса устройства") }
                    Text("Подключённых устройств: ${peers.size}", color = Color(0xFFAAA0BC), fontSize = 12.sp)
                    peers.forEach { peer -> Text("${peer.name} · ${peer.host.hostAddress}:${peer.port}", color = Color(0xFFAAA0BC), fontSize = 11.sp) }
                    TextButton(onClick = { settingsOpen = false }) { Text("Готово") }
                }
            }
        }
        if (!loggedIn) {
            Spacer(Modifier.height(20.dp))
            Text(if (isRegister) "Создать аккаунт на телефоне" else "Войти в локальный аккаунт", color = Color.White, fontSize = 18.sp)
            OutlinedTextField(username, { username = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (isRegister) OutlinedTextField(displayName, { displayName = it }, label = { Text("Отображаемое имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Пароль (10+ символов)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { try {
                val id = withContext(Dispatchers.IO) { if (isRegister) store.register(username, displayName, password.toCharArray()) else store.login(username, password.toCharArray()) }
                activeName = id.displayName; loggedIn = true; password = ""; rooms = store.rooms(); status = ""
            } catch (e: Exception) { status = e.message ?: "Ошибка локального аккаунта" } } }, modifier = Modifier.fillMaxWidth()) { Text(if (isRegister) "Создать локальный аккаунт" else "Войти") }
            TextButton(onClick = { isRegister = !isRegister; status = "" }) { Text(if (isRegister) "Уже есть аккаунт? Войти" else "Создать аккаунт") }
            Text("Учётная запись хранится в базе SQLite на этом телефоне; пароль проверяется локально.", color = Color(0xFFAAA0BC), fontSize = 12.sp)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = Color(0xFFB56BFF))
                Text("  Чаты", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${rooms.size}", color = Color(0xFFAAA0BC), fontSize = 13.sp)
            }
            if (connectPanelOpen) {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp), color = Color(0xFF191426), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Подключить устройство или комнату", color = Color.White, fontWeight = FontWeight.Bold)
                        OutlinedTextField(peerEndpoint, { peerEndpoint = it }, label = { Text("Адрес устройства (IP:порт)") }, supportingText = { Text("Для прямого соединения адрес должен быть доступен из интернета.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                    require(token.isNotBlank()) { "Вставь код приглашения комнаты." }
                    status = "Подключаюсь к узлу $endpoint…"
                    transport.join(peer, token) { ok, message ->
                        status = message
                        if (ok) {
                            rooms = store.rooms()
                            selected = rooms.lastOrNull()
                            selected?.let { localMessages = store.messages(it.id); roomFiles = store.files(it.id) }
                            peerEndpoint = ""
                            inviteToJoin = ""
                        }
                    }
                } catch (e: Exception) { status = e.message ?: "Неверный адрес узла." }
            }, modifier = Modifier.fillMaxWidth(), enabled = peerEndpoint.isNotBlank() && inviteToJoin.isNotBlank()) { Text("Подключиться напрямую") }
                        OutlinedTextField(inviteToJoin, { inviteToJoin = it }, label = { Text("Код/ссылка приглашения комнаты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val raw = inviteToJoin.trim()
                val token = Regex("[?&]token=([^&]+)").find(raw)?.groupValues?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: raw
                if (peers.isEmpty()) {
                    status = "Другие узлы пока не обнаружены. Для текущей версии оба телефона должны быть в одной Wi-Fi сети."
                } else {
                    status = "Пробую подключиться к ${peers.size} узлам…"
                    fun tryPeer(index: Int) {
                        if (index >= peers.size) {
                            status = "Не удалось найти узел, которому принадлежит это приглашение."
                            return
                        }
                        transport.join(peers[index], token) { ok, message ->
                            if (ok) {
                                status = message
                                rooms = store.rooms()
                                selected = rooms.lastOrNull()
                                selected?.let { localMessages = store.messages(it.id); roomFiles = store.files(it.id) }
                                inviteToJoin = ""
                            } else tryPeer(index + 1)
                        }
                    }
                    tryPeer(0)
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = inviteToJoin.isNotBlank()) { Text("Подключиться к приглашению") }
                        OutlinedTextField(roomName, { roomName = it }, label = { Text("Название новой комнаты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { try { selected = store.createRoom(roomName); rooms = store.rooms(); roomName = ""; invite = ""; status = ""; connectPanelOpen = false } catch (e: Exception) { status = e.message ?: "Ошибка" } }, modifier = Modifier.fillMaxWidth()) { Text("Создать комнату") }
                        TextButton(onClick = { connectPanelOpen = false }) { Text("Закрыть") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) { items(rooms, key = { it.id }) { room ->
                Row(Modifier.fillMaxWidth().clickable { selected = room; invite = ""; localMessages = store.messages(room.id); roomFiles = store.files(room.id) }.padding(8.dp)) {
                    Column(Modifier.weight(1f)) { Text(room.name, color = Color.White); Text("ID: " + room.id, color = Color(0xFFAAA0BC), fontSize = 10.sp) }
                    TextButton(onClick = { try { selected = room; invite = store.createInvite(room.id); transport.syncAll(); status = "" } catch (e: Exception) { status = e.message ?: "Ошибка" } }) { Text("Пригласить") }
                }
            } }
            selected?.let { room ->
                if (invite.isNotBlank()) {
                    val inviteUri = "lunex://join/" + room.id + "?token=" + invite
                    Text("Приглашение", color = Color(0xFFAAA0BC), fontSize = 11.sp)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(inviteUri, color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Lunex invitation", inviteUri))
                            status = "Приглашение скопировано."
                        }) { Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать приглашение", tint = Color(0xFFB56BFF)) }
                    }
                } else {
                    Text("Нажми «Пригласить», чтобы создать ссылку для этой комнаты.", color = Color(0xFFAAA0BC), fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Файлы комнаты", color = Color.White, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = store.inviteTokenForRoom(room.id) != null
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null)
                        Text("Прикрепить (до 25 МБ)")
                    }
                }
                if (roomFiles.isEmpty()) {
                    Text("Пока нет файлов в этой комнате.", color = Color(0xFFAAA0BC), fontSize = 11.sp)
                } else {
                    roomFiles.takeLast(5).forEach { file ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.fileName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("${(file.sizeBytes + 1023) / 1024} КБ · ${file.senderName}", color = Color(0xFFAAA0BC), fontSize = 10.sp)
                            }
                            TextButton(onClick = {
                                try {
                                    val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.files", java.io.File(file.localPath))
                                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, file.mimeType)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(intent, "Открыть файл"))
                                } catch (e: Exception) {
                                    status = e.message ?: "Не удалось открыть файл."
                                }
                            }) { Text("Открыть") }
                        }
                    }
                }
                Text("Сообщения комнаты", color = Color.White, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 220.dp)) {
                    items(localMessages, key = { it.id }) { msg ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(msg.senderName, color = Color(0xFFB56BFF), fontSize = 11.sp)
                            Text(msg.body, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(chatDraft, { chatDraft = it }, label = { Text("Сообщение") }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val roomToken = store.inviteTokenForRoom(room.id)
                        if (roomToken == null) status = "Для этой комнаты нет действующего ключа приглашения."
                        else { transport.send(room.id, activeName, chatDraft); chatDraft = ""; localMessages = store.messages(room.id) }
                    }, enabled = chatDraft.isNotBlank()) { Text("Отпр.") }
                }
            }
            TextButton(onClick = { loggedIn = false; isRegister = false; selected = null; invite = "" }) { Text("Выйти") }
        }
        if (shownStatus.isNotBlank()) Text(shownStatus, color = Color(0xFFFF8D9B), fontSize = 12.sp)
    }
}
