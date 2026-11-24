package com.salar.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class HlsRecorderService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra("name") ?: "SALAR TV"
        val streamUrl = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val durationMinutes = intent.getIntExtra("duration", 60).coerceIn(1, 360)
        createChannel()
        startForeground(4102, notification("در حال آماده‌سازی ضبط: $name"))
        running = true
        worker = Thread {
            val folder = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
            val safeName = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
            val deadline = System.currentTimeMillis() + durationMinutes * 60_000L
            var output: File? = null
            var saved = 0L
            try {
                val probe = openConnection(streamUrl)
                val contentType = probe.contentType.orEmpty().lowercase(Locale.ROOT)
                val isHls = streamUrl.substringBefore('?').endsWith(".m3u8", true) ||
                    streamUrl.substringBefore('?').endsWith(".m3u", true) ||
                    contentType.contains("mpegurl") || contentType.contains("vnd.apple")
                if (isHls) {
                    probe.disconnect()
                    val target = File(folder, "SALAR_" + safeName + "_" + System.currentTimeMillis() + ".ts")
                    output = target
                    val seen = HashSet<String>()
                    while (running && System.currentTimeMillis() < deadline) {
                        val playlistText = downloadText(streamUrl)
                        val playlistUrl = URL(streamUrl)
                        val lines = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                        val variant = lines.firstOrNull { !it.startsWith("#") && it.contains(".m3u8", true) }
                        val mediaUrl = if (variant != null) URL(playlistUrl, variant).toString() else streamUrl
                        val mediaText = if (variant != null) downloadText(mediaUrl) else playlistText
                        if (mediaText.contains("#EXT-X-KEY") && !mediaText.contains("METHOD=NONE")) {
                            error("این منبع HLS رمزگذاری شده است و ضبط مستقیم آن پشتیبانی نمی‌شود.")
                        }
                        if (mediaText.contains("#EXT-X-MAP") || mediaText.contains("#EXT-X-BYTERANGE")) {
                            error("این قالب HLS از نوع قطعه‌بندی‌شده خاص است؛ ضبط امن این نوع هنوز پشتیبانی نمی‌شود.")
                        }
                        val base = URL(mediaUrl)
                        val segments = mediaText.lineSequence().map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .map { URL(base, it).toString() }.toList()
                        for (segment in segments) {
                            if (!running || System.currentTimeMillis() >= deadline) break
                            if (seen.add(segment)) {
                                val bytes = downloadBytes(segment)
                                target.appendBytes(bytes)
                                saved += bytes.size
                                updateNotification("در حال ضبط: $name • " + (saved / 1_048_576) + " مگابایت")
                            }
                        }
                        if (mediaText.contains("#EXT-X-ENDLIST")) break
                        Thread.sleep(3000)
                    }
                } else {
                    val extension = recordingExtension(contentType, streamUrl)
                    val target = File(folder, "SALAR_" + safeName + "_" + System.currentTimeMillis() + "." + extension)
                    output = target
                    probe.connectTimeout = 15000
                    probe.readTimeout = 20000
                    probe.setRequestProperty("User-Agent", "SALAR-TV/1.2")
                    val response = probe.responseCode
                    if (response !in 200..299) error("HTTP $response")
                    probe.inputStream.use { input ->
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var lastNotice = 0L
                            while (running && System.currentTimeMillis() < deadline) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                out.write(buffer, 0, count)
                                saved += count
                                if (saved - lastNotice >= 1_048_576L) {
                                    lastNotice = saved
                                    updateNotification("در حال ضبط رادیو/جریان مستقیم: $name • " + (saved / 1_048_576) + " مگابایت")
                                }
                            }
                            out.flush()
                        }
                    }
                }
                updateNotification(if (saved > 0) "ضبط ذخیره شد: " + (output?.name ?: name) else "ضبط انجام نشد؛ منبع داده‌ای برای ذخیره نفرستاد")
            } catch (e: Exception) {
                updateNotification("خطا در ضبط $name: " + (e.message ?: "اتصال ناموفق"))
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.also { it.name = "SalarTvRecorder"; it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openConnection(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "SALAR-TV/1.2")
        connection.setRequestProperty("Icy-MetaData", "0")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            error("HTTP $code")
        }
        return connection
    }

    private fun downloadText(address: String): String = downloadBytes(address).toString(Charsets.UTF_8)

    private fun downloadBytes(address: String): ByteArray {
        val connection = openConnection(address)
        try {
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun recordingExtension(contentType: String, address: String): String {
        val type = contentType.substringBefore(';').trim()
        return when {
            type == "audio/mpeg" || type == "audio/mp3" -> "mp3"
            type.contains("aac") -> "aac"
            type.contains("ogg") -> "ogg"
            type.contains("wav") || type.contains("wave") -> "wav"
            type.contains("audio/mp4") || type.contains("audio/x-m4a") -> "m4a"
            type.contains("video/mp4") -> "mp4"
            type.contains("mp2t") -> "ts"
            else -> {
                val suffix = address.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (suffix in setOf("mp3", "aac", "ogg", "wav", "m4a", "mp4", "ts")) suffix else "audio"
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("salar_recording", "ضبط SALAR TV", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, "salar_recording")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("SALAR TV")
            .setContentText(message)
            .setOngoing(true)
            .build()

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(4102, notification(message))
    }
}
