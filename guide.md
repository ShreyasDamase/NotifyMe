# StockPing — Agent Build Guide

Android app that watches product-page URLs (primarily robu.in, a WooCommerce store) and fires a notification when an item flips **Out of Stock → In Stock**.

Seed product for testing: https://robu.in/product/n25-6v-115rpm-metal-gear-motor-with-encoder-d-type/ (SKU 1607491, currently "Availability: Out of Stock").

---

## 1. Scope

**v1 (build this):**
- Add a product by pasting a URL or **sharing a link from the browser** into the app (share target).
- List watched products: name, image, price, status chip, last-checked time, last error.
- Background check every 15 min via WorkManager, survives reboot.
- High-importance notification on OUT → IN transition; tap opens the product URL.
- "Check now" action, enable/disable per item, delete item.
- Shortcut to battery-optimization settings.

**Out of scope for v1:** accounts, backend, FCM, non-WooCommerce sites, custom intervals, price-drop alerts. (See §11 for follow-ups.)

## 2. Stack (fixed — do not substitute)

Kotlin, Jetpack Compose + Material 3, single Activity, MVVM, Room (+KSP), WorkManager, OkHttp, Jsoup, kotlinx.serialization (JSON), Coil (images). **No Hilt/Koin** — manual DI via an `AppContainer` in the `Application` class. minSdk 26, target/compile 35, JVM 17.

Package: `com.bokya.stockping` (change if the project already has one).

## 3. Project layout

```
app/src/main/java/com/bokya/stockping/
  StockPingApp.kt          // Application + AppContainer
  MainActivity.kt          // share-intent handling, permission, setContent
  data/
    Models.kt              // StockStatus, WatchedProduct, Converters
    ProductDao.kt
    AppDatabase.kt
    ProductRepository.kt   // add/remove/checkAll + transition logic
  net/
    StockChecker.kt        // Store API first, HTML fallback
  work/
    StockCheckWorker.kt
    Scheduler.kt
  notify/
    Notifier.kt
  ui/
    MainViewModel.kt
    MainScreen.kt
app/src/test/ ... StockCheckerTest.kt + resources/robu_out_of_stock.html
```

## 4. Gradle

`gradle/libs.versions.toml` (versions are known-good minimums; bump to latest stable if the IDE suggests):

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
composeBom = "2024.10.01"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
room = "2.6.1"
work = "2.9.1"
okhttp = "4.12.0"
jsoup = "1.18.1"
serialization = "1.7.3"
coil = "2.7.0"
coroutines = "1.9.0"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-icons = { module = "androidx.compose.material:material-icons-extended" }
compose-tooling = { module = "androidx.compose.ui:ui-tooling" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
lifecycle-vm-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coil-compose = { module = "io.coil-kt:coil-compose", version.ref = "coil" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bokya.stockping"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.bokya.stockping"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.vm.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
```

## 5. Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".StockPingApp"
        android:label="StockPing"
        android:icon="@mipmap/ic_launcher"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Share a product link from the browser into the app -->
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

If the Material3 XML theme isn't available in the project's resources, define a minimal `Theme.StockPing` parented to `android:Theme.Material.Light.NoActionBar`. The UI itself is Compose `MaterialTheme`.

## 6. Core behavior (the rules the code must implement)

1. **Status model:** `IN_STOCK`, `OUT_OF_STOCK`, `UNKNOWN`. `UNKNOWN` means "could not determine" (network error, blocked, unparseable). It must **never overwrite** a known `lastStatus`.
2. **Notify only on transition:** `newStatus == IN_STOCK && previous.lastStatus == OUT_OF_STOCK`. First check of a new item never notifies. Item that goes OUT again and returns IN notifies again.
3. **Detection order** (first non-null wins):
    1. WooCommerce Store API: `GET {origin}/wp-json/wc/store/v1/products?slug={slug}` → `is_in_stock`.
    2. HTML JSON-LD `"availability": ".../InStock|OutOfStock|..."`.
    3. HTML text `Availability: Out of Stock | In Stock | N in stock`.
    4. DOM: `p.stock.out-of-stock` → OUT; `button.single_add_to_cart_button` present → IN.
4. Backorder/PreOrder count as **IN_STOCK** (item is purchasable).
5. A failing item must not fail the worker or block other items. Record `lastError`, move on, return `Result.success()`.
6. Be polite to the site: ≥15 min interval, sequential requests with ~1 s delay between items, browser-like User-Agent, no parallel hammering.

## 7. Reference code

### data/Models.kt

```kotlin
package com.bokya.stockping.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class StockStatus { IN_STOCK, OUT_OF_STOCK, UNKNOWN }

@Entity(tableName = "watched_products", indices = [Index(value = ["url"], unique = true)])
data class WatchedProduct(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val slug: String?,
    val name: String,
    val imageUrl: String? = null,
    val priceText: String? = null,
    val lastStatus: StockStatus = StockStatus.UNKNOWN,
    val lastCheckedAt: Long? = null,
    val lastError: String? = null,
    val enabled: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
)

class Converters {
    @TypeConverter fun toStatus(v: String): StockStatus = StockStatus.valueOf(v)
    @TypeConverter fun fromStatus(s: StockStatus): String = s.name
}
```

### data/ProductDao.kt + AppDatabase.kt

```kotlin
package com.bokya.stockping.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM watched_products ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchedProduct>>

    @Query("SELECT * FROM watched_products WHERE enabled = 1")
    suspend fun getEnabled(): List<WatchedProduct>

    @Query("SELECT * FROM watched_products WHERE id = :id")
    suspend fun get(id: Long): WatchedProduct?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: WatchedProduct): Long   // -1 if duplicate url

    @Update suspend fun update(p: WatchedProduct)
    @Delete suspend fun delete(p: WatchedProduct)
}

