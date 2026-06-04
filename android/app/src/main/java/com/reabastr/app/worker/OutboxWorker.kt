package com.reabastr.app.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.entity.OutboxEvent
import com.reabastr.app.data.remote.ApiService
import com.reabastr.app.data.remote.dto.AdjustRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * WorkManager worker that drains the sync outbox chronologically when connectivity
 * is available. Implements exponential backoff (1s, 2s, 4s) for transient failures,
 * marking events as FAILED after 3 retries are exhausted.
 *
 * Connectivity constraint is set by [SyncRepository.scheduleOutboxDrain].
 * Halts processing if >500 pending events (safety valve).
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
        private const val MAX_RETRIES = 3
        private const val MAX_PENDING_EVENTS = 500
        private val BACKOFF_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting outbox drain")

        val pendingEvents = outboxDao.getPendingEvents()
        if (pendingEvents.isEmpty()) {
            Log.d(TAG, "No pending events to drain")
            return Result.success()
        }

        if (pendingEvents.size > MAX_PENDING_EVENTS) {
            Log.w(TAG, "Outbox exceeds $MAX_PENDING_EVENTS events, halting drain")
            return Result.failure()
        }

        var allSucceeded = true

        for (event in pendingEvents) {
            val success = uploadEvent(event)
            if (!success) {
                allSucceeded = false
                // Stop processing further events on network failure to preserve order
                break
            }
        }

        return if (allSucceeded) {
            Log.d(TAG, "Outbox drain complete: all events uploaded")
            Result.success()
        } else {
            Log.w(TAG, "Outbox drain incomplete: will retry on next connectivity")
            Result.retry()
        }
    }

    /**
     * Uploads a single outbox event to the backend with exponential backoff.
     * Returns true if successfully uploaded (event removed from outbox),
     * or false if a transient error occurred and the event should be retried later.
     *
     * After exhausting retries, marks the event as FAILED and continues.
     */
    private suspend fun uploadEvent(event: OutboxEvent): Boolean {
        var currentRetry = event.retryCount

        while (currentRetry < MAX_RETRIES) {
            try {
                val response = apiService.adjust(
                    AdjustRequest(
                        productId = event.productId,
                        delta = event.delta
                    )
                )

                if (response.isSuccessful) {
                    // Successfully uploaded — remove from outbox
                    outboxDao.deleteById(event.id)
                    Log.d(TAG, "Event ${event.id} uploaded successfully")
                    return true
                }

                val code = response.code()
                when {
                    // Client errors (4xx except 429) — mark as failed immediately,
                    // these won't succeed on retry (e.g., 409 INSUFFICIENT_STOCK)
                    code in 400..499 && code != 429 -> {
                        markAsFailed(event, currentRetry)
                        Log.w(TAG, "Event ${event.id} rejected by backend (HTTP $code), marked FAILED")
                        return true // continue with next event
                    }
                    // Throttled or server error — apply backoff
                    else -> {
                        currentRetry++
                        updateRetryCount(event, currentRetry)
                        if (currentRetry < MAX_RETRIES) {
                            val delayMs = BACKOFF_DELAYS_MS[currentRetry - 1]
                            Log.d(TAG, "Event ${event.id} retry $currentRetry, waiting ${delayMs}ms")
                            delay(delayMs)
                        }
                    }
                }
            } catch (e: IOException) {
                // Network error — apply backoff
                currentRetry++
                updateRetryCount(event, currentRetry)
                if (currentRetry < MAX_RETRIES) {
                    val delayMs = BACKOFF_DELAYS_MS[currentRetry - 1]
                    Log.d(TAG, "Event ${event.id} IOException, retry $currentRetry, waiting ${delayMs}ms")
                    delay(delayMs)
                } else {
                    // Network still failing — signal to stop processing
                    markAsFailed(event, currentRetry)
                    Log.w(TAG, "Event ${event.id} exhausted retries due to network error")
                    return false
                }
            } catch (e: Exception) {
                // Unexpected error — mark as failed
                markAsFailed(event, currentRetry)
                Log.e(TAG, "Event ${event.id} unexpected error, marked FAILED", e)
                return true
            }
        }

        // Exhausted all retries
        markAsFailed(event, currentRetry)
        Log.w(TAG, "Event ${event.id} exhausted $MAX_RETRIES retries, marked FAILED")
        return true // continue with next event
    }

    private suspend fun updateRetryCount(event: OutboxEvent, newRetryCount: Int) {
        outboxDao.update(event.copy(retryCount = newRetryCount))
    }

    private suspend fun markAsFailed(event: OutboxEvent, retryCount: Int) {
        outboxDao.update(event.copy(status = "FAILED", retryCount = retryCount))
    }
}
