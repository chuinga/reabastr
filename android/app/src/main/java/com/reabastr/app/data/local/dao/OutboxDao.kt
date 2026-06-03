package com.reabastr.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.reabastr.app.data.local.entity.OutboxEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Query("SELECT * FROM sync_outbox WHERE status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingEvents(): List<OutboxEvent>

    @Query("SELECT * FROM sync_outbox WHERE status = 'FAILED' ORDER BY timestamp ASC")
    suspend fun getFailedEvents(): List<OutboxEvent>

    @Query("SELECT * FROM sync_outbox WHERE status IN ('PENDING', 'FAILED') ORDER BY timestamp ASC")
    fun observeAllActive(): Flow<List<OutboxEvent>>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    @Query("SELECT * FROM sync_outbox WHERE productId = :productId AND status = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getPendingEventsForProduct(productId: String): List<OutboxEvent>

    @Insert
    suspend fun insert(event: OutboxEvent): Long

    @Update
    suspend fun update(event: OutboxEvent)

    @Query("DELETE FROM sync_outbox WHERE id = :eventId")
    suspend fun deleteById(eventId: Long)

    @Query("DELETE FROM sync_outbox WHERE status = 'PENDING'")
    suspend fun deleteAllPending()
}
