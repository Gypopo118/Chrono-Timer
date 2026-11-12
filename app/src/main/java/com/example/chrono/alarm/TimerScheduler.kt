package com.example.chrono.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.chrono.MainActivity
import com.example.chrono.data.Timer

object TimerScheduler {
    fun schedule(context: Context, timer: Timer) {
        val at = timer.endsAtMs ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, TimerReceiver::class.java).putExtra("timer_id", timer.id)
        val pending = PendingIntent.getBroadcast(context, timer.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms() ||
            (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.USE_EXACT_ALARM) == PackageManager.PERMISSION_GRANTED)
        if (canExact) {
            val show = PendingIntent.getActivity(
                context,
                timer.id.toInt(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val scheduled = runCatching { alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending) }.isSuccess
            if (!scheduled) {
                runCatching { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
                    .onFailure { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, id: Long) {
        val intent = Intent(context, TimerReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
    }
}
