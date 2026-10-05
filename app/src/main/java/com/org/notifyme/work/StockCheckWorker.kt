package com.org.notifyme.work

import android.content.Context
import androidx.work.*
import com.org.notifyme.StockPingApp
import com.org.notifyme.watch.WatchService
import java.util.concurrent.TimeUnit

class StockCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (WatchService.isRunning) return Result.success()
        (applicationContext as StockPingApp).container.repository.checkAll()
        return Result.success()
    }
}

object Scheduler {
    private const val UNIQUE = "stock_check"

    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<StockCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
