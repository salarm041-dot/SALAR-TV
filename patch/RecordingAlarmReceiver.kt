package com.salar.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val url = intent?.getStringExtra("url") ?: return
        val service = Intent(context, HlsRecorderService::class.java).apply {
            putExtra("name", intent.getStringExtra("name") ?: "SALAR TV")
            putExtra("url", url)
            putExtra("duration", intent.getIntExtra("duration", 60))
        }
        ContextCompat.startForegroundService(context, service)
    }
}
