package app.lunex

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
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
internal fun LocalNodeScreen() {
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
            OutlinedTextField(roomName, { roomName = it }, label = { Text("Название комнаты") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { try { selected = store.createRoom(roomName); rooms = store.rooms(); roomName = ""; invite = ""; status = "" } catch (e: Exception) { status = e.message ?: "Ошибка" } }, modifier = Modifier.fillMaxWidth()) { Text("Создать комнату") }
            LazyColumn(Modifier.weight(1f)) { items(rooms, key = { it.id }) { room ->
                Row(Modifier.fillMaxWidth().clickable { selected = room; invite = "" }.padding(8.dp)) {
                    Column(Modifier.weight(1f)) { Text(room.name, color = Color.White); Text("ID: " + room.id, color = Color(0xFFAAA0BC), fontSize = 10.sp) }
                    TextButton(onClick = { try { selected = room; invite = store.createInvite(room.id); status = "" } catch (e: Exception) { status = e.message ?: "Ошибка" } }) { Text("Пригласить") }
                }
            } }
            selected?.let { Text("Приглашение: lunex://join/" + it.id + "?token=" + invite, color = Color.White, fontSize = 11.sp) }
            TextButton(onClick = { loggedIn = false; isRegister = false; selected = null; invite = "" }) { Text("Выйти") }
        }
        if (status.isNotBlank()) Text(status, color = Color(0xFFFF8D9B), fontSize = 12.sp)
    }
}
