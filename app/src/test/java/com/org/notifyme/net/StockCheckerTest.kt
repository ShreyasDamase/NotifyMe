package com.org.notifyme.net

import com.org.notifyme.data.StockStatus
import org.jsoup.Jsoup
import org.junit.Test
import kotlin.test.assertEquals

class StockCheckerTest {

    @Test
    fun testSlugOfRobuAndRobocraze() {
        assertEquals("n25-6v-115rpm-metal-gear-motor-with-encoder-d-type", StockChecker.slugOf("https://robu.in/product/n25-6v-115rpm-metal-gear-motor-with-encoder-d-type/?utm=x"))
        assertEquals("some-product-slug", StockChecker.slugOf("https://www.robocraze.com/product/some-product-slug"))
    }

    @Test
    fun testParseHtmlRobuOutOfStock() {
        val htmlStream = javaClass.getResourceAsStream("/robu_out_of_stock.html")
        val html = htmlStream?.bufferedReader()?.readText() ?: "<p class=\"stock out-of-stock\">Out of stock</p>"
        val doc = Jsoup.parse(html)
        val checker = StockChecker(okhttp3.OkHttpClient(), HostGate())
        val result = checker.parseHtml(doc)
        assertEquals(StockStatus.OUT_OF_STOCK, result.status)
    }

    @Test
    fun testParseHtmlRobocrazeInStockDom() {
        val doc = Jsoup.parse("<html><body><h1 class=\"product_title\">Robocraze Motor</h1><button class=\"single_add_to_cart_button\">Add to cart</button></body></html>")
        val checker = StockChecker(okhttp3.OkHttpClient(), HostGate())
        val result = checker.parseHtml(doc)
        assertEquals(StockStatus.IN_STOCK, result.status)
        assertEquals("Robocraze Motor", result.name)
        assertEquals("dom", result.source)
    }

    @Test
    fun testParseJsonLdInStock() {
        val doc = Jsoup.parse("<html><body><script type=\"application/ld+json\">{\"availability\": \"https://schema.org/InStock\"}</script></body></html>")
        val checker = StockChecker(okhttp3.OkHttpClient(), HostGate())
        val result = checker.parseHtml(doc)
        assertEquals(StockStatus.IN_STOCK, result.status)
        assertEquals("json-ld", result.source)
    }

    @Test
    fun testParseJsonLdOutOfStock() {
        val doc = Jsoup.parse("<html><body><script type=\"application/ld+json\">{\"availability\": \"https://schema.org/OutOfStock\"}</script></body></html>")
        val checker = StockChecker(okhttp3.OkHttpClient(), HostGate())
        val result = checker.parseHtml(doc)
        assertEquals(StockStatus.OUT_OF_STOCK, result.status)
        assertEquals("json-ld", result.source)
    }

    @Test
    fun testParseTextAvailabilityInStock() {
        val doc = Jsoup.parse("<html><body><div>Availability: In Stock</div></body></html>")
        val checker = StockChecker(okhttp3.OkHttpClient(), HostGate())
        val result = checker.parseHtml(doc)
        assertEquals(StockStatus.IN_STOCK, result.status)
        assertEquals("text", result.source)
    }

    @Test
    fun testParseTextAvailabilityOutOfStock() {
        val doc = Jsoup.parse("<html><body><div>Availability: Out of Stock</div></body></html>")
        val checker = StockChecker(okhttp3.OkHttpClient(), HostGate())
        val result = checker.parseHtml(doc)
        assertEquals(StockStatus.OUT_OF_STOCK, result.status)
        assertEquals("text", result.source)
    }

    @Test
    fun testParseUnknown() {
        val doc = Jsoup.parse("<html><body><div>Some unknown page content</div></body></html>")
        val checker = StockChecker(okhttp3.OkHttpClient(), HostGate())
        val result = checker.parseHtml(doc)
        assertEquals(StockStatus.UNKNOWN, result.status)
        assertEquals("none", result.source)
    }
}
