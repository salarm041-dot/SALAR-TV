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
import java.util.concurrent.ConcurrentHashMap

class HlsRecorderService : Service() {
    companion object {
        const val ACTION_STOP = "com.salar.tv.action.STOP_RECORDING"
        @Volatile var isRecordingActive: Boolean = false
            private set
        val activeRecordingIds: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
        fun manualRecordingId(url: String): String = "manual:" + url.hashCode()
    }
    private class RecordingTask(val id: String, val scheduleId: Int) {
        @Volatile var running: Boolean = true
        @Volatile var worker: Thread? = null
    }
    private val activeTasks = ConcurrentHashMap<String, RecordingTask>()

    private data class ByteRange(val length: Long, val offset: Long?)
    private data class MediaPart(val uri: String, val range: ByteRange? = null)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val requestedId = intent.getStringExtra("recording_id")
            val targets = if (requestedId.isNullOrBlank()) activeTasks.values.toList() else listOfNotNull(activeTasks[requestedId])
            targets.forEach { task -> task.running = false; updateScheduleStatus(task.scheduleId, "stopped"); task.worker?.interrupt() }
            isRecordingActive = activeTasks.values.any { it.running }
            if (!isRecordingActive) { stopForeground(true); stopSelf() }
            else updateNotification("در حال ضبط هم‌زمان " + activeTasks.values.count { it.running } + " شبکه")
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
        val myScheduleId = intent?.getIntExtra("schedule_id", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val recordingId = intent.getStringExtra("recording_id") ?: if (myScheduleId != Int.MIN_VALUE) "schedule:$myScheduleId" else "manual:" + storedStreamUrl.hashCode()
        val task = RecordingTask(recordingId, myScheduleId)
        if (activeTasks.size >= 2) { updateNotification("در این نسخه حداکثر دو شبکه را هم‌زمان ضبط کنید."); return START_NOT_STICKY }
        if (activeTasks.putIfAbsent(recordingId, task) != null) { updateNotification("این منبع هم‌اکنون در حال ضبط است."); return START_NOT_STICKY }
        activeRecordingIds.add(recordingId)
        isRecordingActive = true
        updateScheduleStatus(myScheduleId, "recording")
        updateNotification("در حال ضبط هم‌زمان " + activeTasks.size + " شبکه")
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
                while (isCurrent(recordingId) && (deadline == Long.MAX_VALUE || System.currentTimeMillis() < deadline)) {
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
                        if (!isCurrent(recordingId) || System.currentTimeMillis() >= deadline) break
                        val absolute = URL(URL(mediaUrl), map.uri).toString()
                        val key = partKey(absolute, map.range)
                        if (writtenMaps.add(key)) {
                            val bytes = downloadPart(absolute, map.range)
                            output!!.appendBytes(bytes)
                            saved += bytes.size
                        }
                    }

                    for (part in parts) {
                        if (!isCurrent(recordingId) || System.currentTimeMillis() >= deadline) break
                        val absolute = URL(URL(mediaUrl), part.uri).toString()
                        val key = partKey(absolute, part.range)
                        if (seen.add(key)) {
                            val bytes = downloadPart(absolute, part.range)
                            output!!.appendBytes(bytes)
                            saved += bytes.size
                            if (isCurrent(recordingId)) {
                                updateNotification("در حال ضبط: $name • " + (saved / 1_048_576) + " مگابایت")
                            }
                        }
                    }

                    if (mediaText.contains("#EXT-X-ENDLIST")) break
                    val target = Regex("#EXT-X-TARGETDURATION:(\\d+)", RegexOption.IGNORE_CASE)
                        .find(mediaText)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
                    try { Thread.sleep((target.coerceIn(2L, 10L) * 500L).coerceIn(1000L, 5000L)) }
                    catch (_: InterruptedException) { if (!isCurrent(recordingId)) break }
                }
                if (isCurrent(recordingId)) {
                    if (saved <= 0 || output == null) {
                        finalStatus = "failed"
                        updateNotification("ضبط انجام نشد؛ قطعه قابل ذخیره دریافت نشد.")
                    } else {
                        val publicUri = runCatching { publishRecordingToMovies(output!!) }.getOrNull()
                        if (publicUri == null) {
                            finalStatus = "completed_private"
                            updateNotification("ضبط در حافظه داخلی برنامه ذخیره شد؛ انتشار در پوشه عمومی ناموفق بود: " + output!!.name)
                        } else {
                            updateNotification("ضبط در پوشه فیلم‌ها ذخیره شد: " + output!!.name)
                        }
                    }
                }
            } catch (e: Exception) {
                finalStatus = "failed"
                if (isCurrent(recordingId)) {
                    updateNotification("خطا در ضبط $name: " + (e.message ?: "اتصال ناموفق"))
                }
            } finally {
                activeTasks.remove(recordingId, task)
                activeRecordingIds.remove(recordingId)
                updateScheduleStatus(myScheduleId, if (task.running) finalStatus else "stopped")
                isRecordingActive = activeTasks.values.any { it.running }
                if (isRecordingActive) updateNotification("در حال ضبط هم‌زمان " + activeTasks.values.count { it.running } + " شبکه")
                else { stopForeground(STOP_FOREGROUND_DETACH); stopSelfResult(startId) }
            }
        }
        synchronized(this) {
            task.worker = recordingWorker
        }
        if (isCurrent(recordingId)) recordingWorker.start()
        return START_NOT_STICKY
    }

    /**
     * Publish a completed recording to the user's public Movies/SALAR TV folder.
     * MediaStore + RELATIVE_PATH is supported from Android 10 (API 29).
     * Keep the app-private original as a recovery copy.
     */
    @android.annotation.TargetApi(29)
    private fun publishRecordingToMovies(source: File): android.net.Uri {
        require(android.os.Build.VERSION.SDK_INT >= 29) { "انتشار عمومی ضبط به Android 10 یا بالاتر نیاز دارد." }
        val mime = when (source.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            else -> "video/mp2t"
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/SALAR TV")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val collection = android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("ساخت فایل عمومی در حافظه ناموفق بود.")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("مسیر نوشتن فایل عمومی در دسترس نیست.")
            val ready = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
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

    private fun isCurrent(recordingId: String): Boolean = activeTasks[recordingId]?.running == true

    override fun onDestroy() {
        activeTasks.values.forEach { task -> task.running = false; task.worker?.interrupt() }
        activeTasks.clear()
        isRecordingActive = false
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
