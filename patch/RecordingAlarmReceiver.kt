package com.salar.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONArray

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val received = intent ?: return
        val url = received.getStringExtra("url") ?: return
        val scheduleId = received.getIntExtra("schedule_id", Int.MIN_VALUE)
        val service = Intent(context, HlsRecorderService::class.java).apply {
            putExtra("name", received.getStringExtra("name") ?: "SALAR TV")
            putExtra("url", url)
            putExtra("duration", received.getIntExtra("duration", 60))
            putExtra("schedule_id", scheduleId)
        }

        try {
            ContextCompat.startForegroundService(context, service)
        } catch (error: Exception) {
            updateScheduleStatus(context, scheduleId, "start_failed")
        }
    }

    private fun updateScheduleStatus(context: Context, scheduleId: Int, status: String) {
        if (scheduleId == Int.MIN_VALUE) return
        val prefs = context.getSharedPreferences("salar_tv", Context.MODE_PRIVATE)
        runCatching {
            val schedules = JSONArray(prefs.getString("schedules_json", "[]") ?: "[]")
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
}

/**
 * Rebuilds future recording alarms after Android restarts.
 * Uses the same requestCode and PendingIntent identity as MainActivity.
 */
class RecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("salar_tv", Context.MODE_PRIVATE)
        val schedules = runCatching {
            JSONArray(prefs.getString("schedules_json", "[]") ?: "[]")
        }.getOrElse { return }

        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        var changed = false

        for (index in 0 until schedules.length()) {
            val item = schedules.optJSONObject(index) ?: continue
            val status = item.optString("status", "scheduled")
            if (status != "scheduled" && status.isNotBlank()) continue

            val requestCode = item.optInt("requestCode", Int.MIN_VALUE)
            val title = item.optString("title", "SALAR TV").ifBlank { "SALAR TV" }
            val url = item.optString("url", "")
            val startMillis = item.optLong("startMillis", 0L)
            val duration = item.optInt("durationMinutes", 60).coerceIn(0, 10080)

            if (requestCode == Int.MIN_VALUE || !(url.startsWith("https://") || url.startsWith("http://")) || startMillis <= 0L) {
                item.put("status", "restore_failed")
                changed = true
                continue
            }

            if (startMillis <= now) {
                item.put("status", "missed")
                changed = true
                continue
            }

            runCatching {
                val alarmIntent = Intent(context, RecordingAlarmReceiver::class.java).apply {
                    putExtra("name", title)
                    putExtra("url", url)
                    putExtra("duration", duration)
                    putExtra("schedule_id", requestCode)
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis, pending)
            }.onFailure {
                item.put("status", "restore_failed")
                changed = true
            }
        }

        if (changed) {
            prefs.edit().putString("schedules_json", schedules.toString()).apply()
        }
    }
}
