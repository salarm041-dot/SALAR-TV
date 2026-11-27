package com.salar.tv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Composable
internal fun LiveAudioTranslationOverlay(modifier: Modifier = Modifier, onTranslation: (String) -> Unit = {}) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("salar_tv", Context.MODE_PRIVATE) }
    val identifier = remember { LanguageIdentification.getClient() }
    val queue = remember { LinkedBlockingQueue<ByteArray>(2) }
    val running = remember { AtomicBoolean(false) }
    val droppedAudioChunks = remember { AtomicInteger(0) }
    val translationGeneration = remember { AtomicInteger(0) }
    val pendingTranslations = remember { ArrayDeque<String>() }
    var translationInFlight by remember { mutableStateOf(false) }
    var droppedAudioChunksUi by remember { mutableStateOf(0) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsPending by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("مترجم چندزبانه آماده است.") }
    var translated by remember { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var projection by remember { mutableStateOf<MediaProjection?>(null) }
    var recorder by remember { mutableStateOf<AudioRecord?>(null) }
    var captureThread by remember { mutableStateOf<Thread?>(null) }
    var recognitionThread by remember { mutableStateOf<Thread?>(null) }
    var model by remember { mutableStateOf<WhisperModel?>(null) }

    DisposableEffect(context) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { result ->
            handler.post {
                if (result == TextToSpeech.SUCCESS) {
                    val language = engine.setLanguage(Locale("fa", "IR"))
                    ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
                } else ttsReady = false
            }
        }
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                handler.post { ttsPending = (ttsPending - 1).coerceAtLeast(0) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handler.post {
                    ttsPending = (ttsPending - 1).coerceAtLeast(0)
                    status = "موتور دوبله هنگام تولید صدا خطا داد."
                }
            }
        })
        onDispose { runCatching { engine.stop() }; runCatching { engine.shutdown() }; tts = null; ttsReady = false; ttsPending = 0 }
    }

    fun stopCapture() {
        running.set(false)
        translationGeneration.incrementAndGet()
        pendingTranslations.clear()
        translationInFlight = false
        runCatching { tts?.stop() }; ttsPending = 0
        val audio = recorder; recorder = null
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        val screen = projection; projection = null
        runCatching { screen?.stop() }
        val capture = captureThread; val recognize = recognitionThread
        captureThread = null; recognitionThread = null
        capture?.interrupt(); recognize?.interrupt()
        val oldModel = model; model = null
        queue.clear(); active = false
        Thread({
            runCatching { capture?.join() }; runCatching { recognize?.join() }
            runCatching { oldModel?.let { LiveWhisperEngine.releaseModel(it) } }
        }, "SalarTvWhisperCleanup").apply { isDaemon = true; start() }
    }

    // Keep speech synthesis in one place so already-Persian broadcasts are dubbed too.
    fun speakPersian(text: String) {
        if (!prefs.getBoolean("live_dubbing_enabled", true) || text.isBlank()) return
        val engine = tts
        if (!ttsReady || engine == null) {
            status = "زیرنویس فارسی آماده شد؛ موتور گفتار فارسی گوشی در دسترس نیست."
            return
        }
        val preset = prefs.getString("live_dubbing_voice", "زیر")
        engine.setPitch(if (preset == "بم" || preset == "مرد") 0.88f else 1.08f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, prefs.getFloat("live_dubbing_volume", 1f).coerceIn(0f, 1f))
        }
        // Keep a short bounded speech backlog. An unlimited QUEUE_ADD would make
        // translated dubbing drift farther behind live video when ASR is slower.
        if (ttsPending >= 3) {
            engine.stop()
            ttsPending = 0
            status = "برای همگام‌ماندن دوبله با پخش زنده، گفتار عقب‌افتاده حذف شد."
        }
        val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, "salar-live-" + System.currentTimeMillis())
        if (result == TextToSpeech.ERROR) status = "تولید صدای دوبله ناموفق بود؛ موتور گفتار گوشی را بررسی کنید."
        else ttsPending += 1
    }

    fun processNextTranslation() {
        if (translationInFlight || pendingTranslations.isEmpty()) return
        val clean = pendingTranslations.removeFirst()
        val generation = translationGeneration.get()
        translationInFlight = true
        fun finish() {
            if (generation == translationGeneration.get()) {
                translationInFlight = false
                processNextTranslation()
            }
        }
        identifier.identifyLanguage(clean.take(200)).addOnSuccessListener { tag ->
            if (generation != translationGeneration.get()) return@addOnSuccessListener
            if (tag == "und") {
                status = "زبان گفتار با اطمینان کافی تشخیص داده نشد."
                finish()
                return@addOnSuccessListener
            }
            val source = TranslateLanguage.fromLanguageTag(tag)
            if (source == null) {
                status = "زبان " + tag + " تشخیص داده شد، اما ترجمه آن پشتیبانی نمی‌شود."
                finish()
                return@addOnSuccessListener
            }
            if (source == TranslateLanguage.PERSIAN) {
                translated = clean
                onTranslation(clean)
                status = "گفتار فارسی تشخیص داده شد."
                speakPersian(clean)
                finish()
                return@addOnSuccessListener
            }
            status = "زبان گفتار: " + tag + "؛ در حال ترجمه فارسی…"
            val translator = Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(TranslateLanguage.PERSIAN).build())
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).addOnSuccessListener {
                if (generation != translationGeneration.get()) {
                    translator.close()
                    return@addOnSuccessListener
                }
                translator.translate(clean).addOnSuccessListener { result ->
                    translator.close()
                    if (generation == translationGeneration.get()) {
                        translated = result
                        onTranslation(result)
                        status = "ترجمه فارسی زنده فعال است؛ زبان تشخیص‌داده‌شده: " + tag
                        speakPersian(result)
                        finish()
                    }
                }.addOnFailureListener {
                    translator.close()
                    if (generation == translationGeneration.get()) {
                        status = "ترجمه ناموفق بود: " + (it.localizedMessage ?: "خطا")
                        finish()
                    }
                }
            }.addOnFailureListener {
                translator.close()
                if (generation == translationGeneration.get()) {
                    status = "دریافت مدل ترجمه ناموفق بود؛ اینترنت را بررسی کنید."
                    finish()
                }
            }
        }.addOnFailureListener {
            if (generation == translationGeneration.get()) {
                status = "تشخیص زبان ناموفق بود: " + (it.localizedMessage ?: "خطا")
                finish()
            }
        }
    }

    fun translateText(text: String) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 2) return
        // ML Kit callbacks are asynchronous. Serialize chunks so a slower earlier
        // translation can never be overtaken by a later sentence.
        if (pendingTranslations.size >= 3) {
            pendingTranslations.removeFirst()
            status = "صف ترجمه عقب افتاد؛ قدیمی‌ترین جمله حذف شد تا زیرنویس از پخش خیلی عقب نماند."
        }
        pendingTranslations.addLast(clean)
        processNextTranslation()
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            stopCapture(); preparing = false; status = "اجازه ضبط صدای پخش تأیید نشد."
        } else {
            try {
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val screen = manager.getMediaProjection(result.resultCode, data)
                projection = screen
                val config = AudioPlaybackCaptureConfiguration.Builder(screen)
                    .addMatchingUid(Process.myUid()).addMatchingUsage(AudioAttributes.USAGE_MEDIA).build()
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                val minBuffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val audio = AudioRecord.Builder().setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer.coerceAtLeast(8192) * 2).setAudioPlaybackCaptureConfig(config).build()
                if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("راه‌اندازی دریافت صدای داخلی ناموفق بود.") }
                val activeModel = model ?: error("مدل چندزبانه بارگذاری نشده است.")
                queue.clear(); droppedAudioChunks.set(0); droppedAudioChunksUi = 0; running.set(true); audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) { audio.release(); error("ضبط صدای خروجی شروع نشد.") }
                recorder = audio; active = true; status = "در حال شنیدن صدای شبکه و تشخیص خودکار زبان…"

                val capture = Thread({
                    val buffer = ByteArray(4096); val chunk = ByteArrayOutputStream(); val required = 16000 * 2 * 8
                    try {
                        while (running.get() && !Thread.currentThread().isInterrupted) {
                            val count = audio.read(buffer, 0, buffer.size)
                            if (count > 0) {
                                chunk.write(buffer, 0, count)
                                if (chunk.size() >= required) {
                                    val bytes = chunk.toByteArray(); chunk.reset()
                                    if (!queue.offer(bytes)) {
                                        // Prefer recent live audio over stale backlog, but expose every gap.
                                        queue.poll()
                                        queue.offer(bytes)
                                        val dropped = droppedAudioChunks.incrementAndGet()
                                        if (dropped == 1 || dropped % 3 == 0) {
                                            handler.post { droppedAudioChunksUi = dropped }
                                        }
                                    }
                                }
                            } else if (count < 0 && running.get()) error("خواندن صدای پخش با کد " + count + " متوقف شد.")
                        }
                    } catch (e: Exception) {
                        if (running.get()) handler.post { status = "دریافت صدای پخش متوقف شد: " + (e.localizedMessage ?: "خطا"); stopCapture() }
                    }
                }, "SalarTvAudioCapture").apply { isDaemon = true }

                val recognize = Thread({
                    var index = 0
                    try {
                        while (running.get() || queue.isNotEmpty()) {
                            val pcm = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                            val wav = File(context.cacheDir, "salar-live-" + index + ".wav"); index++
                            try {
                                writePcmWav(wav, pcm)
                                val words = runBlocking { LiveWhisperEngine.transcribeAutomatically(activeModel, wav.absolutePath) }
                                if (words.isNotBlank()) handler.post { translateText(words) }
                            } finally { wav.delete() }
                        }
                    } catch (e: Exception) {
                        if (running.get()) handler.post { status = "تشخیص گفتار ناموفق بود: " + (e.localizedMessage ?: "خطا"); stopCapture() }
                    }
                }, "SalarTvWhisperRecognition").apply { isDaemon = true }

                captureThread = capture; recognitionThread = recognize
                capture.start(); recognize.start()
                screen.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { handler.post { if (active) { stopCapture(); status = "دسترسی ضبط صدای پخش متوقف شد." } } }
                }, handler)
            } catch (e: Exception) {
                stopCapture(); preparing = false
                status = "شروع ترجمه ناموفق بود: " + (e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    fun startCapture() {
        if (preparing || active) return
        if (Build.VERSION.SDK_INT < 29) { status = "دریافت صدای داخلی به اندروید ۱۰ یا بالاتر نیاز دارد."; return }
        if (!LiveWhisperEngine.isModelDownloaded(context)) { status = "ابتدا مدل چندزبانه را دانلود کنید."; return }
        preparing = true; status = "در حال بارگذاری مدل چندزبانه…"
        Thread({
            try {
                val loaded = runBlocking { LiveWhisperEngine.loadModel(context) }
                handler.post {
                    model = loaded; preparing = false
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                }
            } catch (e: Exception) {
                handler.post { preparing = false; status = "بارگذاری مدل ناموفق بود: " + (e.localizedMessage ?: e.javaClass.simpleName) }
            }
        }, "SalarTvWhisperLoader").apply { isDaemon = true; start() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCapture() else status = "مجوز لازم برای دریافت صدای پخش داده نشد."
    }

    DisposableEffect(identifier) {
        onDispose {
            stopCapture()
            identifier.close()
        }
    }

    Column(modifier = modifier.background(Color.Black.copy(alpha = 0.86f), RoundedCornerShape(10.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ترجمه و دوبله خودکار گفتار به فارسی", color = Color(0xFFFFD27A), fontSize = 12.sp)
        Text("تشخیص خودکار زبان؛ بدون انتخاب زبان برای هر شبکه", color = Color.White, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !preparing && !active, onClick = {
                preparing = true; status = "در حال دانلود مدل چندزبانه Whisper (حدود ۷۸ مگابایت)…"
                Thread({
                    try {
                        LiveWhisperEngine.downloadModel(context)
                        handler.post { preparing = false; status = "مدل دانلود و اعتبارسنجی شد؛ آماده شروع است." }
                    } catch (e: Exception) {
                        handler.post { preparing = false; status = "دانلود مدل ناموفق بود: " + (e.localizedMessage ?: e.javaClass.simpleName) }
                    }
                }, "SalarTvWhisperDownload").apply { isDaemon = true; start() }
            }) { Text("دانلود مدل چندزبانه", fontSize = 10.sp) }
            Button(enabled = !preparing && (active || LiveWhisperEngine.isModelDownloaded(context)), onClick = {
                if (active) { stopCapture(); status = "ترجمه گفتار متوقف شد." }
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startCapture()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (active) "توقف" else if (preparing) "آماده‌سازی…" else "شروع") }
        }
        if (translated.isNotBlank()) Text(translated, color = Color.White, fontSize = 13.sp)
        Text(status, color = Color(0xFFE0E0E0), fontSize = 10.sp)
        if (droppedAudioChunksUi > 0) {
            Text("هشدار: به‌دلیل کندی تشخیص، " + droppedAudioChunksUi + " قطعه صوتی جا افتاده است؛ ممکن است بخشی از گفتار زیرنویس نشود.",
                color = Color(0xFFFF8A65), fontSize = 10.sp)
        }
        Text("ترجمه از صدای گفتار ساخته می‌شود، نه زیرنویس شبکه. بعضی منابع اجازه ضبط صدای داخلی نمی‌دهند. دوبله به وجود صدای فارسی در گوشی وابسته است.",
            color = Color(0xFFFFCC80), fontSize = 9.sp)
    }
}

private fun writePcmWav(file: File, pcm: ByteArray) {
    val rate = 16000; val channels = 1; val byteRate = rate * channels * 2
    FileOutputStream(file).use { out ->
        fun int(v: Int) { out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v shr 24 and 255) }
        fun short(v: Int) { out.write(v and 255); out.write(v shr 8 and 255) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); int(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII)); out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int(16); short(1); short(channels); int(rate); int(byteRate); short(channels * 2); short(16)
        out.write("data".toByteArray(Charsets.US_ASCII)); int(pcm.size); out.write(pcm)
    }
}
