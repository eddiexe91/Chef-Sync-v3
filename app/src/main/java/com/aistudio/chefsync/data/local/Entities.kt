package com.aistudio.chefsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: String, // Likely the row index or a unique name
    val name: String,
    val quantity: Double,
    val unit: String,
    val minStock: Double,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "wastes")
data class Waste(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productName: String,
    val quantity: Double,
    val unit: String,
    val reason: String = "Merma",
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)

@Entity(tableName = "sync_logs")
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val action: String,
    val status: String, // SUCCESS, ERROR
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
