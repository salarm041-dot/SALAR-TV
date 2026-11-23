package com.salar.tv

import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import android.app.Activity
import java.io.File
import android.os.Bundle
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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

private var Gold by mutableStateOf(Color(0xFFD6AD62))
private var Background by mutableStateOf(Color(0xFF0B0D13))
private var Panel by mutableStateOf(Color(0xFF171B26))
private var TextMain by mutableStateOf(Color(0xFFF5F1E8))
private var SecondaryText by mutableStateOf(Color.LightGray)
private var MutedText by mutableStateOf(Color.Gray)

data class Channel(
    val name: String,
    val url: String,
    val country: String = "سایر",
    val kind: String = "تلویزیون",
    val favorite: Boolean = false,
    val logoUrl: String = "",
    val groupTitle: String = ""
)

data class ScheduledRecording(val name: String, val url: String, val startMillis: Long, val durationMinutes: Int) {
    val requestCode: Int get() = (name + url + startMillis).hashCode()
}

class MainActivity : ComponentActivity() {
    fun enterPlayerPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }.onFailure {
                android.widget.Toast.makeText(this, "حالت تصویر در تصویر در این دستگاه در دسترس نیست.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

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
                        o.optString("kind"), o.optBoolean("favorite"), o.optString("logoUrl"), o.optString("groupTitle")))
                }
            }
        }
    }
    fun saveChannels() {
        val arr = JSONArray()
        channels.forEach { c ->
            arr.put(JSONObject().put("name", c.name).put("url", c.url)
                .put("country", c.country).put("kind", c.kind).put("favorite", c.favorite)
                .put("logoUrl", c.logoUrl).put("groupTitle", c.groupTitle))
        }
        prefs.edit().putString("channels", arr.toString()).apply()
    }

    // Seed the bundled public playlist once. Existing user channels are preserved.
    LaunchedEffect(Unit) {
        val bundled = runCatching {
            context.assets.open("channels.m3u").bufferedReader().use { parseM3u(it.readText()) }
        }.getOrDefault(emptyList())
        val existing = channels.map { it.url }.toSet()
        channels.addAll(bundled.filter { it.url !in existing })
        saveChannels()
        prefs.edit().putBoolean("built_in_seeded", true).apply()
    }

    var page by remember { mutableStateOf("خانه") }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var editingChannel by remember { mutableStateOf<Channel?>(null) }
    val scheduledRecordings = remember {
        mutableStateListOf<ScheduledRecording>().apply {
            runCatching {
                val stored = JSONArray(prefs.getString("scheduled_recordings", "[]") ?: "[]")
                for (i in 0 until stored.length()) {
                    val item = stored.getJSONObject(i)
                    add(ScheduledRecording(item.optString("name"), item.optString("url"),
                        item.optLong("startMillis"), item.optInt("durationMinutes", 60)))
                }
            }
        }
    }
    fun saveScheduledRecordings() {
        val stored = JSONArray()
        scheduledRecordings.forEach { item ->
            stored.put(JSONObject().put("name", item.name).put("url", item.url)
                .put("startMillis", item.startMillis).put("durationMinutes", item.durationMinutes))
        }
        prefs.edit().putString("scheduled_recordings", stored.toString()).apply()
    }
    var brightness by remember { mutableFloatStateOf(0.75f) }
    var useExternalPlayer by remember { mutableStateOf(prefs.getBoolean("external_player_default", false)) }
    var rememberPlayerChoice by remember { mutableStateOf(false) }
    var schedules by remember { mutableStateOf(prefs.getString("schedules", "") ?: "") }
    var themeMode by remember { mutableStateOf(prefs.getString("theme_mode", "dark") ?: "dark") }
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) { "light" -> false; "system" -> systemDark; else -> true }

    SideEffect {
        val nextGold = if (useDarkTheme) Color(0xFFD6AD62) else Color(0xFF9A6818)
        val nextBackground = if (useDarkTheme) Color(0xFF0B0D13) else Color(0xFFF5F3EE)
        val nextPanel = if (useDarkTheme) Color(0xFF171B26) else Color(0xFFFFFFFF)
        val nextText = if (useDarkTheme) Color(0xFFF5F1E8) else Color(0xFF20232A)
        val nextSecondary = if (useDarkTheme) Color.LightGray else Color(0xFF555B66)
        val nextMuted = if (useDarkTheme) Color.Gray else Color(0xFF707782)
        if (Gold != nextGold) Gold = nextGold
        if (Background != nextBackground) Background = nextBackground
        if (Panel != nextPanel) Panel = nextPanel
        if (TextMain != nextText) TextMain = nextText
        if (SecondaryText != nextSecondary) SecondaryText = nextSecondary
        if (MutedText != nextMuted) MutedText = nextMuted
    }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var selectedM3uName by remember { mutableStateOf("") }
    val m3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "playlist.m3u"
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("خواندن فایل انتخاب‌شده ممکن نشد.")
            val parsed = parseM3u(content)
            require(parsed.isNotEmpty()) { "فایل انتخاب‌شده شبکه قابل‌شناسایی ندارد یا قالب M3U آن معتبر نیست." }
            val existing = channels.map { it.url }.toSet()
            val fresh = parsed.filter { it.url !in existing }
            channels.addAll(fresh)
            saveChannels()
            selectedM3uName = "$displayName • ${fresh.size} شبکه اضافه شد"
            android.widget.Toast.makeText(context, "فایل وارد شد؛ ${fresh.size} شبکه جدید اضافه شد.", android.widget.Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            android.widget.Toast.makeText(context, error.message ?: "خطا در واردکردن فایل M3U", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) runCatching {
            val backup = JSONObject()
                .put("format", "SALAR-TV-BACKUP")
                .put("version", 1)
                .put("channels", JSONArray(prefs.getString("channels", "[]") ?: "[]"))
                .put("schedules", prefs.getString("schedules", "") ?: "")
                .put("scheduled_recordings", prefs.getString("scheduled_recordings", "[]") ?: "[]")
                .put("external_player_default", prefs.getBoolean("external_player_default", false))
                .put("theme_mode", prefs.getString("theme_mode", "dark") ?: "dark")
            context.contentResolver.openOutputStream(uri)?.use { it.write(backup.toString(2).toByteArray(Charsets.UTF_8)) }
                ?: error("ساخت فایل پشتیبان ممکن نشد.")
            android.widget.Toast.makeText(context, "نسخه پشتیبان ذخیره شد.", android.widget.Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            android.widget.Toast.makeText(context, error.message ?: "خطا در پشتیبان‌گیری", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("خواندن فایل پشتیبان ممکن نشد.")
            val backup = JSONObject(raw)
            require(backup.optString("format") == "SALAR-TV-BACKUP") { "این فایل، پشتیبان معتبر SALAR TV نیست." }
            val restored = backup.optJSONArray("channels") ?: JSONArray()
            channels.clear()
            for (i in 0 until restored.length()) {
                val o = restored.getJSONObject(i)
                channels.add(Channel(o.optString("name"), o.optString("url"), o.optString("country", "سایر"),
                    o.optString("kind", "تلویزیون"), o.optBoolean("favorite"), o.optString("logoUrl"), o.optString("groupTitle")))
            }
            saveChannels()
            prefs.edit()
                .putString("schedules", backup.optString("schedules", ""))
                .putString("scheduled_recordings", backup.optString("scheduled_recordings", "[]"))
                .putBoolean("external_player_default", backup.optBoolean("external_player_default", false))
                .putString("theme_mode", backup.optString("theme_mode", "dark"))
                .apply()
            themeMode = backup.optString("theme_mode", "dark")
            android.widget.Toast.makeText(context, "شبکه‌ها و تنظیمات پشتیبان بازیابی شدند.", android.widget.Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            android.widget.Toast.makeText(context, error.message ?: "بازیابی پشتیبان ناموفق بود.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    MaterialTheme(
        colorScheme = if (useDarkTheme) darkColorScheme(
            primary = Gold, background = Background, surface = Panel,
            onPrimary = Color.Black, onBackground = TextMain, onSurface = TextMain
        ) else lightColorScheme(
            primary = Gold, background = Background, surface = Panel,
            onPrimary = Color.White, onBackground = TextMain, onSurface = TextMain
        )
    ) {
        Scaffold(
            containerColor = Background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("SALAR TV", color = Gold, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("تلویزیون و رادیوی جهان", color = SecondaryText, fontSize = 10.sp)
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
                                indicatorColor = Color(0xFF343020), unselectedIconColor = MutedText
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
                    Text("انتخاب پخش‌کننده", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useExternalPlayer, onClick = { useExternalPlayer = false; if (rememberPlayerChoice) prefs.edit().putBoolean("external_player_default", false).apply() },
                            label = { Text("پخش‌کننده داخلی") },
                            leadingIcon = { Icon(Icons.Default.PlayCircle, null) })
                        FilterChip(selected = useExternalPlayer, onClick = { useExternalPlayer = true; if (rememberPlayerChoice) prefs.edit().putBoolean("external_player_default", true).apply() },
                            label = { Text("پخش‌کننده خارجی") },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("ذخیره به‌عنوان انتخاب پیش‌فرض", color = SecondaryText, fontSize = 12.sp,
                            modifier = Modifier.weight(1f))
                        Switch(checked = rememberPlayerChoice,
                            onCheckedChange = {
                                rememberPlayerChoice = it
                                if (it) prefs.edit().putBoolean("external_player_default", useExternalPlayer).apply()
                                else prefs.edit().remove("external_player_default").apply()
                            })
                    }
                    if (!useExternalPlayer) {
                        AndroidView(
                            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            OutlinedButton(
                                onClick = { (context as? MainActivity)?.enterPlayerPictureInPicture() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PictureInPictureAlt, null)
                                Spacer(Modifier.width(8.dp))
                                Text("پخش شناور (تصویر در تصویر)")
                            }
                        }
                    } else {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.OpenInNew, null, tint = Gold)
                                Spacer(Modifier.width(10.dp))
                                Text("پخش در برنامه خارجی انتخاب‌شده از فهرست اندروید", color = TextMain, fontSize = 12.sp)
                            }
                        }
                    }
                    Text("روشنایی تصویر", color = Gold, fontSize = 12.sp)
                    Slider(value = brightness, onValueChange = {
                        brightness = it
                        (context as? Activity)?.window?.let { window ->
                            val attrs = window.attributes
                            attrs.screenBrightness = it.coerceIn(0.05f, 1f)
                            window.attributes = attrs
                        }
                    }, valueRange = 0.05f..1f)
                    Text("صدای پخش", color = Gold, fontSize = 12.sp)
                    Slider(value = player.volume, onValueChange = { player.volume = it }, valueRange = 0f..1f)
                    LaunchedEffect(selected!!.url, useExternalPlayer) {
                        val streamAddress = selected!!.url
                        val officialWebPage = streamAddress.contains("ott.iranintl.com", ignoreCase = true) ||
                            streamAddress.contains("iranintl.com/live", ignoreCase = true)
                        if (officialWebPage) {
                            player.pause()
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(streamAddress))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure {
                                android.widget.Toast.makeText(context, "صفحه رسمی پخش ایران اینترنشنال باز نشد.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else if (useExternalPlayer) {
                            player.pause()
                            runCatching {
                                val mimeType = if (selected!!.kind == "رادیو") "audio/*" else "application/vnd.apple.mpegurl"
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(streamAddress), mimeType)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(Intent.createChooser(viewIntent, "انتخاب پخش‌کننده خارجی"))
                            }.onFailure {
                                android.widget.Toast.makeText(context, "پخش‌کننده سازگار پیدا نشد؛ پخش‌کننده داخلی را انتخاب کنید.", android.widget.Toast.LENGTH_LONG).show()
                                useExternalPlayer = false
                            }
                        } else {
                            player.setMediaItem(MediaItem.fromUri(streamAddress))
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                    Text("در صورت پشتیبانی منبع، پخش زنده در این قسمت نمایش داده می‌شود.", color = SecondaryText, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    if (!selected!!.url.contains("iranintl.com", ignoreCase = true)) {
                        Button(
                            onClick = { showSchedule = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold)
                        ) {
                            Icon(Icons.Default.CalendarMonth, null, tint = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Text("زمان‌بندی ضبط همین شبکه", color = Color.Black)
                        }
                    } else {
                        Text("پخش ایران اینترنشنال از صفحه رسمی شبکه باز می‌شود؛ ضبط مستقیم این صفحه در برنامه فعال نیست.",
                            color = SecondaryText, fontSize = 12.sp)
                    }
                } else when (page) {
                    "افزودن منابع" -> AddSourcesPage(
                        selectedM3uName = selectedM3uName,
                        onAdd = { c -> channels.add(c); saveChannels(); page = "خانه" },
                        onImport = { m3uLauncher.launch(arrayOf("*/*")) }
                    )
                    "بیشتر" -> MorePage(
                        onSchedule = { page = "زمان‌بندی‌ها" },
                        onAbout = { page = "درباره ما" },
                        onSources = { page = "افزودن منابع" },
                        onRecordings = { page = "ضبط‌ها" },
                        onLiveDubbing = { page = "دوبله هوشمند زنده" },
                        onSettings = { page = "تنظیمات" },
                        onBackupExport = { backupExportLauncher.launch("SALAR-TV-Backup.json") },
                        onBackupImport = { backupImportLauncher.launch(arrayOf("application/json", "text/json", "*/*")) },
                        onPilgrimage = { page = "زیارت آنلاین" },
                        onMultiView = { page = "چندنمایی" }
                    )
                    "چندنمایی" -> MultiViewPage(
                        channels = channels.filter { it.kind == "تلویزیون" }
                    )
                    "زیارت آنلاین" -> PilgrimagePage(
                        savedChannels = channels,
                        onPlay = { selected = it },
                        onAdd = { channel -> channels.add(channel); saveChannels() }
                    )
                    "دوبله هوشمند زنده" -> LiveDubbingPage()
                    "تنظیمات" -> SettingsPage(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            prefs.edit().putString("theme_mode", mode).apply()
                        }
                    )
                    "زمان‌بندی‌ها" -> ScheduledRecordingsPage(
                        items = scheduledRecordings.toList(),
                        onAdd = { showSchedule = true },
                        onCancel = { item ->
                            val alarm = context.getSystemService(AlarmManager::class.java)
                            val pending = PendingIntent.getBroadcast(context, item.requestCode,
                                Intent(context, RecordingAlarmReceiver::class.java),
                                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                            if (pending != null) {
                                alarm.cancel(pending)
                                pending.cancel()
                            }
                            scheduledRecordings.removeAll { it.requestCode == item.requestCode }
                            saveScheduledRecordings()
                        }
                    )
                    "ضبط‌ها" -> RecordingsPage(
                        files = (context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                            ?: context.filesDir).listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("ts", "mp4", "m4v", "mkv") }
                            ?.sortedByDescending { it.lastModified() } ?: emptyList(),
                        onPlay = { file ->
                            useExternalPlayer = false
                            selected = Channel(file.nameWithoutExtension, Uri.fromFile(file).toString(), "ضبط‌شده", "تلویزیون")
                        },
                        onDelete = { file -> file.delete(); page = "ضبط‌ها" }
                    )
                    "درباره ما" -> AboutPage()
                    "تلویزیون" -> ChannelList(channels.filter { it.kind == "تلویزیون" }, onSelect = { selected = it },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() })
                    "رادیو" -> ChannelList(channels.filter { it.kind == "رادیو" }, onSelect = { selected = it },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() })
                    "علاقه‌مندی‌ها" -> ChannelList(channels.filter { it.favorite }, onSelect = { selected = it },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() })
                    else -> HomePage(channels, onSelect = { selected = it }, onAdd = { page = "افزودن منابع" },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() })
                }
            }
        }
    }

    if (editingChannel != null) {
        val original = editingChannel!!
        var name by remember(original.url) { mutableStateOf(original.name) }
        var url by remember(original.url) { mutableStateOf(original.url) }
        var country by remember(original.url) { mutableStateOf(original.country) }
        var kind by remember(original.url) { mutableStateOf(original.kind) }
        AlertDialog(
            onDismissRequest = { editingChannel = null },
            title = { Text("ویرایش شبکه", color = Gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("نام شبکه") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("لینک پخش") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("ایران", "ترکیه", "آذربایجان", "سایر").forEach {
                            FilterChip(selected = country == it, onClick = { country = it }, label = { Text(it) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("تلویزیون", "رادیو").forEach {
                            FilterChip(selected = kind == it, onClick = { kind = it }, label = { Text(it) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                        val index = channels.indexOfFirst { it.url == original.url }
                        if (index >= 0) channels[index] = original.copy(name = name.trim(), url = url.trim(), country = country, kind = kind)
                        saveChannels()
                        editingChannel = null
                    }
                }) { Text("ذخیره تغییرات", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { editingChannel = null }) { Text("انصراف") } }
        )
    }

    if (showSchedule) {
        var title by remember { mutableStateOf(selected?.name ?: "") }
        var streamUrl by remember { mutableStateOf(selected?.url ?: "") }
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
                        color = MutedText, fontSize = 11.sp)
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                            context.startActivity(Intent(
                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            scheduleError = "برای اجرای دقیق ضبط خودکار، مجوز «هشدارها و یادآوری‌ها» را فعال کنید؛ سپس دوباره ذخیره را بزنید."
                            return@TextButton
                        }
                        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, pending)
                        val newItem = ScheduledRecording(title.trim(), streamUrl.trim(), startMillis, minutes)
                        require(scheduledRecordings.none { it.requestCode == newItem.requestCode }) {
                            "این ضبط قبلاً زمان‌بندی شده است."
                        }
                        scheduledRecordings.add(newItem)
                        saveScheduledRecordings()
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
fun ScheduledRecordingsPage(items: List<ScheduledRecording>, onAdd: () -> Unit, onCancel: (ScheduledRecording) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ضبط‌های زمان‌بندی‌شده", color = Gold, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                Text("افزودن", color = Color.Black)
            }
        }
        if (items.isEmpty()) Text("هنوز ضبطی زمان‌بندی نشده است.", color = SecondaryText)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.requestCode }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.name, color = TextMain, fontWeight = FontWeight.Bold)
                        Text(java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(item.startMillis)) + "  •  " + item.durationMinutes + " دقیقه",
                            color = SecondaryText, fontSize = 12.sp)
                        Text(item.url, color = MutedText, fontSize = 10.sp, maxLines = 1)
                        TextButton(onClick = { onCancel(item) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text("لغو زمان‌بندی", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomePage(channels: List<Channel>, onSelect: (Channel) -> Unit, onAdd: () -> Unit,
             onEdit: (Channel) -> Unit = {}, onDelete: (Channel) -> Unit = {}) {
    val tvCount = channels.count { it.kind == "تلویزیون" }
    val radioCount = channels.count { it.kind == "رادیو" }
    val favoriteCount = channels.count { it.favorite }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF29251D), Panel))
            ).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LiveTv, null, tint = Gold, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("SALAR TV", color = Gold, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("مرکز سرگرمی و پخش زنده شما", color = SecondaryText, fontSize = 12.sp)
                    }
                }
                Text("تلویزیون، رادیو و شبکه‌های جهان", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("ایران  •  ترکیه  •  جمهوری آذربایجان  •  جهان", color = SecondaryText, fontSize = 12.sp)
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                    shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Default.AddLink, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("افزودن شبکه یا سرور", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(Icons.Default.Tv, "$tvCount", "تلویزیون"), Triple(Icons.Default.Radio, "$radioCount", "رادیو"),
                Triple(Icons.Default.Favorite, "$favoriteCount", "علاقه‌مندی")).forEach { item ->
                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(item.first, null, tint = Gold, modifier = Modifier.size(22.dp))
                        Text(item.second, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(item.third, color = MutedText, fontSize = 10.sp)
                    }
                }
            }
        }
        Text("دسترسی سریع", color = Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("تلویزیون" to Icons.Default.Tv, "رادیو" to Icons.Default.Radio, "شبکه‌های من" to Icons.Default.PlaylistPlay).forEach { (label, icon) ->
                Card(Modifier.weight(1f).clickable { if (label == "شبکه‌های من") onAdd() else channels.firstOrNull { it.kind == label }?.let(onSelect) },
                    colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, tint = Gold, modifier = Modifier.size(25.dp))
                        Text(label, color = TextMain, fontSize = 11.sp)
                    }
                }
            }
        }
        Text("شبکه‌های شما", color = Gold, fontWeight = FontWeight.Bold)
        if (channels.isEmpty()) {
            Text("هنوز شبکه‌ای اضافه نشده است. از بخش افزودن منابع، لینک مجاز یا فایل M3U وارد کنید.", color = SecondaryText)
        } else ChannelList(channels, onSelect, {}, onEdit, onDelete)
    }
}


