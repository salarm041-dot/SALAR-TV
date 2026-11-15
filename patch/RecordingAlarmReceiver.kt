package com.salar.tv

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
        }

        try {
            ContextCompat.startForegroundService(context, service)
            updateScheduleStatus(context, scheduleId, "start_requested")
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
