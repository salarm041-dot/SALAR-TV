package com.salar.tv

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import dev.ffmpegkit.whisper.WhisperResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/**
 * Hebrew speech-to-text engine for SALAR TV.
 *
 * The multilingual Whisper tiny model is downloaded only when the user explicitly
 * requests it. Audio passed to transcribe() must be a 16 kHz, mono, PCM WAV file.
 */
internal object HebrewWhisperEngine {
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    private const val MODEL_FILE = "ggml-tiny.bin"
    private const val MODEL_BYTES = 77691713L
    private const val MODEL_SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"

    private fun modelFile(context: Context): File =
        File(File(context.filesDir, "salar-whisper"), MODEL_FILE)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isModelDownloaded(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() == MODEL_BYTES &&
            runCatching { sha256(file).equals(MODEL_SHA256, ignoreCase = true) }.getOrDefault(false)
    }

    /**
     * Call only from the explicit "download Hebrew" user action.
     * Existing speech models are retained until the new model is fully downloaded
     * and validated; a failed download never removes the working model.
     */
    fun downloadModel(context: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val destination = modelFile(context)
        if (isModelDownloaded(context)) {
            File(context.filesDir, "salar-vosk").deleteRecursively()
            return destination
        }

        val directory = destination.parentFile
            ?: throw IllegalStateException("مسیر ذخیره مدل عبری نامعتبر است.")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("ساخت پوشه مدل عبری ناموفق بود.")
        }

        val temporary = File(directory, "$MODEL_FILE.part")
        temporary.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 120000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("دانلود مدل عبری با کد ${connection.responseCode} ناموفق بود.")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }

        if (!temporary.isFile || temporary.length() != MODEL_BYTES) {
            temporary.delete()
            throw IllegalStateException("حجم فایل مدل عبری با نسخهٔ رسمی مطابقت ندارد.")
        }
        val actualSha256 = sha256(temporary)
        if (!actualSha256.equals(MODEL_SHA256, ignoreCase = true)) {
            temporary.delete()
            throw IllegalStateException("اعتبارسنجی SHA-256 مدل عبری ناموفق بود؛ مدل قبلی حفظ شد.")
        }
        val backup = File(directory, "$MODEL_FILE.previous")
        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw IllegalStateException("پاک‌سازی نسخهٔ پشتیبان قبلی مدل ناموفق بود.")
        }
        val hadPrevious = destination.exists()
        if (hadPrevious && !destination.renameTo(backup)) {
            temporary.delete()
            throw IllegalStateException("حفظ مدل عبری قبلی پیش از جایگزینی ناموفق بود.")
        }
        if (!temporary.renameTo(destination)) {
            if (hadPrevious) backup.renameTo(destination)
            temporary.delete()
            throw IllegalStateException("ذخیره نهایی مدل عبری ناموفق بود؛ نسخهٔ قبلی حفظ شد.")
        }
        backup.delete()

        // Only after successful validation, remove the previous Vosk language model.
        File(context.filesDir, "salar-vosk").deleteRecursively()
        return destination
    }

    suspend fun loadModel(context: Context): WhisperModel {
        val file = modelFile(context)
        if (!isModelDownloaded(context)) {
            throw IllegalStateException("ابتدا مدل عبری را به‌صورت دستی دانلود کنید.")
        }
        val model = Whisper.loadModel(context, file.absolutePath)
        File(context.filesDir, "salar-vosk").deleteRecursively()
        return model
    }

    suspend fun transcribeHebrew(model: WhisperModel, wavFile: File): WhisperResult {
        if (!wavFile.isFile || wavFile.length() < 44L) {
            throw IllegalArgumentException("فایل صوتی WAV معتبر نیست.")
        }
        return Whisper.transcribe(
            model,
            wavFile.absolutePath,
            WhisperConfig(language = "he", translate = false, threads = 2)
        )
    }

    fun releaseModel(model: WhisperModel) {
        Whisper.releaseModel(model)
    }
}
