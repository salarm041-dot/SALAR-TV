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
import kotlinx.coroutines.delay
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
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

private var Gold by mutableStateOf(Color(0xFFD6AD62))
private var Background by mutableStateOf(Color(0xFF0B0D13))
private var Panel by mutableStateOf(Color(0xFF171B26))
private var TextMain by mutableStateOf(Color(0xFFF5F1E8))
private var SecondaryText by mutableStateOf(Color.LightGray)
private var MutedText by mutableStateOf(Color.Gray)

data class ScheduledRecording(val requestCode: Int, val title: String, val url: String, val startMillis: Long, val durationMinutes: Int, val status: String = "scheduled")

data class Channel(
    val name: String,
    val url: String,
    val country: String = "سایر",
    val kind: String = "تلویزیون",
    val favorite: Boolean = false,
    val logoUrl: String = "",
    val groupTitle: String = "",
    val tvgId: String = ""
)

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
                        o.optString("kind"), o.optBoolean("favorite"), o.optString("logoUrl"), o.optString("groupTitle"), o.optString("tvgId")))
                }
            }
        }
    }
    fun saveChannels() {
        val arr = JSONArray()
        channels.forEach { c ->
            arr.put(JSONObject().put("name", c.name).put("url", c.url)
                .put("country", c.country).put("kind", c.kind).put("favorite", c.favorite)
                .put("logoUrl", c.logoUrl).put("groupTitle", c.groupTitle).put("tvgId", c.tvgId))
        }
        prefs.edit().putString("channels", arr.toString()).apply()
    }

    // Seed the bundled public playlist once. Existing user channels are preserved.
    LaunchedEffect(Unit) {
        val bundled = runCatching {
            context.assets.open("channels.m3u").bufferedReader().use { parseM3u(it.readText()) }
        }.getOrDefault(emptyList())
        val bundledByUrl = bundled.associateBy { it.url }
        for (index in channels.indices) {
            val old = channels[index]
            val fresh = bundledByUrl[old.url] ?: continue
            channels[index] = old.copy(
                tvgId = old.tvgId.ifBlank { fresh.tvgId },
                logoUrl = old.logoUrl.ifBlank { fresh.logoUrl },
                groupTitle = old.groupTitle.ifBlank { fresh.groupTitle },
                country = if (old.country == "سایر") fresh.country else old.country,
                kind = if (old.kind == "تلویزیون" && fresh.kind != "تلویزیون") fresh.kind else old.kind
            )
        }
        val existing = channels.map { it.url }.toSet()
        channels.addAll(bundled.filter { it.url !in existing })
        saveChannels()
        prefs.edit().putBoolean("built_in_seeded", true).apply()
    }

    var page by remember { mutableStateOf("خانه") }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            isRecording = HlsRecorderService.isRecordingActive
            delay(1000)
        }
    }
    var showRecordingOptions by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf("60") }
    var recordingError by remember { mutableStateOf("") }
    var showFullscreenPlayer by remember { mutableStateOf(false) }
    var forceLandscape by remember { mutableStateOf(true) }
    var editingChannel by remember { mutableStateOf<Channel?>(null) }
    var brightness by remember { mutableFloatStateOf(0.75f) }
    var useExternalPlayer by remember { mutableStateOf(prefs.getBoolean("external_player_default", false)) }
    var rememberPlayerChoice by remember { mutableStateOf(false) }
    var schedules by remember { mutableStateOf(prefs.getString("schedules", "") ?: "") }
    val scheduledRecordings = remember {
        mutableStateListOf<ScheduledRecording>().apply {
            runCatching {
                val saved = JSONArray(prefs.getString("schedules_json", "[]") ?: "[]")
                for (i in 0 until saved.length()) {
                    val item = saved.getJSONObject(i)
                    add(ScheduledRecording(item.optInt("requestCode"), item.optString("title"),
                        item.optString("url"), item.optLong("startMillis"), item.optInt("durationMinutes", 60), item.optString("status", "scheduled")))
                }
            }
        }
    }
    fun saveScheduledRecordings() {
        val saved = JSONArray()
        scheduledRecordings.forEach { item ->
            saved.put(JSONObject().put("requestCode", item.requestCode).put("title", item.title)
                .put("url", item.url).put("startMillis", item.startMillis)
                .put("durationMinutes", item.durationMinutes).put("status", item.status))
        }
        prefs.edit().putString("schedules_json", saved.toString()).apply()
    }
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
    var selectedQuality by remember { mutableStateOf(prefs.getString("player_quality", "خودکار") ?: "خودکار") }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(selectedQuality) {
        val maxSize = when (selectedQuality) {
            "1080p" -> 1920 to 1080
            "720p" -> 1280 to 720
            "480p" -> 854 to 480
            "360p" -> 640 to 360
            else -> Int.MAX_VALUE to Int.MAX_VALUE
        }
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(maxSize.first, maxSize.second)
            .build()
        prefs.edit().putString("player_quality", selectedQuality).apply()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var selectedM3uName by remember { mutableStateOf("") }
    var refreshingChannels by remember { mutableStateOf(false) }
    var channelRefreshStatus by remember { mutableStateOf("") }
    val refreshScope = rememberCoroutineScope()
    fun refreshChannelDirectory() {
        if (refreshingChannels) return
        refreshScope.launch {
            refreshingChannels = true
            channelRefreshStatus = "در حال دریافت فهرست جدید شبکه‌ها..."
            try {
                val remoteChannels = withContext(Dispatchers.IO) {
                    val connection = (URL("https://raw.githubusercontent.com/salarm041-dot/SALAR-TV/main/patch/channels.m3u").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 25000
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache")
                        setRequestProperty("User-Agent", "SALAR-TV-Channel-Refresh/1.0")
                        setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain, */*")
                    }
                    try {
                        require(connection.responseCode in 200..299) { "سرور فهرست شبکه‌ها پاسخ مناسب نداد: HTTP ${connection.responseCode}" }
                        val bytes = java.io.ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 5 * 1024 * 1024) { "حجم فهرست شبکه‌ها بیش از حد مجاز است." }
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val playlist = bytes.toString("UTF-8")
                        require(playlist.trimStart().startsWith("#EXTM3U", ignoreCase = true)) { "پاسخ سرور یک فایل M3U معتبر نیست." }
                        val parsed = parseM3u(playlist)
                        require(parsed.size >= 50) { "فهرست دریافتی ناقص به نظر می‌رسد؛ برای جلوگیری از تغییر ناخواسته اعمال نشد." }
                        parsed
                    } finally {
                        connection.disconnect()
                    }
                }

                val existingByUrl = channels.withIndex().associate { it.value.url to it.index }
                val seenUrls = existingByUrl.keys.toMutableSet()
                var added = 0
                remoteChannels.forEach { fresh ->
                    val index = existingByUrl[fresh.url]
                    if (index == null) {
                        if (seenUrls.add(fresh.url)) {
                            channels.add(fresh)
                            added++
                        }
                    } else {
                        val old = channels[index]
                        val merged = old.copy(
                            tvgId = old.tvgId.ifBlank { fresh.tvgId },
                            logoUrl = old.logoUrl.ifBlank { fresh.logoUrl },
                            groupTitle = old.groupTitle.ifBlank { fresh.groupTitle },
                            country = if (old.country == "سایر") fresh.country else old.country,
                            kind = if (old.kind == "تلویزیون" && fresh.kind != "تلویزیون") fresh.kind else old.kind
                        )
                        if (merged != old) channels[index] = merged
                    }
                }
                saveChannels()
                channelRefreshStatus = if (added == 0) {
                    "فهرست به‌روز است؛ شبکه جدیدی پیدا نشد."
                } else {
                    "$added شبکه جدید اضافه شد."
                }
                android.widget.Toast.makeText(
                    context,
                    if (added == 0) "فهرست شبکه‌ها به‌روز است." else "$added شبکه جدید به فهرست اضافه شد.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                channelRefreshStatus = error.message ?: "به‌روزرسانی شبکه‌ها ناموفق بود."
                android.widget.Toast.makeText(context, channelRefreshStatus, android.widget.Toast.LENGTH_LONG).show()
            } finally {
                refreshingChannels = false
            }
        }
    }
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
                .put("schedules_json", prefs.getString("schedules_json", "[]") ?: "[]")
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
                    o.optString("kind", "تلویزیون"), o.optBoolean("favorite"), o.optString("logoUrl"), o.optString("groupTitle"), o.optString("tvgId")))
            }
            saveChannels()
            prefs.edit()
                .putString("schedules", backup.optString("schedules", ""))
                .putString("schedules_json", backup.optString("schedules_json", "[]"))
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
                        Box {
                            OutlinedButton(onClick = { qualityMenuExpanded = true }) {
                                Icon(Icons.Default.HighQuality, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("کیفیت: $selectedQuality")
                            }
                            DropdownMenu(
                                expanded = qualityMenuExpanded,
                                onDismissRequest = { qualityMenuExpanded = false }
                            ) {
                                listOf("خودکار", "1080p", "720p", "480p", "360p").forEach { quality ->
                                    DropdownMenuItem(
                                        text = { Text(quality) },
                                        onClick = {
                                            selectedQuality = quality
                                            qualityMenuExpanded = false
                                        },
                                        leadingIcon = {
                                            if (selectedQuality == quality) Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            "کیفیت انتخابی سقف وضوح تصویر را تعیین می‌کند؛ انتخاب نهایی به کیفیت‌های موجود در پخش زنده بستگی دارد.",
                            color = SecondaryText,
                            fontSize = 11.sp
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    showFullscreenPlayer = true
                                    forceLandscape = true
                                    (context as? Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Fullscreen, null)
                                Spacer(Modifier.width(6.dp))
                                Text("تمام‌صفحه")
                            }
                            OutlinedButton(
                                onClick = {
                                    val activity = context as? Activity
                                    if (isRecording) {
                                        activity?.startService(Intent(context, HlsRecorderService::class.java).setAction(HlsRecorderService.ACTION_STOP))
                                        isRecording = false
                                    } else if (selected!!.url.contains(".m3u8", ignoreCase = true)) {
                                        recordingError = ""
                                        showRecordingOptions = true
                                    } else {
                                        android.widget.Toast.makeText(context, "ضبط مستقیم فقط برای لینک‌های HLS / M3U8 پشتیبانی می‌شود.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isRecording) Color(0xFFFF6B6B) else Gold)
                            ) {
                                Icon(if (isRecording) Icons.Default.StopCircle else Icons.Default.FiberManualRecord, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isRecording) "توقف ضبط" else "تنظیم مدت و ضبط")
                            }
                        }
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
                        onSchedule = { showSchedule = true },
                        onAbout = { page = "درباره ما" },
                        onSources = { page = "افزودن منابع" },
                        onRecordings = { page = "ضبط‌ها" },
                        onScheduledList = { page = "زمان‌بندی‌ها" },
                        onLiveDubbing = { page = "دوبله هوشمند زنده" },
                        onMultiView = { page = "پخش همزمان" },
                        onEpg = { page = "راهنمای برنامه‌ها" },
                        onSettings = { page = "تنظیمات" },
                        onBackupExport = { backupExportLauncher.launch("SALAR-TV-Backup.json") },
                        onBackupImport = { backupImportLauncher.launch(arrayOf("application/json", "text/json", "*/*")) }
                    )
                    "پخش همزمان" -> MultiViewPage(channels)
                    "دوبله هوشمند زنده" -> LiveDubbingPage()
                    "راهنمای برنامه‌ها" -> EpgPage(channels)
                    "تنظیمات" -> SettingsPage(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            prefs.edit().putString("theme_mode", mode).apply()
                        }
                    )
                    "زمان‌بندی‌ها" -> ScheduledRecordingsPage(
                        schedules = scheduledRecordings.toList(),
                        onRefresh = {
                            scheduledRecordings.clear()
                            runCatching {
                                val saved = JSONArray(prefs.getString("schedules_json", "[]") ?: "[]")
                                for (i in 0 until saved.length()) {
                                    val item = saved.getJSONObject(i)
                                    scheduledRecordings.add(ScheduledRecording(
                                        item.optInt("requestCode"), item.optString("title"),
                                        item.optString("url"), item.optLong("startMillis"),
                                        item.optInt("durationMinutes", 60), item.optString("status", "scheduled")
                                    ))
                                }
                            }
                        },
                        onCancel = { item ->
                            val alarmIntent = Intent(context, RecordingAlarmReceiver::class.java)
                            val pending = PendingIntent.getBroadcast(context, item.requestCode, alarmIntent,
                                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                            if (pending != null) {
                                context.getSystemService(AlarmManager::class.java).cancel(pending)
                                pending.cancel()
                            }
                            scheduledRecordings.removeAll { it.requestCode == item.requestCode }
                            saveScheduledRecordings()
                            android.widget.Toast.makeText(context, "زمان‌بندی ضبط لغو شد.", android.widget.Toast.LENGTH_SHORT).show()
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
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() },
                        onRefresh = ::refreshChannelDirectory, refreshing = refreshingChannels, refreshStatus = channelRefreshStatus)
                    "رادیو" -> ChannelList(channels.filter { it.kind == "رادیو" }, onSelect = { selected = it },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() },
                        onRefresh = ::refreshChannelDirectory, refreshing = refreshingChannels, refreshStatus = channelRefreshStatus)
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

    if (showFullscreenPlayer && selected != null && !useExternalPlayer) {
        Dialog(
            onDismissRequest = {
                showFullscreenPlayer = false
                (context as? Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                    modifier = Modifier.fillMaxSize()
                )
                Row(
                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = {
                        forceLandscape = !forceLandscape
                        (context as? Activity)?.requestedOrientation =
                            if (forceLandscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }) {
                        Icon(Icons.Default.ScreenRotation, "چرخش تصویر", tint = Color.White)
                    }
                    IconButton(onClick = {
                        showFullscreenPlayer = false
                        (context as? Activity)?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }) {
                        Icon(Icons.Default.FullscreenExit, "خروج از تمام‌صفحه", tint = Color.White)
                    }
                }
            }
        }
    }

    if (showRecordingOptions && selected != null) {
        AlertDialog(
            onDismissRequest = { showRecordingOptions = false },
            title = { Text("مدت ضبط شبکه", color = Gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("مدت را به دقیقه وارد کن. برای ضبط بدون محدودیت زمانی، عدد ۰ را وارد کن.")
                    OutlinedTextField(
                        value = recordingDuration,
                        onValueChange = { recordingDuration = it.filter(Char::isDigit).take(5) },
                        label = { Text("مدت ضبط (دقیقه)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("60", "120", "240", "480", "0").forEach { value ->
                            TextButton(onClick = { recordingDuration = value }) {
                                Text(if (value == "0") "بدون محدودیت" else "$value دقیقه")
                            }
                        }
                    }
                    if (recordingError.isNotBlank()) Text(recordingError, color = MaterialTheme.colorScheme.error)
                    Text("ضبط بدون محدودیت با دکمهٔ توقف ضبط پایان می‌یابد.", color = MutedText, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = recordingDuration.toIntOrNull()
                    if (minutes == null || minutes !in 0..10080) {
                        recordingError = "مدت باید بین ۰ تا ۱۰٬۰۸۰ دقیقه باشد."
                    } else {
                        val intent = Intent(context, HlsRecorderService::class.java).apply {
                            putExtra("name", selected!!.name)
                            putExtra("url", selected!!.url)
                            putExtra("duration", minutes)
                        }
                        runCatching { ContextCompat.startForegroundService(context, intent) }
                            .onSuccess { isRecording = true; showRecordingOptions = false }
                            .onFailure { recordingError = "شروع ضبط ممکن نشد: " + (it.message ?: "خطا") }
                    }
                }) { Text("شروع ضبط", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { showRecordingOptions = false }) { Text("انصراف") } }
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
                    OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(5) },
                        label = { Text("مدت ضبط به دقیقه (۰ تا ۱۰٬۰۸۰؛ صفر = بدون محدودیت)") }, singleLine = true,
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
                        val minutes = duration.toIntOrNull()?.takeIf { it in 0..10080 } ?: error("مدت ضبط را بین صفر تا ۱۰٬۰۸۰ دقیقه وارد کنید؛ صفر یعنی ضبط تا توقف دستی.")
                        require(title.isNotBlank()) { "نام برنامه را وارد کنید." }
                        require(streamUrl.startsWith("https://") || streamUrl.startsWith("http://")) { "لینک معتبر HLS وارد کنید." }
                        require(startMillis > System.currentTimeMillis()) { "زمان شروع باید در آینده باشد." }
                        val requestCode = (title.trim() + streamUrl.trim() + startMillis).hashCode()
                        val alarmIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                            putExtra("name", title.trim())
                            putExtra("url", streamUrl.trim())
                            putExtra("duration", minutes)
                            putExtra("schedule_id", requestCode)
                        }
                        val pending = PendingIntent.getBroadcast(context, requestCode, alarmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val alarm = context.getSystemService(AlarmManager::class.java)
                        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, pending)
                        schedules = (schedules + "\n$title | $dateTime | " + if (minutes == 0) "بدون محدودیت" else "$minutes دقیقه").trim()
                        prefs.edit().putString("schedules", schedules).apply()
                        scheduledRecordings.add(ScheduledRecording(requestCode, title.trim(), streamUrl.trim(), startMillis, minutes))
                        saveScheduledRecordings()
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
fun HomePage(channels: List<Channel>, onSelect: (Channel) -> Unit, onAdd: () -> Unit,
             onEdit: (Channel) -> Unit = {}, onDelete: (Channel) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("دنیای تلویزیون و رادیو", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("ایران • ترکیه • جمهوری آذربایجان • جهان", color = SecondaryText)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
                    Icon(Icons.Default.Add, null); Text("افزودن شبکه یا سرور", color = Color.Black)
                }
            }
        }
        Text("شبکه‌های شما", color = Gold, fontWeight = FontWeight.Bold)
        if (channels.isEmpty()) {
            Text("هنوز شبکه‌ای اضافه نشده است. از بخش افزودن منابع، لینک مجاز یا فایل M3U وارد کنید.",
                color = SecondaryText)
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
fun ChannelList(
    channels: List<Channel>,
    onSelect: (Channel) -> Unit,
    onFavorite: (Channel) -> Unit,
    onEdit: (Channel) -> Unit = {},
    onDelete: (Channel) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    refreshStatus: String = ""
) {
    var searchQuery by remember { mutableStateOf("") }
    val visibleChannels = channels.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
            it.country.contains(searchQuery, ignoreCase = true) ||
            categoryForChannel(it).contains(searchQuery, ignoreCase = true)
    }
    val sections = visibleChannels.groupBy { "${it.country} / ${categoryForChannel(it)} / ${it.kind}" }.toSortedMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onRefresh != null) {
            item(key = "channel_refresh") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("فهرست شبکه‌ها", color = Gold, fontWeight = FontWeight.Bold)
                        Text(
                            if (refreshStatus.isNotBlank()) refreshStatus else "برای دریافت شبکه‌های تازه، فهرست را به‌روزرسانی کنید.",
                            color = MutedText,
                            fontSize = 10.sp
                        )
                    }
                    Button(
                        onClick = onRefresh,
                        enabled = !refreshing,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("در حال دریافت")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("به‌روزرسانی شبکه‌ها")
                        }
                    }
                }
            }
        }
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
        if (visibleChannels.isEmpty()) {
            item { Text("موردی برای نمایش وجود ندارد.", color = SecondaryText) }
        }
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
            items(sectionChannels, key = { it.url }) { channel ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(channel) },
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(channel.logoUrl, channel.kind)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(channel.name, color = TextMain, fontWeight = FontWeight.SemiBold)
                            Text("${channel.country} • ${channel.kind}", color = MutedText, fontSize = 11.sp)
                        }
                        IconButton(onClick = { onFavorite(channel) }) {
                            Icon(Icons.Default.Favorite, null, tint = if (channel.favorite) Gold else MutedText)
                        }
                        IconButton(onClick = { onEdit(channel) }) {
                            Icon(Icons.Default.Edit, "ویرایش", tint = SecondaryText)
                        }
                        IconButton(onClick = { onDelete(channel) }) {
                            Icon(Icons.Default.Delete, null, tint = Color(0xFFE57373))
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
             onRecordings: () -> Unit, onScheduledList: () -> Unit, onLiveDubbing: () -> Unit, onMultiView: () -> Unit, onEpg: () -> Unit, onSettings: () -> Unit,
             onBackupExport: () -> Unit, onBackupImport: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("امکانات بیشتر", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        MoreButton("تنظیمات", Icons.Default.Settings, onSettings)
        MoreButton("پخش هم‌زمان ۲ یا ۴ شبکه", Icons.Default.Tv, onMultiView)
        MoreButton("مترجم و دوبلور هوشمند زنده", Icons.Default.Translate, onLiveDubbing)
        MoreButton("راهنمای برنامه‌ها (EPG)", Icons.Default.CalendarMonth, onEpg)
        MoreButton("افزودن لینک و سرور", Icons.Default.AddLink, onSources)
        MoreButton("زمان‌بندی ضبط", Icons.Default.CalendarMonth, onSchedule)
        MoreButton("مدیریت ضبط‌های زمان‌بندی‌شده", Icons.Default.EventNote, onScheduledList)
        MoreButton("فایل‌های ضبط‌شده", Icons.Default.VideoLibrary, onRecordings)
        MoreButton("تهیه نسخه پشتیبان", Icons.Default.Backup, onBackupExport)
        MoreButton("بازیابی نسخه پشتیبان", Icons.Default.Restore, onBackupImport)
        MoreButton("درباره سازنده", Icons.Default.Info, onAbout)
        Text("زبان‌های هدف: فارسی، ترکی استانبولی، ترکی آذربایجانی و انگلیسی",
            color = SecondaryText, fontSize = 12.sp)
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
fun ScheduledRecordingsPage(
    schedules: List<ScheduledRecording>,
    onRefresh: () -> Unit,
    onCancel: (ScheduledRecording) -> Unit
) {
    val now = System.currentTimeMillis()
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy/MM/dd • HH:mm") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("مدیریت ضبط‌های زمان‌بندی‌شده", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("زمان‌بندی‌های ثبت‌شده روی همین دستگاه نمایش داده می‌شوند. برای لغو، دکمهٔ لغو همان مورد را بزنید.",
            color = SecondaryText, fontSize = 12.sp)
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("به‌روزرسانی وضعیت زمان‌بندی‌ها")
        }
        if (schedules.isEmpty()) {
            Text("هنوز ضبط زمان‌بندی‌شده‌ای ثبت نشده است.", color = MutedText)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(schedules.sortedBy { it.startMillis }, key = { it.requestCode }) { item ->
                    val pending = item.startMillis > now
                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, color = Gold, fontWeight = FontWeight.Bold)
                            Text(formatter.format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(item.startMillis), ZoneId.systemDefault())),
                                color = TextMain)
                            Text(if (item.durationMinutes == 0) "مدت: بدون محدودیت" else "مدت: ${item.durationMinutes} دقیقه",
                                color = SecondaryText, fontSize = 12.sp)
                            Text(when (item.status) {
                                "start_requested" -> "وضعیت: درخواست شروع ضبط ارسال شد"
                                "start_failed", "failed" -> "وضعیت: ضبط ناموفق بود"
                                "recording" -> "وضعیت: در حال ضبط"
                                "completed" -> "وضعیت: ضبط تکمیل شد"
                                "stopped" -> "وضعیت: ضبط متوقف شد"
                                else -> if (pending) "وضعیت: در انتظار اجرا" else "وضعیت: زمان اجرا گذشته؛ نتیجه آلارم ثبت نشده"
                            }, color = when (item.status) {
                                "start_failed", "failed" -> Color(0xFFFF8A80)
                                "start_requested", "recording", "completed" -> Color(0xFF9CCC65)
                                "stopped" -> Color(0xFFFFCC80)
                                else -> if (pending) SecondaryText else Color(0xFFFFCC80)
                            }, fontSize = 11.sp)
                            if (pending) {
                                TextButton(onClick = { onCancel(item) }) {
                                    Icon(Icons.Default.Cancel, null, tint = Color(0xFFFF8A80))
                                    Spacer(Modifier.width(6.dp))
                                    Text("لغو این زمان‌بندی", color = Color(0xFFFF8A80))
                                }
                            }
                        }
                    }
                }
            }
        }
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
    if (channel.kind == "رادیو") {
        val radioGroup = channel.groupTitle.lowercase()
        return when {
            "استانی" in radioGroup -> "استانی / " + channel.groupTitle.substringAfterLast("/").trim()
            "خارج از ایران" in radioGroup || "خارج از کشور" in radioGroup -> "فارسی‌زبان خارج از کشور"
            "سراسری" in radioGroup || "ایران / رادیو" in radioGroup -> "سراسری"
            else -> "فارسی‌زبان خارج از کشور"
        }
    }
    val value = (channel.name + " " + channel.country + " " + channel.groupTitle).lowercase()
    return when {
        "اسرائیل / شبکه‌های عربی" in value -> "اسرائیل - عربی"
        "اسرائیل / شبکه‌های بین‌المللی" in value -> "اسرائیل - بین‌المللی"
        "اسرائیل / شبکه‌های عبری" in value -> "اسرائیل - عبری"
        "رسانه‌های عربی بین‌المللی" in value -> "خبر عربی بین‌المللی"
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
    var tvgId = ""
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            title = line.substringAfter(",", "شبکه بدون نام").trim().ifBlank { "شبکه بدون نام" }
            tvgId = Regex("tvg-id=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
            logoUrl = Regex("tvg-logo=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
            val lower = line.lowercase()
            groupTitle = Regex("group-title=\\\"([^\\\"]*)\\\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1).orEmpty()
            val group = groupTitle.lowercase()
            val meta = "$lower $group"
            country = when {
                "شبکه‌های خبری عربی بین‌المللی" in meta -> "رسانه‌های عربی بین‌المللی"
                "فارسی‌زبان خارج از ایران" in meta || "فارسی زبان خارج از ایران" in meta -> "فارسی‌زبان خارج از ایران"
                "azerbaijan" in meta || "azərbaycan" in meta || "آذربایجان" in meta || "az/" in meta -> "آذربایجان"
                "turkey" in meta || "turkiye" in meta || "türkiye" in meta || "ترکیه" in meta || "tr/" in meta -> "ترکیه"
                "iran" in meta || "irib" in meta || "ایران" in meta || "ir/" in meta || "شبکه‌های استانی" in meta -> "ایران"
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
            result.add(Channel(title, line, country, kind, logoUrl = logoUrl, groupTitle = groupTitle, tvgId = tvgId))
            title = "شبکه بدون نام"; country = "سایر"; kind = "تلویزیون"; logoUrl = ""; groupTitle = ""; tvgId = ""
        }
    }
    return result.distinctBy { it.url }
}


private data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val stopMillis: Long
)
private data class EpgRow(val channelId: String, val channelName: String, val current: EpgProgram?, val next: EpgProgram?)

private fun parseXmlTvDate(value: String): Long? {
    for (pattern in listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmss")) {
        try {
            return SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value.trim())?.time
        } catch (_: Exception) { }
    }
    return null
}

