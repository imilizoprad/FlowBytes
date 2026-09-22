// Bridges the pure analytics core to real device data.
//
// Responsibilities:
//   1. Backfill / incrementally ingest completed hours from NetworkStatsManager into Room,
//      so the app keeps history long after Android has discarded its own.
//   2. Turn that history plus the live period into a single Insights snapshot for the UI.
//
// Every NetworkStats read goes through NetworkStatsCache, so ingestion of already-settled hours
// costs nothing on repeat runs.
package com.ray.flowmeter.analytics

import android.content.Context
import android.net.NetworkCapabilities
import com.ray.flowmeter.data.FlowMeterDatabase
import com.ray.flowmeter.data.UsageHour
import com.ray.flowmeter.data.UsageHourDao
import com.ray.flowmeter.utils.NetworkStatsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Everything the UI needs to render the intelligence card, computed in one pass. */
data class Insights(
    val dailyForecast: Forecast?,
    val monthlyForecast: Forecast?,
    val hourlyProfile: DoubleArray,
    val busiestHour: Int?,
    val quietestHour: Int?,
    val anomalies: List<Anomaly>,
    val backgroundLeakBytes: Long,
    val suggestedDailyLimitBytes: Long?,
    val suggestedMonthlyLimitBytes: Long?,
    val weekdayAverageBytes: Long,
    val weekendAverageBytes: Long,
    val trendPercent: Int,
    val sampleDays: Int,
) {
    // Generated equals/hashCode would use array identity; override so Compose can diff correctly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Insights) return false
        return dailyForecast == other.dailyForecast &&
            monthlyForecast == other.monthlyForecast &&
            hourlyProfile.contentEquals(other.hourlyProfile) &&
            busiestHour == other.busiestHour &&
            quietestHour == other.quietestHour &&
            anomalies == other.anomalies &&
            backgroundLeakBytes == other.backgroundLeakBytes &&
            suggestedDailyLimitBytes == other.suggestedDailyLimitBytes &&
            suggestedMonthlyLimitBytes == other.suggestedMonthlyLimitBytes &&
            weekdayAverageBytes == other.weekdayAverageBytes &&
            weekendAverageBytes == other.weekendAverageBytes &&
            trendPercent == other.trendPercent &&
            sampleDays == other.sampleDays
    }

    override fun hashCode(): Int {
        var result = dailyForecast?.hashCode() ?: 0
        result = 31 * result + (monthlyForecast?.hashCode() ?: 0)
        result = 31 * result + hourlyProfile.contentHashCode()
        result = 31 * result + (busiestHour ?: -1)
        result = 31 * result + (quietestHour ?: -1)
        result = 31 * result + anomalies.hashCode()
        result = 31 * result + backgroundLeakBytes.hashCode()
        result = 31 * result + trendPercent
        result = 31 * result + sampleDays
        return result
    }

    companion object {
        val EMPTY = Insights(
            dailyForecast = null,
            monthlyForecast = null,
            hourlyProfile = DoubleArray(24) { 1.0 / 24.0 },
            busiestHour = null,
            quietestHour = null,
            anomalies = emptyList(),
            backgroundLeakBytes = 0L,
            suggestedDailyLimitBytes = null,
            suggestedMonthlyLimitBytes = null,
            weekdayAverageBytes = 0L,
            weekendAverageBytes = 0L,
            trendPercent = 0,
            sampleDays = 0,
        )
    }
}

