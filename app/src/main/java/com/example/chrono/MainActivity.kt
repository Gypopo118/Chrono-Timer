package com.example.chrono

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chrono.alarm.TimerScheduler
import com.example.chrono.data.Timer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Ink = Color(0xFF090B0A)
private val Surface = Color(0xFF151916)
private val Lime = Color(0xFFB8F36D)
private val Muted = Color(0xFF8B938D)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val dao = (application as ChronoApp).database.timers()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Ink, surface = Surface, onSurface = Color.White)) {
                val timers by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                var selected by remember { mutableStateOf<Timer?>(null) }
                val scope = rememberCoroutineScope()
                if (selected != null) {
                    TimerEditor(selected!!, onBack = { selected = null }, onSave = { updated -> scope.launch { TimerScheduler.cancel(this@MainActivity, updated.id); if (selected!!.alarming) stopAlarmService(updated.id); dao.put(updated); if (updated.running) TimerScheduler.schedule(this@MainActivity, updated); selected = null } }, onDelete = { id -> scope.launch { TimerScheduler.cancel(this@MainActivity, id); if (selected!!.alarming) stopAlarmService(id); dao.delete(id); selected = null } })
                } else {
                    HomeScreen(timers = timers, onSelect = { selected = it }, onAdd = {
                        scope.launch {
                            val prefs = getSharedPreferences("chrono", MODE_PRIVATE)
                            val next = prefs.getInt("next_name", 1)
                            prefs.edit().putInt("next_name", next + 1).apply()
                            val id = System.currentTimeMillis()
                            val timer = Timer(id = id, name = "Таймер $next", durationMs = 5 * 60_000L)
                            dao.put(timer); selected = timer
                        }
                    }, onToggle = { timer -> scope.launch {
                        if (timer.alarming) {
                            stopAlarmService(timer.id)
                            dao.put(timer.copy(remainingMs = timer.durationMs, endsAtMs = null, running = false, alarming = false))
                        } else if (timer.running) {
                            TimerScheduler.cancel(this@MainActivity, timer.id)
                            dao.put(timer.copy(remainingMs = ((timer.endsAtMs ?: 0) - System.currentTimeMillis()).coerceAtLeast(0), endsAtMs = null, running = false))
                        } else {
                            val started = timer.copy(endsAtMs = System.currentTimeMillis() + timer.remainingMs, running = true, alarming = false)
                            dao.put(started); TimerScheduler.schedule(this@MainActivity, started)
                        }
                    } }, onReset = { timer -> scope.launch { TimerScheduler.cancel(this@MainActivity, timer.id); if (timer.alarming) stopAlarmService(timer.id); dao.put(timer.copy(remainingMs = timer.durationMs, endsAtMs = null, running = false, alarming = false)) } }, onPermissions = { openAlarmSettings() })
                }
            }
        }
    }

    private fun openAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 34 && getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } else if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun stopAlarmService(id: Long) {
        startService(Intent(this, com.example.chrono.alarm.AlarmService::class.java).setAction(com.example.chrono.alarm.AlarmService.ACTION_STOP).putExtra("timer_id", id))
    }
}

@Composable
private fun HomeScreen(timers: List<Timer>, onSelect: (Timer) -> Unit, onAdd: () -> Unit, onToggle: (Timer) -> Unit, onReset: (Timer) -> Unit, onPermissions: () -> Unit) {
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); clock = System.currentTimeMillis() } }
    Scaffold(containerColor = Ink, floatingActionButton = { FloatingActionButton(onClick = onAdd, containerColor = Lime, contentColor = Ink) { Icon(Icons.Default.Add, "Добавить таймер") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("CHRONO", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp); Text("Таймеры", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold) }
                IconButton(onClick = onPermissions) { Icon(Icons.Default.Settings, "Разрешения", tint = Muted) }
            }
            if (timers.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Время в вашем ритме", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp)); Text("Создайте первый таймер кнопкой +", color = Muted)
                }
            } else LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(timers, key = { it.id }) { timer ->
                    val remaining = if (timer.running) ((timer.endsAtMs ?: clock) - clock).coerceAtLeast(0) else timer.remainingMs
                    TimerCard(timer, remaining, onSelect, onToggle, onReset)
                }
            }
        }
    }
}

@Composable
private fun TimerCard(timer: Timer, remaining: Long, onSelect: (Timer) -> Unit, onToggle: (Timer) -> Unit, onReset: (Timer) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(24.dp)).clickable { onSelect(timer) }.padding(start = 20.dp, top = 18.dp, end = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(timer.name, color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(formatTime(remaining), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            if (timer.alarming) Text("ЗВУЧИТ", color = Lime, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { onReset(timer) }) { Icon(Icons.Default.Refresh, "Сбросить", tint = Muted) }
        IconButton(onClick = { onToggle(timer) }, modifier = Modifier.size(52.dp).background(if (timer.running || timer.alarming) Color(0xFFFF5C63) else Lime, RoundedCornerShape(18.dp))) {
            Icon(if (timer.running || timer.alarming) Icons.Default.Stop else Icons.Default.PlayArrow, if (timer.running || timer.alarming) "Пауза" else "Старт", tint = Ink)
        }
    }
}

@Composable
private fun TimerEditor(timer: Timer, onBack: () -> Unit, onSave: (Timer) -> Unit, onDelete: (Long) -> Unit) {
    var name by remember(timer.id) { mutableStateOf(timer.name) }
    var hours by remember(timer.id) { mutableStateOf((timer.durationMs / 3_600_000).toString()) }
    var minutes by remember(timer.id) { mutableStateOf(((timer.durationMs / 60_000) % 60).toString()) }
    Scaffold(containerColor = Ink, topBar = { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White) }; Text("Настройка таймера", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 18.dp)) {
            Text("НАЗВАНИЕ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Название") }, colors = fieldColors())
            Spacer(Modifier.height(32.dp)); Text("ДЛИТЕЛЬНОСТЬ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3) }, modifier = Modifier.weight(1f), label = { Text("Часы") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f), label = { Text("Минуты") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                val duration = (hours.toLongOrNull() ?: 0) * 3_600_000L + (minutes.toLongOrNull() ?: 0) * 60_000L
                if (duration > 0) onSave(timer.copy(name = name.trim().ifBlank { timer.name }, durationMs = duration, remainingMs = duration, endsAtMs = null, running = false, alarming = false))
            }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text("Сохранить", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { onDelete(timer.id) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Default.Delete, null, tint = Color(0xFFFF5C63)); Spacer(Modifier.width(8.dp)); Text("Удалить таймер", color = Color(0xFFFF5C63)) }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lime, focusedLabelColor = Lime, unfocusedBorderColor = Color(0xFF39413B), focusedTextColor = Color.White, unfocusedTextColor = Color.White)

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
