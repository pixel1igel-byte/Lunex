package app.lunex

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
    val store = remember { LocalNodeStore(context) }
    val scope = rememberCoroutineScope()
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
    var peers by remember { mutableStateOf(emptyList<LanPeer>()) }
    var localMessages by remember { mutableStateOf(emptyList<LocalNodeMessage>()) }
    val transport = remember(store) {
        LocalNodeTransport(context, store,
            onPeersChanged = { peers = it },
            onMessage = { msg -> if (selected?.id == msg.roomId) localMessages = store.messages(msg.roomId) },
            onStatus = { status = it }
        )
    }
    DisposableEffect(transport) {
        transport.start()
        onDispose { transport.stop() }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0D0B16)).padding(18.dp)) {
        Text("LUNEX · LOCAL NODE", color = Color(0xFFB56BFF), fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("ID узла: " + store.serverId(), color = Color(0xFFAAA0BC), fontSize = 12.sp)
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
            Text("Вошли как " + activeName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Устройства в этой Wi-Fi сети: ${peers.size}", color = Color(0xFFAAA0BC), fontSize = 12.sp)
            peers.forEach { peer -> Text("• ${peer.name}", color = Color(0xFFAAA0BC), fontSize = 12.sp) }
            OutlinedTextField(peerEndpoint, { peerEndpoint = it }, label = { Text("Адрес узла через интернет (IP:порт)") }, supportingText = { Text("Для прямого подключения узел должен быть доступен извне; может понадобиться проброс порта.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                            selected?.let { localMessages = store.messages(it.id) }
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
                                selected?.let { localMessages = store.messages(it.id) }
                                inviteToJoin = ""
                            } else tryPeer(index + 1)
                        }
                    }
                    tryPeer(0)
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = inviteToJoin.isNotBlank()) { Text("Подключиться к приглашению") }
            OutlinedTextField(roomName, { roomName = it }, label = { Text("Название комнаты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { try { selected = store.createRoom(roomName); rooms = store.rooms(); roomName = ""; invite = ""; status = "" } catch (e: Exception) { status = e.message ?: "Ошибка" } }, modifier = Modifier.fillMaxWidth()) { Text("Создать комнату") }
            LazyColumn(Modifier.weight(1f)) { items(rooms, key = { it.id }) { room ->
                Row(Modifier.fillMaxWidth().clickable { selected = room; invite = ""; localMessages = store.messages(room.id) }.padding(8.dp)) {
                    Column(Modifier.weight(1f)) { Text(room.name, color = Color.White); Text("ID: " + room.id, color = Color(0xFFAAA0BC), fontSize = 10.sp) }
                    TextButton(onClick = { try { selected = room; invite = store.createInvite(room.id); status = "" } catch (e: Exception) { status = e.message ?: "Ошибка" } }) { Text("Пригласить") }
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
                        else { transport.send(room.id, roomToken, activeName, chatDraft); chatDraft = ""; localMessages = store.messages(room.id) }
                    }, enabled = chatDraft.isNotBlank()) { Text("Отпр.") }
                }
            }
            TextButton(onClick = { loggedIn = false; isRegister = false; selected = null; invite = "" }) { Text("Выйти") }
        }
        if (status.isNotBlank()) Text(status, color = Color(0xFFFF8D9B), fontSize = 12.sp)
    }
}
