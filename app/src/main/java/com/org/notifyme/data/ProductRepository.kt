package com.org.notifyme.data

import com.org.notifyme.net.HostBusy
import com.org.notifyme.net.StockChecker
import com.org.notifyme.notify.Notifier
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

    suspend fun addFromText(text: String): AddResult {
        val raw = Regex("https?://\\S+").find(text)?.value ?: return AddResult.INVALID
        val http = raw.toHttpUrlOrNull() ?: return AddResult.INVALID
        val clean = http.newBuilder().query(null).fragment(null).build().toString()

        val slug = StockChecker.slugOf(clean)
        val id = dao.insert(WatchedProduct(url = clean, slug = slug, name = slug ?: http.host))
        if (id == -1L) return AddResult.DUPLICATE
        return AddResult.ADDED
    }

    suspend fun setEnabled(p: WatchedProduct, enabled: Boolean) = dao.update(p.copy(enabled = enabled))
    suspend fun remove(p: WatchedProduct) = dao.delete(p)

    suspend fun watchable() = dao.getEnabled().filter { it.lastStatus == StockStatus.OUT_OF_STOCK }

    suspend fun checkAll() {
        val items = dao.getEnabled()
        items.forEachIndexed { i, p ->
            checkOne(p, allowNotify = true)
            if (i < items.lastIndex) delay(1_000)
        }
    }

    suspend fun checkOne(p: WatchedProduct, allowNotify: Boolean = true) {
        val now = System.currentTimeMillis()
        val updated = runCatching { checker.check(p.url) }.fold(
            onSuccess = { r ->
                val newStatus = if (r.status == StockStatus.UNKNOWN) p.lastStatus else r.status
                if (allowNotify && r.status == StockStatus.IN_STOCK && p.lastStatus == StockStatus.OUT_OF_STOCK) {
                    notifier.notifyUrgent(p.copy(name = r.name ?: p.name, priceText = r.priceText ?: p.priceText))
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
            onFailure = { e ->
                when (e) {
                    is HostBusy -> {
                        notifier.notifyHostPaused(e.host, e.untilMs)
                        p
                    }
                    else -> p.copy(lastCheckedAt = now, lastError = e.message ?: e::class.simpleName)
                }
            },
        )
        dao.update(updated)
    }
}
