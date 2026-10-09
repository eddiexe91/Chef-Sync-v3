package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE name LIKE :name LIMIT 1")
    suspend fun getProductByName(name: String): Product?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<Product>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: Product)
}

@Dao
interface WasteDao {
    @Query("SELECT * FROM wastes WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedWastes(): List<Waste>

    @Insert
    suspend fun insertWaste(waste: Waste)

    @Query("UPDATE wastes SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: Int)
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT 50")
    fun getRecentLogs(): Flow<List<SyncLog>>

    @Insert
    suspend fun insertLog(log: SyncLog)
}
