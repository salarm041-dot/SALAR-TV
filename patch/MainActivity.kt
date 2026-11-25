package com.salar.tv

import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
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
import android.content.res.Configuration
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
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

private const val REMOTE_TV_PLAYLIST = "https://raw.githubusercontent.com/salarm041-dot/SALAR-TV/main/patch/channels.m3u"
private const val REMOTE_RADIO_PLAYLIST = "https://raw.githubusercontent.com/salarm041-dot/SALAR-TV/main/patch/radio-candidates.m3u"

data class Channel(
    val name: String,
    val url: String,
    val country: String = "سایر",
    val kind: String = "تلویزیون",
    val favorite: Boolean = false,
    val logoUrl: String = "",
    val groupTitle: String = ""
)

data class SubtitleChoice(val groupIndex: Int, val trackIndex: Int, val label: String)

data class ScheduledRecording(val name: String, val url: String, val startMillis: Long, val durationMinutes: Int) {
    val requestCode: Int get() = (name + url + startMillis).hashCode()
}

class MainActivity : ComponentActivity() {
    var pipMode by mutableStateOf(false)
        private set
    var activePlayer: ExoPlayer? = null

    fun enterPlayerPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (activePlayer?.isPlaying != true) {
            android.widget.Toast.makeText(this, "ابتدا پخش زنده را اجرا کنید.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }.onFailure {
            android.widget.Toast.makeText(this, "ورود به تصویر در تصویر ممکن نشد.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activePlayer?.isPlaying == true) {
            enterPlayerPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipMode = isInPictureInPictureMode
        setContent {
            SalarTvLaunch(isInPip = pipMode) {
                SalarTvApp(isInPip = pipMode)
            }
        }
    }
}

@Composable
private fun SalarTvLaunch(isInPip: Boolean, content: @Composable () -> Unit) {
    var showSplash by remember { mutableStateOf(!isInPip) }
    LaunchedEffect(isInPip) {
        if (isInPip) {
            showSplash = false
        } else {
            showSplash = true
            delay(2300)
            showSplash = false
        }
    }
    if (showSplash) {
        val pulse = rememberInfiniteTransition(label = "salarSplash")
            .animateFloat(
                initialValue = 0.96f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1050),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoPulse"
            )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF050505)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_salar_tv),
                    contentDescription = "لوگوی کامل SALAR TV",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(148.dp)
                        .padding(9.dp)
                        .graphicsLayer {
                            scaleX = pulse.value
                            scaleY = pulse.value
                        }
                )
                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = "SALAR TV",
                    color = Color(0xFFE2B85F),
                    fontSize = 29.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "سید سالار موسوی ملکی",
                    color = Color(0xFFF5F1E8),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "نسخه ۱٫۱",
                    color = Color(0xFFB9A477),
                    fontSize = 12.sp
                )
            }
        }
    } else {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalarTvApp(isInPip: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("salar_tv", 0) }
    var appFontScale by remember { mutableFloatStateOf(prefs.getFloat("font_scale", 1f).coerceIn(0.85f, 1.3f)) }
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

    // Enable Persian subtitle translation once for existing installs as well as new installs.
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("persian_subtitle_default_migrated", false)) {
            prefs.edit()
                .putBoolean("live_subtitle_enabled", true)
                .putBoolean("persian_subtitle_default_migrated", true)
                .apply()
        }
    }

    // Seed the bundled public playlist once. Existing user channels are preserved.
    LaunchedEffect(Unit) {
        val bundled = runCatching {
            context.assets.open("channels.m3u").bufferedReader().use { parseM3u(it.readText()) }
        }.getOrDefault(emptyList())
        // Replace obsolete built-in pilgrimage links without touching user-added locations.
        val obsoletePilgrimageUrls = setOf(
            "https://haram.razavi.ir/live",
            "https://imamhussain.gov.iq/english/live",
            "https://en.imamali.net/live/",
            "https://imamhusseintv.com/en/live/6",
            "https://www.youtube.com/embed/kYqGbBqmp8g?autoplay=1&playsinline=1",
            "https://www.youtube.com/embed/9A1S0xAPVIs?autoplay=1&playsinline=1"
        )
        channels.removeAll { it.country == "زیارت آنلاین" && it.url in obsoletePilgrimageUrls }
        val pilgrimage = listOf(
            Channel("حرم مطهر رضوی — پخش زنده رسمی", "https://haram.razavi.ir/live", "زیارت آنلاین", "وب", groupTitle = "مشهد مقدس"),
            Channel("حرم امام حسین (ع) — پخش زنده ۲۴ ساعته رسمی", "https://www.youtube.com/@imamhussainlivestream/live", "زیارت آنلاین", "وب", groupTitle = "کربلا"),
            Channel("حرم امام حسین (ع) — پخش زنده وبگاه رسمی", "https://imamhussain.gov.iq/persian/live", "زیارت آنلاین", "وب", groupTitle = "کربلا"),
            Channel("حرم مطهر امام علی (ع) — پخش زنده رسمی", "https://en.imamali.net/live/", "زیارت آنلاین", "وب", groupTitle = "نجف اشرف"),
            Channel("مسجدالحرام مکه — پخش زنده قرآن TV", "https://www.youtube.com/embed/live_stream?channel=UCos52azQNBgW63_9uDJoPDA&autoplay=1&playsinline=1", "زیارت آنلاین", "وب", groupTitle = "مکه مکرمه"),
            Channel("مسجدالنبی مدینه — پخش زنده ۲۴ ساعته شبکه السنة النبوية", "https://www.youtube.com/@SaudiSunnahTv/live", "زیارت آنلاین", "وب", groupTitle = "مدینه منوره")
        )
        val arabic = runCatching {
            context.assets.open("arabic-channels.m3u").bufferedReader().use { parseM3u(it.readText()) }
        }.getOrDefault(emptyList())
        val world = runCatching {
            context.assets.open("world-channels.m3u").bufferedReader().use { parseM3u(it.readText()) }
        }.getOrDefault(emptyList())
        val existing = channels.map { it.url }.toSet()
        channels.addAll((bundled + pilgrimage + arabic + world).distinctBy { it.url }.filter { it.url !in existing })
        saveChannels()
        prefs.edit().putBoolean("built_in_seeded", true).apply()
    }

    val selectedDynamicCountries = remember {
        mutableStateListOf<String>().apply {
            val ordered = runCatching {
                val json = JSONArray(prefs.getString("home_dynamic_countries_order", "[]") ?: "[]")
                List(json.length()) { json.optString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
            val legacy = prefs.getStringSet("home_dynamic_countries", emptySet())?.toList().orEmpty()
            addAll((ordered + legacy).distinct())
        }
    }
    fun rememberDynamicCountry(country: String) {
        if (country !in selectedDynamicCountries) {
            selectedDynamicCountries.add(country)
            prefs.edit()
                .putString("home_dynamic_countries_order", JSONArray(selectedDynamicCountries).toString())
                .putStringSet("home_dynamic_countries", selectedDynamicCountries.toSet())
                .apply()
        }
    }
    var page by remember { mutableStateOf("خانه") }
    var isRefreshingChannels by remember { mutableStateOf(false) }
    var lastChannelRefresh by remember { mutableStateOf(prefs.getString("last_channel_refresh", "") ?: "") }
    val refreshScope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Channel?>(null) }
    val lockedChannelUrls = remember {
        mutableStateListOf<String>().apply {
            runCatching {
                val saved = JSONArray(prefs.getString("child_locked_urls", "[]") ?: "[]")
                for (i in 0 until saved.length()) add(saved.optString(i))
            }
        }
    }
    var childPinHash by remember { mutableStateOf(prefs.getString("child_lock_pin_hash", "") ?: "") }
    var pendingLockedChannel by remember { mutableStateOf<Channel?>(null) }
    var showChildPinDialog by remember { mutableStateOf(false) }
    var childPinEntry by remember { mutableStateOf("") }
    var childPinError by remember { mutableStateOf("") }
    fun saveLockedChannels() {
        prefs.edit().putString("child_locked_urls", JSONArray(lockedChannelUrls).toString()).apply()
    }
    fun selectChannel(channel: Channel) {
        if (channel.url in lockedChannelUrls) {
            pendingLockedChannel = channel
            childPinEntry = ""
            childPinError = ""
            showChildPinDialog = true
        } else selected = channel
    }
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
    var liveDubbingEnabled by remember { mutableStateOf(prefs.getBoolean("live_dubbing_enabled", false)) }
    var keepOriginalAudio by remember { mutableStateOf(prefs.getBoolean("live_dubbing_original", true)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "live_dubbing_enabled" -> liveDubbingEnabled = prefs.getBoolean("live_dubbing_enabled", false)
                "live_dubbing_original" -> keepOriginalAudio = prefs.getBoolean("live_dubbing_original", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) { "light" -> false; "system" -> systemDark; else -> true }
    val selectedPalette = when (themeMode) {
        "gold_gloss" -> listOf(Color(0xFFFFD76A), Color(0xFF050505), Color(0xFF17130A), Color(0xFFFFF8E6), Color(0xFFE5D4A8), Color(0xFFAD9664))
        "silver_gloss" -> listOf(Color(0xFFE4EDF7), Color(0xFF080B10), Color(0xFF1B222C), Color(0xFFF5F8FC), Color(0xFFC7D0DC), Color(0xFF8793A3))
        "ocean" -> listOf(Color(0xFF48C8FF), Color(0xFF071827), Color(0xFF102B40), Color(0xFFEAF7FF), Color(0xFFB7D5E6), Color(0xFF7897AA))
        "emerald" -> listOf(Color(0xFF45D6A0), Color(0xFF071B17), Color(0xFF102B24), Color(0xFFE9FFF5), Color(0xFFB7DCCB), Color(0xFF789C8D))
        "purple" -> listOf(Color(0xFFB99AFF), Color(0xFF130D22), Color(0xFF251936), Color(0xFFF5EEFF), Color(0xFFD0C0E8), Color(0xFF9988B2))
        "ruby" -> listOf(Color(0xFFFF718B), Color(0xFF200D15), Color(0xFF351923), Color(0xFFFFF0F2), Color(0xFFE6BEC6), Color(0xFFAD8992))
        "sunset" -> listOf(Color(0xFFFFA45B), Color(0xFF21130D), Color(0xFF382216), Color(0xFFFFF4E9), Color(0xFFE7CBB2), Color(0xFFB3957C))
        "graphite" -> listOf(Color(0xFFB7C3D0), Color(0xFF101419), Color(0xFF202832), Color(0xFFF1F5F9), Color(0xFFC4CDD7), Color(0xFF8D99A8))
        "light" -> listOf(Color(0xFF9A6818), Color(0xFFF5F3EE), Color(0xFFFFFFFF), Color(0xFF20232A), Color(0xFF555B66), Color(0xFF707782))
        "system" -> if (systemDark) listOf(Color(0xFFD6AD62), Color(0xFF0B0D13), Color(0xFF171B26), Color(0xFFF5F1E8), Color.LightGray, Color.Gray)
                    else listOf(Color(0xFF9A6818), Color(0xFFF5F3EE), Color(0xFFFFFFFF), Color(0xFF20232A), Color(0xFF555B66), Color(0xFF707782))
        "dark" -> listOf(Color(0xFFFFD76A), Color(0xFF050505), Color(0xFF17130A), Color(0xFFFFF8E6), Color(0xFFE5D4A8), Color(0xFFAD9664))
        else -> listOf(Color(0xFFD6AD62), Color(0xFF0B0D13), Color(0xFF171B26), Color(0xFFF5F1E8), Color.LightGray, Color.Gray)
    }

    SideEffect {
        val nextGold = selectedPalette[0]
        val nextBackground = selectedPalette[1]
        val nextPanel = selectedPalette[2]
        val nextText = selectedPalette[3]
        val nextSecondary = selectedPalette[4]
        val nextMuted = selectedPalette[5]
        if (Gold != nextGold) Gold = nextGold
        if (Background != nextBackground) Background = nextBackground
        if (Panel != nextPanel) Panel = nextPanel
        if (TextMain != nextText) TextMain = nextText
        if (SecondaryText != nextSecondary) SecondaryText = nextSecondary
        if (MutedText != nextMuted) MutedText = nextMuted
    }

    val refreshChannels: (String) -> Unit = { requestedKind ->
        if (!isRefreshingChannels) {
            isRefreshingChannels = true
            refreshScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val sourceUrl = if (requestedKind == "رادیو") REMOTE_RADIO_PLAYLIST else REMOTE_TV_PLAYLIST
                        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection)
                        connection.connectTimeout = 15000
                        connection.readTimeout = 30000
                        connection.requestMethod = "GET"
                        connection.setRequestProperty("User-Agent", "SALAR-TV-Android/1.3")
                        connection.setRequestProperty("Cache-Control", "no-cache")
                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) error("دریافت فهرست ناموفق بود؛ HTTP $code")
                            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            require(body.trimStart().startsWith("#EXTM3U", ignoreCase = true)) { "پاسخ سرور، فهرست معتبر M3U نیست." }
                            val parsed = parseM3u(body).filter { it.kind == requestedKind && it.url.startsWith("https://") }
                            require(parsed.isNotEmpty()) { "هیچ شبکه معتبری در فهرست دریافتی پیدا نشد." }
                            parsed
                        } finally { connection.disconnect() }
                    }
                }
                result.onSuccess { remote ->
                    val knownUrls = channels.map { it.url.trim().lowercase(Locale.ROOT) }.toMutableSet()
                    val fresh = remote.filter { channel ->
                        val normalized = channel.url.trim().lowercase(Locale.ROOT)
                        normalized.isNotBlank() && knownUrls.add(normalized)
                    }
                    channels.addAll(fresh)
                    saveChannels()
                    val refreshedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))
                    lastChannelRefresh = refreshedAt
                    prefs.edit()
                        .putString("last_channel_refresh", refreshedAt)
                        .putLong("last_auto_refresh_${requestedKind}", System.currentTimeMillis())
                        .apply()
                    android.widget.Toast.makeText(context,
                        if (fresh.isEmpty()) "فهرست بررسی شد؛ شبکه جدیدی برای افزودن وجود نداشت." else "${fresh.size} شبکه جدید اضافه شد.",
                        android.widget.Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    android.widget.Toast.makeText(context, "بروزرسانی انجام نشد؛ فهرست قبلی حفظ شد. ${error.message ?: "خطای اتصال"}",
                        android.widget.Toast.LENGTH_LONG).show()
                }
                isRefreshingChannels = false
            }
        }
    }

    LaunchedEffect(page) {
        val requestedKind = when {
            page == "رادیو" -> "رادیو"
            page == "تلویزیون" || page.startsWith("کشور:") -> "تلویزیون"
            else -> null
        }
        if (requestedKind != null) {
            val lastAutoRefresh = prefs.getLong("last_auto_refresh_${requestedKind}", 0L)
            val refreshInterval = 24L * 60L * 60L * 1000L
            if (System.currentTimeMillis() - lastAutoRefresh >= refreshInterval) {
                refreshChannels(requestedKind)
            }
        }
    }

    val player = remember { ExoPlayer.Builder(context).build() }
    LaunchedEffect(player, liveDubbingEnabled, keepOriginalAudio) {
        // Preserve speech-recognition input while lowering the original track
        // when the user wants Persian TTS dubbing to stand out.
        player.volume = if (liveDubbingEnabled && !keepOriginalAudio) 0.25f else 1f
    }
    DisposableEffect(player) {
        (context as? MainActivity)?.activePlayer = player
        onDispose {
            val activity = context as? MainActivity
            if (activity?.activePlayer === player) activity.activePlayer = null
            player.release()
        }
    }

    // PiP must render only the video surface. The normal app scaffold and all controls
    // are removed while the Activity is in picture-in-picture mode.
    if (isInPip && selected != null && selected!!.kind != "وب" && !useExternalPlayer) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                setKeepContentOnPlayerReset(true)
            } },
            update = { view ->
                if (view.player !== player) view.player = player
                view.useController = false
                view.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

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

    CompositionLocalProvider(LocalDensity provides LocalDensity.current.copy(fontScale = appFontScale)) {
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
                    if (selected!!.kind != "وب") {
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
                    }
                    if (selected!!.kind == "وب") {
                        val webUrl = selected!!.url
                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    settings.loadsImagesAutomatically = true
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                    webViewClient = object : android.webkit.WebViewClient() {
                                        override fun onReceivedError(
                                            view: android.webkit.WebView,
                                            request: android.webkit.WebResourceRequest,
                                            error: android.webkit.WebResourceError
                                        ) {
                                            if (request.isForMainFrame) {
                                                android.widget.Toast.makeText(ctx, "بارگذاری صفحه پخش ناموفق بود؛ اتصال اینترنت یا دسترسی منطقه‌ای را بررسی کن.", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    val hostActivity = ctx as? android.app.Activity
                                    val originalUiFlags = hostActivity?.window?.decorView?.systemUiVisibility ?: 0
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        private var fullscreenView: android.view.View? = null
                                        private var fullscreenCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
                                        override fun onShowCustomView(view: android.view.View, callback: android.webkit.WebChromeClient.CustomViewCallback) {
                                            val decor = hostActivity?.window?.decorView as? android.widget.FrameLayout
                                            if (decor == null || fullscreenView != null) {
                                                callback.onCustomViewHidden()
                                                return
                                            }
                                            fullscreenView = view
                                            fullscreenCallback = callback
                                            decor.addView(view, android.widget.FrameLayout.LayoutParams(
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                            ))
                                            hostActivity?.window?.decorView?.systemUiVisibility =
                                                (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                                        }
                                        override fun onHideCustomView() {
                                            val decor = hostActivity?.window?.decorView as? android.widget.FrameLayout
                                            fullscreenView?.let { decor?.removeView(it) }
                                            fullscreenView = null
                                            hostActivity?.window?.decorView?.systemUiVisibility = originalUiFlags
                                            fullscreenCallback?.onCustomViewHidden()
                                            fullscreenCallback = null
                                        }
                                    }
                                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    loadUrl(webUrl)
                                }
                            },
                            update = { view -> if (view.url != webUrl) view.loadUrl(webUrl) },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        Text("صفحهٔ پخش رسمی درون برنامه باز می‌شود؛ دکمهٔ تمام‌صفحهٔ ویدئو نیز پشتیبانی می‌شود. دسترسی نهایی به وضعیت پخش منبع و اینترنت بستگی دارد.",
                            color = SecondaryText, fontSize = 11.sp)
                    } else if (!useExternalPlayer) {
                        var playerMenuExpanded by remember(selected!!.url) { mutableStateOf(false) }
                        var showSubtitleDialog by remember(selected!!.url) { mutableStateOf(false) }
                        var showDubbingDialog by remember(selected!!.url) { mutableStateOf(false) }
                        var subtitleChoices by remember(selected!!.url) { mutableStateOf(emptyList<SubtitleChoice>()) }
                        var menuDubbingEnabled by remember { mutableStateOf(prefs.getBoolean("live_dubbing_enabled", false)) }
                        var menuSubtitleEnabled by remember { mutableStateOf(prefs.getBoolean("live_subtitle_enabled", true)) }
                        var menuDubbingVoice by remember { mutableStateOf(if (prefs.getString("live_dubbing_voice", "زیر") == "بم" || prefs.getString("live_dubbing_voice", "") == "مرد") "بم" else "زیر") }
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                            AndroidView(
                                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                                update = { view ->
                                    view.subtitleView?.visibility = if (menuSubtitleEnabled) android.view.View.INVISIBLE else android.view.View.VISIBLE
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (menuSubtitleEnabled) {
                                LiveSubtitleTranslationOverlay(
                                    player = player,
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                                )
                            }
                            LiveAudioTranslationOverlay(
                                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(0.92f).padding(8.dp)
                            )
                            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                                IconButton(
                                    onClick = { playerMenuExpanded = true },
                                    modifier = Modifier.background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(50))
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "تنظیمات پخش", tint = Color.White)
                                }
                                DropdownMenu(expanded = playerMenuExpanded, onDismissRequest = { playerMenuExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text("انتخاب زیرنویس") },
                                        leadingIcon = { Icon(Icons.Default.Subtitles, null) },
                                        onClick = {
                                            subtitleChoices = buildList {
                                                player.currentTracks.groups.forEachIndexed { groupIndex, group ->
                                                    if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                                                        for (trackIndex in 0 until group.length) {
                                                            if (group.isTrackSupported(trackIndex)) {
                                                                val format = group.getTrackFormat(trackIndex)
                                                                val language = format.language?.takeIf { it.isNotBlank() } ?: "بدون نام"
                                                                val label = format.label?.takeIf { it.isNotBlank() } ?: language
                                                                add(SubtitleChoice(groupIndex, trackIndex, label))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            showSubtitleDialog = true
                                            playerMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("پخش چندنمایی") },
                                        leadingIcon = { Icon(Icons.Default.GridView, null) },
                                        onClick = {
                                            playerMenuExpanded = false
                                            selected = null
                                            page = "چندنمایی"
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("تنظیمات دوبله") },
                                        leadingIcon = { Icon(Icons.Default.Translate, null) },
                                        onClick = { showDubbingDialog = true; playerMenuExpanded = false }
                                    )
                                }
                            }
                        }
                        if (showSubtitleDialog) {
                            AlertDialog(
                                onDismissRequest = { showSubtitleDialog = false },
                                title = { Text("انتخاب زیرنویس") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("خاموش", modifier = Modifier.fillMaxWidth().clickable {
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                                .build()
                                            showSubtitleDialog = false
                                        }.padding(vertical = 8.dp))
                                        if (subtitleChoices.isEmpty()) {
                                            Text("این شبکه در حال حاضر زیرنویس قابل انتخابی ارائه نمی‌کند.", color = SecondaryText)
                                        } else subtitleChoices.forEach { choice ->
                                            Text(choice.label, modifier = Modifier.fillMaxWidth().clickable {
                                                val group = player.currentTracks.groups.getOrNull(choice.groupIndex)
                                                if (group != null) {
                                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(
                                                            group.mediaTrackGroup, listOf(choice.trackIndex)
                                                        )).build()
                                                }
                                                showSubtitleDialog = false
                                            }.padding(vertical = 8.dp))
                                        }
                                    }
                                },
                                confirmButton = { TextButton(onClick = { showSubtitleDialog = false }) { Text("بستن") } }
                            )
                        }
                        if (showDubbingDialog) {
                            AlertDialog(
                                onDismissRequest = { showDubbingDialog = false },
                                title = { Text("دوبله فارسی") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("تنظیمات دوبله برای این پخش", color = Gold, fontWeight = FontWeight.Bold)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("فعال‌سازی دوبله", modifier = Modifier.weight(1f))
                                            Switch(checked = menuDubbingEnabled, onCheckedChange = {
                                                menuDubbingEnabled = it
                                                prefs.edit().putBoolean("live_dubbing_enabled", it).apply()
                                            })
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("زیرنویس هوشمند فارسی", modifier = Modifier.weight(1f))
                                            Switch(checked = menuSubtitleEnabled, onCheckedChange = {
                                                menuSubtitleEnabled = it
                                                prefs.edit().putBoolean("live_subtitle_enabled", it).apply()
                                            })
                                        }
                                        Text("زیر و بمی صدای دوبله")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("زیر", "بم").forEach { voiceOption ->
                                                FilterChip(selected = menuDubbingVoice == voiceOption, onClick = {
                                                    menuDubbingVoice = voiceOption
                                                    prefs.edit().putString("live_dubbing_voice", voiceOption).apply()
                                                }, label = { Text(voiceOption) })
                                            }
                                        }
                                        Text("ترجمه زیرنویس متنی با موتور ML Kit به برنامه متصل شده است؛ باید زیرنویس شبکه را انتخاب کنید. دوبله صوتی زنده هنوز متصل نیست.",
                                            color = Color(0xFFFFCC80), fontSize = 11.sp)
                                    }
                                },
                                confirmButton = { TextButton(onClick = { showDubbingDialog = false }) { Text("تمام") } }
                            )
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
                    LaunchedEffect(selected!!.url, useExternalPlayer, selected!!.kind) {
                        val streamAddress = selected!!.url
                        val officialWebPage = streamAddress.contains("ott.iranintl.com", ignoreCase = true) ||
                            streamAddress.contains("iranintl.com/live", ignoreCase = true)
                        if (selected!!.kind == "وب") {
                            player.pause()
                        } else if (officialWebPage) {
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
                        onMultiView = { page = "چندنمایی" },
                    )
                    "چندنمایی" -> MultiViewPage(
                        channels = channels.filter { it.kind == "تلویزیون" && it.url !in lockedChannelUrls }
                    )
                    "زیارت آنلاین" -> PilgrimagePage(
                        savedChannels = channels,
                        onPlay = { selectChannel(it) },
                        onAdd = { channel -> channels.add(channel); saveChannels() }
                    )
                    "دوبله هوشمند زنده" -> LiveDubbingPage()
                    "تنظیمات" -> SettingsPage(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            prefs.edit().putString("theme_mode", mode).apply()
                        },
                        onChildLock = { page = "قفل کودک" },
                        fontScale = appFontScale,
                        onFontScaleChange = { scale ->
                            appFontScale = scale.coerceIn(0.85f, 1.3f)
                            prefs.edit().putFloat("font_scale", appFontScale).apply()
                        }
                    )
                    "قفل کودک" -> ChildLockPage(
                        channels = channels.distinctBy { it.url },
                        lockedUrls = lockedChannelUrls.toSet(),
                        currentPinHash = childPinHash,
                        onBack = { page = "تنظیمات" },
                        onToggle = { channel, locked, enteredPin ->
                            if (hashChildPin(enteredPin) == childPinHash) {
                                if (locked && channel.url !in lockedChannelUrls) lockedChannelUrls.add(channel.url)
                                else if (!locked) lockedChannelUrls.remove(channel.url)
                                saveLockedChannels()
                                true
                            } else false
                        },
                        onSavePin = { oldPin, newPin ->
                            val oldMatches = childPinHash.isBlank() || hashChildPin(oldPin) == childPinHash
                            if (oldMatches && newPin.length in 4..8 && newPin.all(Char::isDigit)) {
                                childPinHash = hashChildPin(newPin)
                                prefs.edit().putString("child_lock_pin_hash", childPinHash).apply()
                                true
                            } else false
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
                            ?: context.filesDir).listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("ts", "mp4", "m4v", "mkv", "mp3", "aac", "ogg", "wav", "m4a", "audio") }
                            ?.sortedByDescending { it.lastModified() } ?: emptyList(),
                        onPlay = { file ->
                            useExternalPlayer = false
                            selected = Channel(file.nameWithoutExtension, Uri.fromFile(file).toString(), "ضبط‌شده", "تلویزیون")
                        },
                        onDelete = { file -> file.delete(); page = "ضبط‌ها" }
                    )
                    "درباره ما" -> AboutPage()
                    "تلویزیون" -> TvCountryPage(onCountry = { country -> page = if (country == "عربی") "شبکه‌های عربی" else "کشور:$country" })
                    "شبکه‌های عربی" -> ArabicCountriesPage(channels = channels, onCountry = { country -> page = "عربی:$country" })
                    "کشور:سایر" -> WorldChannelsPage(channels = channels, onGroup = { group -> page = "جهان:$group" })
                    "کشورهای منتخب" -> TvCountryPage(onCountry = { country -> page = "کشور:$country" })
                    "کشور:ایران" -> IranTvPage(
                        channels = channels,
                        onSelect = { selectChannel(it) },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                        onEdit = { editingChannel = it },
                        onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() },
                        onRefresh = { refreshChannels("تلویزیون") },
                        isRefreshing = isRefreshingChannels,
                        lastRefresh = lastChannelRefresh
                    )
                    page.takeIf { it.startsWith("کشور:") } -> {
                        val country = page.removePrefix("کشور:")
                        val arabicCountries = setOf("عربستان", "امارات", "قطر", "کویت", "بحرین", "عمان", "عراق", "اردن", "لبنان", "سوریه", "فلسطین", "مصر", "مراکش", "الجزایر", "تونس", "لیبی", "یمن", "سودان")
                        val filtered = channels.filter { c ->
                            c.kind == "تلویزیون" && when (country) {
                                "ایران" -> c.country == "ایران"
                                "ترکیه" -> c.country == "ترکیه"
                                "آذربایجان" -> c.country == "آذربایجان" || c.country == "جمهوری آذربایجان"
                                "اسرائیل" -> c.country == "اسرائیل"
                                "سایر" -> c.country == "سایر" || c.country == "خارج از ایران" ||
                                    c.country == "فارسی‌زبان خارج از ایران" ||
                                    (c.country !in setOf("ایران", "ترکیه", "آذربایجان", "جمهوری آذربایجان", "اسرائیل") && c.country !in arabicCountries)
                                else -> c.country == country
                            }
                        }
                        ChannelList(filtered, onSelect = { selectChannel(it) },
                            onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                            onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() },
                            onRefresh = { refreshChannels("تلویزیون") }, isRefreshing = isRefreshingChannels, lastRefresh = lastChannelRefresh)
                    }
                    "رادیو" -> ChannelList(channels.filter { it.kind == "رادیو" }, onSelect = { selectChannel(it) },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() },
                        onRefresh = { refreshChannels("رادیو") }, isRefreshing = isRefreshingChannels, lastRefresh = lastChannelRefresh)
                    "علاقه‌مندی‌ها" -> ChannelList(channels.filter { it.favorite }, onSelect = { selectChannel(it) },
                        onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() })
                    else -> if (page.startsWith("عربی:") || page.startsWith("جهان:")) {
                        val group = if (page.startsWith("عربی:")) page.removePrefix("عربی:") else page.removePrefix("جهان:")
                        ChannelList(channels.filter { it.kind == "تلویزیون" && it.country == group },
                            onSelect = { selectChannel(it) },
                            onFavorite = { c -> toggleFavorite(channels, c, ::saveChannels) },
                            onEdit = { editingChannel = it },
                            onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() })
                    } else HomePage(channels, onSelect = { selectChannel(it) }, onAdd = { page = "افزودن منابع" },
                        onEdit = { editingChannel = it }, onDelete = { c -> channels.removeAll { it.url == c.url }; saveChannels() },
                        onPage = { destination -> page = destination },
                        dynamicCountries = (listOf("ایران", "ترکیه", "آذربایجان", "اسرائیل", "عربی", "سایر") + selectedDynamicCountries)
                            .distinct().map { name ->
                                name to when (name) {
                                    "ایران" -> "🇮🇷"
                                    "ترکیه" -> "🇹🇷"
                                    "آذربایجان", "جمهوری آذربایجان" -> "🇦🇿"
                                    "اسرائیل" -> "🇮🇱"
                                    "عربی" -> "🌍"
                                    else -> "🌐"
                                }
                            },
                        onDynamicCountry = { country -> page = "کشور:$country" },
                        onMoreDynamicCountries = { page = "کشورهای منتخب" })
                }
            }
        }
    }
    }

    if (showChildPinDialog) {
        AlertDialog(
            onDismissRequest = { showChildPinDialog = false; pendingLockedChannel = null },
            title = { Text("قفل کودک", color = Gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("برای پخش «${pendingLockedChannel?.name ?: "این شبکه"}» رمز والد را وارد کنید.")
                    OutlinedTextField(
                        value = childPinEntry,
                        onValueChange = { childPinEntry = it.filter(Char::isDigit).take(8); childPinError = "" },
                        label = { Text("رمز والد") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (childPinError.isNotBlank()) Text(childPinError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (hashChildPin(childPinEntry) == childPinHash && childPinEntry.isNotBlank()) {
                        selected = pendingLockedChannel
                        showChildPinDialog = false
                        pendingLockedChannel = null
                        childPinEntry = ""
                    } else childPinError = "رمز واردشده صحیح نیست."
                }) { Text("تأیید و پخش", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { showChildPinDialog = false; pendingLockedChannel = null }) { Text("انصراف") } }
        )
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
                        if (index >= 0) channels[index] = original.copy(
                            name = name.trim(), url = url.trim(), country = country, kind = kind
                        )
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
                    OutlinedTextField(streamUrl, { streamUrl = it }, label = { Text("لینک پخش مستقیم یا HLS / M3U8") },
                        placeholder = { Text("https://server/live/index.m3u8") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text("زمان شروع (نمونه: 2026-12-01 20:30)")
                    OutlinedTextField(dateTime, { dateTime = it }, placeholder = { Text("YYYY-MM-DD HH:mm") }, singleLine = true)
                    OutlinedTextField(duration, { duration = it.filter(Char::isDigit).take(3) },
                        label = { Text("مدت ضبط به دقیقه (۱ تا ۳۶۰)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (scheduleError.isNotBlank()) Text(scheduleError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Text("ضبط شبکه‌های HLS و رادیوهای دارای جریان مستقیم HTTP/HTTPS پشتیبانی می‌شود؛ اتصال اینترنت پایدار لازم است.",
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
                        require(streamUrl.trim().startsWith("https://") || streamUrl.trim().startsWith("http://")) { "لینک معتبر HTTP یا HTTPS وارد کنید." }
                        require(startMillis > System.currentTimeMillis()) { "زمان شروع باید در آینده باشد." }
                        val requestCode = (title + streamUrl + startMillis).hashCode()
                        val newItem = ScheduledRecording(title.trim(), streamUrl.trim(), startMillis, minutes)
                        require(scheduledRecordings.none { it.requestCode == newItem.requestCode }) {
                            "این ضبط قبلاً زمان‌بندی شده است."
                        }
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
             onEdit: (Channel) -> Unit = {}, onDelete: (Channel) -> Unit = {},
             onPage: (String) -> Unit = {},
             dynamicCountries: List<Pair<String, String>> = emptyList(),
             onDynamicCountry: (String) -> Unit = {},
             onMoreDynamicCountries: () -> Unit = {}) {
    val tvCount = channels.count { it.kind == "تلویزیون" }
    val radioCount = channels.count { it.kind == "رادیو" }
    val countries = dynamicCountries.distinctBy { it.first }
    val gold = Gold
    val neonBlue = Gold
    val quick = listOf(
        Triple("تلویزیون", "پخش زنده شبکه‌ها", Icons.Default.LiveTv),
        Triple("رادیو", "ایستگاه‌های رادیویی", Icons.Default.Radio),
        Triple("شبکه‌های خارجی", "کشورهای جهان", Icons.Default.Public),
        Triple("زمان‌بندی‌ها", "ضبط خودکار برنامه", Icons.Default.CalendarMonth),
        Triple("زیارت آنلاین", "پخش حرم‌های مطهر", Icons.Default.AccountBalance),
        Triple("افزودن منابع", "لینک و فهرست M3U", Icons.Default.AddCircle),
        Triple("تنظیمات", "شخصی‌سازی برنامه", Icons.Default.Settings),
        Triple("علاقه‌مندی‌ها", "شبکه‌های منتخب", Icons.Default.Favorite)
    )
    val accents = listOf(Color(0xFF18A7FF), Color(0xFFFFC247), Color(0xFF5D8CFF), Color(0xFFFF9C3D),
        Color(0xFFFFC247), Color(0xFF28C9E8), Color(0xFF9BA9C5), Color(0xFFFF6E9A))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(17.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Gold.copy(alpha = .98f), Gold.copy(alpha = .42f), Background)),
                RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LiveTv, null, tint = Background, modifier = Modifier.size(35.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SALAR ", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("TV", color = gold, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
                Text("تلویزیون، رادیو و سرگرمی آنلاین", color = SecondaryText, fontSize = 11.sp)
            }
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF101F36),
                border = androidx.compose.foundation.BorderStroke(1.dp, gold.copy(alpha = 0.65f))) {
                IconButton(onClick = onAdd) { Icon(Icons.Default.AddLink, "افزودن منبع", tint = gold) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("$tvCount شبکه تلویزیونی" to Icons.Default.Tv, "$radioCount ایستگاه رادیویی" to Icons.Default.Radio).forEach { item ->
                Surface(Modifier.weight(1f), color = Panel, shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .52f))) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(item.second, null, tint = gold, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(item.first, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("دسترسی سریع", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text("۸ بخش اصلی", color = gold, fontSize = 10.sp)
        }
        quick.chunked(2).forEachIndexed { rowIndex, rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                rowItems.forEachIndexed { index, item ->
                    val itemIndex = rowIndex * 2 + index
                    val accent = accents[itemIndex]
                    Card(Modifier.weight(1f).height(124.dp).clickable { onPage(if (item.first == "شبکه‌های خارجی") "تلویزیون" else item.first) },
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, accent.copy(alpha = .92f))) {
                        Box(Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.linearGradient(listOf(accent.copy(alpha = .26f), Panel, Background)))) {
                            Box(Modifier.align(Alignment.TopEnd).size(88.dp).background(
                                androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = .28f), Color.Transparent)),
                                RoundedCornerShape(bottomStart = 88.dp)))
                            Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = .95f), Color.Transparent))))
                            Column(Modifier.fillMaxSize().padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween) {
                                Box(Modifier.size(52.dp).background(
                                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = .42f), Color(0xFF10223D))),
                                    RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                    Icon(item.third, null, tint = accent, modifier = Modifier.size(34.dp))
                                }
                                Text(item.first, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                Text(item.second, color = SecondaryText, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("انتخاب کشور", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text("برای دیدن همه، افقی بکشید  ↔", color = gold, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            countries.forEachIndexed { index, pair ->
                val (name, flag) = pair
                val accent = when(name) {
                    "ایران" -> Color(0xFF31D69A)
                    "ترکیه" -> Color(0xFFFF6375)
                    "آذربایجان", "جمهوری آذربایجان" -> Color(0xFF35C9F4)
                    "اسرائیل" -> Color(0xFF77A9FF)
                    else -> accents[index % accents.size]
                }
                Card(Modifier.width(116.dp).height(130.dp).clickable { onDynamicCountry(name) },
                    shape = RoundedCornerShape(21.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, accent)) {
                    Box(Modifier.fillMaxSize().background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = .28f), Panel, Background)))) {
                        Column(Modifier.fillMaxSize().padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly) {
                            CountryArtwork(name = name, accent = accent, compact = true)
                            Text(name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            Text("مشاهده شبکه‌ها  ›", color = accent, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("شبکه‌های منتخب", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("مشاهده تلویزیون  ←", color = gold, fontSize = 10.sp, modifier = Modifier.clickable { onPage("تلویزیون") })
        }
        val featured = channels.filter { it.kind == "تلویزیون" && it.logoUrl.isNotBlank() }.take(8)
        if (featured.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                featured.forEach { channel ->
                    Card(Modifier.width(94.dp).clickable { onSelect(channel) }, shape = RoundedCornerShape(15.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF101D32)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1C75C9))) {
                        Column(Modifier.fillMaxWidth().padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            ChannelLogo(channel.logoUrl, channel.kind)
                            Text(channel.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryArtwork(name: String, accent: Color, compact: Boolean = false) {
    val emblem = when (name) {
        "ایران" -> "🏛️"
        "ترکیه" -> "🕌"
        "آذربایجان", "جمهوری آذربایجان" -> "🔥"
        "عربی" -> "🌙"
        "اسرائیل" -> "🏙️"
        else -> "🌐"
    }
    val landmark = when (name) {
        "ایران" -> "برج آزادی"
        "ترکیه" -> "ایاصوفیه"
        "آذربایجان", "جمهوری آذربایجان" -> "برج‌های شعله"
        else -> "شبکه‌های جهانی"
    }
    val flagColors = when (name) {
        "ایران" -> listOf(Color(0xFF239B56), Color.White, Color(0xFFDA3A3A))
        "ترکیه" -> listOf(Color(0xFFE32636), Color(0xFFE32636), Color(0xFFE32636))
        "آذربایجان", "جمهوری آذربایجان" -> listOf(Color(0xFF20A7E8), Color(0xFFE84155), Color(0xFF36A66A))
        else -> listOf(accent, Color(0xFF182A48), accent)
    }
    val artworkHeight = if (compact) 58.dp else 82.dp
    Box(
        Modifier.fillMaxWidth().height(artworkHeight)
            .background(Panel, RoundedCornerShape(13.dp))
            .border(1.dp, accent.copy(alpha = .65f), RoundedCornerShape(13.dp))
            .clip(RoundedCornerShape(13.dp))
    ) {
        Column(Modifier.fillMaxSize()) {
            flagColors.forEach { color -> Box(Modifier.fillMaxWidth().weight(1f).background(color.copy(alpha = .72f))) }
        }
        Box(Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                listOf(Color(0xFF061225).copy(alpha = .08f), Color(0xFF061225).copy(alpha = .82f))
            )
        ))
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(emblem, fontSize = if (compact) 29.sp else 43.sp)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                if (name == "ترکیه") Text("☾ ★", color = Color.White, fontSize = if (compact) 12.sp else 16.sp,
                    fontWeight = FontWeight.Black)
                if (name == "آذربایجان" || name == "جمهوری آذربایجان") Text("☾ ★", color = Color.White,
                    fontSize = if (compact) 12.sp else 16.sp, fontWeight = FontWeight.Black)
                Text(landmark, color = Color.White, fontSize = if (compact) 8.sp else 11.sp,
                    fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun TvCountryPage(onCountry: (String) -> Unit) {
    data class CountryCard(val name: String, val color: Color, val subtitle: String)
    val countries = listOf(
        CountryCard("ایران", Color(0xFF36D69A), "شبکه‌های سراسری و استانی"),
        CountryCard("ترکیه", Color(0xFFFF6578), "شبکه‌های ملی و خصوصی"),
        CountryCard("آذربایجان", Color(0xFF36C9F1), "شبکه‌های جمهوری آذربایجان"),
        CountryCard("اسرائیل", Color(0xFF78A9FF), "شبکه‌های داخلی و بین‌المللی"),
        CountryCard("عربی", Color(0xFFFFB84A), "شبکه‌های کشورهای عربی"),
        CountryCard("سایر", Color(0xFFB69AFF), "دیگر شبکه‌های جهان")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("شبکه‌های تلویزیونی", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text("کشور و منطقهٔ موردنظر را انتخاب کنید", color = Color(0xFFB8C8E0), fontSize = 12.sp)
        countries.chunked(2).forEach { rowCountries ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                rowCountries.forEach { country ->
                    Card(Modifier.weight(1f).height(174.dp).clickable { onCountry(country.name) },
                        shape = RoundedCornerShape(21.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF09182E)),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, country.color)) {
                        Box(Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(country.color.copy(alpha = .28f),
                                Color(0xFF102342), Color(0xFF070F20))))) {
                            Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly) {
                                CountryArtwork(country.name, country.color)
                                Text(country.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                Text(country.subtitle, color = country.color, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun ArabicCountriesPage(channels: List<Channel>, onCountry: (String) -> Unit) {
    data class ArabicCountry(val name: String, val flag: String, val accent: Color)
    val countries = listOf(
        ArabicCountry("عربستان", "🇸🇦", Color(0xFF45D483)),
        ArabicCountry("امارات", "🇦🇪", Color(0xFF39C9E8)),
        ArabicCountry("قطر", "🇶🇦", Color(0xFFB58BDB)),
        ArabicCountry("کویت", "🇰🇼", Color(0xFF67A9FF)),
        ArabicCountry("بحرین", "🇧🇭", Color(0xFFFFB45E)),
        ArabicCountry("عمان", "🇴🇲", Color(0xFF45C9A1)),
        ArabicCountry("عراق", "🇮🇶", Color(0xFFE5B84D)),
        ArabicCountry("اردن", "🇯🇴", Color(0xFFE77C75)),
        ArabicCountry("لبنان", "🇱🇧", Color(0xFF68C9B0)),
        ArabicCountry("سوریه", "🇸🇾", Color(0xFFD58A62)),
        ArabicCountry("فلسطین", "🇵🇸", Color(0xFF6BCB8C)),
        ArabicCountry("مصر", "🇪🇬", Color(0xFFE0B45D)),
        ArabicCountry("مراکش", "🇲🇦", Color(0xFFDB7777)),
        ArabicCountry("الجزایر", "🇩🇿", Color(0xFF75B98A)),
        ArabicCountry("تونس", "🇹🇳", Color(0xFFE06D75)),
        ArabicCountry("لیبی", "🇱🇾", Color(0xFF79A9D9)),
        ArabicCountry("یمن", "🇾🇪", Color(0xFFCE9B65)),
        ArabicCountry("سودان", "🇸🇩", Color(0xFFB28BD6))
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("شبکه‌های کشورهای عربی", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text("کشور را انتخاب کنید تا شبکه‌های همان کشور نمایش داده شوند",
            color = Color(0xFFB8C8E0), fontSize = 12.sp)
        countries.chunked(2).forEach { rowCountries ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                rowCountries.forEach { country ->
                    val count = channels.count { it.kind == "تلویزیون" && it.country == country.name }
                    Card(Modifier.weight(1f).height(132.dp).clickable { onCountry(country.name) },
                        shape = RoundedCornerShape(19.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF09182E)),
                        border = androidx.compose.foundation.BorderStroke(1.4.dp, country.accent)) {
                        Box(Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(country.accent.copy(alpha = .25f), Color(0xFF102342), Color(0xFF070F20))))) {
                            Column(Modifier.fillMaxSize().padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly) {
                                Text(country.flag, fontSize = 30.sp)
                                Text(country.name, color = Color.White, fontSize = 15.sp,
                                    fontWeight = FontWeight.ExtraBold)
                                Text("$count شبکه", color = country.accent, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (rowCountries.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


@Composable
fun WorldChannelsPage(channels: List<Channel>, onGroup: (String) -> Unit) {
    data class WorldGroup(val name: String, val flag: String, val accent: Color)
    val groups = listOf(
        WorldGroup("روسیه", "🇷🇺", Color(0xFF6EA8FF)),
        WorldGroup("آمریکا", "🇺🇸", Color(0xFFE77B85)),
        WorldGroup("فرانسه", "🇫🇷", Color(0xFF7A9DFF)),
        WorldGroup("آلمان", "🇩🇪", Color(0xFFFFBD65)),
        WorldGroup("زبان کردی", "🟩", Color(0xFF55C99A)),
        WorldGroup("زبان روسی", "🟥", Color(0xFF7AA7F5)),
        WorldGroup("زبان فرانسوی", "🟦", Color(0xFF72A9E8)),
        WorldGroup("زبان آلمانی", "⬛", Color(0xFFE2B45F)),
        WorldGroup("زبان انگلیسی", "🌐", Color(0xFFB28BDE))
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("شبکه‌های جهان", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text("انتخاب کشور یا زبان؛ فهرست‌ها با لینک‌های پخش تلویزیونی بارگذاری می‌شوند",
            color = Color(0xFFB8C8E0), fontSize = 11.sp)
        groups.chunked(2).forEach { rowGroups ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                rowGroups.forEach { group ->
                    val count = channels.count { it.kind == "تلویزیون" && it.country == group.name }
                    Card(Modifier.weight(1f).height(125.dp).clickable { onGroup(group.name) },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF09182E)),
                        border = androidx.compose.foundation.BorderStroke(1.4.dp, group.accent)) {
                        Box(Modifier.fillMaxSize().background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(group.accent.copy(alpha = .24f), Color(0xFF102342), Color(0xFF070F20))))) {
                            Column(Modifier.fillMaxSize().padding(9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly) {
                                Text(group.flag, fontSize = 28.sp)
                                Text(group.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                Text("$count شبکه", color = group.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (rowGroups.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun IranTvPage(channels: List<Channel>, onSelect: (Channel) -> Unit, onFavorite: (Channel) -> Unit,
               onEdit: (Channel) -> Unit, onDelete: (Channel) -> Unit,
               onRefresh: () -> Unit, isRefreshing: Boolean, lastRefresh: String) {
    var section by remember { mutableStateOf("همه") }
    val iran = channels.filter { it.kind == "تلویزیون" && it.country == "ایران" }
    fun isNational(c: Channel) = categoryForChannel(c) == "شبکه‌های سراسری"
    fun isProvincial(c: Channel) = categoryForChannel(c) == "شبکه‌های استانی"
    fun isSatellite(c: Channel): Boolean {
        val v = (c.name + " " + c.groupTitle).lowercase()
        return listOf("ماهواره", "satellite", "persiana", "gem ", "manoto", "ایران اینترنشنال",
            "من و تو", "بی‌بی‌سی فارسی", "bbc persian", "voa").any { it in v } ||
            (!isNational(c) && !isProvincial(c))
    }
    val filtered = when(section) {
        "سراسری" -> iran.filter(::isNational)
        "استانی" -> iran.filter(::isProvincial)
        "ماهواره" -> iran.filter(::isSatellite)
        else -> iran
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("تلویزیون ایران", color = TextMain, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Text(filtered.size.toString() + " شبکه در این بخش", color = SecondaryText, fontSize = 11.sp)
            }
            Surface(color = Panel, shape = RoundedCornerShape(13.dp)) {
                Text("🇮🇷", fontSize = 24.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("همه", "سراسری", "استانی", "ماهواره").forEach { tab ->
                val selected = section == tab
                Surface(Modifier.weight(1f).clickable { section = tab },
                    color = if (selected) Gold else Color.Transparent, shape = RoundedCornerShape(11.dp)) {
                    Text(tab, color = if (selected) Background else SecondaryText,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        if (section == "سراسری") Text("شبکه‌های ملی صدا و سیما", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        else if (section == "استانی") Text("همه شبکه‌های استانی در یک فهرست", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        else if (section == "ماهواره") Text("شبکه‌های فارسی‌زبان و ماهواره‌ای", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f)) {
            ChannelList(filtered, onSelect = onSelect, onFavorite = onFavorite, onEdit = onEdit, onDelete = onDelete,
                onRefresh = onRefresh, isRefreshing = isRefreshing, lastRefresh = lastRefresh, largeCards = true)
        }
    }
}
@Composable
// Reads optional tvg-logo URLs from M3U and renders a safe TV/radio fallback.
private fun ChannelLogo(logoUrl: String, kind: String, size: androidx.compose.ui.unit.Dp = 42.dp) {
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
        Modifier.size(size).background(Panel, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "لوگوی شبکه",
                modifier = Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
        } else {
            Icon(if (kind == "رادیو") Icons.Default.Radio else Icons.Default.Tv,
                contentDescription = null, tint = Gold, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
fun ChannelList(channels: List<Channel>, onSelect: (Channel) -> Unit, onFavorite: (Channel) -> Unit,
                onEdit: (Channel) -> Unit = {}, onDelete: (Channel) -> Unit = {},
                onRefresh: (() -> Unit)? = null, isRefreshing: Boolean = false, lastRefresh: String = "",
                largeCards: Boolean = false) {
    var searchQuery by remember { mutableStateOf("") }
    if (channels.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("موردی برای نمایش وجود ندارد.", color = SecondaryText)
            if (onRefresh != null) Button(onClick = onRefresh, enabled = !isRefreshing,
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) {
                if (isRefreshing) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                else Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isRefreshing) "در حال دریافت فهرست..." else "بروزرسانی شبکه‌ها")
            }
        }
    } else {
        val visibleChannels = channels.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.country.contains(searchQuery, ignoreCase = true) ||
                categoryForChannel(it).contains(searchQuery, ignoreCase = true)
        }
        val sections = visibleChannels.groupBy { channel ->
            if (channel.kind == "رادیو") {
                "${channel.country.ifBlank { "سایر" }} / رادیو"
            } else {
                "${channel.country.ifBlank { "سایر" }} / ${categoryForChannel(channel)}"
            }
        }.toSortedMap(compareBy<String> { sectionSortOrder(it) }.thenBy { it })
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRefresh != null) item(key = "refresh_channels") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Button(onClick = onRefresh, enabled = !isRefreshing, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) {
                        if (isRefreshing) CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRefreshing) "در حال دریافت فهرست تازه..." else "بروزرسانی شبکه‌ها")
                    }
                    if (lastRefresh.isNotBlank()) Text("آخرین بروزرسانی موفق: $lastRefresh", color = MutedText, fontSize = 10.sp)
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
                items(
                    sectionChannels.sortedWith(
                        compareBy<Channel> { channelSortOrder(it) }.thenBy { it.name.lowercase(Locale.ROOT) }
                    ).chunked(if (largeCards) 2 else 1),
                    key = { rowChannels -> rowChannels.first().url }
                ) { rowChannels ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowChannels.forEach { c ->
                            if (largeCards) {
                                Card(
                                    modifier = Modifier.weight(1f).height(178.dp).clickable { onSelect(c) },
                                    colors = CardDefaults.cardColors(containerColor = Panel),
                                    shape = RoundedCornerShape(18.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .48f))
                                ) {
                                    Column(
                                        Modifier.fillMaxSize().padding(9.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        ChannelLogo(c.logoUrl, c.kind, size = 86.dp)
                                        Text(
                                            c.name,
                                            color = TextMain,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(onClick = { onFavorite(c) }, modifier = Modifier.size(30.dp)) {
                                                Icon(Icons.Default.Favorite, "علاقه‌مندی",
                                                    tint = if (c.favorite) Gold else MutedText, modifier = Modifier.size(17.dp))
                                            }
                                            IconButton(onClick = { onEdit(c) }, modifier = Modifier.size(30.dp)) {
                                                Icon(Icons.Default.Edit, "ویرایش", tint = SecondaryText, modifier = Modifier.size(17.dp))
                                            }
                                            IconButton(onClick = { onDelete(c) }, modifier = Modifier.size(30.dp)) {
                                                Icon(Icons.Default.Delete, "حذف", tint = Color(0xFFE57373), modifier = Modifier.size(17.dp))
                                            }
                                        }
                                    }
                                }
                            } else {
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
                        if (largeCards && rowChannels.size == 1) Spacer(Modifier.weight(1f))
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
                Text("منابع رسمی پخش اماکن مذهبی از پیش اضافه شده‌اند؛ مکان یا لینک تازه هم می‌توانی اضافه کنی.", color = SecondaryText, fontSize = 12.sp)
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
        Text("زبان‌های زیرنویس قابل ترجمه به فارسی: انگلیسی، ترکی استانبولی، ترکی آذربایجانی و عبری", color = SecondaryText, fontSize = 12.sp)
    }
}


@Composable
private fun LiveSubtitleTranslationOverlay(player: ExoPlayer, modifier: Modifier = Modifier) {
    var translatedText by remember(player) { mutableStateOf("") }
    var translationStatus by remember(player) { mutableStateOf("در انتظار زیرنویس شبکه…") }

    DisposableEffect(player) {
        val languageIdentifier = LanguageIdentification.getClient()
        var activeTranslator: Translator? = null
        var activeSourceLanguage: String? = null
        var lastSourceText = ""
        var requestId = 0

        val listener = object : Player.Listener {
            private fun selectFirstAvailableSubtitleTrack() {
                val textGroups = player.currentTracks.groups
                    .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                if (textGroups.any { group -> (0 until group.length).any { group.isTrackSelected(it) } }) return
                for (group in textGroups) {
                    val firstSupported = (0 until group.length).firstOrNull { group.isTrackSupported(it) }
                    if (firstSupported != null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(
                                group.mediaTrackGroup, listOf(firstSupported)
                            ))
                            .build()
                        return
                    }
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                selectFirstAvailableSubtitleTrack()
            }

            override fun onCues(cueGroup: CueGroup) {
                val sourceText = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.trim()?.takeIf { text -> text.isNotBlank() } }
                    .joinToString(" ")
                if (sourceText.isBlank()) {
                    translatedText = ""
                    return
                }
                if (sourceText == lastSourceText) return
                lastSourceText = sourceText
                val thisRequest = ++requestId
                translationStatus = "در حال تشخیص زبان زیرنویس…"

                val selectedLanguage = player.currentTracks.groups
                    .asSequence()
                    .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                    .mapNotNull { group ->
                        (0 until group.length)
                            .firstOrNull { group.isTrackSelected(it) }
                            ?.let { group.getTrackFormat(it).language }
                    }
                    .firstOrNull { !it.isNullOrBlank() && it != "und" }

                fun translateFrom(languageTag: String) {
                    if (thisRequest != requestId) return
                    val baseLanguageTag = languageTag.substringBefore("-").lowercase()
                    val normalizedLanguageTag = if (baseLanguageTag == "iw" || baseLanguageTag == "heb") "he" else languageTag
                    val sourceLanguage = TranslateLanguage.fromLanguageTag(normalizedLanguageTag)
                    if (sourceLanguage == null || sourceLanguage !in TranslateLanguage.getAllLanguages()) {
                        translationStatus = "زبان این زیرنویس در موتور ترجمه پشتیبانی نمی‌شود."
                        translatedText = ""
                        return
                    }
                    if (sourceLanguage == TranslateLanguage.PERSIAN) {
                        translatedText = sourceText
                        translationStatus = "زیرنویس فارسی"
                        return
                    }
                    if (activeTranslator == null || activeSourceLanguage != sourceLanguage) {
                        activeTranslator?.close()
                        activeTranslator = Translation.getClient(
                            TranslatorOptions.Builder()
                                .setSourceLanguage(sourceLanguage)
                                .setTargetLanguage(TranslateLanguage.PERSIAN)
                                .build()
                        )
                        activeSourceLanguage = sourceLanguage
                    }
                    val translator = activeTranslator ?: return
                    translationStatus = "در حال آماده‌سازی مدل ترجمه…"
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            if (thisRequest != requestId) return@addOnSuccessListener
                            translationStatus = "در حال ترجمه…"
                            translator.translate(sourceText)
                                .addOnSuccessListener { result ->
                                    if (thisRequest == requestId) {
                                        translatedText = result
                                        translationStatus = ""
                                    }
                                }
                                .addOnFailureListener {
                                    if (thisRequest == requestId) translationStatus = "ترجمه این بخش انجام نشد."
                                }
                        }
                        .addOnFailureListener {
                            if (thisRequest == requestId) {
                                translationStatus = "دانلود مدل ترجمه ناموفق بود؛ اتصال اینترنت را بررسی کنید."
                            }
                        }
                }

                if (!selectedLanguage.isNullOrBlank()) {
                    translateFrom(selectedLanguage)
                } else {
                    languageIdentifier.identifyLanguage(sourceText)
                        .addOnSuccessListener { detected ->
                            if (thisRequest != requestId) return@addOnSuccessListener
                            if (detected == "und") translationStatus = "زبان زیرنویس شناسایی نشد."
                            else translateFrom(detected)
                        }
                        .addOnFailureListener {
                            if (thisRequest == requestId) translationStatus = "تشخیص زبان زیرنویس ناموفق بود."
                        }
                }
            }
        }
        player.addListener(listener)
        // When Persian translation is enabled, choose a supported subtitle track automatically.
        listener.run { onTracksChanged(player.currentTracks) }
        onDispose {
            player.removeListener(listener)
            activeTranslator?.close()
            languageIdentifier.close()
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (translatedText.isNotBlank()) {
            Text(translatedText, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (translationStatus.isNotBlank()) {
            Text(translationStatus, color = Color(0xFFFFDFA6), fontSize = 10.sp)
        }
    }
}

@Composable
fun MultiViewPage(channels: List<Channel>) {
    val context = LocalContext.current
    var first by remember { mutableStateOf<Channel?>(channels.getOrNull(0)) }
    var second by remember { mutableStateOf<Channel?>(channels.getOrNull(1)) }
    val firstPlayer = remember { ExoPlayer.Builder(context).build() }
    val secondPlayer = remember { ExoPlayer.Builder(context).build() }
    var fullScreenSlot by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) { onDispose { firstPlayer.release(); secondPlayer.release() } }
    LaunchedEffect(first?.url) {
        first?.let { firstPlayer.setMediaItem(MediaItem.fromUri(it.url)); firstPlayer.prepare(); firstPlayer.playWhenReady = true }
            ?: firstPlayer.stop()
    }
    LaunchedEffect(second?.url) {
        second?.let { secondPlayer.setMediaItem(MediaItem.fromUri(it.url)); secondPlayer.prepare(); secondPlayer.playWhenReady = true }
            ?: secondPlayer.stop()
    }
    if (fullScreenSlot != null) {
        val fullscreenPlayer = if (fullScreenSlot == 1) firstPlayer else secondPlayer
        val fullscreenChannel = if (fullScreenSlot == 1) first else second
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { fullScreenSlot = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "بازگشت", tint = Gold)
                }
                Text(fullscreenChannel?.name ?: "پخش تمام‌صفحه", color = TextMain,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { fullScreenSlot = null }) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "خروج از تمام‌صفحه", tint = Gold)
                }
            }
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = fullscreenPlayer; useController = true } },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("چندنمایی هم‌زمان", color = Gold, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text("برای هر پنجره نام شبکه را جستجو و انتخاب کنید. برای بزرگ‌نمایی هر شبکه، دکمه تمام‌صفحه همان پنجره را بزنید. پخش هم‌زمان به توان سخت‌افزاری گوشی و نوع پخش وابسته است.",
                color = SecondaryText, fontSize = 12.sp)
            MultiViewSlot("پنجره اول", channels, first, { first = it }, firstPlayer, { fullScreenSlot = 1 })
            MultiViewSlot("پنجره دوم", channels, second, { second = it }, secondPlayer, { fullScreenSlot = 2 })
            if (channels.isEmpty()) Text("ابتدا شبکه‌های تلویزیونی را از بخش افزودن منابع اضافه کنید.", color = SecondaryText)
        }
    }
}

@Composable
private fun MultiViewSlot(title: String, channels: List<Channel>, selected: Channel?,
                          onSelect: (Channel?) -> Unit, player: ExoPlayer, onFullscreen: () -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val filteredChannels = remember(channels, searchQuery) {
        channels.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) ||
            it.country.contains(searchQuery.trim(), ignoreCase = true) ||
            it.groupTitle.contains(searchQuery.trim(), ignoreCase = true) }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onFullscreen) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "پخش تمام‌صفحه", tint = Gold)
                }
                TextButton(onClick = { searchQuery = ""; showPicker = true }) {
                    Text(selected?.name ?: "جستجو و انتخاب شبکه", color = TextMain, maxLines = 1)
                    Icon(Icons.Default.Search, null, tint = Gold)
                }
            }
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("جستجوی شبکه برای $title") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("نام شبکه، کشور یا گروه") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(filteredChannels, key = { it.url }) { channel ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(channel.name)
                                        Text(listOf(channel.country, channel.groupTitle).filter { it.isNotBlank() }.joinToString(" • "),
                                            color = MutedText, fontSize = 10.sp)
                                    }
                                },
                                onClick = { onSelect(channel); showPicker = false }
                            )
                        }
                        if (filteredChannels.isEmpty()) {
                            item { Text("شبکه‌ای با این عبارت پیدا نشد.", color = SecondaryText, modifier = Modifier.padding(12.dp)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("بستن") } },
            dismissButton = {
                TextButton(onClick = { onSelect(null); showPicker = false }) { Text("پاک‌کردن انتخاب") }
            }
        )
    }
}

@Composable
fun PilgrimagePage(savedChannels: List<Channel>, onPlay: (Channel) -> Unit, onAdd: (Channel) -> Unit) {
    var place by remember { mutableStateOf("مشهد مقدس") }
    var title by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var customPlace by remember { mutableStateOf("") }
    val defaultPlaces = listOf("مشهد مقدس", "کربلا", "نجف اشرف", "مکه مکرمه", "مدینه منوره", "سایر اماکن مذهبی")
    val savedPlaces = savedChannels.filter { it.country == "زیارت آنلاین" }.map { it.groupTitle }.filter { it.isNotBlank() }
    val places = (defaultPlaces + savedPlaces).distinct()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("زیارت آنلاین", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("مکان را انتخاب کن و یکی از پخش‌های رسمی آماده را اجرا کن؛ امکان افزودن مکان و لینک تازه نیز وجود دارد.",
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
                    Text(
                        if (place == "مدینه منوره") "فعلاً پخش زندهٔ ۲۴ ساعتهٔ قابل‌تأیید برای مسجدالنبی در دسترس نیست."
                        else "هنوز لینک پخش زنده‌ای برای $place ثبت نشده است.",
                        color = TextMain, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (place == "مدینه منوره") "در آخرین بررسی، پخش ۲۴ ساعتهٔ سنت TV آفلاین گزارش شده بود؛ برای جلوگیری از نمایش پخش خراب، لینک غیرفعال اضافه نشده است."
                        else "نشانی پخش رسمی یا مجاز را در کادر زیر وارد کن.",
                        color = SecondaryText, fontSize = 12.sp
                    )
                }
            }
        } else {
            available.chunked(2).forEach { cameraRow ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    cameraRow.forEach { camera ->
                        Card(
                            modifier = Modifier.weight(1f).height(150.dp).clickable { onPlay(camera) },
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Gold.copy(alpha = .55f))
                        ) {
                            Box(Modifier.fillMaxSize().background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Gold.copy(alpha = .16f), Panel, Background)
                                )
                            )) {
                                Column(
                                    Modifier.fillMaxSize().padding(11.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Icon(Icons.Default.LiveTv, null, tint = Gold, modifier = Modifier.size(43.dp))
                                    Text(camera.name, color = TextMain, fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 3,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PlayCircle, null, tint = Gold, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("مشاهده پخش زنده", color = Gold, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (cameraRow.size == 1) Spacer(Modifier.weight(1f))
                }
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
                val directMedia = Regex("(?i)\\.(m3u8|mpd|mp4|ts)(?:\\?.*)?$").containsMatchIn(cleanUrl)
                onAdd(Channel(title.trim(), cleanUrl, "زیارت آنلاین", if (directMedia) "تلویزیون" else "وب", groupTitle = place))
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

private fun hashChildPin(pin: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(pin.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

@Composable
fun ChildLockPage(
    channels: List<Channel>,
    lockedUrls: Set<String>,
    currentPinHash: String,
    onBack: () -> Unit,
    onToggle: (Channel, Boolean, String) -> Boolean,
    onSavePin: (String, String) -> Boolean
) {
    var showPinEditor by remember { mutableStateOf(false) }
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    var pendingLockChange by remember { mutableStateOf<Pair<Channel, Boolean>?>(null) }
    var accessPin by remember { mutableStateOf("") }
    var accessError by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = null)
            Text("بازگشت به تنظیمات")
        }
        Text("قفل کودک", color = Gold, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("شبکه‌های قفل‌شده فقط پس از واردکردن رمز والد پخش می‌شوند. رمز برای تغییر این فهرست نیز لازم است.",
            color = SecondaryText, fontSize = 12.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (currentPinHash.isBlank()) "رمز والد هنوز تنظیم نشده است." else "رمز والد تنظیم شده است.",
                    color = if (currentPinHash.isBlank()) Color(0xFFFFCC80) else Gold, fontWeight = FontWeight.SemiBold)
                Button(onClick = { showPinEditor = true; oldPin = ""; newPin = ""; confirmPin = ""; pinError = "" },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) {
                    Text(if (currentPinHash.isBlank()) "تنظیم رمز والد" else "تغییر رمز والد")
                }
            }
        }
        Text("شبکه‌ها و ایستگاه‌های قفل‌شده (${lockedUrls.size})", color = Gold, fontWeight = FontWeight.Bold)
        if (currentPinHash.isBlank()) {
            Text("ابتدا رمز والد را تنظیم کنید تا امکان قفل‌کردن شبکه‌ها فعال شود.", color = MutedText, fontSize = 12.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(channels.distinctBy { it.url }, key = { it.url }) { channel ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(channel.name, color = TextMain, fontWeight = FontWeight.Medium)
                            Text("${channel.country} • ${channel.kind}", color = MutedText, fontSize = 10.sp)
                        }
                        Switch(
                            checked = channel.url in lockedUrls,
                            enabled = currentPinHash.isNotBlank(),
                            onCheckedChange = {
                                pendingLockChange = channel to it
                                accessPin = ""
                                accessError = ""
                            }
                        )
                    }
                }
            }
        }
    }
    if (pendingLockChange != null) {
        AlertDialog(
            onDismissRequest = { pendingLockChange = null },
            title = { Text("تأیید رمز والد", color = Gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("برای تغییر وضعیت قفل این شبکه، رمز والد را وارد کنید.")
                    OutlinedTextField(accessPin, { accessPin = it.filter(Char::isDigit).take(8); accessError = "" },
                        label = { Text("رمز والد") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (accessError.isNotBlank()) Text(accessError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val change = pendingLockChange
                    if (change != null && onToggle(change.first, change.second, accessPin)) pendingLockChange = null
                    else accessError = "رمز والد صحیح نیست."
                }) { Text("تأیید", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { pendingLockChange = null }) { Text("انصراف") } }
        )
    }
    if (showPinEditor) {
        AlertDialog(
            onDismissRequest = { showPinEditor = false },
            title = { Text(if (currentPinHash.isBlank()) "تنظیم رمز والد" else "تغییر رمز والد", color = Gold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentPinHash.isNotBlank()) OutlinedTextField(oldPin, { oldPin = it.filter(Char::isDigit).take(8) },
                        label = { Text("رمز فعلی") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    OutlinedTextField(newPin, { newPin = it.filter(Char::isDigit).take(8) },
                        label = { Text("رمز جدید (۴ تا ۸ رقم)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(8) },
                        label = { Text("تکرار رمز جدید") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    if (pinError.isNotBlank()) Text(pinError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        newPin.length !in 4..8 -> pinError = "رمز باید بین ۴ تا ۸ رقم باشد."
                        newPin != confirmPin -> pinError = "تکرار رمز با رمز جدید یکسان نیست."
                        !onSavePin(oldPin, newPin) -> pinError = "رمز فعلی صحیح نیست."
                        else -> showPinEditor = false
                    }
                }) { Text("ذخیره رمز", color = Gold) }
            },
            dismissButton = { TextButton(onClick = { showPinEditor = false }) { Text("انصراف") } }
        )
    }
}

@Composable
fun SettingsPage(themeMode: String, onThemeModeChange: (String) -> Unit, onChildLock: () -> Unit,
                 fontScale: Float, onFontScaleChange: (Float) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("salar_tv", 0) }
    var confirmDelete by remember { mutableStateOf(prefs.getBoolean("confirm_before_delete", true)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("ظاهر و شخصی‌سازی", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("حالت نمایش", color = TextMain, fontWeight = FontWeight.SemiBold)
        listOf("dark" to "مشکی و طلایی براق", "light" to "روشن", "system" to "هماهنگ با دستگاه").forEach { (value, label) ->
            Card(Modifier.fillMaxWidth().clickable { onThemeModeChange(value) },
                colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (value) { "dark" -> Icons.Default.DarkMode; "light" -> Icons.Default.LightMode; else -> Icons.Default.SettingsSuggest }, null, tint = Gold)
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = TextMain, modifier = Modifier.weight(1f))
                    RadioButton(selected = themeMode == value, onClick = { onThemeModeChange(value) })
                }
            }
        }
        Divider(color = Gold.copy(alpha = 0.3f))
        Text("تم‌های رنگی اختصاصی", color = TextMain, fontWeight = FontWeight.SemiBold)
        Text("بدون تغییر حالت روشن و تاریک؛ هر تم ظاهر و رنگ تأکیدی مستقل دارد.", color = SecondaryText, fontSize = 11.sp)
        data class ThemeChoice(val id: String, val title: String, val colors: List<Color>)
        val colorThemes = listOf(
            ThemeChoice("gold_gloss", "مشکی و طلایی براق", listOf(Color(0xFFFFD76A), Color(0xFF050505), Color(0xFF5A4218))),
            ThemeChoice("silver_gloss", "مشکی و نقره‌ای براق", listOf(Color(0xFFE4EDF7), Color(0xFF080B10), Color(0xFF657386))),
            ThemeChoice("ocean", "اقیانوسی", listOf(Color(0xFF48C8FF), Color(0xFF071827), Color(0xFF102B40))),
            ThemeChoice("emerald", "زمردی", listOf(Color(0xFF45D6A0), Color(0xFF071B17), Color(0xFF102B24))),
            ThemeChoice("purple", "بنفش کهکشانی", listOf(Color(0xFFB99AFF), Color(0xFF130D22), Color(0xFF251936))),
            ThemeChoice("ruby", "یاقوتی", listOf(Color(0xFFFF718B), Color(0xFF200D15), Color(0xFF351923))),
            ThemeChoice("sunset", "غروب آفتاب", listOf(Color(0xFFFFA45B), Color(0xFF21130D), Color(0xFF382216))),
            ThemeChoice("graphite", "گرافیتی", listOf(Color(0xFFB7C3D0), Color(0xFF101419), Color(0xFF202832)))
        )
        colorThemes.chunked(2).forEach { rowThemes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                rowThemes.forEach { choice ->
                    Card(Modifier.weight(1f).clickable { onThemeModeChange(choice.id) },
                        colors = CardDefaults.cardColors(containerColor = choice.colors[1]),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            if (themeMode == choice.id) 2.dp else 1.dp,
                            if (themeMode == choice.id) choice.colors[0] else choice.colors[2]
                        )) {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                choice.colors.forEach { swatch ->
                                    Box(Modifier.weight(1f).height(20.dp)
                                        .background(swatch, RoundedCornerShape(6.dp)))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(choice.title, color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (themeMode == choice.id) Icon(Icons.Default.CheckCircle, null,
                                    tint = choice.colors[0], modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (rowThemes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Divider(color = Gold.copy(alpha = 0.3f))
        Text("اندازه قلم برنامه: " + (fontScale * 100).toInt() + "%", color = Gold, fontWeight = FontWeight.Bold)
        Slider(value = fontScale, onValueChange = onFontScaleChange, valueRange = 0.85f..1.3f, steps = 8)
        Text("پیش‌نمایش اندازه نوشته", color = TextMain, fontSize = 16.sp)
        Divider(color = Gold.copy(alpha = 0.3f))
        Text("امنیت و ورود", color = Gold, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = Gold)
                    Spacer(Modifier.width(10.dp))
                    Text("حفاظت و حریم خصوصی", color = TextMain, fontWeight = FontWeight.Bold)
                }
                Text("برای محدودکردن پخش شبکه‌های انتخابی، قفل کودک را تنظیم کنید.",
                    color = SecondaryText, fontSize = 12.sp)
                Button(onClick = onChildLock, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color.Black)) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("مدیریت قفل کودک")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("تأیید پیش از حذف شبکه", color = TextMain, modifier = Modifier.weight(1f))
                    Switch(checked = confirmDelete, onCheckedChange = {
                        confirmDelete = it
                        prefs.edit().putBoolean("confirm_before_delete", it).apply()
                    })
                }
            }
        }
        Text("تم و اندازه قلم در دستگاه ذخیره می‌شوند.", color = MutedText, fontSize = 11.sp)
    }
}

@Composable
fun LiveDubbingPage() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("salar_tv", 0) }
    var subtitleEnabled by remember { mutableStateOf(prefs.getBoolean("live_subtitle_enabled", true)) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("live_dubbing_enabled", false)) }
    var keepOriginal by remember { mutableStateOf(prefs.getBoolean("live_dubbing_original", true)) }
    var voice by remember { mutableStateOf(if (prefs.getString("live_dubbing_voice", "زیر") == "بم" || prefs.getString("live_dubbing_voice", "") == "مرد") "بم" else "زیر") }
    var volume by remember { mutableFloatStateOf(prefs.getFloat("live_dubbing_volume", 1f)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("مترجم و دوبلور هوشمند زنده", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("وضعیت موتور هوش مصنوعی", color = Gold, fontWeight = FontWeight.Bold)
                Text("ترجمهٔ زیرنویس به فارسی فعال است. هنگام پخش، برنامه اولین زیرنویس متنیِ قابل‌استفادهٔ شبکه را خودکار انتخاب و ترجمه می‌کند.",
                    color = Color(0xFFFFCC80), fontSize = 12.sp)
                Text("برای دوبلهٔ صوتی، دوبلهٔ هوشمند را روشن کنید. خروجی از موتور گفتار فارسی نصب‌شده در گوشی ساخته می‌شود؛ گزینه‌های زیر و بم فقط زیر و بمی صدا را تغییر می‌دهند و جنسیت صدا به موتور دستگاه بستگی دارد.",
                    color = SecondaryText, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("زیرنویس هوشمند فارسی", color = TextMain)
                Text("پیش‌فرض روشن؛ انتخاب خودکار زیرنویس متنی و ترجمه به فارسی", color = MutedText, fontSize = 11.sp)
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
        Text("زیر و بمی صدای دوبله", color = Gold, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("زیر", "بم").forEach { option ->
                FilterChip(selected = voice == option, onClick = {
                    voice = option
                    prefs.edit().putString("live_dubbing_voice", option).apply()
                }, label = { Text(option) })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("حفظ صدای اصلی شبکه", color = TextMain)
                Text("در حالت خاموش، صدای اصلی هنگام دوبله به ۲۵٪ کاهش می‌یابد؛ کاملاً قطع نمی‌شود تا تشخیص گفتار مختل نشود.", color = MutedText, fontSize = 11.sp)
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
        Text("زبان‌های ورودی موردنظر: انگلیسی، ترکی استانبولی، ترکی آذربایجانی و عبری؛ خروجی فارسی.",
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


private fun provinceForChannel(channel: Channel): String {
    val name = channel.name.replace('ي', 'ی').replace('ك', 'ک')
    // Prefer the province explicitly supplied in playlist labels, then use
    // a maintained mapping for provincial stations whose title omits it.
    Regex("\\(([^)]+)\\)").find(name)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf { it.isNotBlank() }?.let { return it }
    return when {
        "آبادان" in name || "خوزستان" in name -> "خوزستان"
        "افلاک" in name || "لرستان" in name -> "لرستان"
        "آفتاب" in name || "مرکزی" in name -> "مرکزی"
        "البرز" in name -> "البرز"
        "اترک" in name || "خراسان شمالی" in name -> "خراسان شمالی"
        "باران" in name || "گیلان" in name -> "گیلان"
        "بوشهر" in name -> "بوشهر"
        "دنا" in name || "کهگیلویه" in name || "بویراحمد" in name -> "کهگیلویه و بویراحمد"
        "اشراق" in name || "زنجان" in name -> "زنجان"
        "فارس" in name -> "فارس"
        "همدان" in name -> "همدان"
        "هامون" in name || "مکران" in name || "سیستان" in name || "بلوچستان" in name -> "سیستان و بلوچستان"
        "ایلام" in name -> "ایلام"
        "اصفهان" in name -> "اصفهان"
        "جهانبین" in name || "چهارمحال" in name || "بختیاری" in name -> "چهارمحال و بختیاری"
        "کرمان" in name -> "کرمان"
        "کیش" in name || "خلیج فارس" in name || "هرمزگان" in name -> "هرمزگان"
        "خاوران" in name || "خراسان جنوبی" in name -> "خراسان جنوبی"
        "خراسان رضوی" in name || "سبز" in name -> "خراسان رضوی"
        "کردستان" in name -> "کردستان"
        "مهاباد" in name || "آذربایجان غربی" in name -> "آذربایجان غربی"
        "نور" in name || "تبرستان" in name || "مازندران" in name -> "مازندران"
        "قزوین" in name -> "قزوین"
        "سبلان" in name || "اردبیل" in name -> "اردبیل"
        "سمنان" in name -> "سمنان"
        "سهند" in name || "آذربایجان شرقی" in name -> "آذربایجان شرقی"
        "تابان" in name || "یزد" in name -> "یزد"
        "زاگرس" in name || "کرمانشاه" in name -> "کرمانشاه"
        "شبکه تهران" in name || name == "تهران" -> "تهران"
        else -> "استان نامشخص"
    }
}

private fun categoryForChannel(channel: Channel): String {
    if (channel.kind == "رادیو") return "رادیو"
    val value = (channel.name + " " + channel.country + " " + channel.groupTitle).lowercase()
    val group = channel.groupTitle.lowercase()
        .replace('ي', 'ی').replace('ك', 'ک')
    // Official IRIB structure takes precedence over genre keywords:
    // national, provincial and international networks must not be mixed
    // into generic news/sports/entertainment sections.
    if (listOf("شبکه‌های استانی", "شبکه های استانی", "استانی").any { it in group }) return "شبکه‌های استانی"
    if (listOf("شبکه‌های سراسری", "شبکه های سراسری", "سراسری").any { it in group }) return "شبکه‌های سراسری"
    if (listOf("برون‌مرزی", "برون مرزی", "بین‌الملل", "بین الملل").any { it in group }) return "شبکه‌های برون‌مرزی"
    val nationalNames = listOf(
        "شبکه یک", "شبکه ۱", "شبکه دو", "شبکه ۲", "شبکه سه", "شبکه ۳",
        "شبکه چهار", "شبکه ۴", "شبکه پنج", "شبکه ۵", "شبکه خبر",
        "شبکه آموزش", "شبکه قرآن", "شبکه ورزش", "شبکه مستند", "شبکه سلامت",
        "شبکه افق", "شبکه نسیم", "شبکه تماشا", "شبکه نمایش", "شبکه امید",
        "شبکه کودک", "شبکه پویا", "شبکه نهال", "فراتر", "شبکه فراتر", "faratar",
        "خبر ۲", "خبر دو", "irinn 2", "جام جم ۱", "جام جم ۲", "جام جم یک", "جام جم دو",
        "jame jam 1", "jame jam 2", "jamejam1", "jamejam2",
        "irib tv1", "irib tv2", "irib tv3", "irib tv4", "irib tv5"
    )
    if (nationalNames.any { it in value }) return "شبکه‌های سراسری"
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
        channel.country == "ایران" -> "سایر شبکه‌های داخلی"
        else -> "عمومی"
    }
}

private fun sectionSortOrder(title: String): Int = when {
    "شبکه‌های سراسری" in title -> 0
    "استانی /" in title || "شبکه‌های استانی" in title -> 1
    "شبکه‌های برون‌مرزی" in title -> 2
    "سایر شبکه‌های داخلی" in title -> 3
    title.endsWith("/ رادیو") -> 4
    else -> 10
}

private fun channelSortOrder(channel: Channel): Int {
    if (categoryForChannel(channel) == "شبکه‌های سراسری") {
        val name = channel.name.replace('ي', 'ی').replace('ك', 'ک')
        val nationalOrder = listOf(
            "شبکه یک", "شبکه دو", "شبکه سه", "شبکه چهار", "شبکه پنج",
            "شبکه آموزش", "شبکه قرآن", "شبکه خبر", "شبکه ورزش", "شبکه مستند",
            "شبکه سلامت", "شبکه افق", "شبکه نسیم", "شبکه تماشا", "شبکه نمایش",
            "شبکه امید", "شبکه کودک", "شبکه پویا", "شبکه نهال", "فراتر", "خبر ۲", "جام جم ۱", "جام جم ۲"
        )
        val index = nationalOrder.indexOfFirst { name.contains(it, ignoreCase = true) }
        return if (index >= 0) index else 100
    }
    return 1000
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
                "زبان کردی" in group -> "زبان کردی"
                "زبان روسی" in group -> "زبان روسی"
                "زبان فرانسوی" in group -> "زبان فرانسوی"
                "زبان آلمانی" in group -> "زبان آلمانی"
                "زبان انگلیسی" in group -> "زبان انگلیسی"
                "آمریکا" in meta || "united states" in meta || "usa" in meta || "us/" in meta -> "آمریکا"
                "روسیه" in meta || "russia" in meta || "ru/" in meta -> "روسیه"
                "فرانسه" in meta || "france" in meta || "fr/" in meta -> "فرانسه"
                "آلمان" in meta || "germany" in meta || "de/" in meta -> "آلمان"
                "ماهواره" in meta || "satellite" in meta || "خارج از ایران" in meta || "فارسی‌زبان خارج" in meta || "فارسی زبان خارج" in meta -> "ایران"
                "azerbaijan" in meta || "azərbaycan" in meta || "آذربایجان" in meta || "az/" in meta -> "آذربایجان"
                "turkey" in meta || "turkiye" in meta || "türkiye" in meta || "ترکیه" in meta || "tr/" in meta -> "ترکیه"
                "صداوسیما" in meta || "صدا و سیما" in meta ||
                    "شبکه‌های استانی" in meta || "شبکه های استانی" in meta ||
                    "شبکه‌های سراسری" in meta || "شبکه های سراسری" in meta ||
                    "شبکه‌های برون‌مرزی" in meta || "شبکه های برون مرزی" in meta ||
                    "irib" in meta || "iran" in meta || "ایران" in meta || "ir/" in meta -> "ایران"
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