@Composable
// Reads optional tvg-logo URLs from M3U and renders a safe TV/radio fallback.
private fun ChannelLogo(logoUrl: String, kind: String) {
    var bitmap by remember(logoUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(logoUrl) {
        bitmap = if (logoUrl.startsWith("https://") || logoUrl.startsWith("http://")) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(logoUrl).openConnection()
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
        } else null
    }
    Box(
        Modifier.size(42.dp).background(Color(0xFF252B38), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "لوگوی شبکه",
                modifier = Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
        } else {
            Icon(if (kind == "رادیو") Icons.Default.Radio else Icons.Default.Tv,
                contentDescription = null, tint = Gold, modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
fun ChannelList(channels: List<Channel>, onSelect: (Channel) -> Unit, onFavorite: (Channel) -> Unit,
                onEdit: (Channel) -> Unit = {}, onDelete: (Channel) -> Unit = {}) {
    var searchQuery by remember { mutableStateOf("") }
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("موردی برای نمایش وجود ندارد.", color = SecondaryText)
        }
    } else {
        val visibleChannels = channels.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.country.contains(searchQuery, ignoreCase = true) ||
                categoryForChannel(it).contains(searchQuery, ignoreCase = true)
        }
        val sections = visibleChannels.groupBy { "${it.country} / ${categoryForChannel(it)} / ${it.kind}" }.toSortedMap()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "channel_search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("جست‌وجوی شبکه، کشور یا موضوع") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
            }
            if (visibleChannels.isEmpty()) item { Text("شبکه‌ای با این عبارت پیدا نشد.", color = SecondaryText) }
            sections.forEach { (sectionTitle, sectionChannels) ->
                item(key = "section_${sectionTitle}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (sectionTitle.endsWith("رادیو")) Icons.Default.Radio else Icons.Default.Tv,
                            null, tint = Gold, modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(sectionTitle, color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${sectionChannels.size} شبکه", color = MutedText, fontSize = 11.sp)
                    }
                    Divider(color = Gold.copy(alpha = 0.28f))
                }
                items(sectionChannels, key = { it.url }) { c ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(c) },
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(c.logoUrl, c.kind)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                                Text("${c.country} • ${c.kind}", color = MutedText, fontSize = 11.sp)
                            }
                            IconButton(onClick = { onFavorite(c) }) {
                                Icon(Icons.Default.Favorite, null, tint = if (c.favorite) Gold else MutedText)
                            }
                            IconButton(onClick = { onEdit(c) }) { Icon(Icons.Default.Edit, "ویرایش", tint = SecondaryText) }
                            IconButton(onClick = { onDelete(c) }) { Icon(Icons.Default.Delete, "حذف", tint = Color(0xFFE57373)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddSourcesPage(selectedM3uName: String, onAdd: (Channel) -> Unit, onImport: () -> Unit) {
    var mode by remember { mutableStateOf("url") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("ایران") }
    var kind by remember { mutableStateOf("تلویزیون") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("مدیریت منابع پخش", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = mode == "url", onClick = { mode = "url" }, label = { Text("لینک مستقیم") })
            FilterChip(selected = mode == "xtream", onClick = { mode = "xtream" }, label = { Text("سرور IPTV") })
            FilterChip(selected = mode == "file", onClick = { mode = "file" }, label = { Text("فایل M3U") })
        }
        when (mode) {
            "url" -> {
                OutlinedTextField(name, { name = it }, label = { Text("نام شبکه / ایستگاه") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("آدرس مستقیم پخش") }, placeholder = { Text("https://example.com/live.m3u8") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
                Button(enabled = !busy, onClick = {
                    val clean = url.trim()
                    if (!(clean.startsWith("https://") || clean.startsWith("http://"))) status = "آدرس معتبر http/https را وارد کنید."
                    else if (clean.substringBefore("?").endsWith(".m3u", true) || clean.substringBefore("?").endsWith(".m3u8", true)) {
                        busy = true; status = "در حال دریافت فهرست M3U از اینترنت…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                val connection = URL(clean).openConnection()
                                connection.connectTimeout = 12000; connection.readTimeout = 25000
                                connection.getInputStream().bufferedReader(Charsets.UTF_8).use { parseM3u(it.readText()) }
                            } }
                            result.onSuccess { imported ->
                                if (imported.isEmpty()) status = "فهرست دریافت شد اما شبکه قابل‌شناسایی نداشت."
                                else { imported.forEach(onAdd); status = "تعداد ${imported.size} شبکه از فهرست اینترنتی اضافه شد." }
                            }.onFailure { err -> status = "دریافت فهرست ناموفق: ${err.message ?: "خطای شبکه"}" }
                            busy = false
                        }
                    } else if (name.isNotBlank()) {
                        onAdd(Channel(name.trim(), clean, country, kind)); name = ""; url = ""; status = "لینک شبکه ذخیره شد."
                    } else status = "برای لینک پخش مستقیم، نام شبکه را وارد کنید."
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold)) { Text(if (busy) "در حال دریافت…" else "ذخیره لینک / دریافت فهرست", color = Color.Black) }
            }
            "xtream" -> {
                OutlinedTextField(server, { server = it }, label = { Text("آدرس سرور (با پورت در صورت نیاز)") }, placeholder = { Text("http://example.com:8080") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text("نام کاربری") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("رمز عبور") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("اطلاعات ورود برای دریافت فهرست استفاده می‌شود و در تنظیمات ذخیره نمی‌شود.", color = MutedText, fontSize = 11.sp)
                Button(enabled = !busy, onClick = {
                    val base = server.trim().trimEnd('/')
                    if (!(base.startsWith("http://") || base.startsWith("https://")) || username.isBlank() || password.isBlank()) status = "آدرس سرور، نام کاربری و رمز عبور را کامل وارد کنید."
                    else {
                        busy = true; status = "در حال اتصال و دریافت شبکه‌های زنده…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching {
                                val u = URLEncoder.encode(username.trim(), "UTF-8")
                                val p = URLEncoder.encode(password, "UTF-8")
                                fun request(action: String): JSONArray {
                                    val conn = (URL("$base/player_api.php?username=$u&password=$p&action=$action").openConnection() as HttpURLConnection)
                                    conn.connectTimeout = 12000; conn.readTimeout = 20000
                                    return try {
                                        val code = conn.responseCode
                                        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                                        if (code !in 200..299) error("پاسخ سرور: HTTP $code")
                                        JSONArray(body)
                                    } finally { conn.disconnect() }
                                }
                                val categories = request("get_live_categories")
                                val categoryNames = mutableMapOf<String, String>()
                                for (i in 0 until categories.length()) { val c = categories.optJSONObject(i) ?: continue; categoryNames[c.optString("category_id")] = c.optString("category_name") }
                                val streams = request("get_live_streams")
                                val imported = mutableListOf<Channel>()
                                for (i in 0 until streams.length()) {
                                    val item = streams.optJSONObject(i) ?: continue
                                    val id = item.optString("stream_id")
                                    if (id.isBlank() || id == "null") continue
                                    val streamName = item.optString("name", "شبکه $id")
                                    val streamUrl = "$base/live/$u/$p/$id.ts"
                                    imported.add(Channel(streamName, streamUrl, country, "تلویزیون", logoUrl = item.optString("stream_icon"), groupTitle = categoryNames[item.optString("category_id")].orEmpty().ifBlank { "سرور IPTV" }))
                                }
                                if (imported.isEmpty()) error("فهرست شبکه زنده‌ای از سرور دریافت نشد.")
                                imported
                            } }
                            result.onSuccess { imported -> imported.forEach(onAdd); status = "تعداد ${imported.size} شبکه زنده دریافت و اضافه شد." }
                            result.onFailure { err -> status = "اتصال ناموفق: ${err.message ?: "خطای ناشناخته"}" }
                            busy = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold)) { Text(if (busy) "در حال دریافت…" else "اتصال و دریافت شبکه‌های زنده", color = Color.Black) }
            }
            else -> {
                Text("فایل M3U یا M3U8 را از حافظه گوشی انتخاب کنید.", color = SecondaryText)
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("انتخاب فایل M3U / M3U8") }
                OutlinedTextField(value = selectedM3uName, onValueChange = {}, readOnly = true, label = { Text("فایل انتخاب‌شده و نتیجه واردکردن") }, placeholder = { Text("نام فایل و تعداد شبکه‌ها اینجا نمایش داده می‌شود") }, modifier = Modifier.fillMaxWidth(), singleLine = false)
            }
        }
        if (mode != "xtream") {
            Text("کشور", color = Gold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf("ایران", "ترکیه", "آذربایجان", "عربستان", "امارات", "قطر", "کویت", "بحرین", "عمان", "عراق", "اردن", "لبنان", "سوریه", "فلسطین", "مصر", "مراکش", "الجزایر", "تونس", "لیبی", "یمن", "سودان", "اسرائیل", "فارسی‌زبان خارج از ایران", "سایر")) { option -> FilterChip(selected = country == option, onClick = { country = option }, label = { Text(option) }) } }
            Text("نوع منبع", color = Gold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = kind == "تلویزیون", onClick = { kind = "تلویزیون" }, label = { Text("تلویزیون") }); FilterChip(selected = kind == "رادیو", onClick = { kind = "رادیو" }, label = { Text("رادیو") }) }
        }
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("اتصال ناموفق")) Color(0xFFFFCC80) else Gold, fontSize = 12.sp)
        Text("فقط منابع رسمی یا لینک‌هایی را وارد کنید که مجوز استفاده از آن‌ها را دارید.", color = MutedText, fontSize = 11.sp)
    }
}

