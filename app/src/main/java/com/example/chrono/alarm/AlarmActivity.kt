package com.example.chrono.alarm

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id = intent.getLongExtra("timer_id", -1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF090B0A)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Время вышло", color = Color.White, fontSize = 34.sp)
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { ContextCompat.startForegroundService(this@AlarmActivity, Intent(this@AlarmActivity, AlarmService::class.java).setAction(AlarmService.ACTION_STOP).putExtra("timer_id", id)); finish() }) { Text("Отключить") }
                }
            }
        }
    }
}
