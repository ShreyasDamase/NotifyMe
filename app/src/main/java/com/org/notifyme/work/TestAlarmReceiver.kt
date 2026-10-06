package com.org.notifyme.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.org.notifyme.StockPingApp
import com.org.notifyme.data.StockStatus
import com.org.notifyme.data.WatchedProduct

class TestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.org.notifyme.TEST_ALARM") {
            val app = context.applicationContext as? StockPingApp
            app?.container?.notifier?.notifyUrgent(
                WatchedProduct(
                    id = intent.getLongExtra("notification_id", 888L),
                    url = "https://robu.in/product/n25-6v-115rpm-metal-gear-motor-with-encoder-d-type/",
                    slug = "test-alarm-motor",
                    name = "Test Alarm Motor (In Stock)",
                    priceText = "₹450",
                    lastStatus = StockStatus.OUT_OF_STOCK
                )
            )
        }
    }
}