@Database(entities = [WatchedProduct::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        fun build(ctx: Context) =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "stockping.db").build()
    }
}
```

### net/StockChecker.kt

```kotlin
package com.bokya.stockping.net

import com.bokya.stockping.data.StockStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class CheckResult(
    val status: StockStatus,
    val name: String?,
    val imageUrl: String?,
    val priceText: String?,
    val source: String,            // "store-api" | "json-ld" | "text" | "dom" | "none" (for logs/debug)
)

class StockChecker(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(url: String): CheckResult = withContext(Dispatchers.IO) {
        val slug = slugOf(url)
        val viaApi = if (slug != null) runCatching { viaStoreApi(url, slug) }.getOrNull() else null
        viaApi ?: viaHtml(url)
    }

    // ---------- 1. WooCommerce Store API ----------
    private fun viaStoreApi(url: String, slug: String): CheckResult? {
        val base = url.toHttpUrl()
        val api: HttpUrl = HttpUrl.Builder()
            .scheme(base.scheme).host(base.host)
            .addPathSegments("wp-json/wc/store/v1/products")
            .addQueryParameter("slug", slug)
            .build()
        val p = json.parseToJsonElement(get(api.toString())).jsonArray
            .firstOrNull()?.jsonObject ?: return null
        val inStock = p["is_in_stock"]?.jsonPrimitive?.booleanOrNull ?: return null

        val prices = p["prices"]?.jsonObject
        val minor = prices?.get("currency_minor_unit")?.jsonPrimitive?.intOrNull ?: 2
        val sym = prices?.get("currency_symbol")?.jsonPrimitive?.contentOrNull?.let(::decode) ?: ""
        val price = prices?.get("price")?.jsonPrimitive?.contentOrNull
            ?.toBigDecimalOrNull()?.movePointLeft(minor)?.setScale(minor)?.toPlainString()

        return CheckResult(
            status = if (inStock) StockStatus.IN_STOCK else StockStatus.OUT_OF_STOCK,
            name = p["name"]?.jsonPrimitive?.contentOrNull?.let(::decode),
            imageUrl = p["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("src")?.jsonPrimitive?.contentOrNull,
            priceText = price?.let { "$sym$it" },
            source = "store-api",
        )
    }

    // ---------- 2-4. HTML fallback ----------
    private fun viaHtml(url: String): CheckResult {
        val doc = Jsoup.parse(get(url), url)
        return parseHtml(doc)
    }

    internal fun parseHtml(doc: Document): CheckResult {
        val (status, source) =
            fromJsonLd(doc)?.let { it to "json-ld" }
                ?: fromText(doc)?.let { it to "text" }
                ?: fromDom(doc)?.let { it to "dom" }
                ?: (StockStatus.UNKNOWN to "none")

        val name = doc.selectFirst("h1.product_title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
        val image = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val amount = doc.selectFirst("meta[property=product:price:amount]")?.attr("content")
        val cur = doc.selectFirst("meta[property=product:price:currency]")?.attr("content")
        val price = amount?.let { if (cur.isNullOrBlank()) it else "$it $cur" }
            ?: doc.selectFirst("p.price .woocommerce-Price-amount")?.text()

        return CheckResult(status, name, image, price, source)
    }

    private val ldRegex = Regex(
        "\"availability\"\\s*:\\s*\"[^\"]*?(InStock|LimitedAvailability|OutOfStock|SoldOut|Discontinued|PreOrder|BackOrder)\"",
        RegexOption.IGNORE_CASE,
    )

    private fun fromJsonLd(doc: Document): StockStatus? =
        doc.select("script[type=application/ld+json]")
            .firstNotNullOfOrNull { ldRegex.find(it.data())?.groupValues?.get(1) }
            ?.let {
                when (it.lowercase()) {
                    "instock", "limitedavailability", "preorder", "backorder" -> StockStatus.IN_STOCK
                    else -> StockStatus.OUT_OF_STOCK
                }
            }

    private val textRegex = Regex(
        "Availability\\s*:\\s*(Out of Stock|In Stock|\\d+\\s+in stock)", RegexOption.IGNORE_CASE,
    )

    private fun fromText(doc: Document): StockStatus? =
        textRegex.find(doc.text())?.groupValues?.get(1)?.let {
            if (it.contains("out", ignoreCase = true)) StockStatus.OUT_OF_STOCK else StockStatus.IN_STOCK
        }

    private fun fromDom(doc: Document): StockStatus? = when {
        doc.selectFirst("p.stock.out-of-stock") != null -> StockStatus.OUT_OF_STOCK
        doc.selectFirst("button.single_add_to_cart_button") != null -> StockStatus.IN_STOCK
        else -> null
    }

    // ---------- helpers ----------
    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "en-IN,en;q=0.9")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string() ?: error("Empty body")
        }
    }

    private fun decode(s: String) = Jsoup.parse(s).text()

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        private val slugRegex = Regex("/product/([^/]+)/?")
        fun slugOf(url: String): String? =
            runCatching { slugRegex.find(url.toHttpUrl().encodedPath)?.groupValues?.get(1) }.getOrNull()
    }
}
```

### notify/Notifier.kt

```kotlin
package com.bokya.stockping.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bokya.stockping.data.WatchedProduct

