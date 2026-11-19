package com.salar.tv

import android.media.AudioFormat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Connects the primary player's PCM tap to the broker and translates completed
 * source-language speech turns into Persian. No microphone permission is used.
 */
class SalarLiveSubtitleEngine(
    private val capture: SalarAiAudioCapture,
    private val brokerBaseUrl: String,
    private val installId: String = UUID.randomUUID().toString(),
    private val onPersianSubtitle: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    @Volatile private var transport: SalarRealtimeTranscriptionClient? = null
    private var worker: Thread? = null
    private val resampler = SalarPcm16MonoResampler()
    private val translationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "Salar-Subtitle-Translate").apply { isDaemon = true }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runSession() }, "Salar-Live-Subtitle").apply {
            isDaemon = true
            start()
        }
    }

    private fun runSession() {
        try {
            val base = brokerBaseUrl.trim().trimEnd('/')
            require(base.startsWith("https://")) { "نشانی سرور باید با https:// شروع شود" }
            onStatus("در حال دریافت مجوز موقت زیرنویس…")
            val session = postJson("$base/v1/live/subtitle-session", JSONObject()
                .put("installId", installId)
                .put("languages", org.json.JSONArray(listOf("en", "tr", "az", "ar", "fa"))))
            val secret = session.optString("client_secret")
            require(secret.isNotBlank()) { "سرور کلید موقت زیرنویس را برنگرداند" }

            val connected = CountDownLatch(1)
            val client = SalarRealtimeTranscriptionClient(
                clientSecret = secret,
                onPartialTranscript = { _, _ -> },
                onFinalTranscript = { _, transcript ->
                    if (running.get() && transcript.isNotBlank()) {
                        translationExecutor.execute {
                            try {
                                val translated = postJson("$base/v1/live/translate-subtitle",
                                    JSONObject()
                                        .put("text", transcript)
                                        .put("sourceLanguage", "unknown")
                                        .put("installId", installId)
                                ).optString("translated_text")
                                if (running.get() && translated.isNotBlank()) onPersianSubtitle(translated)
                            } catch (e: Exception) {
                                if (running.get()) onError("ترجمهٔ زیرنویس: ${e.message ?: "خطای نامشخص"}")
                            }
                        }
                    }
                },
                onState = { state ->
                    if (state == "connected") {
                        connected.countDown()
                        onStatus("اتصال برقرار شد؛ دریافت صدای شبکه…")
                    } else if (running.get()) {
                        onStatus("اتصال زیرنویس قطع شد")
                    }
                },
                onError = { message -> if (running.get()) onError(message) }
            )
            transport = client
            client.connect()
            if (!connected.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("اتصال به سرویس تشخیص گفتار در زمان مقرر برقرار نشد")
            }

            capture.start()
            onStatus("زیرنویس هوشمند فعال است")
            var speechActive = false
            var silenceMs = 0L
            var speechMs = 0L

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val chunk = capture.pollChunk(120) ?: continue
                val pcm = resampler.convert(chunk) ?: continue
                if (pcm.encoding != AudioFormat.ENCODING_PCM_16BIT ||
                    pcm.sampleRateHz != 24_000 || pcm.channelCount != 1) continue

                if (!client.appendPcm16(pcm.pcm)) {
                    throw IllegalStateException("ارسال صدای زیرنویس ناموفق بود")
                }
                val durationMs = (pcm.pcm.size * 1000L) / (24_000L * 2L)
                val voiced = rms(pcm.pcm) >= 0.008
                if (voiced) {
                    speechActive = true
                    silenceMs = 0
                    speechMs += durationMs
                } else if (speechActive) {
                    silenceMs += durationMs
                }

                // The transcription session has no server VAD: commit at client-side
                // silence boundaries, with a hard cap to keep long broadcasts flowing.
                if (speechActive && (silenceMs >= 650L || speechMs >= 12_000L)) {
                    client.commitAudioTurn()
                    speechActive = false
                    silenceMs = 0
                    speechMs = 0
                }
            }
        } catch (e: Exception) {
            if (running.get()) onError(e.message ?: "راه‌اندازی زیرنویس ناموفق بود")
        } finally {
            capture.stop()
            transport?.close()
            transport = null
            translationExecutor.shutdownNow()
            resampler.reset()
            if (running.get()) onStatus("زیرنویس متوقف شد")
        }
    }

    private fun rms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xff
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt() / 32768.0
            sum += sample * sample
            count++
            i += 2
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(response).optString("message") }.getOrNull()
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "خطای سرور ($code)")
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        capture.stop()
        transport?.close()
        worker?.interrupt()
        worker = null
    }
}