@Composable
fun MorePage(onSchedule: () -> Unit, onAbout: () -> Unit, onSources: () -> Unit,
             onRecordings: () -> Unit, onLiveDubbing: () -> Unit, onSettings: () -> Unit,
             onBackupExport: () -> Unit, onBackupImport: () -> Unit, onPilgrimage: () -> Unit,
             onMultiView: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("امکانات بیشتر", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("زیارت آنلاین", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("پخش زنده اماکن مذهبی؛ لینک رسمی هر مکان را می‌توانی اضافه کنی.", color = SecondaryText, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPilgrimage, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
                    Icon(Icons.Default.Place, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("ورود به زیارت آنلاین", color = Color.Black)
                }
            }
        }
        MoreButton("چندنمایی هم‌زمان", Icons.Default.GridView, onMultiView)
        MoreButton("تنظیمات", Icons.Default.Settings, onSettings)
        MoreButton("مترجم و دوبلور هوشمند زنده", Icons.Default.Translate, onLiveDubbing)
        MoreButton("افزودن لینک و سرور", Icons.Default.AddLink, onSources)
        MoreButton("زمان‌بندی ضبط", Icons.Default.CalendarMonth, onSchedule)
        MoreButton("فایل‌های ضبط‌شده", Icons.Default.VideoLibrary, onRecordings)
        MoreButton("تهیه نسخه پشتیبان", Icons.Default.Backup, onBackupExport)
        MoreButton("بازیابی نسخه پشتیبان", Icons.Default.Restore, onBackupImport)
        MoreButton("درباره سازنده", Icons.Default.Info, onAbout)
        Text("زبان‌های هدف: فارسی، ترکی استانبولی، ترکی آذربایجانی و انگلیسی", color = SecondaryText, fontSize = 12.sp)
    }
}

