package com.example.chrono.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.chrono.ChronoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as ChronoApp).database.timers().running().forEach { timer ->
                    if (timer.alarming || (timer.endsAtMs ?: 0) <= System.currentTimeMillis()) {
                        val dueSoon = timer.copy(running = true, alarming = false, endsAtMs = System.currentTimeMillis() + 1_000, remainingMs = 1_000)
                        (context.applicationContext as ChronoApp).database.timers().put(dueSoon)
                        TimerScheduler.schedule(context, dueSoon)
                    } else TimerScheduler.schedule(context, timer)
                }
            } finally { pending.finish() }
        }
    }
}