private fun readXmlTv(input: InputStream): Pair<Map<String, String>, List<EpgProgram>> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(input, "UTF-8")
    val channelNames = linkedMapOf<String, String>()
    val programs = mutableListOf<EpgProgram>()
    val now = System.currentTimeMillis()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            when (parser.name) {
                "channel" -> {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    var displayName = ""
                    var depth = 1
                    while (depth > 0) {
                        when (parser.next()) {
                            XmlPullParser.START_TAG -> {
                                depth++
                                if (parser.name == "display-name") {
                                    val candidate = runCatching { parser.nextText() }.getOrDefault("")
                                    if (displayName.isBlank()) displayName = candidate.trim()
                                    depth--
                                }
                            }
                            XmlPullParser.END_TAG -> depth--
                            XmlPullParser.END_DOCUMENT -> depth = 0
                        }
                    }
                    if (id.isNotBlank()) channelNames[id] = displayName.ifBlank { id }
                }
                "programme" -> {
                    val id = parser.getAttributeValue(null, "channel").orEmpty()
                    val start = parseXmlTvDate(parser.getAttributeValue(null, "start").orEmpty())
                    val stop = parseXmlTvDate(parser.getAttributeValue(null, "stop").orEmpty())
                    var title = ""
                    var description = ""
                    var depth = 1
                    while (depth > 0) {
                        when (parser.next()) {
                            XmlPullParser.START_TAG -> {
                                depth++
                                when (parser.name) {
                                    "title" -> {
                                        title = runCatching { parser.nextText() }.getOrDefault("").trim()
                                        depth--
                                    }
                                    "desc" -> {
                                        description = runCatching { parser.nextText() }.getOrDefault("").trim()
                                        depth--
                                    }
                                }
                            }
                            XmlPullParser.END_TAG -> depth--
                            XmlPullParser.END_DOCUMENT -> depth = 0
                        }
                    }
                    if (id.isNotBlank() && start != null && stop != null &&
                        stop > now - 2 * 60 * 60 * 1000L &&
                        start < now + 24 * 60 * 60 * 1000L && title.isNotBlank()) {
                        programs.add(EpgProgram(id, title, description, start, stop))
                    }
                }
            }
        }
        event = parser.next()
        if (programs.size >= 20000) break
    }
    return channelNames to programs
}

