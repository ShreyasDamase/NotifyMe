package com.org.notifyme.data

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