class Notifier(private val ctx: Context) {

    fun ensureChannel() {
        val ch = NotificationChannel(CHANNEL, "Stock alerts", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Fires when a watched product is back in stock" }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun notifyInStock(p: WatchedProduct) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            ctx, p.id.toInt(),
            Intent(Intent.ACTION_VIEW, Uri.parse(p.url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)   // replace with app icon
            .setContentTitle("Back in stock")
            .setContentText(p.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${p.name}\n${p.priceText ?: ""}".trim()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(p.id.toInt(), n)
    }

    companion object { const val CHANNEL = "stock_alerts" }
}
```

### data/ProductRepository.kt

```kotlin
package com.bokya.stockping.data

import com.bokya.stockping.net.StockChecker
import com.bokya.stockping.notify.Notifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AddResult { ADDED, DUPLICATE, INVALID }

class ProductRepository(
    private val dao: ProductDao,
    private val checker: StockChecker,
    private val notifier: Notifier,
) {
    fun observeAll(): Flow<List<WatchedProduct>> = dao.observeAll()

    /** Accepts raw shared text ("Check this out https://...") or a bare URL. */
    suspend fun addFromText(text: String): AddResult {
        val raw = Regex("https?://\\S+").find(text)?.value ?: return AddResult.INVALID
        val http = raw.toHttpUrlOrNull() ?: return AddResult.INVALID
        val clean = http.newBuilder().query(null).fragment(null).build().toString()

        val slug = StockChecker.slugOf(clean)
        val id = dao.insert(WatchedProduct(url = clean, slug = slug, name = slug ?: http.host))
        if (id == -1L) return AddResult.DUPLICATE

        dao.get(id)?.let { checkOne(it, allowNotify = false) }   // populate name/image/status now
        return AddResult.ADDED
    }

    suspend fun setEnabled(p: WatchedProduct, enabled: Boolean) = dao.update(p.copy(enabled = enabled))
    suspend fun remove(p: WatchedProduct) = dao.delete(p)

    suspend fun checkAll() {
        val items = dao.getEnabled()
        items.forEachIndexed { i, p ->
            checkOne(p, allowNotify = true)
            if (i < items.lastIndex) delay(1_000)
        }
    }

    private suspend fun checkOne(p: WatchedProduct, allowNotify: Boolean) {
        val now = System.currentTimeMillis()
        val updated = runCatching { checker.check(p.url) }.fold(
            onSuccess = { r ->
                val newStatus = if (r.status == StockStatus.UNKNOWN) p.lastStatus else r.status
                if (allowNotify && r.status == StockStatus.IN_STOCK && p.lastStatus == StockStatus.OUT_OF_STOCK) {
                    notifier.notifyInStock(p.copy(name = r.name ?: p.name, priceText = r.priceText ?: p.priceText))
                }
                p.copy(
                    name = r.name ?: p.name,
                    imageUrl = r.imageUrl ?: p.imageUrl,
                    priceText = r.priceText ?: p.priceText,
                    lastStatus = newStatus,
                    lastCheckedAt = now,
                    lastError = if (r.status == StockStatus.UNKNOWN) "Couldn't read stock status" else null,
                )
            },
            onFailure = { e -> p.copy(lastCheckedAt = now, lastError = e.message ?: e::class.simpleName) },
        )
        dao.update(updated)
    }
}
```

### work/StockCheckWorker.kt + Scheduler.kt

```kotlin
package com.bokya.stockping.work

import android.content.Context
import androidx.work.*
import com.bokya.stockping.StockPingApp
import java.util.concurrent.TimeUnit

class StockCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        (applicationContext as StockPingApp).container.repository.checkAll()
        return Result.success()   // per-item errors are stored on the row; never fail the periodic chain
    }
}

object Scheduler {
    private const val UNIQUE = "stock_check"

