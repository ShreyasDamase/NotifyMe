package com.org.notifyme.net

import com.org.notifyme.data.StockStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

data class CheckResult(
    val status: StockStatus,
    val name: String?,
    val imageUrl: String?,
    val priceText: String?,
    val source: String,
)

class HostBusy(val host: String, val untilMs: Long) : Exception("Paused until $untilMs")

class StockChecker(private val client: OkHttpClient, private val gate: HostGate) {

    private val json = Json { ignoreUnknownKeys = true }
    private val etags = ConcurrentHashMap<String, String>()
    private val lastResult = ConcurrentHashMap<String, CheckResult>()

    suspend fun check(url: String): CheckResult = withContext(Dispatchers.IO) {
        val slug = slugOf(url)
        val viaApi = if (slug != null) runCatching { viaStoreApi(url, slug) }.getOrNull() else null
        val res = viaApi ?: viaHtml(url)
        lastResult[url] = res
        res
    }

    private fun viaStoreApi(url: String, slug: String): CheckResult? {
        val base = url.toHttpUrl()
        val api: HttpUrl = HttpUrl.Builder()
            .scheme(base.scheme).host(base.host)
            .addPathSegments("wp-json/wc/store/v1/products")
            .addQueryParameter("slug", slug)
            .build()
        val body = get(api.toString()) ?: return lastResult[url]
        val p = json.parseToJsonElement(body).jsonArray
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

    private fun viaHtml(url: String): CheckResult {
        val html = get(url) ?: return lastResult[url] ?: CheckResult(StockStatus.UNKNOWN, null, null, null, "304")
        val doc = Jsoup.parse(html, url)
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

    private fun get(url: String): String? {
        val host = url.toHttpUrl().host
        val allowed = gate.allowedAt(host)
        if (allowed > System.currentTimeMillis()) throw HostBusy(host, allowed)
        gate.recordRequest(host)

        val b = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en-IN,en;q=0.9")
        etags[url]?.let { b.header("If-None-Match", it) }
        try {
            client.newCall(b.build()).execute().use { r ->
                gate.recordOutcome(host, r.code, r.header("Retry-After")?.toLongOrNull()?.times(1000))
                if (r.code == 304) return null
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.header("ETag")?.let { etags[url] = it }
                return r.peekBody(Limits.MAX_BODY_BYTES).string()
            }
        } catch (e: java.io.IOException) {
            gate.recordOutcome(host, null, null); throw e
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