@Composable
fun MultiViewPage(channels: List<Channel>) {
    val context = LocalContext.current
    var first by remember { mutableStateOf<Channel?>(channels.getOrNull(0)) }
    var second by remember { mutableStateOf<Channel?>(channels.getOrNull(1)) }
    val firstPlayer = remember { ExoPlayer.Builder(context).build() }
    val secondPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { firstPlayer.release(); secondPlayer.release() } }
    LaunchedEffect(first?.url) {
        first?.let { firstPlayer.setMediaItem(MediaItem.fromUri(it.url)); firstPlayer.prepare(); firstPlayer.playWhenReady = true }
            ?: firstPlayer.stop()
    }
    LaunchedEffect(second?.url) {
        second?.let { secondPlayer.setMediaItem(MediaItem.fromUri(it.url)); secondPlayer.prepare(); secondPlayer.playWhenReady = true }
            ?: secondPlayer.stop()
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("چندنمایی هم‌زمان", color = Gold, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("دو شبکه را از فهرست خود انتخاب کنید. پخش هم‌زمان به توان سخت‌افزاری گوشی و نوع پخش وابسته است.",
            color = SecondaryText, fontSize = 12.sp)
        MultiViewSlot("پنجره اول", channels, first, { first = it }, firstPlayer)
        MultiViewSlot("پنجره دوم", channels, second, { second = it }, secondPlayer)
        if (channels.isEmpty()) Text("ابتدا شبکه‌های تلویزیونی را از بخش افزودن منابع اضافه کنید.", color = SecondaryText)
    }
}

