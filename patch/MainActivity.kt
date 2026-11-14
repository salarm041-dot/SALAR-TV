package com.salar.tv

import android.net.Uri
import android.os.Bundle
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

private val Gold = Color(0xFFD6AD62)
private val Background = Color(0xFF0B0D13)
private val Panel = Color(0xFF171B26)
private val TextMain = Color(0xFFF5F1E8)

data class Channel(
    val name: String,
    val url: String,
    val country: String = "سایر",
    val kind: String = "تلویزیون",
    val favorite: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SalarTvApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalarTvApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("salar_tv", 0) }
    val channels = remember {
        mutableStateListOf<Channel>().apply {
            val raw = prefs.getString("channels", "[]") ?: "[]"
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Channel(o.optString("name"), o.optString("url"), o.optString("country"),
                        o.optString("kind"), o.optBoolean("favorite")))
                }
            }
        }
    }
    fun saveChannels() {
        val arr = JSONArray()
        channels.forEach { c ->
            arr.put(JSONObject().put("name", c.name).put("url", c.url)
                .put("country", c.country).put("kind", c.kind).put("favorite", c.favorite))
        }
        prefs.edit().putString("channels", arr.toString()).apply()
    }

    // Seed the bundled public playlist once. Existing user channels are preserved.
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("built_in_seeded", false)) {
            val bundled = runCatching {
                context.assets.open("channels.m3u").bufferedReader().use { parseM3u(it.readText()) }
            }.getOrDefault(emptyList())
            val existing = channels.map { it.url }.toSet()
            channels.addAll(bundled.filter { it.url !in existing })
            saveChannels()
            prefs.edit().putBoolean("built_in_seeded", true).apply()
        }
    }

    var page by remember { mutableStateOf("خانه") }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var schedules by remember { mutableStateOf(prefs.getString("schedules", "") ?: "") }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val parsed = parseM3u(content)
            channels.addAll(parsed)
            saveChannels()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Gold, background = Background, surface = Panel,
            onPrimary = Color.Black, onBackground = TextMain, onSurface = TextMain
        )
    ) {
        Scaffold(
            containerColor = Background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("SALAR TV", color = Gold, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("تلویزیون و رادیوی جهان", color = Color.LightGray, fontSize = 10.sp)
                        }
                    },
                    actions = {
                        IconButton(onClick = { page = "افزودن منابع" }) {
                            Icon(Icons.Default.AddLink, "افزودن لینک", tint = Gold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Gold) {
                    listOf("خانه" to Icons.Default.Home, "تلویزیون" to Icons.Default.Tv,
                        "رادیو" to Icons.Default.Radio, "علاقه‌مندی‌ها" to Icons.Default.Favorite,
                        "بیشتر" to Icons.Default.MoreHoriz).forEach { (label, icon) ->
                        NavigationBarItem(
                            selected = page == label,
                            onClick = { page = label },
                            icon = { Icon(icon, label) },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Gold, selectedTextColor = Gold,
                                indicatorColor = Color(0xFF343020), unselectedIconColor = Color.Gray
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                if (selected != null) {
                    TextButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, null); Text("بازگشت") }
                    Text(selected!!.name, color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    )
                    LaunchedEffect(selected!!.url) {
                        player.setMediaItem(MediaItem.fromUri(selected!!.url))
                        player.prepare()
                        player.playWhenReady = true
                    }
                    Text("در صورت پشتیبانی منبع، پخش زنده در این قسمت نمایش داده می‌شود.", color = Color.LightGray, fontSize = 12.sp)
                } else when (page) {
                    "افزودن منابع" -> AddSourcesPage(
                        onAdd = { c -> channels.add(c); saveChannels(); page = "خانه" },
                        onImport = { m3uLauncher.launch(arrayOf("audio/*", "application/*", "text/*")) }
                    )
                    "بیشتر" -> MorePage(
                        onSchedule = { showSchedule = true },
                        onAbout = { page = "درباره ما" },
                        onSources = { page = "افزودن منابع" }
                    )
                    "درباره ما" -> AboutPage()
                    "تلویزیون" -> ChannelList(channels.filter { it.kind == "تلویزیون" }, onSelect = { selected = it },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) })
                    "رادیو" -> ChannelList(channels.filter { it.kind == "رادیو" }, onSelect = { selected = it },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) })
                    "علاقه‌مندی‌ها" -> ChannelList(channels.filter { it.favorite }, onSelect = { selected = it },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) })
                    else -> HomePage(channels, onSelect = { selected = it }, onAdd = { page = "افزودن منابع" })
                }
            }
        }
    }

    if (showSchedule) {
        var title by remember { mutableStateOf("") }
        var streamUrl by remember { mutableStateOf("") }
        var dateTime by remember { mutableStateOf("") }
        var duration by remember { mutableStateOf("60") }
        var scheduleError by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSchedule = false },
            title = { Text("زمان‌بندی ضبط", color = Gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("نام شبکه یا برنامه")
                    OutlinedTextField(title, { title = it }, placeholder = { Text("مثلاً خبر") })
                    OutlinedTextField(streamUrl, { streamUrl = it }, label = { Text("لینک مستقیم HLS / M3U8") },
                        placeholder = { Text("https://server/live/index.m3u8") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("زمان شروع (نمونه: 2026-12-01 20:30)")
                    OutlinedTextField(dateTime, { dateTime = it }, placeholder = { Text("YYYY-MM-DD HH:mm") }, singleLine = true)
                    OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(3) },
                        label = { Text("مدت ضبط به دقیقه (۱ تا ۳۶۰)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (scheduleError.isNotBlank()) Text(scheduleError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Text("برای ضبط خودکار، لینک HLS قابل دسترس و اتصال اینترنت پایدار لازم است.",
                        color = Color.Gray, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val start = LocalDateTime.parse(dateTime.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        val startMillis = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val minutes = duration.toIntOrNull()?.coerceIn(1, 360) ?: error("مدت ضبط را بین ۱ تا ۳۶۰ دقیقه وارد کنید.")
                        require(title.isNotBlank()) { "نام برنامه را وارد کنید." }
                        require(streamUrl.startsWith("https://") || streamUrl.startsWith("http://")) { "لینک معتبر HLS وارد کنید." }
                        require(startMillis > System.currentTimeMillis()) { "زمان شروع باید در آینده باشد." }
                        val requestCode = (title + streamUrl + startMillis).hashCode()
                        val alarmIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                            putExtra("name", title.trim())
                            putExtra("url", streamUrl.trim())
                            putExtra("duration", minutes)
                        }
                        val pending = PendingIntent.getBroadcast(context, requestCode, alarmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val alarm = context.getSystemService(AlarmManager::class.java)
                        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, pending)
                        schedules = (schedules + "\n$title | $dateTime | $minutes دقیقه").trim()
                        prefs.edit().putString("schedules", schedules).apply()
                        showSchedule = false
                    } catch (e: Exception) {
                        scheduleError = e.message ?: "اطلاعات زمان‌بندی معتبر نیست."
                    }
                }) { Text("ذخیره", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { showSchedule = false }) { Text("انصراف") } }
        )
    }
}

