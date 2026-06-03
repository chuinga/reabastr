package com.reabastr.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val productId: String,
    val householdId: String,
    val name: String,
    val categoryId: String?,
    val idealQty: Int,
    val currentQty: Int,
    val eans: List<String>,
    val lastSyncedAt: Long
)