@Composable
private fun MultiViewSlot(title: String, channels: List<Channel>, selected: Channel?,
                          onSelect: (Channel?) -> Unit, player: ExoPlayer) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(selected?.name ?: "انتخاب شبکه", color = TextMain)
                        Icon(Icons.Default.ArrowDropDown, null, tint = Gold)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        channels.forEach { channel ->
                            DropdownMenuItem(text = { Text(channel.name) }, onClick = { onSelect(channel); expanded = false })
                        }
                    }
                }
            }
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
    }
}

@Composable
fun PilgrimagePage(savedChannels: List<Channel>, onPlay: (Channel) -> Unit, onAdd: (Channel) -> Unit) {
    var place by remember { mutableStateOf("مشهد مقدس") }
    var title by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var customPlace by remember { mutableStateOf("") }
    val defaultPlaces = listOf("مشهد مقدس", "کربلا", "نجف", "مکه مکرمه", "مدینه منوره", "سایر اماکن مذهبی")
    val savedPlaces = savedChannels.filter { it.country == "زیارت آنلاین" }.map { it.groupTitle }.filter { it.isNotBlank() }
    val places = (defaultPlaces + savedPlaces).distinct()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("زیارت آنلاین", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("مکان موردنظر را انتخاب کن. برای جلوگیری از لینک‌های ساختگی، پخش زنده فقط پس از افزودن نشانی معتبر و رسمی فعال می‌شود.",
            color = SecondaryText, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            items(places) { item ->
                FilterChip(selected = place == item, onClick = { place = item }, label = { Text(item) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = customPlace, onValueChange = { customPlace = it },
                modifier = Modifier.weight(1f), singleLine = true, label = { Text("افزودن مکان جدید") })
            TextButton(onClick = {
                val clean = customPlace.trim()
                if (clean.isNotBlank() && clean !in places) place = clean
                customPlace = ""
            }) { Text("افزودن", color = Gold) }
        }
        val available = savedChannels.filter { it.country == "زیارت آنلاین" && it.groupTitle == place }
        if (available.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Icon(Icons.Default.Place, null, tint = Gold, modifier = Modifier.size(30.dp))
                    Text("هنوز لینک پخش زنده‌ای برای $place ثبت نشده است.", color = TextMain, fontWeight = FontWeight.SemiBold)
                    Text("نشانی پخش رسمی یا مجاز را در کادر زیر وارد کن.", color = SecondaryText, fontSize = 12.sp)
                }
            }
        } else {
            available.forEach { channel ->
                MoreButton(channel.name, Icons.Default.PlayCircle, { onPlay(channel) })
            }
        }
        OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("عنوان پخش (مثلاً حرم مطهر)") }, singleLine = true)
        OutlinedTextField(value = streamUrl, onValueChange = { streamUrl = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("لینک مستقیم پخش زنده") }, placeholder = { Text("https://…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
        Button(onClick = {
            val cleanUrl = streamUrl.trim()
            if (title.isNotBlank() && (cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://"))) {
                onAdd(Channel(title.trim(), cleanUrl, "زیارت آنلاین", "تلویزیون", groupTitle = place))
                title = ""
                streamUrl = ""
            }
        }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
            Icon(Icons.Default.Add, null, tint = Color.Black)
            Spacer(Modifier.width(8.dp))
            Text("ذخیره لینک این مکان", color = Color.Black)
        }
        Text("توجه: دسترسی و پخش واقعی به معتبر بودن لینک و پشتیبانی قالب آن بستگی دارد.", color = MutedText, fontSize = 11.sp)
    }
}

@Composable
fun SettingsPage(themeMode: String, onThemeModeChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("تنظیمات ظاهری", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("حالت نمایش برنامه", color = TextMain, fontWeight = FontWeight.SemiBold)
        Text("ظاهر برنامه را مطابق سلیقه یا تنظیمات دستگاه انتخاب کنید.", color = SecondaryText, fontSize = 12.sp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "حالت شب", "light" to "حالت روز", "system" to "حالت خودکار").forEach { (value, label) ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onThemeModeChange(value) },
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (value) {
                                "dark" -> Icons.Default.DarkMode
                                "light" -> Icons.Default.LightMode
                                else -> Icons.Default.SettingsSuggest
                            }, null, tint = Gold
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = TextMain, modifier = Modifier.weight(1f))
                        RadioButton(selected = themeMode == value, onClick = { onThemeModeChange(value) })
                    }
                }
            }
        }
        Text("انتخاب شما ذخیره می‌شود و پس از اجرای دوباره برنامه نیز باقی می‌ماند.",
            color = MutedText, fontSize = 11.sp)
    }
}

