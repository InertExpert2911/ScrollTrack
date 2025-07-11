package com.example.scrolltrack

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.scrolltrack.db.RawAppEvent
import com.example.scrolltrack.db.RawAppEventDao
import com.example.scrolltrack.util.DateUtil
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.abs
import com.example.scrolltrack.util.AppConstants
import kotlin.math.sqrt
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.scrolltrack.services.InferredScrollWorker
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class ScrollTrackService : AccessibilityService() {
    private val TAG = "ScrollTrackService"
    private val SERVICE_NOTIFICATION_ID = 1

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Inject
    lateinit var rawAppEventDao: RawAppEventDao

    // Allow injecting WorkManager for testing
    @Inject
    lateinit var workManager: WorkManager

    private var currentForegroundPackage: String? = null
    private val lastTypingEventTimestamp = ConcurrentHashMap<String, Long>()
    private val TYPING_DEBOUNCE_MS = 3000L // 3 seconds

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                Log.d(TAG, "ACTION_USER_UNLOCKED received in-service, logging event.")
                logRawEvent(
                    packageName = "android.system.unlock", // Generic package for system events
                    className = null,
                    eventType = RawAppEvent.EVENT_TYPE_USER_UNLOCKED,
                    value = null
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScrollTrackService onCreate.")
        startForeground(SERVICE_NOTIFICATION_ID, createServiceNotification())
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        registerReceiver(unlockReceiver, filter)
    }

    private fun createServiceNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, ScrollTrackApplication.SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ScrollTrack is running")
            .setContentText("Actively monitoring scroll events.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100 // ms
        }
        this.serviceInfo = info
        Log.i(TAG, "Accessibility service connected and configured.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChange(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleMeasuredScroll(event, packageName)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleInferredScroll(packageName)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleGenericEvent(event, RawAppEvent.EVENT_TYPE_ACCESSIBILITY_VIEW_CLICKED, packageName)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleGenericEvent(event, RawAppEvent.EVENT_TYPE_ACCESSIBILITY_VIEW_FOCUSED, packageName)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTypingEvent(event, packageName)
        }
    }

    private fun handleWindowStateChange(event: AccessibilityEvent) {
        val sourceNode = event.source
        if (sourceNode != null && sourceNode.isFocused) {
            currentForegroundPackage = sourceNode.packageName?.toString()
            Log.d(TAG, "Foreground package updated to: $currentForegroundPackage")
        }
        sourceNode?.recycle()
    }

    private fun handleMeasuredScroll(event: AccessibilityEvent, packageName: String) {
        if (!isNodeScrollable(event.source)) return

        if (event.scrollDeltaX == 0 && event.scrollDeltaY == 0) return

        val activePackage = currentForegroundPackage ?: packageName
        logRawEvent(
            activePackage,
            event.className?.toString(),
            RawAppEvent.EVENT_TYPE_SCROLL_MEASURED,
            null, // The 'value' field is no longer needed for scroll
            event.scrollDeltaX,
            event.scrollDeltaY
        )
    }

    private fun handleTypingEvent(event: AccessibilityEvent, packageName: String) {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastTypingEventTimestamp[packageName] ?: 0L

        if (currentTime - lastTime > TYPING_DEBOUNCE_MS) {
            val activePackage = currentForegroundPackage ?: packageName
            logRawEvent(
                activePackage,
                event.className?.toString(),
                RawAppEvent.EVENT_TYPE_ACCESSIBILITY_TYPING,
                null
            )
            lastTypingEventTimestamp[packageName] = currentTime
        }
    }

    private fun handleGenericEvent(event: AccessibilityEvent, eventType: Int, packageName: String) {
        val activePackage = currentForegroundPackage ?: packageName
        logRawEvent(
            activePackage,
            event.className?.toString(),
            eventType,
            null
        )
    }

    private fun handleInferredScroll(packageName: String) {
        val workName = "${InferredScrollWorker.WORK_NAME_PREFIX}$packageName"

        val workRequest = OneTimeWorkRequestBuilder<InferredScrollWorker>()
            .setInputData(workDataOf(InferredScrollWorker.KEY_PACKAGE_NAME to packageName))
            .setInitialDelay(AppConstants.INFERRED_SCROLL_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE, // This is the key to debouncing
            workRequest
        )
    }

    internal fun isNodeScrollable(node: AccessibilityNodeInfo?): Boolean {
        var currentNode = node
        var depth = 0
        while (currentNode != null && depth < 10) {
            if (currentNode.isScrollable) {
                currentNode.recycle()
                return true
            }
            val parent = currentNode.parent
            currentNode.recycle()
            currentNode = parent
            depth++
        }
        return false
    }

    private fun logRawEvent(
        packageName: String,
        className: String?,
        eventType: Int,
        value: Long?,
        scrollDeltaX: Int? = null,
        scrollDeltaY: Int? = null
    ) {
        serviceScope.launch {
            val eventToStore = RawAppEvent(
                packageName = packageName,
                className = className,
                eventType = eventType,
                eventTimestamp = System.currentTimeMillis(),
                eventDateString = DateUtil.getCurrentLocalDateString(),
                source = RawAppEvent.SOURCE_ACCESSIBILITY,
                value = value,
                scrollDeltaX = scrollDeltaX,
                scrollDeltaY = scrollDeltaY
            )
            rawAppEventDao.insertEvent(eventToStore)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(unlockReceiver)
        serviceJob.cancel()
        Log.d(TAG, "ScrollTrackService destroyed.")
    }
}