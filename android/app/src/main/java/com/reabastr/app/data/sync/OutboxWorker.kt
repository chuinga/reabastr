package com.reabastr.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.entity.OutboxEvent
import com.reabastr.app.data.remote.ApiService
import com.reabastr.app.data.remote.dto.SyncBatchEvent
import com.reabastr.app.data.remote.dto.SyncBatchRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WorkManager worker that drains the sync outbox when connectivity is available.
 *
 * Behavior:
 * - Reads pending events in chronological order (oldest first)
 * - Halts if pending count exceeds 500 (capacity warning)
 * - Uploads events via POST /sync/batch
 * - On success: removes the event from the outbox
 * - On transient failure: retries up to 3 times with exponential backoff (1s, 2s, 4s)
 * - After 3 retries: marks the event as FAILED
 * - Persists across app restarts (WorkManager + Room guarantee)
 */
@HiltWorker
class OutboxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val apiService: ApiService
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OutboxWorker"
        private const val MAX_PENDING_EVENTS = 500
        private const val MAX_RETRIES = 3
        private val BACKOFF_DELAYS_MS = longArrayOf(1000L, 2000L, 4000L)
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "OutboxWorker started")

        val pendingCount = outboxDao.getPendingCount()
        if (pendingCount > MAX_PENDING_EVENTS) {
            Log.w(TAG, "Outbox capacity exceeded ($pendingCount events). Halting.")
            return Result.success()
        }

        val pendingEvents = outboxDao.getPendingEvents()
        if (pendingEvents.isEmpty()) {
            Log.d(TAG, "No pending events to drain.")
            return Result.success()
        }

        Log.d(TAG, "Draining ${pendingEvents.size} pending events")

        for (event in pendingEvents) {
            val success = processEvent(event)
            if (!success) {
                // If the event failed after retries, continue to next event
                // (already marked FAILED in processEvent)
                continue
            }
        }

        return Result.success()
    }

    /**
     * Processes a single outbox event with retry logic.
     * Returns true if the event was successfully uploaded and removed.
     * Returns false if the event was marked as FAILED.
     */
    private suspend fun processEvent(event: OutboxEvent): Boolean {
        var currentRetry = event.retryCount

        while (currentRetry < MAX_RETRIES) {
            try {
                val response = apiService.syncBatch(
                    SyncBatchRequest(
                        events = listOf(
                            SyncBatchEvent(
                                productId = event.productId,
                                delta = event.delta,
                                timestamp = formatTimestamp(event.timestamp)
                            )
                        )
                    )
                )

                if (response.isSuccessful) {
                    val body = response.body()
                    val result = body?.results?.firstOrNull()

                    if (result?.success == true) {
                        // Successfully synced — remove from outbox
                        outboxDao.deleteById(event.id)
                        Log.d(TAG, "Event ${event.id} synced successfully")
                        return true
                    } else {
                        // Backend rejected the event (e.g., insufficient stock)
                        // Mark as FAILED — this is a permanent failure
                        markFailed(event)
                        Log.w(TAG, "Event ${event.id} rejected by backend: ${result?.error}")
                        return false
                    }
                } else if (response.code() in 400..499 && response.code() != 429) {
                    // Client error (not retryable, except 429)
                    markFailed(event)
                    Log.w(TAG, "Event ${event.id} got ${response.code()}, marking FAILED")
                    return false
                } else {
                    // Server error or 429 — retryable
                    currentRetry++
                    if (currentRetry < MAX_RETRIES) {
                        val delay = BACKOFF_DELAYS_MS[currentRetry - 1]
                        Log.d(TAG, "Event ${event.id} retry $currentRetry, backing off ${delay}ms")
                        outboxDao.update(event.copy(retryCount = currentRetry))
                        kotlinx.coroutines.delay(delay)
                    }
                }
            } catch (e: IOException) {
                // Network error — retryable
                currentRetry++
                if (currentRetry < MAX_RETRIES) {
                    val delay = BACKOFF_DELAYS_MS[currentRetry - 1]
                    Log.d(TAG, "Event ${event.id} IOException, retry $currentRetry, backing off ${delay}ms")
                    outboxDao.update(event.copy(retryCount = currentRetry))
                    kotlinx.coroutines.delay(delay)
                }
            } catch (e: Exception) {
                // Unexpected error — mark failed immediately
                markFailed(event)
                Log.e(TAG, "Event ${event.id} unexpected error", e)
                return false
            }
        }

        // Exhausted all retries
        markFailed(event)
        Log.w(TAG, "Event ${event.id} exhausted $MAX_RETRIES retries, marking FAILED")
        return false
    }

    private suspend fun markFailed(event: OutboxEvent) {
        outboxDao.update(event.copy(status = "FAILED", retryCount = MAX_RETRIES))
    }

    private fun formatTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }
}