@Composable
fun LiveDubbingPage() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("salar_tv", 0) }
    var subtitleEnabled by remember { mutableStateOf(prefs.getBoolean("live_subtitle_enabled", false)) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("live_dubbing_enabled", false)) }
    var keepOriginal by remember { mutableStateOf(prefs.getBoolean("live_dubbing_original", true)) }
    var voice by remember { mutableStateOf(prefs.getString("live_dubbing_voice", "زن") ?: "زن") }
    var volume by remember { mutableFloatStateOf(prefs.getFloat("live_dubbing_volume", 1f)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("مترجم و دوبلور هوشمند زنده", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("وضعیت موتور هوش مصنوعی", color = Gold, fontWeight = FontWeight.Bold)
                Text("هنوز به سرویس تشخیص گفتار، ترجمه و تولید صدای زنده متصل نشده است.",
                    color = Color(0xFFFFCC80), fontSize = 12.sp)
                Text("این صفحه تنظیمات قابلیت را نگه می‌دارد؛ دوبله واقعی پس از اتصال موتور پردازش صدا فعال می‌شود.",
                    color = SecondaryText, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("زیرنویس هوشمند فارسی", color = TextMain)
                Text("تشخیص گفتار و نمایش ترجمه؛ مستقل از صدای دوبله", color = MutedText, fontSize = 11.sp)
            }
            Switch(checked = subtitleEnabled, onCheckedChange = {
                subtitleEnabled = it
                prefs.edit().putBoolean("live_subtitle_enabled", it).apply()
            })
        }
        Divider(color = Gold.copy(alpha = 0.25f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("دوبله هوشمند فارسی", color = TextMain)
                Text("ترجمه صوتی؛ قابل استفاده مستقل یا همراه زیرنویس", color = MutedText, fontSize = 11.sp)
            }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean("live_dubbing_enabled", it).apply()
            })
        }
        Text("صدای دوبله", color = Gold, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("زن", "مرد").forEach { option ->
                FilterChip(selected = voice == option, onClick = {
                    voice = option
                    prefs.edit().putString("live_dubbing_voice", option).apply()
                }, label = { Text(option) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("حفظ صدای اصلی شبکه", color = TextMain)
                Text("برای شنیدن صدای اصلی همراه دوبله", color = MutedText, fontSize = 11.sp)
            }
            Switch(checked = keepOriginal, onCheckedChange = {
                keepOriginal = it
                prefs.edit().putBoolean("live_dubbing_original", it).apply()
            })
        }
        Text("بلندی صدای دوبله: ${(volume * 100).toInt()}٪", color = Gold, fontSize = 12.sp)
        Slider(value = volume, onValueChange = {
            volume = it
            prefs.edit().putFloat("live_dubbing_volume", it).apply()
        }, valueRange = 0f..1f)
        Text("زبان‌های ورودی موردنظر: انگلیسی، ترکی استانبولی و ترکی آذربایجانی؛ خروجی فارسی.",
            color = SecondaryText, fontSize = 12.sp)
    }
}

