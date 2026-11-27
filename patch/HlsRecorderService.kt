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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class HlsRecorderService : Service() {
    private data class RecordingSession(
        val id: Int,
        val name: String,
        val active: AtomicBoolean = AtomicBoolean(true),
        @Volatile var worker: Thread? = null
    )

    private val sessions = ConcurrentHashMap<Int, RecordingSession>()
    private val notificationId = 4102

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val id = intent?.getIntExtra(EXTRA_RECORDING_ID, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (intent?.action == ACTION_STOP) {
            if (id != Int.MIN_VALUE) stopSession(id)
            return START_NOT_STICKY
        }

        val streamUrl = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val name = intent.getStringExtra("name") ?: "SALAR TV"
        val durationMinutes = intent.getIntExtra("duration", 60).coerceIn(1, 360)
        val sessionId = if (id != Int.MIN_VALUE) id else streamUrl.hashCode()

        synchronized(sessions) {
            if (sessions.containsKey(sessionId)) {
                startForeground(notificationId, notification("این شبکه هم‌اکنون در حال ضبط است."))
                return START_NOT_STICKY
            }
            if (sessions.size >= MAX_SIMULTANEOUS_RECORDINGS) {
                startForeground(notificationId, notification("حداکثر دو ضبط هم‌زمان مجاز است؛ ابتدا یکی را متوقف کنید."))
                return START_NOT_STICKY
            }
            val session = RecordingSession(sessionId, name)
            sessions[sessionId] = session
            persistActiveSessions()
            startForeground(notificationId, notification("در حال آماده‌سازی ضبط: $name (\${sessions.size}/2)"))
            session.worker = Thread({ recordStream(session, streamUrl, durationMinutes) },
                "SalarRecorder-$sessionId").apply { isDaemon = true; start() }
        }
        return START_NOT_STICKY
    }

    private fun recordStream(session: RecordingSession, streamUrl: String, durationMinutes: Int) {
        val folder = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
        val safeName = session.name.replace(Regex("[^\\\\p{L}\\\\p{N}_-]"), "_").take(40)
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
                while (session.active.get() && System.currentTimeMillis() < deadline) {
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
                        if (!session.active.get() || System.currentTimeMillis() >= deadline) break
                        if (seen.add(segment)) {
                            val bytes = downloadBytes(segment)
                            target.appendBytes(bytes)
                            saved += bytes.size
                            updateNotification("ضبط \${session.name}: " + (saved / 1_048_576) + " مگابایت")
                        }
                    }
                    if (mediaText.contains("#EXT-X-ENDLIST")) break
                    Thread.sleep(3000)
                }
            } else {
                val extension = recordingExtension(contentType, streamUrl)
                val target = File(folder, "SALAR_" + safeName + "_" + System.currentTimeMillis() + "." + extension)
                output = target
                probe.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var lastNotice = 0L
                        while (session.active.get() && System.currentTimeMillis() < deadline) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            out.write(buffer, 0, count)
                            saved += count
                            if (saved - lastNotice >= 1_048_576L) {
                                lastNotice = saved
                                updateNotification("ضبط \${session.name}: " + (saved / 1_048_576) + " مگابایت")
                            }
                        }
                        out.flush()
                    }
                }
            }
            updateNotification(if (saved > 0) "ضبط \${session.name} ذخیره شد: " + (output?.name ?: "") else "ضبط \${session.name} داده‌ای دریافت نکرد.")
        } catch (e: Exception) {
            updateNotification("خطا در ضبط \${session.name}: " + (e.message ?: "اتصال ناموفق"))
        } finally {
            session.active.set(false)
            finishSession(session)
        }
    }

    private fun stopSession(id: Int) {
        val session = sessions[id] ?: return
        session.active.set(false)
        session.worker?.interrupt()
        updateNotification("درخواست توقف ضبط \${session.name} دریافت شد.")
    }

    private fun finishSession(session: RecordingSession) {
        synchronized(sessions) {
            sessions.remove(session.id, session)
            persistActiveSessions()
            if (sessions.isEmpty()) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH)
                else { @Suppress("DEPRECATION") stopForeground(false) }
                stopSelf()
            } else {
                updateNotification("در حال ضبط \${sessions.size} شبکه: " + sessions.values.joinToString("، ") { it.name })
            }
        }
    }

    private fun persistActiveSessions() {
        getSharedPreferences("salar_tv", MODE_PRIVATE).edit()
            .putStringSet("active_recording_ids", sessions.keys.map { it.toString() }.toSet())
            .apply()
    }

    override fun onDestroy() {
        sessions.values.forEach { session ->
            session.active.set(false)
            session.worker?.interrupt()
        }
        sessions.clear()
        persistActiveSessions()
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
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    companion object {
        const val ACTION_STOP = "com.salar.tv.action.STOP_RECORDING"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val MAX_SIMULTANEOUS_RECORDINGS = 2
    }
}
