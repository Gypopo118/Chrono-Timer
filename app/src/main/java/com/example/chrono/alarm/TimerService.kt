package com.example.chrono.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.chrono.ChronoApp
import com.example.chrono.MainActivity
import com.example.chrono.data.Timer
import com.example.chrono.util.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao get() = (application as ChronoApp).database.timers()
    private var tick: Job? = null
    private var shownId = -1L
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        active = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_TIMER_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> {
                shownId = id
                val name = intent?.getStringExtra(EXTRA_NAME) ?: "Таймер"
                val remaining = intent?.getLongExtra(EXTRA_REMAINING, 0L) ?: 0L
                ensureForeground(buildNotification(id, name, remaining, true))
                startTicking()
                scope.launch { render() }
            }
            ACTION_REFRESH -> scope.launch { render() }
            ACTION_PAUSE_RESUME -> scope.launch { togglePause(id) }
            ACTION_RESET -> scope.launch { reset(id) }
            ACTION_REMOVE -> scope.launch { detach(id) }
            else -> {
                ensureForeground(placeholder(null))
                startTicking()
                scope.launch { restore() }
            }
        }
        return START_STICKY
    }

    private fun startTicking() {
        if (tick?.isActive == true) return
        tick = scope.launch {
            while (isActive && foregroundStarted) {
                delay(1_000)
                render()
            }
        }
    }

    private suspend fun render() {
        if (!foregroundStarted) { stopSelf(); return }
        val now = System.currentTimeMillis()
        val current = if (shownId > 0) dao.get(shownId) else null
        val timer = if (current != null && current.isOngoing(now)) current else {
            val next = pickOngoing(now, shownId) ?: run { stopOngoing(); return }
            shownId = next.id
            next
        }
        val remaining = remainingOf(timer, now)
        if (remaining <= 0) { stopOngoing(); return }
        post(buildNotification(timer.id, timer.name, remaining, timer.running))
    }

    private suspend fun restore() {
        val next = pickOngoing(System.currentTimeMillis())
        if (next == null) { stopOngoing(); return }
        shownId = next.id
        render()
    }

    private suspend fun togglePause(id: Long) {
        val timer = dao.get(id) ?: return render()
        if (timer.alarming) return render()
        val now = System.currentTimeMillis()
        if (timer.running) {
            val remaining = remainingOf(timer, now)
            if (remaining <= 0) return render()
            TimerScheduler.cancel(this, id)
            dao.put(timer.copy(remainingMs = remaining, endsAtMs = null, running = false))
        } else if (timer.remainingMs > 0) {
            val endsAt = now + timer.remainingMs
            dao.put(timer.copy(endsAtMs = endsAt, running = true, alarming = false))
            TimerScheduler.schedule(this, timer.copy(endsAtMs = endsAt, running = true))
        }
        render()
    }

    private suspend fun reset(id: Long) {
        val timer = dao.get(id)
        if (timer != null) {
            TimerScheduler.cancel(this, id)
            if (timer.alarming) startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP).putExtra("timer_id", id))
            dao.put(timer.copy(remainingMs = timer.durationMs, endsAtMs = null, running = false, alarming = false))
        }
        render()
    }

    private suspend fun detach(id: Long) {
        if (shownId == id) shownId = -1L
        render()
    }

    private suspend fun pickOngoing(now: Long, excludeId: Long = -1L): Timer? =
        dao.all().asSequence()
            .filter { it.id != excludeId && it.isOngoing(now) }
            .sortedWith(compareByDescending<Timer> { it.running }.thenBy { remainingOf(it, now) })
            .firstOrNull()

    private fun Timer.isOngoing(now: Long): Boolean {
        if (alarming) return false
        if (running) return (endsAtMs ?: 0L) > now
        return remainingMs in 1 until durationMs
    }

    private fun remainingOf(timer: Timer, now: Long): Long =
        if (timer.running) ((timer.endsAtMs ?: now) - now).coerceAtLeast(0) else timer.remainingMs

    private fun ensureForeground(notification: Notification) {
        if (foregroundStarted) return
        foregroundStarted = true
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    @SuppressLint("MissingPermission")
    private fun post(notification: Notification) {
        if (!foregroundStarted) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun stopOngoing() {
        if (foregroundStarted) {
            foregroundStarted = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        }
        tick?.cancel()
        tick = null
        stopSelf()
    }

    private fun buildNotification(id: Long, name: String, remaining: Long, running: Boolean): Notification {
        val open = PendingIntent.getActivity(this, REQUEST_OPEN, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseResume = PendingIntent.getService(this, REQUEST_PAUSE_RESUME, Intent(this, TimerService::class.java).setAction(ACTION_PAUSE_RESUME).putExtra(EXTRA_TIMER_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val reset = PendingIntent.getService(this, REQUEST_RESET, Intent(this, TimerService::class.java).setAction(ACTION_RESET).putExtra(EXTRA_TIMER_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(formatTime(remaining))
            .setContentText(name)
            .setSubText(if (running) null else "Пауза")
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (running) "Пауза" else "Продолжить",
                pauseResume
            )
            .addAction(android.R.drawable.ic_menu_revert, "Сброс", reset)
            .build()
    }

    private fun placeholder(name: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(name ?: "Таймер")
            .setContentText("Запуск…")
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Таймер", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Текущее время работающего таймера"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        foregroundStarted = false
        tick?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        active = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.chrono.TIMER_START"
        const val ACTION_REFRESH = "com.example.chrono.TIMER_REFRESH"
        const val ACTION_PAUSE_RESUME = "com.example.chrono.TIMER_PAUSE_RESUME"
        const val ACTION_RESET = "com.example.chrono.TIMER_RESET"
        const val ACTION_REMOVE = "com.example.chrono.TIMER_REMOVE"
        private const val EXTRA_TIMER_ID = "timer_id"
        private const val EXTRA_NAME = "timer_name"
        private const val EXTRA_REMAINING = "timer_remaining"
        private const val CHANNEL = "timer_ongoing"
        private const val NOTIFICATION_ID = 43
        private const val REQUEST_OPEN = 100
        private const val REQUEST_PAUSE_RESUME = 101
        private const val REQUEST_RESET = 102

        @Volatile private var active = false

        fun start(context: Context, timer: Timer) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TimerService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_TIMER_ID, timer.id)
                    .putExtra(EXTRA_NAME, timer.name)
                    .putExtra(EXTRA_REMAINING, timer.remainingMs)
            )
        }

        fun refresh(context: Context) {
            if (!active) return
            context.startService(Intent(context, TimerService::class.java).setAction(ACTION_REFRESH))
        }

        fun remove(context: Context, id: Long) {
            if (!active) return
            context.startService(Intent(context, TimerService::class.java).setAction(ACTION_REMOVE).putExtra(EXTRA_TIMER_ID, id))
        }
    }
}
