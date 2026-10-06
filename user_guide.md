# StockPing — User Guide & Technical Documentation

StockPing is an advanced, battery-efficient Android application designed to monitor product-page URLs (primarily WooCommerce stores like robu.in and robocraze) and fire urgent full-screen alarm notifications when an item flips **Out of Stock → In Stock**.

---

## 🚀 How to Use StockPing

### 1. Adding Products to Watch
- **Method A (In-App)**: Tap the floating action button (**+**) at the bottom right, paste the product URL (e.g., from robu.in or robocraze), and tap **Add**.
- **Method B (Share from Browser)**: While browsing products in Chrome or any browser, tap **Share** and select **StockPing** to add the link instantly.

### 2. Checking Stock Status
- **Automatic Background Checks**: StockPing automatically checks your enabled products every **15 minutes** in the background using WorkManager.
- **Manual Check**: Tap the **Check now** (refresh) icon in the top right corner to verify all items immediately.

### 3. Using Urgent Mode (For Active Restocks)
When you are waiting for a fast-selling item to come back in stock:
1. Select your desired watch duration (**1h**, **2h**, **4h**, or **8h**) using the duration chips on the Urgent Mode card.
2. Tap **Start**. StockPing will poll actively with optimized pacing and rate-limiting.
3. The card displays live stats (remaining items, interval, and hourly request count `x/60 req`).
4. Tap **Stop** when finished.

### 4. Receiving Restock Alerts
- When a watched item goes from **Out of Stock → In Stock**, StockPing fires a high-priority alarm and launches a full-screen alert (`AlarmActivity`) with sound and vibration.
- Tap **OPEN PRODUCT** to jump straight to the product page and buy it before it sells out again.

### 5. Battery & Reliability Setup
- Tap the **Battery settings** (battery) icon in the top right corner.
- Exclude StockPing from battery optimization and enable autostart in your device settings to prevent background killing (especially crucial on Xiaomi, Oppo, Vivo, Realme, and OnePlus devices).

---

## 2. Detection Path & Strategies

StockPing uses a tiered detection strategy per site to determine stock status:
1. **WooCommerce Store API**: Queries `{origin}/wp-json/wc/store/v1/products?slug={slug}` for fast, structured stock and price data.
2. **Shopify / E-Commerce JS Endpoints**: Supports Shopify-based JSON endpoints where applicable.
3. **HTML JSON-LD**: Parses `<script type="application/ld+json">` metadata for `schema.org/InStock`, `LimitedAvailability`, `OutOfStock`, `SoldOut`, `PreOrder`, or `BackOrder`.
4. **Text Availability Matching**: Regex scanning for `Availability: In Stock` / `Out of Stock`.
5. **DOM Fallbacks**: Checks for `p.stock.out-of-stock` or presence of `button.single_add_to_cart_button`.

---

## 3. Guardrails & Rate-Limiting Numbers

To prevent IP blocks, rate-limiting, and battery drain, StockPing enforces strict guardrails (defined in `Limits.kt` and managed by `HostGate`):
- **Min Gap**: At least **10 seconds** between any two requests to the same host.
- **Hourly Cap**: Maximum **60 requests/hour/host** in a rolling window.
- **Intervals**: Default 180s per cycle; dynamically raised via `Plan.effectiveIntervalSec(maxItemsOnOneHost, userSec)` (e.g., 9 items on one host requires ≥540s to stay under 60 req/h).
- **Backoff & Circuit Breaker**:
  - `429` / `403` responses pause the host for `max(Retry-After, 15 min × consecutiveBlocks)`, capped at 60 min.
  - `5xx` errors / network timeouts use exponential backoff (`min(60 min, 60 s × 2^failures)`).
- **Battery Guard**: Urgent mode automatically stops if battery drops below **20%** (unplugged) or Power Saver mode is enabled.

---

## 4. Permissions Checklist

For reliable full-screen alarm alerts on Android 13/14+, ensure the following permissions are granted:
1. **Notifications (`POST_NOTIFICATIONS`)**: Required to post stock alerts (Android 13+).
2. **Full-Screen Intent (`USE_FULL_SCREEN_INTENT`)**: Required to launch `AlarmActivity` over the lock screen.
3. **Do Not Disturb Override**: Alarms bypass standard DND ("alarms allowed" setting).
4. **Battery Optimization Exemption**: Exclude StockPing from battery restrictions in system settings and enable OEM autostart (Xiaomi, Oppo, Vivo, Realme, OnePlus).

---

## 5. Honest Doze Limits & Reliability

- **Intervals are Minimums**: Even with the foreground service (`WatchService`), Android Doze mode and App Standby can stretch polling intervals when the phone sits unplugged and idle for extended periods.
- **Best Practices**: Keep the phone plugged in overnight during urgent restock watches, and keep battery optimizations disabled.
- **Debug Features**: Includes a debug-only "Fire test alert" button in the top bar to verify full-screen alarm behavior anytime.
