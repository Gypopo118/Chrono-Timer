package com.example.chrono.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.chrono.data.Timer

object TimerScheduler {
    fun schedule(context: Context, timer: Timer) {
        val at = timer.endsAtMs ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, TimerReceiver::class.java).putExtra("timer_id", timer.id)
        val pending = PendingIntent.getBroadcast(context, timer.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, id: Long) {
        val intent = Intent(context, TimerReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }
}
