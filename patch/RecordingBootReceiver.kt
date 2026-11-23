package com.salar.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray

class RecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) return
        val prefs = context.getSharedPreferences("salar_tv", Context.MODE_PRIVATE)
        val stored = runCatching {
            JSONArray(prefs.getString("scheduled_recordings", "[]") ?: "[]")
        }.getOrNull() ?: return
        for (i in 0 until stored.length()) {
            val item = stored.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val url = item.optString("url")
            val startMillis = item.optLong("startMillis")
            val duration = item.optInt("durationMinutes", 60).coerceIn(1, 360)
            if (name.isBlank() || !(url.startsWith("http://") || url.startsWith("https://")) ||
                startMillis <= System.currentTimeMillis()) continue
            val requestCode = (name + url + startMillis).hashCode()
            val alarmIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                putExtra("name", name)
                putExtra("url", url)
                putExtra("duration", duration)
            }
            val pending = PendingIntent.getBroadcast(
                context, requestCode, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, pending)
        }
    }
}
