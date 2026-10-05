package com.org.notifyme.data

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