@Composable
fun HomePage(channels: List<Channel>, onSelect: (Channel) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("دنیای تلویزیون و رادیو", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("ایران • ترکیه • جمهوری آذربایجان • جهان", color = Color.LightGray)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
                    Icon(Icons.Default.Add, null); Text("افزودن شبکه یا سرور", color = Color.Black)
                }
            }
        }
        Text("شبکه‌های شما", color = Gold, fontWeight = FontWeight.Bold)
        if (channels.isEmpty()) {
            Text("هنوز شبکه‌ای اضافه نشده است. از بخش افزودن منابع، لینک مجاز یا فایل M3U وارد کنید.",
                color = Color.LightGray)
        } else ChannelList(channels, onSelect, {})
    }
}

@Composable
fun ChannelList(channels: List<Channel>, onSelect: (Channel) -> Unit, onFavorite: (Channel) -> Unit) {
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("موردی برای نمایش وجود ندارد.", color = Color.LightGray)
        }
    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(channels) { c ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(c) },
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (c.kind == "رادیو") Icons.Default.Radio else Icons.Default.Tv, null, tint = Gold)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text("${c.country} • ${c.kind}", color = Color.Gray, fontSize = 11.sp)
                    }
                    IconButton(onClick = { onFavorite(c) }) {
                        Icon(Icons.Default.Favorite, null, tint = if (c.favorite) Gold else Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun AddSourcesPage(onAdd: (Channel) -> Unit, onImport: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("ایران") }
    var kind by remember { mutableStateOf("تلویزیون") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("مدیریت منابع پخش", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(name, { name = it }, label = { Text("نام شبکه / ایستگاه") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("آدرس پخش یا سرور") },
            placeholder = { Text("https://example.com/live.m3u8") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
        Text("کشور", color = Gold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ایران", "ترکیه", "آذربایجان", "سایر").forEach {
                FilterChip(selected = country == it, onClick = { country = it }, label = { Text(it) })
            }
        }
        Text("نوع منبع", color = Gold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == "تلویزیون", onClick = { kind = "تلویزیون" }, label = { Text("تلویزیون") })
            FilterChip(selected = kind == "رادیو", onClick = { kind = "رادیو" }, label = { Text("رادیو") })
        }
        Button(
            onClick = {
                if (name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
                    onAdd(Channel(name.trim(), url.trim(), country, kind))
                    name = ""; url = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Gold)
        ) { Text("ذخیره شبکه", color = Color.Black) }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("واردکردن فایل M3U / M3U8")
        }
        Text("فقط منابع رسمی یا لینک‌هایی را وارد کنید که مجوز استفاده از آن‌ها را دارید.",
            color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun MorePage(onSchedule: () -> Unit, onAbout: () -> Unit, onSources: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("امکانات بیشتر", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        MoreButton("افزودن لینک و سرور", Icons.Default.AddLink, onSources)
        MoreButton("زمان‌بندی ضبط", Icons.Default.CalendarMonth, onSchedule)
        MoreButton("درباره سازنده", Icons.Default.Info, onAbout)
        Text("زبان‌های هدف: فارسی، ترکی استانبولی، ترکی آذربایجانی و انگلیسی",
            color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
fun MoreButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { action() }, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Gold); Spacer(Modifier.width(12.dp)); Text(label, color = TextMain)
        }
    }
}

@Composable
fun AboutPage() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Tv, null, tint = Gold, modifier = Modifier.size(64.dp))
        Text("SALAR TV", color = Gold, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("سازنده و مالک: سید سالار موسوی ملکی", color = TextMain)
        Text("ایمیل: Salar.m041@gmail.com", color = Color.LightGray)
        Text("اینستاگرام: Salar_mousavi_41", color = Color.LightGray)
        Text("نسخه 1.0.0", color = Color.Gray)
    }
}

private fun toggleFavorite(list: MutableList<Channel>, channel: Channel, save: () -> Unit) {
    val i = list.indexOfFirst { it.url == channel.url }
    if (i >= 0) list[i] = list[i].copy(favorite = !list[i].favorite)
    save()
}

private fun parseM3u(text: String): List<Channel> {
    val result = mutableListOf<Channel>()
    var title = "شبکه بدون نام"
    var country = "سایر"
    var kind = "تلویزیون"
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            title = line.substringAfter(",", "شبکه بدون نام").trim().ifBlank { "شبکه بدون نام" }
            val lower = line.lowercase()
            val group = Regex("group-title=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
            val meta = "$lower $group"
            country = when {
                "azerbaijan" in meta || "azərbaycan" in meta || "آذربایجان" in meta || "az/" in meta -> "آذربایجان"
                "turkey" in meta || "turkiye" in meta || "türkiye" in meta || "ترکیه" in meta || "tr/" in meta -> "ترکیه"
                "iran" in meta || "ایران" in meta || "ir/" in meta -> "ایران"
                else -> "سایر"
            }
            kind = if ("radio" in meta || "رادیو" in meta) "رادیو" else "تلویزیون"
        } else if (line.startsWith("http://") || line.startsWith("https://")) {
            result.add(Channel(title, line, country, kind))
            title = "شبکه بدون نام"; country = "سایر"; kind = "تلویزیون"
        }
    }
    return result.distinctBy { it.url }
}
