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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

private data class LiveSpeechModel(val id: String, val language: String, val url: String)

@Composable
internal fun LiveAudioTranslationOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val models = remember {
        listOf(
            LiveSpeechModel("en", TranslateLanguage.ENGLISH, "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            LiveSpeechModel("tr", TranslateLanguage.TURKISH, "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip")
        )
    }
    var selectedModel by remember { mutableStateOf("en") }
    var status by remember { mutableStateOf("ترجمهٔ گفتار آمادهٔ شروع است.") }
    var translated by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var isPreparing by remember { mutableStateOf(false) }
    var projection by remember { mutableStateOf<MediaProjection?>(null) }
    var recorder by remember { mutableStateOf<AudioRecord?>(null) }
    var recognitionThread by remember { mutableStateOf<Thread?>(null) }
    var recognizer by remember { mutableStateOf<Recognizer?>(null) }
    var modelInstance by remember { mutableStateOf<Model?>(null) }
    val runningFlag = remember { AtomicBoolean(false) }
    val translator = remember(selectedModel) {
        val language = models.first { it.id == selectedModel }.language
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(language)
                .setTargetLanguage(TranslateLanguage.PERSIAN)
                .build()
        )
    }

    fun stopCapture() {
        runningFlag.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { projection?.stop() }
        projection = null
        recognitionThread?.interrupt()
        recognitionThread = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { modelInstance?.close() }
        modelInstance = null
        isRunning = false
    }

    fun translateText(source: String) {
        if (source.isBlank()) return
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(source)
                    .addOnSuccessListener { result ->
                        translated = result
                        status = "ترجمهٔ زنده فعال است"
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
            status = "دسترسی ضبط صدای پخش‌کننده تأیید نشد."
            runCatching { modelInstance?.close() }
            modelInstance = null
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
                val activeModel = modelInstance ?: throw IllegalStateException("مدل گفتار بارگذاری نشده است.")
                val activeRecognizer = Recognizer(activeModel, 16000.0f)
                recorder = audioRecord
                recognizer = activeRecognizer
                runningFlag.set(true)
                isRunning = true
                status = "در حال شنیدن صدای خودِ پخش‌کننده…"
                val thread = Thread {
                    val buffer = ByteArray(8192)
                    try {
                        audioRecord.startRecording()
                        while (runningFlag.get() && !Thread.currentThread().isInterrupted) {
                            val count = audioRecord.read(buffer, 0, buffer.size)
                            if (count > 0 && activeRecognizer.acceptWaveForm(buffer, count)) {
                                val recognized = runCatching {
                                    JSONObject(activeRecognizer.result).optString("text", "")
                                }.getOrDefault("")
                                if (recognized.isNotBlank()) {
                                    mainHandler.post { translateText(recognized) }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            if (runningFlag.get()) status = "خطای دریافت صدای پخش: ${e.localizedMessage ?: e.javaClass.simpleName}"
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
        status = "در حال آماده‌سازی مدل تشخیص گفتار؛ دانلود اولیه ممکن است حدود ۴۰ مگابایت باشد…"
        val chosen = models.first { it.id == selectedModel }
        executor.execute {
            try {
                val modelDir = ensureSpeechModel(context, chosen)
                val loadedModel = Model(modelDir.absolutePath)
                mainHandler.post {
                    modelInstance = loadedModel
                    isPreparing = false
                    status = "مدل آماده شد؛ مجوز ضبط صدای داخلی را تأیید کنید."
                    requestCapture()
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
            translator.close()
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("ترجمهٔ گفتار زنده به فارسی", color = Color(0xFFFFD27A), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = selectedModel == "en", onClick = {
                if (!isRunning && !isPreparing) selectedModel = "en"
            }, label = { Text("انگلیسی", fontSize = 11.sp) })
            FilterChip(selected = selectedModel == "tr", onClick = {
                if (!isRunning && !isPreparing) selectedModel = "tr"
            }, label = { Text("ترکی", fontSize = 11.sp) })
            Button(
                enabled = !isPreparing,
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
            "این قابلیت صدای خروجی SALAR TV را می‌گیرد؛ صدای میکروفون نیست. فقط شبکه‌های مجاز به ضبط صدای پخش قابل تشخیص‌اند.",
            color = Color(0xFFFFCC80), fontSize = 9.sp
        )
    }
}

private fun ensureSpeechModel(context: Context, model: LiveSpeechModel): File {
    val base = File(context.filesDir, "salar-vosk")
    if (!base.exists() && !base.mkdirs()) throw IllegalStateException("ساخت پوشهٔ مدل ممکن نشد.")
    val target = File(base, model.id)
    if (File(target, "am/final.mdl").exists() && File(target, "conf").exists()) return target

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
    } finally {
        connection.disconnect()
    }

    val staging = File(base, "${model.id}-extract")
    if (staging.exists()) staging.deleteRecursively()
    if (!staging.mkdirs()) throw IllegalStateException("ساخت پوشهٔ موقت مدل ناموفق بود.")
    ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val normalized = entry.name.replace('\\\\', '/')
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
    archive.delete()
    if (!File(staging, "am/final.mdl").exists()) {
        staging.deleteRecursively()
        throw IllegalStateException("ساختار مدل گفتار معتبر نیست.")
    }
    if (target.exists()) target.deleteRecursively()
    if (!staging.renameTo(target)) throw IllegalStateException("انتقال مدل به پوشهٔ نهایی ناموفق بود.")
    return target
}
