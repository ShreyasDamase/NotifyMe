package com.org.notifyme.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.org.notifyme.alarm.AlarmActivity
import com.org.notifyme.data.WatchedProduct

class Notifier(private val ctx: Context) {

    fun ensureChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

        nm.createNotificationChannel(NotificationChannel(CH_URGENT, "URGENT: back in stock", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(alarmUri, attrs)
            enableVibration(true)
            // A sustained alert (about 16 seconds) so a restock is harder to miss.
            vibrationPattern = longArrayOf(
                0, 1_000, 350, 1_000, 350, 1_000, 350,
                1_000, 350, 1_000, 350, 1_000, 350,
                1_000, 350, 1_000, 350, 1_000, 350,
                1_000, 350, 1_000, 350, 1_000,
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Watching", NotificationManager.IMPORTANCE_LOW))
    }

    fun hasDndAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < 23 ||
            (ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true)

    @SuppressLint("MissingPermission")
    fun notifyUrgent(p: WatchedProduct) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(ctx, p.id.toInt(), Intent(Intent.ACTION_VIEW, p.url.toUri()), flags)
        val full = PendingIntent.getActivity(
            ctx, 10_000 + p.id.toInt(),
            Intent(ctx, AlarmActivity::class.java).putExtra("name", p.name).putExtra("url", p.url)
                .putExtra("price", p.priceText).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)

        val n = NotificationCompat.Builder(ctx, CH_URGENT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("IN STOCK — BUY NOW")
            .setContentText(listOfNotNull(p.name, p.priceText).joinToString(" · "))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true)
            .setContentIntent(open)
            .addAction(0, "OPEN PRODUCT", open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(p.id.toInt(), n)
    }

    fun notifyWatchStopped(reason: String) {
        // Handled silently or in-app; do not spam push notifications
    }

    companion object {
        // A new ID is required so Android applies the updated vibration pattern to existing installs.
        const val CH_URGENT = "stock_urgent_v2"
        const val CH_WATCH = "stock_watch"
    }
}
