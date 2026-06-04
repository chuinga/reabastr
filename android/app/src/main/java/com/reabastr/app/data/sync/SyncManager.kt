package com.reabastr.app.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.reabastr.app.R
import com.reabastr.app.data.local.dao.OutboxDao
import com.reabastr.app.data.local.dao.ProductDao
import com.reabastr.app.data.local.entity.OutboxEvent
import com.reabastr.app.worker.OutboxWorker
import com.reabastr.app.worker.ReconcileWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lifecycle-aware manager that coordinates WorkManager scheduling for sync operations.
 *
 * Responsibilities:
 * - Registers OutboxWorker with connectivity constraint (persistent across reboots)
 * - Schedules periodic reconciliation and triggers on app foreground
 * - Monitors sync capacity (>500 pending events) and exposes warning state
 * - Posts notifications when events fail after exhausting retries
 *
 * Requirements: 8.3, 8.4, 8.5, 8.6, 8.7, 8.8
 */
@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val outboxDao: OutboxDao,
    private val productDao: ProductDao
) {

    companion object {
        private const val TAG = "SyncManager"
        const val OUTBOX_WORK_NAME = "outbox_drain"
        const val RECONCILE_ONE_TIME_WORK_NAME = "reconcile_foreground"
        const val RECONCILE_PERIODIC_WORK_NAME = "reconcile_periodic"
        const val NOTIFICATION_CHANNEL_ID = "sync_status"
        const val FAILED_EVENT_NOTIFICATION_ID = 1001
        const val CAPACITY_WARNING_NOTIFICATION_ID = 1002
        const val MAX_PENDING_EVENTS = 500
        private const val PERIODIC_RECONCILE_INTERVAL_MINUTES = 15L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _syncCapacityWarning = MutableStateFlow(false)
    /** True when >500 pending events — UI should show a warning. */
    val syncCapacityWarning: StateFlow<Boolean> = _syncCapacityWarning.asStateFlow()

    private val _failedEvents = MutableStateFlow<List<OutboxEvent>>(emptyList())
    /** Failed events that the user should be notified about. */
    val failedEvents: StateFlow<List<OutboxEvent>> = _failedEvents.asStateFlow()

    /**
     * Initialize the SyncManager. Call this once from Application.onCreate().
     * Sets up notification channel, registers lifecycle observer, and schedules workers.
     */
    fun initialize() {
        createNotificationChannel()
        registerLifecycleObserver()
        schedulePersistedOutboxWorker()
        schedulePeriodicReconciliation()
        monitorOutboxState()
        Log.d(TAG, "SyncManager initialized")
    }

    /**
     * Schedules the OutboxWorker with a CONNECTED network constraint.
     * WorkManager persists work across app restarts and device reboots.
     * Uses KEEP policy — if work is already enqueued/running, don't replace it.
     */
    fun schedulePersistedOutboxWorker() {
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

        Log.d(TAG, "OutboxWorker scheduled with connectivity constraint")
    }

    /**
     * Schedules periodic reconciliation (every 15 min when connected).
     * This ensures the local cache stays reasonably fresh even if the user
     * doesn't explicitly trigger a sync.
     */
    private fun schedulePeriodicReconciliation() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<ReconcileWorker>(
            PERIODIC_RECONCILE_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            RECONCILE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )

        Log.d(TAG, "Periodic ReconcileWorker scheduled (${PERIODIC_RECONCILE_INTERVAL_MINUTES}min)")
    }

    /**
     * Triggers an immediate reconciliation. Called when the app comes to foreground.
     * Uses REPLACE so only the latest foreground reconciliation runs.
     */
    fun triggerForegroundReconciliation() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ReconcileWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            RECONCILE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        // Also re-trigger outbox drain in case there are pending events
        schedulePersistedOutboxWorker()

        Log.d(TAG, "Foreground reconciliation triggered")
    }

    /**
     * Checks the sync capacity and returns whether new events can be enqueued.
     * If >500 pending events, surfaces a capacity warning.
     */
    suspend fun checkSyncCapacity(): Boolean {
        val pendingCount = outboxDao.getPendingCount()
        val isOverCapacity = pendingCount >= MAX_PENDING_EVENTS

        _syncCapacityWarning.value = isOverCapacity

        if (isOverCapacity) {
            Log.w(TAG, "Sync capacity reached: $pendingCount pending events (max $MAX_PENDING_EVENTS)")
            showCapacityWarningNotification()
        }

        return !isOverCapacity
    }

    /**
     * Called when an outbox event has been marked as FAILED (after exhausting retries).
     * Posts a notification to inform the user which product/action failed.
     */
    suspend fun notifyFailedEvent(event: OutboxEvent) {
        val product = productDao.getProductById(event.productId)
        val productName = product?.name ?: "Unknown product"
        val action = if (event.delta > 0) "+${event.delta}" else "${event.delta}"

        showFailedEventNotification(productName, action)

        // Update failed events list
        _failedEvents.value = outboxDao.getFailedEvents()
    }

    /**
     * Monitors the outbox state continuously, updating capacity warning
     * and failed event state.
     */
    private fun monitorOutboxState() {
        scope.launch {
            outboxDao.observeAllActive().collect { events ->
                val pendingCount = events.count { it.status == "PENDING" }
                _syncCapacityWarning.value = pendingCount >= MAX_PENDING_EVENTS

                val failedList = events.filter { it.status == "FAILED" }
                _failedEvents.value = failedList
            }
        }
    }

    /**
     * Registers a ProcessLifecycleOwner observer to detect app foreground transitions.
     * When the app comes to foreground, triggers reconciliation (Req 8.6).
     */
    private fun registerLifecycleObserver() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // App has come to foreground
                Log.d(TAG, "App foregrounded — triggering reconciliation")
                triggerForegroundReconciliation()
            }
        })
    }

    /**
     * Creates the notification channel for sync status notifications.
     * Required for Android 8.0+ (API 26+).
     */
    private fun createNotificationChannel() {
        val name = context.getString(R.string.sync_notification_channel_name)
        val description = context.getString(R.string.sync_notification_channel_description)
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
            this.description = description
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Shows a notification when a sync event has failed.
     * The notification tells the user which product and action could not be synced.
     */
    private fun showFailedEventNotification(productName: String, action: String) {
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.sync_failed_notification_title))
            .setContentText(
                context.getString(R.string.sync_failed_notification_body, action, productName)
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(FAILED_EVENT_NOTIFICATION_ID, notification)
    }

    /**
     * Shows a notification when the outbox exceeds 500 pending events.
     */
    private fun showCapacityWarningNotification() {
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.sync_capacity_warning_title))
            .setContentText(context.getString(R.string.sync_capacity_warning_message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(CAPACITY_WARNING_NOTIFICATION_ID, notification)
    }

    /**
     * Checks if the app has notification permission (required on Android 13+).
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