    fun ensurePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<StockCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
```

### StockPingApp.kt

```kotlin
package com.bokya.stockping

import android.app.Application
import com.bokya.stockping.data.*
import com.bokya.stockping.net.StockChecker
import com.bokya.stockping.notify.Notifier
import com.bokya.stockping.work.Scheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(app: Application) {
    private val db = AppDatabase.build(app)
    val notifier = Notifier(app)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val repository = ProductRepository(db.productDao(), StockChecker(http), notifier)
}

class StockPingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.ensureChannel()
        Scheduler.ensurePeriodic(this)
    }
}
```

### ui/MainViewModel.kt

```kotlin
package com.bokya.stockping.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bokya.stockping.StockPingApp
import com.bokya.stockping.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(val checking: Boolean = false, val message: String? = null)

class MainViewModel(private val repo: ProductRepository) : ViewModel() {
    val products: StateFlow<List<WatchedProduct>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun add(text: String) = viewModelScope.launch {
        _ui.update { it.copy(checking = true) }
        val msg = when (repo.addFromText(text)) {
            AddResult.ADDED -> "Added"
            AddResult.DUPLICATE -> "Already watching this product"
            AddResult.INVALID -> "No valid link found"
        }
        _ui.update { UiState(checking = false, message = msg) }
    }

    fun checkNow() = viewModelScope.launch {
        _ui.update { it.copy(checking = true) }
        repo.checkAll()
        _ui.update { UiState(checking = false, message = "Checked") }
    }

    fun toggle(p: WatchedProduct) = viewModelScope.launch { repo.setEnabled(p, !p.enabled) }
    fun remove(p: WatchedProduct) = viewModelScope.launch { repo.remove(p) }
    fun messageShown() = _ui.update { it.copy(message = null) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StockPingApp
                MainViewModel(app.container.repository)
            }
        }
    }
}
```

### MainActivity.kt

```kotlin
package com.bokya.stockping

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import com.bokya.stockping.ui.MainScreen
import com.bokya.stockping.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) askPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MaterialTheme { MainScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::add)
        }
    }
}
```

### ui/MainScreen.kt (reference; agent may polish styling, keep behavior)

```kotlin
package com.bokya.stockping.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bokya.stockping.data.StockStatus
import com.bokya.stockping.data.WatchedProduct

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val products by vm.products.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StockPing") },
                actions = {
                    IconButton(onClick = { vm.checkNow() }, enabled = !ui.checking) {
                        Icon(Icons.Default.Refresh, "Check now")
                    }
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Icon(Icons.Default.BatterySaver, "Battery settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (ui.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (products.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Share a product link here, or tap +", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(products, key = { it.id }) { p -> ProductCard(p, vm) }
                }
            }
        }
    }

    if (showAdd) AddDialog(onDismiss = { showAdd = false }, onAdd = { vm.add(it); showAdd = false })
}