class InsightsEngine(
    private val context: Context,
    private val dao: UsageHourDao = FlowMeterDatabase.getDatabase(context).usageHourDao(),
) {

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS

        /** How much history to keep. 120 days is plenty for weekday/weekend profiling. */
        private const val RETENTION_DAYS = 120

        /** Cap on hours fetched in one ingest pass, so a cold start cannot stall. */
        private const val MAX_BACKFILL_HOURS = 24 * 16
    }

    private val ingestMutex = Mutex()

    /**
     * Pull any completed hours that are not yet in the database.
     *
     * Only hours that have fully elapsed are stored, so a row is never written with partial data.
     * Safe to call often: it is mutex-guarded, idempotent, and no-ops when already up to date.
     */
    suspend fun ingestCompletedHours(nowMillis: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            ingestMutex.withLock {
                val currentHourStart = floorHour(nowMillis)
                val latest = dao.latestHourStart()
                val defaultStart = currentHourStart - (7L * 24 * HOUR_MS)
                var cursor = if (latest == null) defaultStart else latest + HOUR_MS
                if (cursor < currentHourStart - (RETENTION_DAYS * DAY_MS)) {
                    cursor = currentHourStart - (RETENTION_DAYS * DAY_MS)
                }

                val rows = ArrayList<UsageHour>()
                var guard = 0
                val calendar = Calendar.getInstance()
                while (cursor < currentHourStart && guard < MAX_BACKFILL_HOURS) {
                    val end = cursor + HOUR_MS
                    val mobile = NetworkStatsCache.summary(
                        context, NetworkCapabilities.TRANSPORT_CELLULAR, cursor, end,
                    ).total
                    val wifi = NetworkStatsCache.summary(
                        context, NetworkCapabilities.TRANSPORT_WIFI, cursor, end,
                    ).total

                    calendar.timeInMillis = cursor
                    rows.add(
                        UsageHour(
                            hourStart = cursor,
                            mobileBytes = mobile,
                            wifiBytes = wifi,
                            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
                            dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK),
                        ),
                    )
                    cursor = end
                    guard++
                }

                if (rows.isNotEmpty()) {
                    dao.upsertAll(rows)
                    dao.pruneBefore(currentHourStart - (RETENTION_DAYS * DAY_MS))
                }
                rows.size
            }
        }

    /**
     * Compute the full insight snapshot.
     *
     * @param dailyUsedBytes usage so far in the current daily period
     * @param dailyPeriodStart epoch millis when the current daily period began
     * @param monthlyUsedBytes usage so far in the current monthly period
     * @param monthlyPeriodStart epoch millis when the current monthly period began
     */
    suspend fun compute(
        dailyUsedBytes: Long,
        dailyPeriodStart: Long,
        dailyLimitBytes: Long,
        monthlyUsedBytes: Long,
        monthlyPeriodStart: Long,
        monthlyLimitBytes: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Insights = withContext(Dispatchers.Default) {
        val history = withContext(Dispatchers.IO) {
            dao.since(nowMillis - (RETENTION_DAYS * DAY_MS))
        }
        if (history.isEmpty()) return@withContext Insights.EMPTY

        val hourSamples = history.map {
            HourSample(it.hourStart, it.hourOfDay, it.mobileBytes, it.wifiBytes)
        }
        val profile = UsageIntelligence.hourlyProfile(hourSamples)

        val daySamples = toDaySamples(history)
        val dailyTotals = daySamples.map { it.totalBytes }

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = dailyPeriodStart
        val dailyStartHour = calendar.get(Calendar.HOUR_OF_DAY)

        val dailyForecast = UsageIntelligence.forecast(
            usedBytes = dailyUsedBytes,
            elapsedMillis = nowMillis - dailyPeriodStart,
            periodMillis = DAY_MS,
            limitBytes = dailyLimitBytes,
            hourlyProfile = profile,
            periodStartHour = dailyStartHour,
            historicalPeriodTotals = dailyTotals.takeLast(30),
            nowMillis = nowMillis,
        )

        val monthlyPeriodMillis = 30 * DAY_MS
        val monthlyForecast = UsageIntelligence.forecast(
            usedBytes = monthlyUsedBytes,
            elapsedMillis = nowMillis - monthlyPeriodStart,
            periodMillis = monthlyPeriodMillis,
            limitBytes = monthlyLimitBytes,
            hourlyProfile = profile,
            periodStartHour = dailyStartHour,
            historicalPeriodTotals = emptyList(),
            nowMillis = nowMillis,
        )

        val recent = hourSamples.filter { it.epochMillis >= nowMillis - (36 * HOUR_MS) }
        val leak = UsageIntelligence.detectBackgroundLeak(recent, profile)

        val anomalies = UsageIntelligence.detectAnomalies(
            values = daySamples.map { it.totalBytes },
            timestamps = daySamples.map { it.epochMillis },
        ).takeLast(5)

        val weekend = daySamples.filter { it.dayOfWeek == 1 || it.dayOfWeek == 7 }
        val weekday = daySamples.filter { it.dayOfWeek != 1 && it.dayOfWeek != 7 }

        val trend = computeTrendPercent(dailyTotals)

        val busiest = profile.indices.maxByOrNull { profile[it] }
        val quietest = profile.indices.minByOrNull { profile[it] }

        Insights(
            dailyForecast = dailyForecast,
            monthlyForecast = monthlyForecast,
            hourlyProfile = profile,
            busiestHour = busiest,
            quietestHour = quietest,
            anomalies = anomalies,
            backgroundLeakBytes = leak,
            suggestedDailyLimitBytes = UsageIntelligence.suggestDailyLimit(dailyTotals),
            suggestedMonthlyLimitBytes = UsageIntelligence.suggestMonthlyLimit(daySamples),
            weekdayAverageBytes = (UsageIntelligence.trimmedMean(weekday.map { it.totalBytes }) ?: 0.0).toLong(),
            weekendAverageBytes = (UsageIntelligence.trimmedMean(weekend.map { it.totalBytes }) ?: 0.0).toLong(),
            trendPercent = trend,
            sampleDays = daySamples.size,
        )
    }

    /** Percentage change of the last 7 days versus the 7 before that. */
    private fun computeTrendPercent(dailyTotals: List<Long>): Int {
        if (dailyTotals.size < 8) return 0
        val recent = dailyTotals.takeLast(7)
        val prior = dailyTotals.dropLast(7).takeLast(7)
        if (prior.isEmpty()) return 0
        val recentMean = recent.average()
        val priorMean = prior.average()
        if (priorMean <= 0.0) return 0
        return (((recentMean - priorMean) / priorMean) * 100.0)
            .toInt()
            .coerceIn(-999, 999)
    }

    /** Group stored hours into completed calendar days (the in-progress day is excluded). */
    private fun toDaySamples(history: List<UsageHour>): List<DaySample> {
        if (history.isEmpty()) return emptyList()
        val calendar = Calendar.getInstance()
        val buckets = LinkedHashMap<Long, LongArray>()
        val dow = HashMap<Long, Int>()

        for (h in history) {
            calendar.timeInMillis = h.hourStart
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val dayKey = calendar.timeInMillis
            val acc = buckets.getOrPut(dayKey) { longArrayOf(0L, 0L) }
            acc[0] += h.mobileBytes
            acc[1] += h.wifiBytes
            dow[dayKey] = calendar.get(Calendar.DAY_OF_WEEK)
        }

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return buckets.entries
            .filter { it.key < todayStart }
            .sortedBy { it.key }
            .map { (day, acc) ->
                DaySample(
                    epochMillis = day,
                    dayOfWeek = dow[day] ?: 1,
                    mobileBytes = acc[0],
                    wifiBytes = acc[1],
                )
            }
    }

    private fun floorHour(millis: Long): Long = millis - (millis % HOUR_MS)
}
