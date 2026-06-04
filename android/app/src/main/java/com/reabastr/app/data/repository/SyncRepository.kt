package com.reabastr.app.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.entity.OutboxEvent
import com.reabastr.app.worker.OutboxWorker
import com.reabastr.app.worker.ReconcileWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the sync outbox and WorkManager scheduling for background sync.
 *
 * Responsibilities:
 * - Enqueue delta events to the outbox (within 1s of local mutation)
 * - Schedule OutboxWorker to drain pending events when connectivity is available
 * - Schedule ReconcileWorker to pull full state on reconnection
 * - Enforce the 500 pending event halt threshold
 */
@Singleton
class SyncRepository @Inject constructor(
    private val outboxDao: OutboxDao,
    private val workManager: WorkManager
) {

    companion object {
        const val OUTBOX_WORK_NAME = "outbox_drain"
        const val RECONCILE_WORK_NAME = "reconcile_sync"
        const val MAX_PENDING_EVENTS = 500
    }

    /**
     * Enqueues a delta event to the sync outbox.
     * Returns false if the outbox has reached the 500-event halt threshold.
     * Schedules the OutboxWorker for background drain on connectivity.
     */
    suspend fun enqueueDeltaEvent(productId: String, delta: Int): EnqueueResult {
        val pendingCount = outboxDao.getPendingCount()
        if (pendingCount >= MAX_PENDING_EVENTS) {
            return EnqueueResult.HaltThresholdReached
        }

        val event = OutboxEvent(
            productId = productId,
            delta = delta,
            timestamp = System.currentTimeMillis()
        )
        outboxDao.insert(event)

        scheduleOutboxDrain()
        return EnqueueResult.Success
    }

    /**
     * Schedules the OutboxWorker with a connectivity constraint.
     * Uses KEEP policy to avoid redundant scheduling if already enqueued.
     */
    fun scheduleOutboxDrain() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            OUTBOX_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * Schedules the ReconcileWorker to pull full state and reconcile cache.
     * Uses REPLACE policy so only the latest reconciliation runs.
     */
    fun scheduleReconciliation() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ReconcileWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            RECONCILE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    /** Observes all active outbox events (pending + failed). */
    fun observeOutboxEvents(): Flow<List<OutboxEvent>> {
        return outboxDao.observeAllActive()
    }

    /** Observes whether the outbox has reached the halt threshold. */
    fun observeHaltThreshold(): Flow<Boolean> {
        return outboxDao.observeAllActive().map { events ->
            events.count { it.status == "PENDING" } >= MAX_PENDING_EVENTS
        }
    }

    /** Returns the current count of pending events. */
    suspend fun getPendingCount(): Int {
        return outboxDao.getPendingCount()
    }

    /** Returns all failed events for display/notification. */
    suspend fun getFailedEvents(): List<OutboxEvent> {
        return outboxDao.getFailedEvents()
    }
}

/** Result of attempting to enqueue a delta event. */
sealed interface EnqueueResult {
    data object Success : EnqueueResult
    data object HaltThresholdReached : EnqueueResult
}
