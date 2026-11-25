package com.salar.tv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

private data class LiveSpeechModel(val id: String, val language: String, val url: String)

@Composable
internal fun LiveAudioTranslationOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("salar_tv", Context.MODE_PRIVATE) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { result ->
            mainHandler.post {
                if (result == TextToSpeech.SUCCESS) {
                    val languageResult = engine.setLanguage(Locale("fa", "IR"))
                    ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                } else {
                    ttsReady = false
                }
            }
        }
        ttsEngine = engine
        onDispose {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
            ttsEngine = null
            ttsReady = false
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) {
        onDispose { executor.shutdownNow() }
    }
    val models = remember {
        listOf(
            LiveSpeechModel("en", TranslateLanguage.ENGLISH, "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            LiveSpeechModel("tr", TranslateLanguage.TURKISH, "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"),
            LiveSpeechModel("ar", TranslateLanguage.ARABIC, "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"),
            LiveSpeechModel("he", TranslateLanguage.HEBREW, ""),
            LiveSpeechModel("ru", TranslateLanguage.RUSSIAN, "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"),
            LiveSpeechModel("fr", TranslateLanguage.FRENCH, "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"),
            LiveSpeechModel("de", TranslateLanguage.GERMAN, "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"),
            LiveSpeechModel("es", TranslateLanguage.SPANISH, "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
            LiveSpeechModel("it", TranslateLanguage.ITALIAN, "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"),
            LiveSpeechModel("pt", TranslateLanguage.PORTUGUESE, "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),
            LiveSpeechModel("nl", TranslateLanguage.DUTCH, "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip"),
            LiveSpeechModel("pl", TranslateLanguage.POLISH, "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"),
            LiveSpeechModel("uk", TranslateLanguage.UKRAINIAN, "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip"),
            LiveSpeechModel("zh", TranslateLanguage.CHINESE, "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"),
            LiveSpeechModel("ja", TranslateLanguage.JAPANESE, "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"),
            LiveSpeechModel("hi", TranslateLanguage.HINDI, "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip")
        )
    }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("ترجمهٔ گفتار آمادهٔ شروع است.") }
    var translated by remember { mutableStateOf("") }
    var lastSpokenText by remember { mutableStateOf("") }
    var lastSpokenAt by remember { mutableStateOf(0L) }
    var isRunning by remember { mutableStateOf(false) }
    var isPreparing by remember { mutableStateOf(false) }
    var projection by remember { mutableStateOf<MediaProjection?>(null) }
    var recorder by remember { mutableStateOf<AudioRecord?>(null) }
    var recognitionThread by remember { mutableStateOf<Thread?>(null) }
    var recognizer by remember { mutableStateOf<Recognizer?>(null) }
    var modelInstance by remember { mutableStateOf<Model?>(null) }
    var whisperModelInstance by remember { mutableStateOf<WhisperModel?>(null) }
    val runningFlag = remember { AtomicBoolean(false) }
    val translator = remember(selectedModel) {
        selectedModel?.let { selectedId ->
            val language = models.first { it.id == selectedId }.language
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(language)
                    .setTargetLanguage(TranslateLanguage.PERSIAN)
                    .build()
            )
        }
    }

    fun stopCapture() {
        runningFlag.set(false)
        runCatching { ttsEngine?.stop() }
        val threadToJoin = recognitionThread
        val recognizerToClose = recognizer
        val voskToClose = modelInstance
        val whisperToClose = whisperModelInstance
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { projection?.stop() }
        projection = null
        threadToJoin?.interrupt()
        recognitionThread = null
        recognizer = null
        modelInstance = null
        whisperModelInstance = null
        isRunning = false

        // Whisper uses native inference. Never free its native model while a chunk
        // is still being transcribed on the capture thread.
        if (threadToJoin != null) {
            Thread({
                runCatching { threadToJoin.join() }
                runCatching { recognizerToClose?.close() }
                runCatching { voskToClose?.close() }
                runCatching { whisperToClose?.let { HebrewWhisperEngine.releaseModel(it) } }
            }, "SalarTvSpeechCleanup").apply { isDaemon = true; start() }
        } else {
            runCatching { recognizerToClose?.close() }
            runCatching { voskToClose?.close() }
            runCatching { whisperToClose?.let { HebrewWhisperEngine.releaseModel(it) } }
        }
    }

    fun translateText(source: String) {
        if (source.isBlank()) return
        val activeTranslator = translator ?: run {
            status = "ابتدا زبان گفتار را انتخاب کنید."
            return
        }
        activeTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                activeTranslator.translate(source)
                    .addOnSuccessListener { result ->
                        translated = result
                        if (prefs.getBoolean("live_dubbing_enabled", false)) {
                            val speech = ttsEngine
                            if (ttsReady && speech != null) {
                                val speechText = result.trim().replace(Regex("\\s+"), " ")
                                val now = System.currentTimeMillis()
                                val isNewSpeech = speechText.isNotBlank() &&
                                    (speechText != lastSpokenText || now - lastSpokenAt > 5_000L)
                                if (isNewSpeech) {
                                    lastSpokenText = speechText
                                    lastSpokenAt = now
                                    val voicePreset = prefs.getString("live_dubbing_voice", "زیر")
                                    speech.setPitch(if (voicePreset == "بم" || voicePreset == "مرد") 0.88f else 1.12f)
                                    val volume = prefs.getFloat("live_dubbing_volume", 1f).coerceIn(0f, 1f)
                                    val parameters = Bundle().apply {
                                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
                                    }
                                    val speakResult = speech.speak(
                                        speechText,
                                        TextToSpeech.QUEUE_ADD,
                                        parameters,
                                        "salar-live-" + now
                                    )
                                    status = if (speakResult == TextToSpeech.SUCCESS) {
                                        "ترجمهٔ فارسی به‌ترتیب در صف دوبله قرار گرفت"
                                    } else {
                                        "موتور گفتار نتوانست جمله را به صف دوبله اضافه کند."
                                    }
                                } else {
                                    status = "ترجمهٔ تکراری برای دوبله نادیده گرفته شد."
                                }
                            } else {
                                status = "ترجمه انجام شد؛ موتور گفتار فارسی دستگاه آماده نیست."
                            }
                        } else {
                            status = "ترجمهٔ زنده فعال است"
                        }
                    }
                    .addOnFailureListener { status = "ترجمه انجام نشد: ${it.localizedMessage ?: "خطا"}" }
            }
            .addOnFailureListener { status = "دریافت مدل ترجمه ناموفق بود؛ اینترنت را بررسی کنید." }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            stopCapture()
            isPreparing = false
            status = "دسترسی ضبط صدای پخش‌کننده تأیید نشد."
        } else {
            try {
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjection = manager.getMediaProjection(result.resultCode, data)
                projection = mediaProjection
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUid(Process.myUid())
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val audioRecord = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes((minBuffer.coerceAtLeast(8192)) * 2)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord.release()
                    throw IllegalStateException("سیستم ضبط صدای خروجی را راه‌اندازی نکرد.")
                }
                val hebrewMode = selectedModel == "he"
                val activeWhisper = if (hebrewMode) whisperModelInstance ?: throw IllegalStateException("مدل عبری بارگذاری نشده است.") else null
                val activeRecognizer = if (hebrewMode) null else Recognizer(modelInstance ?: throw IllegalStateException("مدل گفتار بارگذاری نشده است."), 16000.0f)
                recorder = audioRecord
                recognizer = activeRecognizer
                runningFlag.set(true)
                isRunning = true
                status = "در حال شنیدن صدای خودِ پخش‌کننده…"
                val thread = Thread {
                    val buffer = ByteArray(8192)
                    val hebrewChunk = ByteArrayOutputStream()
                    val chunkTargetBytes = 16000 * 2 * 8
                    var chunkNumber = 0
                    try {
                        audioRecord.startRecording()
                        while (runningFlag.get() && !Thread.currentThread().isInterrupted) {
                            val count = audioRecord.read(buffer, 0, buffer.size)
                            if (count > 0) {
                                if (hebrewMode) {
                                    hebrewChunk.write(buffer, 0, count)
                                    if (hebrewChunk.size() >= chunkTargetBytes) {
                                        val wav = File(context.cacheDir, "hebrew-live-$chunkNumber.wav")
                                        chunkNumber++
                                        writePcmWav(wav, hebrewChunk.toByteArray(), 16000, 1)
                                        hebrewChunk.reset()
                                        try {
                                            val recognized = runBlocking {
                                                dev.ffmpegkit.whisper.Whisper.transcribe(
                                                    activeWhisper!!,
                                                    wav.absolutePath,
                                                    WhisperConfig(language = "he", translate = false, threads = 2)
                                                ).text
                                            }.trim()
                                            if (recognized.isNotBlank()) mainHandler.post { translateText(recognized) }
                                        } finally {
                                            wav.delete()
                                        }
                                    }
                                } else if (activeRecognizer!!.acceptWaveForm(buffer, count)) {
                                    val recognized = runCatching {
                                        JSONObject(activeRecognizer.result).optString("text", "")
                                    }.getOrDefault("")
                                    if (recognized.isNotBlank()) mainHandler.post { translateText(recognized) }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            if (runningFlag.get()) {
                                stopCapture()
                                status = "خطای دریافت صدای پخش: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                }.apply { name = "SalarTvPlaybackSpeech"; start() }
                recognitionThread = thread
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post {
                            if (isRunning) {
                                stopCapture()
                                status = "ضبط صدای پخش متوقف شد."
                            }
                        }
                    }
                }, mainHandler)
            } catch (e: Exception) {
                stopCapture()
                status = "شروع ترجمهٔ گفتار ناموفق بود: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }
    }

    fun requestCapture() {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    fun prepareAndRequest() {
        if (isPreparing || isRunning) return
        if (Build.VERSION.SDK_INT < 29) {
            status = "ترجمهٔ صدای داخلی به اندروید ۱۰ یا بالاتر نیاز دارد."
            return
        }
        isPreparing = true
        status = "در حال آماده‌سازی مدل تشخیص گفتار؛ حجم دانلود بسته به زبان انتخابی متفاوت است و ممکن است زیاد باشد…"
        val chosen = models.firstOrNull { it.id == selectedModel }
        if (chosen == null) {
            isPreparing = false
            status = "ابتدا زبان موردنظر را خودتان انتخاب کنید؛ هیچ مدلی خودکار دانلود نمی‌شود."
            return
        }
        executor.execute {
            try {
                if (chosen.id == "he") {
                    val loadedWhisper = runBlocking { HebrewWhisperEngine.loadModel(context) }
                    mainHandler.post {
                        whisperModelInstance = loadedWhisper
                        isPreparing = false
                        status = "مدل چندزبانهٔ عبری آماده شد؛ مجوز ضبط صدای داخلی را تأیید کنید."
                        requestCapture()
                    }
                } else {
                    val modelDir = File(File(context.filesDir, "salar-vosk"), chosen.id)
                    if (!File(modelDir, "am/final.mdl").isFile || !File(modelDir, "conf").isDirectory) {
                        throw IllegalStateException("مدل این زبان هنوز دانلود نشده است؛ ابتدا دکمهٔ «دانلود دستی زبان انتخابی» را بزنید.")
                    }
                    val loadedModel = Model(modelDir.absolutePath)
                    val base = File(context.filesDir, "salar-vosk")
                    base.listFiles()?.filter { it.isDirectory && it.name != chosen.id }
                        ?.forEach { it.deleteRecursively() }
                    File(context.filesDir, "salar-whisper").deleteRecursively()
                    mainHandler.post {
                        modelInstance = loadedModel
                        isPreparing = false
                        status = "مدل آماده شد؛ مجوز ضبط صدای داخلی را تأیید کنید."
                        requestCapture()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    isPreparing = false
                    status = "آماده‌سازی مدل ناموفق بود: ${e.localizedMessage ?: e.javaClass.simpleName}"
                }
            }
        }
    }



    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) prepareAndRequest()
        else status = "مجوز لازم برای دریافت صدای پخش‌کننده داده نشد."
    }

    DisposableEffect(translator) {
        onDispose {
            stopCapture()
            translator?.close()
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("ترجمه و دوبلهٔ گفتار زنده به فارسی", color = Color(0xFFFFD27A), fontSize = 12.sp)
        Text(
            if (ttsReady) "موتور گفتار فارسی آماده است؛ برای شنیدن خروجی، دوبلهٔ هوشمند را روشن کنید."
            else "برای دوبلهٔ صوتی، وجود صدای فارسی در موتور گفتار گوشی لازم است.",
            color = Color.White,
            fontSize = 10.sp
        )
        Text("زبان‌های بیشتر را هم می‌توانید انتخاب و جداگانه دانلود کنید؛ هیچ زبان اضافه‌ای خودکار دریافت نمی‌شود.", color = Color.White, fontSize = 10.sp)
        Text("انتخاب زبان گفتار", color = Color.White, fontSize = 11.sp)
        models.chunked(4).forEach { languageRow ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                languageRow.forEach { item ->
                    val label = when (item.id) {
                        "en" -> "انگلیسی"; "tr" -> "ترکی"; "ar" -> "عربی"; "he" -> "عبری"
                        "ru" -> "روسی"; "fr" -> "فرانسوی"; "de" -> "آلمانی"
                        "es" -> "اسپانیایی"; "it" -> "ایتالیایی"; "pt" -> "پرتغالی"
                        "nl" -> "هلندی"; "pl" -> "لهستانی"; "uk" -> "اوکراینی"
                        "zh" -> "چینی"; "ja" -> "ژاپنی"; "hi" -> "هندی"
                        else -> item.id
                    }
                    FilterChip(selected = selectedModel == item.id, enabled = !isRunning && !isPreparing,
                        onClick = { selectedModel = item.id },
                        label = { Text(label, fontSize = 10.sp) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = !isPreparing && !isRunning && selectedModel != null,
                onClick = {
                    val chosen = models.first { it.id == selectedModel }
                    isPreparing = true
                    status = if (chosen.id == "he") "در حال دریافت دستی مدل چندزبانهٔ عبری (حدود ۷۵ مگابایت)…" else "در حال دریافت دستی مدل ${chosen.id}؛ فقط همین زبان ذخیره می‌شود…"
                    executor.execute {
                        try {
                            if (chosen.id == "he") {
                                HebrewWhisperEngine.downloadModel(context)
                            } else {
                                val modelDir = ensureSpeechModel(context, chosen)
                                val validation = Model(modelDir.absolutePath)
                                validation.close()
                                val base = File(context.filesDir, "salar-vosk")
                                base.listFiles()?.filter { it.isDirectory && it.name != chosen.id }
                                    ?.forEach { it.deleteRecursively() }
                                File(context.filesDir, "salar-whisper").deleteRecursively()
                            }
                            mainHandler.post {
                                isPreparing = false
                                status = "مدل انتخابی دانلود و آمادهٔ استفاده شد."
                            }
                        } catch (e: Exception) {
                            mainHandler.post {
                                isPreparing = false
                                status = "دانلود مدل ناموفق بود: ${e.localizedMessage ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                }
            ) { Text("دانلود دستی زبان انتخابی", fontSize = 10.sp) }
            Button(
                enabled = !isPreparing && (isRunning || selectedModel != null),
                onClick = {
                    if (isRunning) {
                        stopCapture()
                        status = "ترجمهٔ گفتار متوقف شد."
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        prepareAndRequest()
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) {
                Text(if (isRunning) "توقف" else if (isPreparing) "آماده‌سازی…" else "شروع")
            }
        }
        if (translated.isNotBlank()) {
            Text(translated, color = Color.White, fontSize = 16.sp)
        }
        Text(status, color = Color(0xFFE0E0E0), fontSize = 10.sp)
        Text(
            "فقط یک مدل تشخیص گفتار روی گوشی نگهداری می‌شود؛ با دانلود زبان دیگر، مدل قبلی پس از موفقیت دانلود پاک می‌شود. این قابلیت صدای خروجی SALAR TV را می‌گیرد؛ صدای میکروفون نیست. فقط شبکه‌های مجاز به ضبط صدای پخش قابل تشخیص‌اند.",
            color = Color(0xFFFFCC80), fontSize = 9.sp
        )
    }
}

private fun writePcmWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
    val byteRate = sampleRate * channels * 2
    FileOutputStream(file).use { out ->
        fun writeInt(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
        }
        fun writeShort(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeInt(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeInt(16)
        writeShort(1)
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(channels * 2)
        writeShort(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeInt(pcm.size)
        out.write(pcm)
    }
}

private fun ensureSpeechModel(context: Context, model: LiveSpeechModel): File {
    val base = File(context.filesDir, "salar-vosk")
    if (!base.exists() && !base.mkdirs()) throw IllegalStateException("ساخت پوشهٔ مدل ممکن نشد.")
    val target = File(base, model.id)
    if (File(target, "am/final.mdl").exists() && File(target, "conf").exists()) {
        // Validate the existing model before treating it as the selected model.
        // Do not delete another working model until native loading succeeds.
        val validation = Model(target.absolutePath)
        validation.close()
        return target
    }

    val archive = File(context.cacheDir, "vosk-${model.id}.zip")
    val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 60000
        instanceFollowRedirects = true
    }
    try {
        connection.connect()
        if (connection.responseCode !in 200..299) throw IllegalStateException("دانلود مدل با کد ${connection.responseCode} متوقف شد.")
        connection.inputStream.use { input ->
            FileOutputStream(archive).use { output -> input.copyTo(output) }
        }
    } catch (error: Exception) {
        archive.delete()
        throw error
    } finally {
        connection.disconnect()
    }

    val staging = File(base, "${model.id}-extract")
    if (staging.exists()) staging.deleteRecursively()
    if (!staging.mkdirs()) throw IllegalStateException("ساخت پوشهٔ موقت مدل ناموفق بود.")
    try {
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val normalized = entry.name.replace(92.toChar(), '/')
                val relative = normalized.substringAfter('/', normalized).trimStart('/')
                if (relative.isNotBlank()) {
                    val output = File(staging, relative)
                    if (!output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw IllegalStateException("مسیر نامعتبر در فایل مدل.")
                    }
                    if (entry.isDirectory) output.mkdirs()
                    else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { out -> zip.copyTo(out) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!File(staging, "am/final.mdl").exists() || !File(staging, "conf").exists()) {
            throw IllegalStateException("ساختار مدل گفتار معتبر نیست.")
        }
        // Native-load the extracted model before replacing any previous model.
        val validation = Model(staging.absolutePath)
        validation.close()
    } catch (error: Exception) {
        archive.delete()
        staging.deleteRecursively()
        throw error
    }
    archive.delete()
    val backup = File(base, "${model.id}-previous")
    if (backup.exists()) backup.deleteRecursively()
    val hadPrevious = target.exists()
    if (hadPrevious && !target.renameTo(backup)) {
        staging.deleteRecursively()
        throw IllegalStateException("حفظ مدل قبلی پیش از جایگزینی ناموفق بود.")
    }
    if (!staging.renameTo(target)) {
        if (hadPrevious) backup.renameTo(target)
        staging.deleteRecursively()
        throw IllegalStateException("انتقال مدل به پوشهٔ نهایی ناموفق بود؛ مدل قبلی حفظ شد.")
    }
    backup.deleteRecursively()
    // Previous language models are deliberately retained here. The caller removes
    // them only after the selected model has loaded successfully.
    return target
}
