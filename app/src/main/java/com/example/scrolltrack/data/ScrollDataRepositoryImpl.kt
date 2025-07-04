package com.example.scrolltrack.data

import android.content.Context
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.example.scrolltrack.db.AppDatabase
import com.example.scrolltrack.db.DailyAppUsageDao
import com.example.scrolltrack.db.DailyDeviceSummaryDao
import com.example.scrolltrack.db.NotificationDao
import com.example.scrolltrack.db.RawAppEventDao
import com.example.scrolltrack.db.ScrollSessionDao
import com.example.scrolltrack.db.DailyAppUsageRecord
import com.example.scrolltrack.db.DailyDeviceSummary
import com.example.scrolltrack.db.RawAppEvent
import com.example.scrolltrack.db.ScrollSessionRecord
import com.example.scrolltrack.db.AppScrollDataPerDate
import com.example.scrolltrack.di.IoDispatcher
import com.example.scrolltrack.util.AppConstants
import com.example.scrolltrack.util.DateUtil
import com.example.scrolltrack.util.PermissionUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScrollDataRepositoryImpl @Inject constructor(
    private val appDatabase: AppDatabase,
    private val appMetadataRepository: AppMetadataRepository,
    private val scrollSessionDao: ScrollSessionDao,
    private val dailyAppUsageDao: DailyAppUsageDao,
    private val rawAppEventDao: RawAppEventDao,
    private val notificationDao: NotificationDao,
    private val dailyDeviceSummaryDao: DailyDeviceSummaryDao,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ScrollDataRepository {

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("ScrollTrackRepoPrefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_LAST_SYSTEM_EVENT_SYNC_TIMESTAMP = "last_system_event_sync_timestamp"
    }

    override suspend fun syncSystemEvents(): Boolean = withContext(ioDispatcher) {
        if (!PermissionUtils.hasUsageStatsPermission(context)) {
            Timber.w("Cannot sync system events, Usage Stats permission not granted.")
            return@withContext false
        }

        val lastSyncTimestamp = prefs.getLong(KEY_LAST_SYSTEM_EVENT_SYNC_TIMESTAMP, 0L)
        val currentTime = System.currentTimeMillis()
        val startTime = if (lastSyncTimestamp == 0L) currentTime - TimeUnit.DAYS.toMillis(1) else lastSyncTimestamp + 1

        Timber.d("Syncing system events from $startTime to $currentTime")
        try {
            val usageEvents = usageStatsManager.queryEvents(startTime, currentTime)
            val eventsToInsert = mutableListOf<RawAppEvent>()
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                mapUsageEventToRawAppEvent(event)?.let { eventsToInsert.add(it) }
            }

            if (eventsToInsert.isNotEmpty()) {
                rawAppEventDao.insertEvents(eventsToInsert)
                Timber.i("Inserted ${eventsToInsert.size} new system events.")
            } else {
                Timber.i("No new system events to insert.")
            }

            prefs.edit().putLong(KEY_LAST_SYSTEM_EVENT_SYNC_TIMESTAMP, currentTime).apply()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync system events.")
            false
        }
    }

    override suspend fun processAndSummarizeDate(dateString: String) = withContext(ioDispatcher) {
        Timber.d("Starting processing for date: $dateString")
        val startTime = DateUtil.getStartOfDayUtcMillis(dateString)
        val endTime = DateUtil.getEndOfDayUtcMillis(dateString)
        val events = rawAppEventDao.getEventsForPeriod(startTime, endTime)

        if (events.isEmpty()) {
            Timber.i("No events for $dateString. Clearing summary data.")
            appDatabase.withTransaction {
                dailyAppUsageDao.deleteUsageForDate(dateString)
                scrollSessionDao.deleteSessionsForDate(dateString)
                dailyDeviceSummaryDao.deleteSummaryForDate(dateString)
            }
            return@withContext
        }

        val filterSet = buildFilterSet()
        val scrollSessions = processScrollEvents(events, filterSet)
        val (usageRecords, deviceSummary) = processUsageAndDeviceSummary(events, filterSet, dateString)

        appDatabase.withTransaction {
            dailyAppUsageDao.deleteUsageForDate(dateString)
            scrollSessionDao.deleteSessionsForDate(dateString)
            dailyDeviceSummaryDao.deleteSummaryForDate(dateString)

            if (scrollSessions.isNotEmpty()) scrollSessionDao.insertSessions(scrollSessions)
            if (usageRecords.isNotEmpty()) dailyAppUsageDao.insertAllUsage(usageRecords)
            deviceSummary?.let { dailyDeviceSummaryDao.insertOrUpdate(it) }
        }
        Timber.i("Finished processing for date: $dateString. Saved ${scrollSessions.size} scroll sessions, ${usageRecords.size} usage records.")
    }

    private suspend fun buildFilterSet(): Set<String> {
        return appMetadataRepository.getAllMetadata()
            .filter { metadata -> metadata.userHidesOverride ?: !metadata.isUserVisible }
            .map { it.packageName }
            .toMutableSet()
            .apply {
                add(context.packageName)
                add("com.android.systemui")
            }
    }

    private fun processScrollEvents(events: List<RawAppEvent>, filterSet: Set<String>): List<ScrollSessionRecord> {
        val scrollEvents = events
            .filter { it.eventType == RawAppEvent.EVENT_TYPE_SCROLL && it.value != null && it.packageName !in filterSet }
            .sortedBy { it.eventTimestamp }

        if (scrollEvents.isEmpty()) return emptyList()

        val mergedSessions = mutableListOf<ScrollSessionRecord>()
        var currentSession: ScrollSessionRecord? = null

        for (event in scrollEvents) {
            if (currentSession == null) {
                currentSession = ScrollSessionRecord(
                    packageName = event.packageName,
                    sessionStartTime = event.eventTimestamp,
                    sessionEndTime = event.eventTimestamp,
                    scrollAmount = event.value!!,
                    dateString = event.eventDateString,
                    sessionEndReason = "PROCESSED",
                    dataType = "MEASURED"
                )
            } else {
                val timeDiff = event.eventTimestamp - currentSession.sessionEndTime
                if (event.packageName == currentSession.packageName && timeDiff <= AppConstants.SESSION_MERGE_GAP_MS) {
                    currentSession = currentSession.copy(
                        sessionEndTime = event.eventTimestamp,
                        scrollAmount = currentSession.scrollAmount + event.value!!
                    )
                } else {
                    mergedSessions.add(currentSession)
                    currentSession = ScrollSessionRecord(
                        packageName = event.packageName,
                        sessionStartTime = event.eventTimestamp,
                        sessionEndTime = event.eventTimestamp,
                        scrollAmount = event.value!!,
                        dateString = event.eventDateString,
                        sessionEndReason = "PROCESSED",
                        dataType = "MEASURED"
                    )
                }
            }
        }
        currentSession?.let { mergedSessions.add(it) }
        return mergedSessions
    }

    private suspend fun processUsageAndDeviceSummary(events: List<RawAppEvent>, filterSet: Set<String>, dateString: String): Pair<List<DailyAppUsageRecord>, DailyDeviceSummary?> {
        val endTime = DateUtil.getEndOfDayUtcMillis(dateString)
        val filteredEvents = events.filter { it.packageName !in filterSet }

        val usageAggregates = aggregateUsage(filteredEvents, endTime)
        val appOpens = calculateAppOpens(filteredEvents)
        val notifications = calculateAccurateNotificationCounts(filteredEvents)
        val totalNotifications = notifications.values.sum()

        val unlockEvents = filteredEvents.filter { it.eventType == RawAppEvent.EVENT_TYPE_USER_PRESENT || it.eventType == RawAppEvent.EVENT_TYPE_KEYGUARD_HIDDEN }
        val totalUnlocks = unlockEvents.size
        val firstUnlockTime = unlockEvents.minOfOrNull { it.eventTimestamp }
        val lastUnlockTime = unlockEvents.maxOfOrNull { it.eventTimestamp }

        val allPackages = usageAggregates.keys.union(appOpens.keys).union(notifications.keys)
        val usageRecords = allPackages.mapNotNull { pkg ->
            val (usage, active) = usageAggregates[pkg] ?: (0L to 0L)
            if (usage < AppConstants.MINIMUM_SIGNIFICANT_SESSION_DURATION_MS) null
            else DailyAppUsageRecord(
                packageName = pkg,
                dateString = dateString,
                usageTimeMillis = usage,
                activeTimeMillis = active,
                appOpenCount = appOpens.getOrDefault(pkg, 0),
                notificationCount = notifications.getOrDefault(pkg, 0),
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        }

        val deviceSummary = DailyDeviceSummary(
            dateString = dateString,
            totalUsageTimeMillis = usageRecords.sumOf { it.usageTimeMillis },
            totalUnlockCount = totalUnlocks,
            firstUnlockTimestampUtc = firstUnlockTime,
            lastUnlockTimestampUtc = lastUnlockTime,
            totalNotificationCount = totalNotifications,
            totalAppOpens = appOpens.values.sum(),
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        return Pair(usageRecords, deviceSummary)
    }

    override fun getLiveSummaryForDate(dateString: String): Flow<DailyDeviceSummary> {
        val startOfDayUTC = DateUtil.getStartOfDayUtcMillis(dateString)
        val endOfDayUTC = DateUtil.getEndOfDayUtcMillis(dateString)

        return rawAppEventDao.getEventsForPeriodFlow(startOfDayUTC, endOfDayUTC)
            .distinctUntilChanged()
            .map { events ->
                Timber.d("Live summary triggered for $dateString. Processing ${events.size} events.")
                val (_, summary) = processUsageAndDeviceSummary(events, buildFilterSet(), dateString)
                summary ?: DailyDeviceSummary(dateString = dateString) // Return default if null
            }
    }

    private fun isFiltered(packageName: String, filterSet: Set<String>): Boolean {
        return filterSet.contains(packageName)
    }

    override fun getTotalScrollForDate(dateString: String): Flow<Long?> = scrollSessionDao.getTotalScrollForDate(dateString)
    override fun getTotalUsageTimeMillisForDate(dateString: String): Flow<Long?> = dailyAppUsageDao.getTotalUsageTimeMillisForDate(dateString)
    override fun getAppUsageForDate(dateString: String): Flow<List<DailyAppUsageRecord>> = dailyAppUsageDao.getUsageForDate(dateString)
    override suspend fun getUsageForPackageAndDates(packageName: String, dateStrings: List<String>): List<DailyAppUsageRecord> = dailyAppUsageDao.getUsageForPackageAndDates(packageName, dateStrings)
    override suspend fun getAggregatedScrollForPackageAndDates(packageName: String, dateStrings: List<String>): List<AppScrollDataPerDate> = scrollSessionDao.getAggregatedScrollForPackageAndDates(packageName, dateStrings)
    override fun getAllDistinctUsageDateStrings(): Flow<List<String>> = dailyAppUsageDao.getAllDistinctUsageDateStrings()
    override fun getTotalUnlockCountForDate(dateString: String): Flow<Int> = dailyDeviceSummaryDao.getUnlockCountForDate(dateString).map { it ?: 0 }
    override fun getTotalNotificationCountForDate(dateString: String): Flow<Int> = dailyDeviceSummaryDao.getNotificationCountForDate(dateString).map { it ?: 0 }
    override fun getDeviceSummaryForDate(dateString: String): Flow<DailyDeviceSummary?> = dailyDeviceSummaryDao.getSummaryForDate(dateString)
    override fun getScrollDataForDate(dateString: String): Flow<List<AppScrollData>> = scrollSessionDao.getScrollDataForDate(dateString)

    override fun getUsageRecordsForDateRange(startDateString: String, endDateString: String): Flow<List<DailyAppUsageRecord>> =
        dailyAppUsageDao.getUsageRecordsForDateRange(startDateString, endDateString)

    override fun getAllDeviceSummaries(): Flow<List<DailyDeviceSummary>> = dailyDeviceSummaryDao.getAllSummaries()

    override fun getNotificationSummaryForPeriod(startDateString: String, endDateString: String): Flow<List<NotificationSummary>> = notificationDao.getNotificationSummaryForPeriod(startDateString, endDateString)
    override fun getNotificationCountPerAppForPeriod(startDateString: String, endDateString: String): Flow<List<NotificationCountPerApp>> = notificationDao.getNotificationCountPerAppForPeriod(startDateString, endDateString)

    private suspend fun calculateAppOpens(events: List<RawAppEvent>): Map<String, Int> {
        val resumeEvents = events
            .filter { it.eventType == RawAppEvent.EVENT_TYPE_ACTIVITY_RESUMED }
            .sortedBy { it.eventTimestamp }

        if (resumeEvents.isEmpty()) return emptyMap()

        val appOpenTimestamps = mutableMapOf<String, Long>()
        val appOpens = mutableMapOf<String, Int>()

        resumeEvents.forEach { event ->
            val lastOpenTimestamp = appOpenTimestamps[event.packageName] ?: 0L
            if (event.eventTimestamp - lastOpenTimestamp > AppConstants.CONTEXTUAL_APP_OPEN_DEBOUNCE_MS) {
                appOpens[event.packageName] = appOpens.getOrDefault(event.packageName, 0) + 1
            }
            appOpenTimestamps[event.packageName] = event.eventTimestamp
        }
        return appOpens
    }

    private fun calculateAccurateNotificationCounts(events: List<RawAppEvent>): Map<String, Int> {
        val notificationEvents = events.filter { it.eventType == RawAppEvent.EVENT_TYPE_NOTIFICATION_POSTED }
        return notificationEvents.groupingBy { it.packageName }.eachCount()
    }

    private fun mapUsageEventToRawAppEvent(event: UsageEvents.Event): RawAppEvent? {
        val internalEventType = when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> RawAppEvent.EVENT_TYPE_ACTIVITY_RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> RawAppEvent.EVENT_TYPE_ACTIVITY_PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> RawAppEvent.EVENT_TYPE_ACTIVITY_STOPPED
            UsageEvents.Event.USER_INTERACTION -> RawAppEvent.EVENT_TYPE_USER_INTERACTION
            UsageEvents.Event.SCREEN_INTERACTIVE -> RawAppEvent.EVENT_TYPE_SCREEN_INTERACTIVE
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> RawAppEvent.EVENT_TYPE_SCREEN_NON_INTERACTIVE
            UsageEvents.Event.KEYGUARD_SHOWN -> RawAppEvent.EVENT_TYPE_KEYGUARD_SHOWN
            UsageEvents.Event.KEYGUARD_HIDDEN -> RawAppEvent.EVENT_TYPE_KEYGUARD_HIDDEN
            12 -> RawAppEvent.EVENT_TYPE_USER_PRESENT // For USER_PRESENT on API < 33
            else -> -1
        }
        if (internalEventType == -1) return null

        return RawAppEvent(
            packageName = event.packageName,
            className = event.className,
            eventType = internalEventType,
            eventTimestamp = event.timeStamp,
            eventDateString = DateUtil.formatUtcTimestampToLocalDateString(event.timeStamp),
            source = RawAppEvent.SOURCE_USAGE_STATS
        )
    }

    private suspend fun aggregateUsage(allEvents: List<RawAppEvent>, periodEndDate: Long): Map<String, Pair<Long, Long>> {
        data class Session(val pkg: String, val startTime: Long, val endTime: Long)
        val sessions = mutableListOf<Session>()
        val foregroundAppStartTimes = mutableMapOf<String, Long>()

        allEvents.forEachIndexed { index, event ->
            val pkg = event.packageName
            when (event.eventType) {
                RawAppEvent.EVENT_TYPE_ACTIVITY_RESUMED -> {
                    if (!foregroundAppStartTimes.containsKey(pkg)) {
                        foregroundAppStartTimes[pkg] = event.eventTimestamp
                    }
                }
                RawAppEvent.EVENT_TYPE_ACTIVITY_PAUSED, RawAppEvent.EVENT_TYPE_ACTIVITY_STOPPED -> {
                    foregroundAppStartTimes.remove(pkg)?.let { startTime ->
                        val nextEvent = allEvents.getOrNull(index + 1)
                        val endTime = if (nextEvent?.eventType == RawAppEvent.EVENT_TYPE_ACTIVITY_RESUMED) {
                            nextEvent.eventTimestamp - 1
                        } else {
                            event.eventTimestamp
                        }
                        if (endTime > startTime) sessions.add(Session(pkg, startTime, endTime))
                    }
                }
                RawAppEvent.EVENT_TYPE_SCREEN_NON_INTERACTIVE -> {
                    foregroundAppStartTimes.keys.toList().forEach { runningPkg ->
                        foregroundAppStartTimes.remove(runningPkg)?.let { startTime ->
                            if (event.eventTimestamp > startTime) {
                                sessions.add(Session(runningPkg, startTime, event.eventTimestamp))
                            }
                        }
                    }
                }
            }
        }

        foregroundAppStartTimes.forEach { (pkg, startTime) ->
            if (periodEndDate > startTime) sessions.add(Session(pkg, startTime, periodEndDate))
        }

        val aggregator = mutableMapOf<String, Pair<Long, Long>>()
        val interactionEventsByPackage = allEvents
            .filter { RawAppEvent.isAccessibilityEvent(it.eventType) }
            .groupBy { it.packageName }

        sessions.forEach { session ->
            val usageTime = session.endTime - session.startTime
            val sessionInteractionTimestamps = interactionEventsByPackage[session.pkg]
                ?.map { it.eventTimestamp }
                ?.filter { it in session.startTime..session.endTime }
                ?.sorted() ?: emptyList()

            val activeTime = calculateActiveTimeFromInteractions(sessionInteractionTimestamps, session.startTime, session.endTime)
            val (currentUsage, currentActive) = aggregator.getOrDefault(session.pkg, 0L to 0L)
            aggregator[session.pkg] = (currentUsage + usageTime) to (currentActive + activeTime)
        }
        return aggregator
    }

    private fun calculateActiveTimeFromInteractions(timestamps: List<Long>, sessionStart: Long, sessionEnd: Long): Long {
        if (timestamps.isEmpty()) return 0L
        val intervals = timestamps.map { it to it + AppConstants.ACTIVE_TIME_INTERACTION_WINDOW_MS }
        val merged = mutableListOf<Pair<Long, Long>>()
        merged.add(intervals.first())
        for (i in 1 until intervals.size) {
            val (lastStart, lastEnd) = merged.last()
            val (currentStart, currentEnd) = intervals[i]
            if (currentStart < lastEnd) {
                merged[merged.size - 1] = lastStart to maxOf(lastEnd, currentEnd)
            } else {
                merged.add(intervals[i])
            }
        }
        return merged.sumOf { (start, end) ->
            (minOf(end, sessionEnd) - maxOf(start, sessionStart)).coerceAtLeast(0L)
        }
    }

    override suspend fun backfillHistoricalAppUsageData(numberOfDays: Int): Boolean = withContext(ioDispatcher) {
        Timber.i("Starting historical backfill for $numberOfDays days.")
        val today = DateUtil.getCurrentLocalDateString()
        for (i in 0 until numberOfDays) {
            val date = DateUtil.getPastDateString(i)
            if (date == today) continue
            try {
                processAndSummarizeDate(date)
            } catch (e: Exception) {
                Timber.e(e, "Error during backfill for date: $date")
                return@withContext false
            }
        }
        Timber.i("Historical backfill completed.")
        true
    }
} 