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

class HlsRecorderService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra("name") ?: "SALAR TV"
        val streamUrl = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val durationMinutes = intent.getIntExtra("duration", 60).coerceIn(1, 360)
        createChannel()
        startForeground(4102, notification("در حال ضبط: $name"))
        running = true
        worker = Thread {
            val folder = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
            val safeName = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
            val output = File(folder, "SALAR_" + safeName + "_" + System.currentTimeMillis() + ".ts")
            val seen = HashSet<String>()
            val deadline = System.currentTimeMillis() + durationMinutes * 60_000L
            var saved = 0L
            try {
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
                            output.appendBytes(bytes)
                            saved += bytes.size
                            updateNotification("در حال ضبط: $name • " + (saved / 1_048_576) + " مگابایت")
                        }
                    }
                    if (mediaText.contains("#EXT-X-ENDLIST")) break
                    Thread.sleep(3000)
                }
                updateNotification(if (saved > 0) "ضبط ذخیره شد: " + output.name else "ضبط انجام نشد؛ منبع قطعه قابل ذخیره ارائه نکرد")
            } catch (e: Exception) {
                updateNotification("خطا در ضبط $name: " + (e.message ?: "اتصال ناموفق"))
            } finally {
                running = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun downloadText(address: String): String = downloadBytes(address).toString(Charsets.UTF_8)

    private fun downloadBytes(address: String): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "SALAR-TV/1.1")
        try {
            if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
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
