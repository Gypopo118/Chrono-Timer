package com.example.chrono.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_FIRE).putExtra("timer_id", intent.getLongExtra("timer_id", -1)))
    }
}
