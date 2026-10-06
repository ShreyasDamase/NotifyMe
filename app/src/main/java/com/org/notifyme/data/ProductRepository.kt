package com.org.notifyme.data

import com.org.notifyme.net.HostBusy
import com.org.notifyme.net.StockChecker
import com.org.notifyme.notify.Notifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class AddResult {
    ADDED, DUPLICATE, INVALID, AMAZON_UNSUPPORTED, FLIPKART_NEEDS_API, UNREADABLE, CHECK_FAILED, HOST_PAUSED,
}

class ProductRepository(
    private val dao: ProductDao,
    private val checker: StockChecker,
    private val notifier: Notifier,
) {
    fun observeAll(): Flow<List<WatchedProduct>> = dao.observeAll()

    suspend fun addFromText(text: String): AddResult {
        val raw = Regex("https?://\\S+").find(text)?.value ?: return AddResult.INVALID
        val http = raw.toHttpUrlOrNull() ?: return AddResult.INVALID
        val host = http.host.lowercase()
        if (isAmazonHost(host)) return AddResult.AMAZON_UNSUPPORTED

        // Preserve functional parameters such as Flipkart's pid, but strip common tracking fields.
        val clean = http.newBuilder().fragment(null).apply {
            http.queryParameterNames.filter(::isTrackingParameter)
                .forEach { removeAllQueryParameters(it) }
        }.build().toString()
        if (dao.getByUrl(clean) != null) return AddResult.DUPLICATE

        val initial = try {
            checker.check(clean)
        } catch (_: HostBusy) {
            return AddResult.HOST_PAUSED
        } catch (e: IllegalStateException) {
            if (e.message?.contains("HTTP 403") == true || e.message?.contains("HTTP 429") == true) {
                return AddResult.HOST_PAUSED
            }
            return AddResult.CHECK_FAILED
        } catch (_: Exception) {
            return AddResult.CHECK_FAILED
        }
        if (initial.status == StockStatus.UNKNOWN) {
            return if (isFlipkartHost(host)) AddResult.FLIPKART_NEEDS_API else AddResult.UNREADABLE
        }

        val slug = StockChecker.slugOf(clean)
        val id = dao.insert(
            WatchedProduct(
                url = clean,
                slug = slug,
                name = initial.name ?: slug ?: http.host,
                imageUrl = initial.imageUrl,
                priceText = initial.priceText,
                lastStatus = initial.status,
                lastCheckedAt = System.currentTimeMillis(),
            )
        )
        if (id == -1L) return AddResult.DUPLICATE
        return AddResult.ADDED
    }

    private fun isTrackingParameter(name: String): Boolean {
        val key = name.lowercase()
        return key.startsWith("utm_") || key.startsWith("otracker") || key in setOf(
            "ref", "tag", "linkcode", "ascsubtag", "affid", "affextparam1", "affextparam2",
        )
    }

    private fun isAmazonHost(host: String): Boolean =
        host in setOf("amzn.in", "amzn.to", "amzn.eu", "a.co") ||
            host.startsWith("amazon.") || host.contains(".amazon.")

    private fun isFlipkartHost(host: String): Boolean =
        host == "flipkart.com" || host.endsWith(".flipkart.com") || host in setOf("fkrt.it", "fkrt.cc")

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
                        // Silent in background; managed entirely by HostGate and shown optionally in-app banner if desired.
                        p
                    }
                    else -> p.copy(lastCheckedAt = now, lastError = e.message ?: e::class.simpleName)
                }
            },
        )
        dao.update(updated)
    }
}
