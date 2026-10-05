package com.org.notifyme.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

class AlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        val name = intent.getStringExtra("name") ?: "Product"
        val url = intent.getStringExtra("url") ?: return finish()
        startAlarm()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 3 * 60_000L)

        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                       horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("IN STOCK", style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    intent.getStringExtra("price")?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())); finish() },
                           modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("OPEN PRODUCT") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("STOP") }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAlarm() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(this@AlarmActivity, uri); isLooping = true; prepare(); start()
        }
        vibrator = getSystemService(Vibrator::class.java).also {
            it?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        player?.run { stop(); release() }; vibrator?.cancel(); super.onDestroy()
    }
}