@Composable
private fun ProductCard(p: WatchedProduct, vm: MainViewModel) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
    }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = p.imageUrl, contentDescription = null,
                modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                p.priceText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(4.dp))
                StatusChip(p.lastStatus)
                Text(
                    p.lastCheckedAt?.let { "Checked " + DateUtils.getRelativeTimeSpanString(it) } ?: "Not checked yet",
                    style = MaterialTheme.typography.labelSmall,
                )
                p.lastError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = p.enabled, onCheckedChange = { vm.toggle(p) })
                IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Default.Delete, "Remove") }
            }
        }
    }
}

@Composable
private fun StatusChip(s: StockStatus) {
    val (label, color) = when (s) {
        StockStatus.IN_STOCK -> "In stock" to MaterialTheme.colorScheme.primaryContainer
        StockStatus.OUT_OF_STOCK -> "Out of stock" to MaterialTheme.colorScheme.errorContainer
        StockStatus.UNKNOWN -> "Unknown" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch a product") },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text("Product URL") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

## 8. Build order for the agent

1. Scaffold project, apply §4 Gradle + §5 Manifest, confirm it builds with an empty `MainActivity`.
2. Add `data/` (Models, Dao, Database) → build.
3. Add `net/StockChecker.kt` + unit tests (§9) → `./gradlew testDebugUnitTest` green **before** touching UI/worker.
4. Add `Notifier`, `ProductRepository`, `StockPingApp`/`AppContainer`.
5. Add `Worker` + `Scheduler`.
6. Add `MainViewModel`, `MainActivity`, `MainScreen`.
7. Install on a device/emulator, run §10 acceptance checks.

## 9. Tests

`StockCheckerTest` (JVM, no Android deps beyond Jsoup — `parseHtml` is `internal` for this reason):

- Save the real product page HTML to `src/test/resources/robu_out_of_stock.html`. Assert `parseHtml(Jsoup.parse(html)).status == OUT_OF_STOCK`.
- Craft a modified copy with `Availability: In Stock` and the add-to-cart button → assert `IN_STOCK`.
- JSON-LD fixtures: `InStock`, `OutOfStock`, `BackOrder` → `IN_STOCK`, `OUT_OF_STOCK`, `IN_STOCK`.
- `slugOf("https://robu.in/product/n25-6v-115rpm-metal-gear-motor-with-encoder-d-type/?utm=x")` → `n25-6v-115rpm-metal-gear-motor-with-encoder-d-type`.
- Repository transition logic (fake `StockChecker` interface or MockWebServer): OUT→IN notifies once; first check never notifies; UNKNOWN keeps old status; IN→IN doesn't notify.

If the site returns 403 / a Cloudflare challenge to OkHttp, **do not** add evasion tricks. Surface it as `lastError` ("HTTP 403") and tell the user.

## 10. Acceptance checklist

- [ ] Share the seed URL from Chrome → app opens, item appears, status shows **Out of stock**, name + image + price populated.
- [ ] Pasting the same URL again → "Already watching this product".
- [ ] Airplane mode + Check now → row shows error, previous status preserved.
- [ ] Debug-only: long-press a card sets `lastStatus = OUT_OF_STOCK`; with an in-stock item, Check now fires the notification; tapping it opens the page.
- [ ] Debug-only menu item "Test notification" posts a sample alert (verifies channel + permission).
- [ ] `adb shell dumpsys jobscheduler | grep stockping` (or App Inspection → Background Task Inspector) shows the periodic work enqueued; still enqueued after reboot.
- [ ] Notification permission denied → app still works, no crash, no notification.

## 11. Pitfalls (read before coding)

- **15 min is WorkManager's floor** and Doze/App Standby will stretch it. Don't use exact alarms or wakelocks. Indian OEMs (Xiaomi, Oppo, Vivo, Realme, OnePlus) aggressively kill background work, so keep the battery-settings shortcut and mention "disable battery optimization / allow autostart" in the empty-state or an info dialog.
- `ExistingPeriodicWorkPolicy.KEEP` so app launches don't reset the timer.
- Never store `UNKNOWN` over a known status (would cause a missed or duplicate alert).
- Product names from the API contain HTML entities (`&amp;`, `&#8377;`) → `decode()` via Jsoup.
- `PendingIntent` needs `FLAG_IMMUTABLE` on API 31+.
- Don't parse `doc.text()` for plain "out of stock" (related-product widgets contain it); only the anchored `Availability:` regex.
- Keep R8/ProGuard off for v1 to avoid serialization/Room surprises.

## 12. Follow-ups (not now)

Configurable interval (DataStore), auto-pause item after alert, price-drop threshold, ntfy.sh/FCM fallback for reliability, per-item "notify on backorder" toggle, Compose Navigation + detail screen, export/import list.