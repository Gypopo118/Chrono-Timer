package com.example.chrono.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.chrono.ChronoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var timeout: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra("timer_id", -1) ?: -1
        if (intent?.action == ACTION_STOP) { stopAlarm(id); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification(id))
        scope.launch {
            val dao = (application as ChronoApp).database.timers()
            val timer = dao.get(id) ?: run { stopSelf(); return@launch }
            dao.put(timer.copy(running = false, alarming = true, remainingMs = 0, endsAtMs = null))
            startSound()
            timeout?.cancel()
            timeout = launch { delay(60_000); stopAlarm(id) }
        }
        return START_NOT_STICKY
    }

    private fun startSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }
    }

    private fun stopAlarm(id: Long) {
        timeout?.cancel()
        player?.let { p -> runCatching { p.stop(); p.release() } }
        player = null
        scope.launch {
            val dao = (application as ChronoApp).database.timers()
            dao.get(id)?.let { dao.put(it.copy(remainingMs = it.durationMs, running = false, alarming = false, endsAtMs = null)) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(id: Long): Notification {
        val open = PendingIntent.getActivity(this, id.toInt(), Intent(this, AlarmActivity::class.java).putExtra("timer_id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, (id + 100000).toInt(), Intent(this, AlarmService::class.java).setAction(ACTION_STOP).putExtra("timer_id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Время вышло").setContentText("Нажмите, чтобы отключить сигнал")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stop).build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Будильники", NotificationManager.IMPORTANCE_HIGH).apply { description = "Сигналы таймеров"; setBypassDnd(true) }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val ACTION_FIRE = "com.example.chrono.FIRE"; const val ACTION_STOP = "com.example.chrono.STOP"; const val CHANNEL = "alarms"; const val NOTIFICATION_ID = 42 }
}
