package com.example.chrono

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chrono.alarm.TimerScheduler
import com.example.chrono.alarm.TimerService
import com.example.chrono.data.Timer
import com.example.chrono.util.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Ink = Color(0xFF090B0A)
private val Surface = Color(0xFF151916)
private val SurfaceRaised = Color(0xFF1E2420)
private val Lime = Color(0xFFB8F36D)
private val Muted = Color(0xFF8B938D)

class MainActivity : ComponentActivity() {
    private val notificationsAllowed = mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notificationsAllowed.value = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationsAllowed.value = hasNotificationsPermission()
        val dao = (application as ChronoApp).database.timers()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Ink, surface = Surface, onSurface = Color.White)) {
                val timers by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
                var selected by remember { mutableStateOf<Timer?>(null) }
                var showNotificationHelp by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                if (selected != null) {
                    TimerEditor(selected!!, onBack = { selected = null }, onSave = { updated -> scope.launch { TimerScheduler.cancel(this@MainActivity, updated.id); if (selected!!.alarming) stopAlarmService(updated.id); dao.put(updated); if (updated.running) TimerScheduler.schedule(this@MainActivity, updated); TimerService.remove(this@MainActivity, updated.id); selected = null } }, onDelete = { id -> scope.launch { TimerScheduler.cancel(this@MainActivity, id); if (selected!!.alarming) stopAlarmService(id); dao.delete(id); TimerService.remove(this@MainActivity, id); selected = null } })
                } else {
                    HomeScreen(timers = timers, notificationsAllowed = notificationsAllowed.value, onSelect = { selected = it }, onAdd = {
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
                            TimerService.remove(this@MainActivity, timer.id)
                        } else if (timer.running) {
                            TimerScheduler.cancel(this@MainActivity, timer.id)
                            dao.put(timer.copy(remainingMs = ((timer.endsAtMs ?: 0) - System.currentTimeMillis()).coerceAtLeast(0), endsAtMs = null, running = false))
                            TimerService.refresh(this@MainActivity)
                        } else {
                            val started = timer.copy(endsAtMs = System.currentTimeMillis() + timer.remainingMs, running = true, alarming = false)
                            dao.put(started); TimerScheduler.schedule(this@MainActivity, started)
                            TimerService.start(this@MainActivity, started)
                            if (shouldExplainNotificationPermission()) showNotificationHelp = true
                        }
                    } }, onReset = { timer -> scope.launch { TimerScheduler.cancel(this@MainActivity, timer.id); if (timer.alarming) stopAlarmService(timer.id); dao.put(timer.copy(remainingMs = timer.durationMs, endsAtMs = null, running = false, alarming = false)); TimerService.remove(this@MainActivity, timer.id) } }, onPermissions = { openAlarmSettings() }, onEnableNotifications = { openNotificationSettings() })
                }
                if (showNotificationHelp) {
                    AlertDialog(
                        onDismissRequest = { showNotificationHelp = false },
                        title = { Text("Уведомление в шторке") },
                        text = { Text("Разрешите уведомления, чтобы видеть время таймера в шторке и управлять им, не открывая приложение. Без разрешения таймер продолжит работать в приложении.") },
                        confirmButton = { TextButton(onClick = { showNotificationHelp = false; if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Разрешить") } },
                        dismissButton = { TextButton(onClick = { showNotificationHelp = false }) { Text("Не сейчас") } }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed.value = hasNotificationsPermission()
    }

    private fun hasNotificationsPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun shouldExplainNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationsPermission()) return false
        val prefs = getSharedPreferences("chrono", MODE_PRIVATE)
        if (prefs.getBoolean("notification_help_shown", false)) return false
        prefs.edit().putBoolean("notification_help_shown", true).apply()
        return true
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
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
private fun HomeScreen(timers: List<Timer>, notificationsAllowed: Boolean, onSelect: (Timer) -> Unit, onAdd: () -> Unit, onToggle: (Timer) -> Unit, onReset: (Timer) -> Unit, onPermissions: () -> Unit, onEnableNotifications: () -> Unit) {
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(500); clock = System.currentTimeMillis() } }
    Scaffold(containerColor = Ink) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("CHRONO", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onPermissions) { Icon(Icons.Default.Settings, "Разрешения", tint = Muted) }
                }
                if (!notificationsAllowed) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).background(SurfaceRaised, RoundedCornerShape(16.dp)).clickable(onClick = onEnableNotifications).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("Уведомления выключены — время таймера не появится в шторке. Нажмите, чтобы включить.", color = Lime, fontSize = 13.sp)
                    }
                }
                if (timers.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Время в вашем ритме", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp)); Text("Создайте первый таймер кнопкой +", color = Muted)
                    }
                } else LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(timers, key = { it.id }) { timer ->
                        val remaining = if (timer.running) ((timer.endsAtMs ?: clock) - clock).coerceAtLeast(0) else timer.remainingMs
                        TimerCard(timer, remaining, onSelect, onToggle, onReset)
                    }
                }
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp, vertical = 16.dp).fillMaxWidth().heightIn(min = 94.dp).background(SurfaceRaised, RoundedCornerShape(24.dp)).clickable(onClickLabel = "Добавить таймер", onClick = onAdd), contentAlignment = Alignment.Center) {
                Text("+", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun TimerCard(timer: Timer, remaining: Long, onSelect: (Timer) -> Unit, onToggle: (Timer) -> Unit, onReset: (Timer) -> Unit) {
    val isPaused = !timer.running && !timer.alarming && timer.remainingMs > 0 && timer.remainingMs < timer.durationMs
    val isActive = timer.running || timer.alarming || isPaused
    Row(Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(24.dp)).clickable { onSelect(timer) }.padding(start = 20.dp, top = 18.dp, end = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(timer.name, color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(formatTime(remaining), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            if (timer.alarming) Text("ЗВУЧИТ", color = Lime, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            if (isPaused) Text("НА ПАУЗЕ", color = Color(0xFFFF5C63), fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { onReset(timer) }) { Icon(Icons.Default.Refresh, "Сбросить", tint = Muted) }
        IconButton(onClick = { onToggle(timer) }, modifier = Modifier.size(52.dp).background(if (isActive) Color(0xFFFF5C63) else Lime, RoundedCornerShape(18.dp))) {
            Icon(
                if (timer.running || timer.alarming) Icons.Default.Stop else if (isPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (timer.running || timer.alarming) "Стоп" else if (isPaused) "Продолжить" else "Старт",
                tint = Ink
            )
        }
    }
}

@Composable
private fun TimerEditor(timer: Timer, onBack: () -> Unit, onSave: (Timer) -> Unit, onDelete: (Long) -> Unit) {
    var name by remember(timer.id) { mutableStateOf(timer.name) }
    var hours by remember(timer.id) { mutableStateOf((timer.durationMs / 3_600_000).toString()) }
    var minutes by remember(timer.id) { mutableStateOf(((timer.durationMs / 60_000) % 60).toString()) }
    var seconds by remember(timer.id) { mutableStateOf(((timer.durationMs / 1_000) % 60).toString()) }
    var hoursTouched by remember(timer.id) { mutableStateOf(false) }
    var minutesTouched by remember(timer.id) { mutableStateOf(false) }
    var secondsTouched by remember(timer.id) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Ink, topBar = { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White) }; Text("Настройка таймера", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 18.dp)) {
            Text("НАЗВАНИЕ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Название") }, colors = fieldColors())
            Spacer(Modifier.height(32.dp)); Text("ДЛИТЕЛЬНОСТЬ", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3); hoursTouched = true }, modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused && !hoursTouched) { hours = ""; hoursTouched = true } }, label = { Text("Часы") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2); minutesTouched = true }, modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused && !minutesTouched) { minutes = ""; minutesTouched = true } }, label = { Text("Минуты") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2); secondsTouched = true }, modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused && !secondsTouched) { seconds = ""; secondsTouched = true } }, label = { Text("Секунды") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors())
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = {
                val duration = (hours.toLongOrNull() ?: 0) * 3_600_000L + (minutes.toLongOrNull() ?: 0) * 60_000L + (seconds.toLongOrNull() ?: 0) * 1_000L
                if (duration > 0) onSave(timer.copy(name = name.trim().ifBlank { timer.name }, durationMs = duration, remainingMs = duration, endsAtMs = null, running = false, alarming = false))
            }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text("Сохранить", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onDelete(timer.id) }, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242B28), contentColor = Color(0xFFFF5C63))) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(8.dp)); Text("Удалить таймер", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lime, focusedLabelColor = Lime, unfocusedBorderColor = Color(0xFF39413B), focusedTextColor = Color.White, unfocusedTextColor = Color.White)
