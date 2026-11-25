package com.salar.tv
import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/** One multilingual Whisper model; the speech language is detected automatically. */
internal object LiveWhisperEngine {
    private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    private const val MODEL_FILE = "ggml-tiny.bin"
    private const val MODEL_BYTES = 77691713L
    private const val MODEL_SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    private fun modelFile(context: Context) = File(File(context.filesDir, "salar-whisper"), MODEL_FILE)
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(131072)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun isModelDownloaded(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() == MODEL_BYTES &&
            runCatching { sha256(file).equals(MODEL_SHA256, true) }.getOrDefault(false)
    }
    fun downloadModel(context: Context): File {
        val destination = modelFile(context)
        if (isModelDownloaded(context)) return destination
        val directory = destination.parentFile ?: error("مسیر ذخیره مدل نامعتبر است.")
        if (!directory.exists() && !directory.mkdirs()) error("ساخت پوشه مدل ناموفق بود.")
        val temporary = File(directory, MODEL_FILE + ".part").apply { delete() }
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 120000; instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) error("دانلود مدل با کد " + connection.responseCode + " ناموفق بود.")
            connection.inputStream.use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() } }
        } catch (e: Exception) { temporary.delete(); throw e }
        finally { connection.disconnect() }
        if (!temporary.isFile || temporary.length() != MODEL_BYTES || !sha256(temporary).equals(MODEL_SHA256, true)) {
            temporary.delete(); error("اعتبارسنجی حجم یا SHA-256 مدل ناموفق بود؛ نسخه قبلی حفظ شد.")
        }
        val backup = File(directory, MODEL_FILE + ".previous")
        backup.delete()
        val hadPrevious = destination.exists()
        if (hadPrevious && !destination.renameTo(backup)) { temporary.delete(); error("حفظ مدل قبلی ناموفق بود.") }
        if (!temporary.renameTo(destination)) {
            if (hadPrevious) backup.renameTo(destination)
            temporary.delete(); error("ذخیره مدل جدید ناموفق بود؛ نسخه قبلی حفظ شد.")
        }
        backup.delete()
        return destination
    }
    suspend fun loadModel(context: Context): WhisperModel {
        val file = modelFile(context)
        if (!isModelDownloaded(context)) error("ابتدا مدل چندزبانه را دانلود کنید.")
        return Whisper.loadModel(context, file.absolutePath)
    }
    suspend fun transcribeAutomatically(model: WhisperModel, wavPath: String): String =
        Whisper.transcribe(model, wavPath, WhisperConfig(language = "auto", translate = false, threads = 2)).text.trim()
    fun releaseModel(model: WhisperModel) = Whisper.releaseModel(model)
}
