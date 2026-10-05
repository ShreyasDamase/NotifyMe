package com.org.notifyme

import android.app.Application
import com.org.notifyme.data.*
import com.org.notifyme.net.HostGate
import com.org.notifyme.net.StockChecker
import com.org.notifyme.notify.Notifier
import com.org.notifyme.work.Scheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    private val db = AppDatabase.build(app)
    val notifier = Notifier(app)
    val hostGate = HostGate()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val repository = ProductRepository(db.productDao(), StockChecker(http, hostGate), notifier)
}

class StockPingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannels()
        Scheduler.ensurePeriodic(this)
    }
}