@Composable
fun RecordingsPage(files: List<File>, onPlay: (File) -> Unit, onDelete: (File) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("فایل‌های ضبط‌شده", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("برای پخش هر فایل، دکمه اجرا را بزنید.", color = SecondaryText, fontSize = 12.sp)
        if (files.isEmpty()) Text("هنوز فایلی ضبط نشده است.", color = SecondaryText)
        files.forEach { file ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, color = TextMain, fontSize = 12.sp)
                        Text("${file.length() / 1_048_576} مگابایت", color = MutedText, fontSize = 11.sp)
                    }
                    IconButton(onClick = { onPlay(file) }) {
                        Icon(Icons.Default.PlayArrow, "پخش فایل ضبط‌شده", tint = Gold)
                    }
                    IconButton(onClick = { onDelete(file) }) {
                        Icon(Icons.Default.Delete, "حذف فایل", tint = Color(0xFFE57373))
                    }
                }
            }
        }
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
        Text("ایمیل: Salar.m041@gmail.com", color = SecondaryText)
        Text("اینستاگرام: Salar_mousavi_41", color = SecondaryText)
        Text("نسخه 1.3.0", color = MutedText)
    }
}


private fun categoryForChannel(channel: Channel): String {
    if (channel.kind == "رادیو") return "رادیو"
    val value = (channel.name + " " + channel.country + " " + channel.groupTitle).lowercase()
    return when {
        listOf("خبر", "news", "haber", "bbc", "cnn", "irinn", "voa persian", "congress tv", "revolution tv").any { it in value } -> "خبری"
        listOf("ورزش", "sport", "sports", "varzesh", "trt spor", "persiana fight").any { it in value } -> "ورزشی"
        listOf("کودک", "پویا", "نهال", "kids", "cocuk", "çocuk", "cartoon", "persiana junior").any { it in value } -> "کودک"
        listOf("مستند", "documentary", "belgesel", "persiana docs", "persiana travel").any { it in value } -> "مستند و سفر"
        listOf("فیلم", "سریال", "تماشا", "film", "movie", "dizi", "persiana cinema", "persiana series").any { it in value } -> "فیلم و سریال"
        listOf("موسیقی", "music", "muzik", "müzik", "persiana folk", "persiana vibe", "persiana nostalgia").any { it in value } -> "موسیقی"
        listOf("آموزش", "آموزشی", "edu", "education").any { it in value } -> "آموزشی"
        listOf("سلامت", "health", "saglik", "sağlık", "persiana medical").any { it in value } -> "سلامت"
        listOf("قرآن", "معارف", "quran", "religion").any { it in value } -> "دینی و فرهنگی"
        "persiana comedy" in value -> "سرگرمی"
        channel.groupTitle.contains("شبکه‌های استانی", ignoreCase = true) || (channel.country == "ایران" && listOf("استان", "شبکه آذربایجان غربی", "شبکه سهند", "شبکه سبلان").any { it in value }) -> "استانی"
        else -> "عمومی"
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
    var logoUrl = ""
    var groupTitle = ""
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            title = line.substringAfter(",", "شبکه بدون نام").trim().ifBlank { "شبکه بدون نام" }
            logoUrl = Regex("tvg-logo=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
            val lower = line.lowercase()
            groupTitle = Regex("group-title=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
            val group = groupTitle.lowercase()
            val meta = "$lower $group"
            country = when {
                "فارسی‌زبان خارج از ایران" in meta || "فارسی زبان خارج از ایران" in meta -> "فارسی‌زبان خارج از ایران"
                "azerbaijan" in meta || "azərbaycan" in meta || "آذربایجان" in meta || "az/" in meta -> "آذربایجان"
                "turkey" in meta || "turkiye" in meta || "türkiye" in meta || "ترکیه" in meta || "tr/" in meta -> "ترکیه"
                "iran" in meta || "ایران" in meta || "ir/" in meta || "شبکه‌های استانی" in meta -> "ایران"
                "saudi" in meta || "عربستان" in meta || "sa/" in meta -> "عربستان"
                "uae" in meta || "emirates" in meta || "امارات" in meta || "ae/" in meta -> "امارات"
                "qatar" in meta || "قطر" in meta || "qa/" in meta -> "قطر"
                "kuwait" in meta || "کویت" in meta || "kw/" in meta -> "کویت"
                "bahrain" in meta || "بحرین" in meta || "bh/" in meta -> "بحرین"
                "oman" in meta || "عمان" in meta || "om/" in meta -> "عمان"
                "iraq" in meta || "عراق" in meta || "iq/" in meta -> "عراق"
                "jordan" in meta || "اردن" in meta || "jo/" in meta -> "اردن"
                "lebanon" in meta || "لبنان" in meta || "lb/" in meta -> "لبنان"
                "syria" in meta || "سوریه" in meta || "sy/" in meta -> "سوریه"
                "palestine" in meta || "فلسطین" in meta || "ps/" in meta -> "فلسطین"
                "egypt" in meta || "مصر" in meta || "eg/" in meta -> "مصر"
                "morocco" in meta || "مراکش" in meta || "ma/" in meta -> "مراکش"
                "algeria" in meta || "الجزایر" in meta || "dz/" in meta -> "الجزایر"
                "tunisia" in meta || "تونس" in meta || "tn/" in meta -> "تونس"
                "libya" in meta || "لیبی" in meta || "ly/" in meta -> "لیبی"
                "yemen" in meta || "یمن" in meta || "ye/" in meta -> "یمن"
                "sudan" in meta || "سودان" in meta || "sd/" in meta -> "سودان"
                "israel" in meta || "اسرائیل" in meta || "il/" in meta -> "اسرائیل"
                else -> "سایر"
            }
            kind = if ("radio" in meta || "رادیو" in meta) "رادیو" else "تلویزیون"
        } else if (line.startsWith("http://") || line.startsWith("https://")) {
            result.add(Channel(title, line, country, kind, logoUrl = logoUrl, groupTitle = groupTitle))
            title = "شبکه بدون نام"; country = "سایر"; kind = "تلویزیون"; logoUrl = ""; groupTitle = ""
        }
    }
    return result.distinctBy { it.url }
}
