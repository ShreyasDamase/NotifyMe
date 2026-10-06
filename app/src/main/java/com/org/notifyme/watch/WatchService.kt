package com.org.notifyme.watch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.org.notifyme.StockPingApp
import com.org.notifyme.net.Limits
import com.org.notifyme.net.Plan
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

object BatteryGuard {
    fun stopReason(ctx: Context): String? {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (bm?.isCharging == true) return null
        if (pm?.isPowerSaveMode == true) return "Battery Saver is on"
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        if (pct in 0 until Limits.BATTERY_STOP_PCT) return "Battery below ${Limits.BATTERY_STOP_PCT}%"
        return null
    }
}

class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val app get() = application as StockPingApp

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val hours = (i?.getIntExtra(EXTRA_HOURS, 2) ?: 2).coerceIn(1, 8)
        isRunning = true
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing("Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        job?.cancel()
        job = scope.launch {
            try {
                runWatch(hours)
            } finally {
                isRunning = false
                _watchState.value = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runWatch(hours: Int) {
        val repo = app.container.repository
        val gate = app.container.hostGate
        val wallClockEnd = System.currentTimeMillis() + hours * 3_600_000L
        val deadline = SystemClock.elapsedRealtime() + hours * 3_600_000L
        val pm = getSystemService(PowerManager::class.java)

        while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < deadline) {
            val items = repo.watchable()
            if (items.isEmpty()) { stopNote("Nothing left to watch"); return }
            BatteryGuard.stopReason(this)?.let { stopNote(it); return }
            if (!isOnline()) { delay(60_000); continue }

            val host = items.firstOrNull()?.url?.toUri()?.host ?: "host"
            val maxItemsOnOneHost = items.groupBy { it.url.toUri().host }.maxOf { it.value.size }
            val intervalSec = Plan.effectiveIntervalSec(maxItemsOnOneHost, Limits.INTERVAL_DEFAULT_SEC)
            val gapMs = intervalSec * 1000L / maxOf(1, items.size)
            val finishTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(wallClockEnd))
            val reqCount = gate.requestCountThisHour(host)

            val raisedNote = if (intervalSec > Limits.INTERVAL_DEFAULT_SEC) " (raised to ${intervalSec / 60} min to stay under 60 req/h)" else ""
            val stat = "Watching ${items.size} items · every ~${intervalSec / 60} min · until $finishTime$raisedNote ($host: $reqCount/60 req)"
            _watchState.value = stat
            updateOngoing(stat)

            for (p in items) {
                if (!currentCoroutineContext().isActive) return
                val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stockping:check")?.apply { acquire(30_000) }
                try { repo.checkOne(p) } finally { if (wl?.isHeld == true) wl.release() }
                delay((gapMs * Random.nextDouble(0.8, 1.2)).toLong())
            }
        }
        stopNote("Watch time finished")
    }

    private fun isOnline(): Boolean = true

    private fun ongoing(text: String) = NotificationCompat.Builder(this, com.org.notifyme.notify.Notifier.CH_WATCH)
        .setSmallIcon(android.R.drawable.stat_notify_more)
        .setContentTitle("StockPing Urgent Watch")
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun updateOngoing(text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm?.notify(ONGOING_ID, ongoing(text))
    }

    private fun stopNote(reason: String) {
        app.container.notifier.notifyWatchStopped(reason)
    }

    override fun onDestroy() {
        isRunning = false
        _watchState.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stockping.STOP"
        const val EXTRA_HOURS = "hours"
        const val ONGOING_ID = 1001

        private val _watchState = MutableStateFlow<String?>(null)
        val watchState: StateFlow<String?> = _watchState

        var isRunning = false
            private set

        fun start(ctx: Context, hours: Int) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java).putExtra(EXTRA_HOURS, hours))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, WatchService::class.java).setAction(ACTION_STOP))
    }
}
