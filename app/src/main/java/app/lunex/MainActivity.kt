package app.lunex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF0D0B16)
private val Panel = Color(0xFF191426)
private val Panel2 = Color(0xFF241B35)
private val Purple = Color(0xFFB56BFF)
private val Pink = Color(0xFFFF73D1)
private val Muted = Color(0xFFAAA0BC)
private val White = Color(0xFFF8F3FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LunexApp() }
    }
}

private enum class Tab(val label: String) {
    Chats("Чаты"), Calls("Звонки"), Communities("Серверы"),
    Alerts("Уведомления"), Settings("Ещё")
}

private data class Chat(val name: String, val message: String, val time: String, val unread: Int, val avatar: String)

@Composable
private fun LunexApp() {
    var tab by remember { mutableStateOf(Tab.Chats) }
    var selectedChat by remember { mutableStateOf<Chat?>(null) }
    var accent by remember { mutableStateOf(Purple) }
    var animations by remember { mutableStateOf(true) }
    var transparency by remember { mutableFloatStateOf(0.72f) }
    val chats = listOf(
        Chat("Luna", "Смотри, какой красивый фон ✨", "15:24", 2, "L"),
        Chat("The Void", "Kira: вечером играем?", "15:02", 12, "V"),
        Chat("Аниме чат", "Новое видео", "14:48", 0, "A"),
        Chat("Max", "Отправил файл", "13:30", 0, "M"),
        Chat("Работа", "Документ готов", "Вчера", 0, "Р")
    )

    MaterialTheme(colorScheme = darkColorScheme(
        primary = accent, secondary = Pink, background = Night,
        surface = Panel, onBackground = White, onSurface = White
    )) {
        Surface(Modifier.fillMaxSize(), color = Night) {
            Column(Modifier.fillMaxSize()) {
                if (selectedChat != null) {
                    ChatScreen(selectedChat!!, accent, onBack = { selectedChat = null })
                } else {
                    Header(tab)
                    when (tab) {
                        Tab.Chats -> ChatsScreen(chats, accent) { selectedChat = it }
                        Tab.Calls -> PlaceholderScreen("Звонки", "Голосовые и видеозвонки появятся на следующем этапе.", Icons.Default.Call)
                        Tab.Communities -> CommunitiesScreen(accent)
                        Tab.Alerts -> PlaceholderScreen("Уведомления", "Здесь будут упоминания, ответы и события серверов.", Icons.Default.Notifications)
                        Tab.Settings -> SettingsScreen(
                            accent = accent,
                            onAccent = { accent = it },
                            animations = animations,
                            onAnimations = { animations = it },
                            transparency = transparency,
                            onTransparency = { transparency = it }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationBar(containerColor = Panel) {
                        Tab.values().forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    Icon(when (item) {
                                        Tab.Chats -> Icons.Default.Chat
                                        Tab.Calls -> Icons.Default.Call
                                        Tab.Communities -> Icons.Default.Groups
                                        Tab.Alerts -> Icons.Default.Notifications
                                        Tab.Settings -> Icons.Default.Tune
                                    }, contentDescription = item.label)
                                },
                                label = { Text(item.label, fontSize = 10.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = accent,
                                    selectedTextColor = accent,
                                    indicatorColor = Panel2,
                                    unselectedIconColor = Muted,
                                    unselectedTextColor = Muted
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(tab: Tab) {
    Row(
        Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Panel2),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.DarkMode, null, tint = Purple) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("LUNEX", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 18.sp)
            Text(tab.label, color = Muted, fontSize = 12.sp)
        }
        IconButton(onClick = {}) { Icon(Icons.Default.Search, "Поиск", tint = Muted) }
        IconButton(onClick = {}) { Icon(Icons.Default.Add, "Создать", tint = Purple) }
    }
}

@Composable
private fun ChatsScreen(chats: List<Chat>, accent: Color, onChat: (Chat) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Твои разговоры", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Личные чаты и группы в одном месте", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Все", "Личные", "Группы", "Каналы").forEachIndexed { index, label ->
                Surface(color = if (index == 0) accent.copy(alpha = .2f) else Panel, shape = CircleShape) {
                    Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = if (index == 0) accent else Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(chats) { chat ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel)
                        .clickable { onChat(chat) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(chat.avatar, accent, 48)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chat.name, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(chat.message, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(chat.time, color = Muted, fontSize = 10.sp)
                        if (chat.unread > 0) {
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.clip(CircleShape).background(accent).padding(horizontal = 7.dp, vertical = 3.dp)) {
                                Text(chat.unread.toString(), color = Night, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(chat: Chat, accent: Color, onBack: () -> Unit) {
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Avatar(chat.avatar, accent, 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(chat.name, fontWeight = FontWeight.Bold)
                Text("демо-переписка", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = {}) { Icon(Icons.Default.Call, "Звонок", tint = accent) }
            IconButton(onClick = {}) { Icon(Icons.Default.VideoCall, "Видео", tint = accent) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Сегодня", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            MessageBubble("Привет! Добро пожаловать в Lunex 🌙", false, accent)
            MessageBubble("Здесь можно будет настроить фон именно этого чата.", true, accent)
            MessageBubble("И цвета, шрифты, прозрачность и анимации — тоже!", false, accent)
        }
        Row(Modifier.fillMaxWidth().background(Panel).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {}) { Icon(Icons.Default.AddCircle, "Вложение", tint = accent) }
            OutlinedTextField(
                value = message, onValueChange = { message = it },
                modifier = Modifier.weight(1f), placeholder = { Text("Сообщение…", color = Muted) },
                shape = RoundedCornerShape(22.dp), singleLine = true
            )
            IconButton(onClick = { message = "" }) { Icon(Icons.Default.Send, "Отправить", tint = accent) }
        }
    }
}

@Composable
private fun MessageBubble(text: String, own: Boolean, accent: Color) {
    Surface(
        modifier = Modifier.widthIn(max = 300.dp).alignIf(own),
        color = if (own) accent.copy(alpha = .23f) else Panel,
        shape = RoundedCornerShape(18.dp)
    ) { Text(text, Modifier.padding(13.dp), color = White, fontSize = 14.sp) }
}

private fun Modifier.alignIf(own: Boolean): Modifier = this.then(
    if (own) Modifier.fillMaxWidth().wrapContentWidth(Alignment.End)
    else Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start)
)

@Composable
private fun CommunitiesScreen(accent: Color) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Сообщества", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Серверы, каналы и люди по интересам", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = accent), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Создать сообщество")
        }
        Spacer(Modifier.height(12.dp))
        listOf("The Void" to "Игры · аниме · общение", "Anime Paradise" to "Аниме и творчество", "Gaming Hub" to "Ищем команду", "Study & Work" to "Учёба и работа").forEachIndexed { i, pair ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(pair.first.take(1), listOf(accent, Pink, Color(0xFF66D9E8), Color(0xFF8DDB9B))[i], 46)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pair.first, fontWeight = FontWeight.SemiBold)
                    Text(pair.second, color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    accent: Color, onAccent: (Color) -> Unit,
    animations: Boolean, onAnimations: (Boolean) -> Unit,
    transparency: Float, onTransparency: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Настрой Lunex", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Твой интерфейс — твои правила", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Text("Цвет акцента", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Purple, Pink, Color(0xFF58C7FF), Color(0xFF6DE3A5), Color(0xFFFFB86B), Color(0xFFFF6B7A)).forEach { color ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(color)
                    .clickable { onAccent(color) }, contentAlignment = Alignment.Center) {
                    if (accent == color) Icon(Icons.Default.Check, null, tint = Night)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Прозрачность панелей", fontWeight = FontWeight.SemiBold)
                Slider(value = transparency, onValueChange = onTransparency, valueRange = .25f..1f)
                Text("${(transparency * 100).toInt()}%", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(color = Panel, shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Анимации интерфейса", fontWeight = FontWeight.SemiBold)
                    Text("Эффекты переходов и появления", color = Muted, fontSize = 12.sp)
                }
                Switch(checked = animations, onCheckedChange = onAnimations)
            }
        }
        Spacer(Modifier.height(10.dp))
        listOf("Фон и обои" to Icons.Default.Wallpaper, "Шрифты и размер текста" to Icons.Default.TextFields,
            "Форма сообщений" to Icons.Default.ChatBubble, "Уведомления" to Icons.Default.Notifications,
            "Импорт и экспорт темы" to Icons.Default.Share).forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(Panel).clickable { }.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(item.second, null, tint = accent)
                Spacer(Modifier.width(12.dp))
                Text(item.first, Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, tint = Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Демо-настройки пока действуют только в памяти и сбрасываются после перезапуска.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(70.dp))
        Icon(icon, null, tint = Purple, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(18.dp))
        Text("Раздел запланирован", color = Purple, fontSize = 12.sp)
    }
}

@Composable
private fun Avatar(label: String, color: Color, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = .22f)),
        contentAlignment = Alignment.Center) {
        Text(label.take(1), color = color, fontWeight = FontWeight.Bold, fontSize = (size / 2.6).sp)
    }
}