@Composable
private fun EpgPage(channels: List<Channel>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("salar_tv", 0) }
    val iranGuide = "https://iptv-org.github.io/epg/guides/ir/tva.tv.epg.xml.gz"
    val turkeyGuide = "https://iptv-epg.org/files/epg-tr.xml"
    val israelGuide = "https://iptv-epg.org/files/epg-il.xml"
    val savedGuide = (prefs.getString("xmltv_url", iranGuide) ?: iranGuide).ifBlank { iranGuide }
    var xmlUrl by remember { mutableStateOf(savedGuide) }
    var loadedUrl by remember { mutableStateOf(savedGuide) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<EpgRow>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("منبع راهنمای برنامه‌های ایران آماده دریافت است.") }

    fun loadGuide() {
        val targets = xmlUrl.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (targets.isEmpty()) {
            status = "حداقل یک نشانی XMLTV وارد کنید."
            return
        }
        val invalid = targets.firstOrNull {
            !it.startsWith("https://", true) && !it.startsWith("http://", true)
        }
        if (invalid != null) {
            status = "نشانی نامعتبر است: $invalid"
            return
        }
        val normalized = targets.distinct().joinToString("\n")
        prefs.edit().putString("xmltv_url", normalized).apply()
        xmlUrl = normalized
        loadedUrl = normalized
        reloadKey++
    }

    fun addGuide(source: String) {
        val current = xmlUrl.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (source !in current) xmlUrl = (current + source).joinToString("\n")
    }

    LaunchedEffect(loadedUrl, reloadKey, channels.size) {
        if (loadedUrl.isBlank()) return@LaunchedEffect
        loading = true
        status = "در حال دریافت و پردازش راهنمای برنامه‌ها…"
        try {
            val sources = loadedUrl.lines().map { it.trim() }.filter { it.isNotBlank() }
            val mergedNames = linkedMapOf<String, String>()
            val mergedPrograms = mutableListOf<EpgProgram>()
            val sourceErrors = mutableListOf<String>()
            for (source in sources) {
                try {
                    val parsed = withContext(Dispatchers.IO) {
                        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15000
                            readTimeout = 30000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", "SALAR-TV/1.4 Android")
                            setRequestProperty("Accept-Encoding", "identity")
                        }
                        try {
                            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                            val raw = connection.inputStream
                            val input = if (source.substringBefore("?").endsWith(".gz", true)) GZIPInputStream(raw) else raw
                            input.use { readXmlTv(it) }
                        } finally {
                            connection.disconnect()
                        }
                    }
                    mergedNames.putAll(parsed.first)
                    mergedPrograms.addAll(parsed.second)
                } catch (e: Exception) {
                    sourceErrors += source.substringAfterLast("/").take(48) + ": " + (e.message ?: "خطای دریافت")
                }
            }
            if (mergedPrograms.isEmpty()) {
                error(if (sourceErrors.isEmpty()) "هیچ برنامه‌ای در منابع پیدا نشد." else sourceErrors.joinToString("؛ "))
            }

            val now = System.currentTimeMillis()
            val appById = channels.filter { it.tvgId.isNotBlank() }.associateBy { it.tvgId }
            val allRows = mergedPrograms.groupBy { it.channelId }.mapNotNull { (id, list) ->
                val ordered = list.distinctBy { "${id}|${it.startMillis}|${it.stopMillis}|${it.title}" }.sortedBy { it.startMillis }
                val current = ordered.firstOrNull { it.startMillis <= now && it.stopMillis > now }
                val next = ordered.firstOrNull { it.startMillis > now }
                if (current == null && next == null) null
                else EpgRow(id, appById[id]?.name ?: mergedNames[id] ?: id, current, next)
            }.sortedBy { it.channelName.lowercase(Locale.ROOT) }

            val matchedRows = if (appById.isEmpty()) emptyList() else allRows.filter { it.channelId in appById.keys }
            rows = if (matchedRows.isNotEmpty()) matchedRows else allRows
            status = buildString {
                append("${rows.size} شبکه دارای برنامه جاری یا بعدی هستند.")
                if (matchedRows.isNotEmpty()) append(" ${matchedRows.size} مورد با شناسه فهرست SALAR TV تطبیق یافت.")
                else append(" تطبیق شناسه با فهرست فعلی پیدا نشد؛ راهنما به‌صورت عمومی نمایش داده می‌شود.")
                if (sourceErrors.isNotEmpty()) append(" منابع ناموفق: ${sourceErrors.joinToString("؛ ")}")
            }
        } catch (e: Exception) {
            rows = emptyList()
            status = "دریافت راهنما ناموفق بود: ${e.message ?: "خطای نامشخص"}"
        } finally {
            loading = false
        }
    }

    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("راهنمای برنامه‌ها (EPG)", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("هر خط یک نشانی XMLTV است؛ فایل‌های فشرده ‎.gz نیز پشتیبانی می‌شوند. برنامه جاری و بعدی بر اساس شناسه شبکه تطبیق داده می‌شود.",
            color = SecondaryText, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { addGuide(iranGuide) }) { Text("راهنمای ایران") }
            OutlinedButton(onClick = { addGuide(turkeyGuide) }) { Text("راهنمای ترکیه") }
            OutlinedButton(onClick = { addGuide(israelGuide) }) { Text("راهنمای اسرائیل") }
        }
        OutlinedTextField(
            value = xmlUrl, onValueChange = { xmlUrl = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("نشانی‌های XMLTV (هر خط یک منبع)") },
            placeholder = { Text("https://example.com/guide.xml.gz") },
            minLines = 2, maxLines = 4
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { loadGuide() }, enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
                Text("دریافت / به‌روزرسانی", color = Color.Black)
            }
            if (loadedUrl.isNotBlank()) {
                TextButton(onClick = { reloadKey++ }, enabled = !loading) { Text("تلاش دوباره", color = Gold) }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
        Text(status, color = if (status.contains("ناموفق") || status.contains("نامعتبر")) Color(0xFFFFCC80) else SecondaryText,
            fontSize = 12.sp)
        if (rows.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows) { row ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(row.channelName, color = Gold, fontWeight = FontWeight.Bold)
                            row.current?.let { p ->
                                Text("اکنون: ${p.title}", color = TextMain, fontWeight = FontWeight.SemiBold)
                                Text("${clock.format(Date(p.startMillis))} تا ${clock.format(Date(p.stopMillis))}",
                                    color = MutedText, fontSize = 10.sp)
                                if (p.description.isNotBlank()) Text(p.description, color = SecondaryText, fontSize = 11.sp, maxLines = 3)
                            }
                            row.next?.let { p ->
                                Text("بعدی: ${p.title}", color = TextMain)
                                Text("${clock.format(Date(p.startMillis))} تا ${clock.format(Date(p.stopMillis))}",
                                    color = MutedText, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
