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
    companion object {
        const val ACTION_STOP = "com.salar.tv.action.STOP_RECORDING"
        @Volatile var isRecordingActive: Boolean = false
            private set
    }
    @Volatile private var running = false
    @Volatile private var recordingGeneration = 0L
    private var worker: Thread? = null
    private var activeScheduleId: Int = Int.MIN_VALUE

    private data class ByteRange(val length: Long, val offset: Long?)
    private data class MediaPart(val uri: String, val range: ByteRange? = null)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            synchronized(this) {
                recordingGeneration += 1
                running = false
                isRecordingActive = false
                updateScheduleStatus(activeScheduleId, "stopped")
                activeScheduleId = Int.MIN_VALUE
                worker?.interrupt()
                worker = null
            }
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra("name") ?: "SALAR TV"
        val storedStreamUrl = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val durationMinutes = intent.getIntExtra("duration", 60).coerceIn(0, 10080)
        createChannel()
        startForeground(4102, notification("در حال ضبط: $name"))
        val streamUrl = runCatching { XtreamCredentialVault.resolve(this, storedStreamUrl) }.getOrElse {
            updateNotification("ضبط شروع نشد؛ اطلاعات ورود منبع در دسترس نیست.")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        activeScheduleId = intent?.getIntExtra("schedule_id", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val myScheduleId = activeScheduleId
        updateScheduleStatus(myScheduleId, "recording")
        val myGeneration = synchronized(this) {
            recordingGeneration += 1
            worker?.interrupt()
            running = true
            isRecordingActive = true
            recordingGeneration
        }
        val recordingWorker = Thread {
            val folder = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
            if (!folder.exists()) folder.mkdirs()
            val safeName = name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40)
            val deadline = if (durationMinutes == 0) Long.MAX_VALUE else System.currentTimeMillis() + durationMinutes * 60_000L
            val seen = HashSet<String>()
            val writtenMaps = HashSet<String>()
            var output: File? = null
            var saved = 0L
            var finalStatus = "completed"
            try {
                while (isCurrent(myGeneration) && (deadline == Long.MAX_VALUE || System.currentTimeMillis() < deadline)) {
                    val masterText = downloadText(streamUrl)
                    val mediaUrl = resolveMediaPlaylist(streamUrl, masterText)
                    val mediaText = if (mediaUrl == streamUrl) masterText else downloadText(mediaUrl)
                    if (mediaText.contains("#EXT-X-KEY") &&
                        Regex("METHOD=([^,\\s]+)", RegexOption.IGNORE_CASE).findAll(mediaText)
                            .any { !it.groupValues[1].equals("NONE", true) }) {
                        error("این منبع HLS رمزگذاری شده است؛ ضبط بدون کلید مجاز/قابل پشتیبانی نیست.")
                    }

                    val mapParts = parseMaps(mediaText)
                    val parts = parseSegments(mediaText)
                    if (parts.isEmpty()) error("پلی‌لیست رسانه هیچ قطعه‌ای برای ضبط ندارد.")
                    if (output == null) {
                        val ext = if (mapParts.isNotEmpty()) "mp4" else "ts"
                        output = File(folder, "SALAR_" + safeName + "_" + System.currentTimeMillis() + "." + ext)
                    }

                    for (map in mapParts) {
                        if (!isCurrent(myGeneration) || System.currentTimeMillis() >= deadline) break
                        val absolute = URL(URL(mediaUrl), map.uri).toString()
                        val key = partKey(absolute, map.range)
                        if (writtenMaps.add(key)) {
                            val bytes = downloadPart(absolute, map.range)
                            output!!.appendBytes(bytes)
                            saved += bytes.size
                        }
                    }

                    for (part in parts) {
                        if (!isCurrent(myGeneration) || System.currentTimeMillis() >= deadline) break
                        val absolute = URL(URL(mediaUrl), part.uri).toString()
                        val key = partKey(absolute, part.range)
                        if (seen.add(key)) {
                            val bytes = downloadPart(absolute, part.range)
                            output!!.appendBytes(bytes)
                            saved += bytes.size
                            if (isCurrent(myGeneration)) {
                                updateNotification("در حال ضبط: $name • " + (saved / 1_048_576) + " مگابایت")
                            }
                        }
                    }

                    if (mediaText.contains("#EXT-X-ENDLIST")) break
                    val target = Regex("#EXT-X-TARGETDURATION:(\\d+)", RegexOption.IGNORE_CASE)
                        .find(mediaText)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
                    try { Thread.sleep((target.coerceIn(2L, 10L) * 500L).coerceIn(1000L, 5000L)) }
                    catch (_: InterruptedException) { if (!isCurrent(myGeneration)) break }
                }
                if (isCurrent(myGeneration)) {
                    if (saved <= 0 || output == null) finalStatus = "failed"
                    updateNotification(if (saved > 0 && output != null) {
                        "ضبط ذخیره شد: " + output!!.name
                    } else "ضبط انجام نشد؛ قطعه قابل ذخیره دریافت نشد.")
                }
            } catch (e: Exception) {
                finalStatus = "failed"
                if (isCurrent(myGeneration)) {
                    updateNotification("خطا در ضبط $name: " + (e.message ?: "اتصال ناموفق"))
                }
            } finally {
                val shouldStop = synchronized(this) {
                    if (recordingGeneration == myGeneration) {
                        running = false
                        isRecordingActive = false
                        worker = null
                        true
                    } else false
                }
                if (shouldStop) {
                    updateScheduleStatus(myScheduleId, finalStatus)
                    if (activeScheduleId == myScheduleId) activeScheduleId = Int.MIN_VALUE
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelfResult(startId)
                }
            }
        }
        synchronized(this) {
            if (recordingGeneration == myGeneration) worker = recordingWorker
        }
        if (isCurrent(myGeneration)) recordingWorker.start()
        return START_NOT_STICKY
    }

    private fun updateScheduleStatus(scheduleId: Int, status: String) {
        if (scheduleId == Int.MIN_VALUE) return
        val prefs = getSharedPreferences("salar_tv", MODE_PRIVATE)
        runCatching {
            val schedules = org.json.JSONArray(prefs.getString("schedules_json", "[]") ?: "[]")
            for (index in 0 until schedules.length()) {
                val item = schedules.optJSONObject(index) ?: continue
                if (item.optInt("requestCode", Int.MIN_VALUE) == scheduleId) {
                    item.put("status", status)
                    break
                }
            }
            prefs.edit().putString("schedules_json", schedules.toString()).apply()
        }
    }

    private fun resolveMediaPlaylist(masterUrl: String, text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variants = mutableListOf<Pair<Long, String>>()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF:", true)) {
                val bandwidth = Regex("(?:AVERAGE-BANDWIDTH|BANDWIDTH)=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
                val uri = lines.drop(i + 1).firstOrNull { !it.startsWith("#") }
                if (uri != null) variants += bandwidth to uri
            }
        }
        val selected = variants.minByOrNull { it.first }?.second
        return if (selected == null) masterUrl else URL(URL(masterUrl), selected).toString()
    }

    private fun parseMaps(text: String): List<MediaPart> {
        val result = mutableListOf<MediaPart>()
        for (line in text.lineSequence().map { it.trim() }) {
            if (!line.startsWith("#EXT-X-MAP:", true)) continue
            val uri = Regex("URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1) ?: continue
            val rangeText = Regex("BYTERANGE=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.get(1)
            result += MediaPart(uri, parseRange(rangeText, null))
        }
        return result
    }

    private fun parseSegments(text: String): List<MediaPart> {
        val result = mutableListOf<MediaPart>()
        var pendingRange: ByteRange? = null
        var previousRangeEnd: Long? = null
        var previousRangeUri: String? = null
        for (line in text.lineSequence().map { it.trim() }) {
            if (line.startsWith("#EXT-X-BYTERANGE:", true)) {
                pendingRange = parseRange(line.substringAfter(":"), null)
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val resolvedRange = pendingRange?.let { range ->
                    if (range.offset != null) range
                    else if (previousRangeUri == line && previousRangeEnd != null) range.copy(offset = previousRangeEnd)
                    else range
                }
                result += MediaPart(line, resolvedRange)
                if (resolvedRange != null) {
                    previousRangeEnd = resolvedRange.offset?.plus(resolvedRange.length)
                    previousRangeUri = line
                } else {
                    previousRangeEnd = null
                    previousRangeUri = null
                }
                pendingRange = null
            }
        }
        return result
    }

    private fun parseRange(raw: String?, implicitOffset: Long?): ByteRange? {
        if (raw.isNullOrBlank()) return null
        val pieces = raw.trim().split("@", limit = 2)
        val length = pieces[0].toLongOrNull() ?: error("مقدار EXT-X-BYTERANGE نامعتبر است.")
        val offset = if (pieces.size == 2) pieces[1].toLongOrNull()
            ?: error("آفست EXT-X-BYTERANGE نامعتبر است.") else implicitOffset
        require(length > 0 && (offset == null || offset >= 0)) { "محدوده بایت نامعتبر است." }
        return ByteRange(length, offset)
    }

    private fun partKey(url: String, range: ByteRange?): String =
        if (range == null) url else url + "|" + (range.offset?.toString() ?: "implicit") + "|" + range.length

    private fun downloadText(address: String): String =
        downloadBytes(address, null, 1_048_576).toString(Charsets.UTF_8)

    private fun downloadPart(address: String, range: ByteRange?): ByteArray {
        val header = range?.let {
            val start = it.offset ?: error("آفست ضمنی BYTERANGE برای این قطعه قابل تعیین نیست.")
            "bytes=" + start + "-" + (start + it.length - 1)
        }
        val bytes = downloadBytes(address, header, 32 * 1024 * 1024)
        if (range != null && bytes.size.toLong() != range.length) {
            error("اندازه قطعه با EXT-X-BYTERANGE مطابقت ندارد.")
        }
        return bytes
    }

    private fun downloadBytes(address: String, range: String?, maxBytes: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 25000
        connection.setRequestProperty("User-Agent", "SALAR-TV/1.2")
        connection.setRequestProperty("Accept", "*/*")
        if (range != null) connection.setRequestProperty("Range", range)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            if (range != null) {
                if (code != 206) error("سرور درخواست محدوده بایت را نپذیرفت (HTTP $code).")
                val requested = Regex("bytes=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE)
                    .matchEntire(range.trim()) ?: error("درخواست محدوده بایت نامعتبر است.")
                val returned = connection.getHeaderField("Content-Range") ?: error("سرور Content-Range را برنگرداند.")
                val actual = Regex("bytes\\s+(\\d+)-(\\d+)/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
                    .matchEntire(returned.trim()) ?: error("هدر Content-Range نامعتبر است.")
                if (actual.groupValues[1] != requested.groupValues[1] || actual.groupValues[2] != requested.groupValues[2]) {
                    error("محدوده بایت دریافتی با درخواست ضبط مطابقت ندارد.")
                }
            }
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use {
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) error("پاسخ سرور بیش از حد مجاز بزرگ است.")
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun isCurrent(generation: Long): Boolean =
        running && recordingGeneration == generation

    override fun onDestroy() {
        recordingGeneration += 1
        running = false
        isRecordingActive = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
