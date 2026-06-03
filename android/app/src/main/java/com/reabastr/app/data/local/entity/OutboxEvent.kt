package com.reabastr.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_outbox")
data class OutboxEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: String,
    val delta: Int,
    val timestamp: Long,
    val retryCount: Int = 0,
    val status: String = "PENDING"
)
