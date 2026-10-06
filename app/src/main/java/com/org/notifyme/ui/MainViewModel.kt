package com.org.notifyme.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.org.notifyme.StockPingApp
import com.org.notifyme.data.*
import com.org.notifyme.watch.WatchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

data class UiState(val checking: Boolean = false, val message: String? = null)

class MainViewModel(private val repo: ProductRepository, private val app: StockPingApp) : ViewModel() {
    private val _productsLoaded = MutableStateFlow(false)
    val productsLoaded: StateFlow<Boolean> = _productsLoaded.asStateFlow()
    val products: StateFlow<List<WatchedProduct>> =
        repo.observeAll()
            .onEach { _productsLoaded.value = true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val watchState: StateFlow<String?> = WatchService.watchState

    private val _pausedBanner = MutableStateFlow<String?>(null)
    val pausedBanner: StateFlow<String?> = _pausedBanner

    init {
        viewModelScope.launch {
            while (true) {
                updatePausedBanner()
                delay(5_000)
            }
        }
    }

    fun updatePausedBanner() {
        val pauses = app.container.hostGate.getActivePauses()
        if (pauses.isEmpty()) {
            _pausedBanner.value = null
        } else {
            val (host, until) = pauses.entries.first()
            val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))
            _pausedBanner.value = "$host paused until $timeStr"
        }
    }

    fun add(text: String) = viewModelScope.launch {
        _ui.update { it.copy(checking = true) }
        val msg = when (repo.addFromText(text)) {
            AddResult.ADDED -> "Added"
            AddResult.DUPLICATE -> "Already watching this product"
            AddResult.INVALID -> "No valid link found"
            AddResult.AMAZON_UNSUPPORTED ->
                "Amazon links aren't supported: Amazon's Product Advertising API data can't be used in mobile apps."
            AddResult.FLIPKART_NEEDS_API ->
                "Notify couldn't read stock from this Flipkart page. Reliable tracking needs Flipkart Affiliate API credentials."
            AddResult.UNREADABLE ->
                "This page doesn't expose stock status Notify can read, so it wasn't added. The store may need its own integration."
            AddResult.CHECK_FAILED ->
                "Couldn't reach this store. Check the link and connection, then try again."
            AddResult.HOST_PAUSED ->
                "This store is temporarily rate-limiting checks. Try adding this link again later."
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

    fun testAlert() {
        app.container.notifier.notifyUrgent(
            WatchedProduct(
                id = 999,
                url = "https://robu.in/product/n25-6v-115rpm-metal-gear-motor-with-encoder-d-type/",
                slug = "test-motor",
                name = "N25 6V 115RPM Metal Gear Motor (Test)",
                priceText = "₹450",
                lastStatus = StockStatus.OUT_OF_STOCK
            )
        )
        _ui.update { it.copy(message = "Triggered test urgent alarm!") }
    }

    fun startUrgentWatch(ctx: Context, hours: Int) {
        WatchService.start(ctx, hours)
        _ui.update { it.copy(message = "Urgent watch started for ${hours}h") }
    }

    fun stopUrgentWatch(ctx: Context) {
        WatchService.stop(ctx)
        _ui.update { it.copy(message = "Urgent watch stopped") }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as StockPingApp
                MainViewModel(app.container.repository, app)
            }
        }
    }
}
