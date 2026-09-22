// Pure-Kotlin analytics core for FlowBytes.
//
// Deliberately free of Android dependencies so every function here is directly unit-testable
// on the JVM (see app/src/test/.../UsageIntelligenceTest.kt). Everything is O(n) or O(n log n)
// over at most a few hundred samples, so it is cheap enough to run on each refresh.
package com.ray.flowmeter.analytics

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** One completed hour of usage. [hourOfDay] is 0..23 in the user's local time. */
data class HourSample(
    val epochMillis: Long,
    val hourOfDay: Int,
    val mobileBytes: Long,
    val wifiBytes: Long,
) {
    val totalBytes: Long get() = mobileBytes + wifiBytes
}

/** One completed day of usage. */
data class DaySample(
    val epochMillis: Long,
    /** 1 = Sunday .. 7 = Saturday, matching java.util.Calendar.DAY_OF_WEEK. */
    val dayOfWeek: Int,
    val mobileBytes: Long,
    val wifiBytes: Long,
) {
    val totalBytes: Long get() = mobileBytes + wifiBytes
}

enum class RiskLevel { SAFE, ON_TRACK, WATCH, AT_RISK, EXCEEDED }

/**
 * Result of projecting the rest of the current period.
 *
 * @param projectedBytes best estimate of where the period will end up
 * @param lowerBytes/upperBytes an 80%-ish plausible band around the projection
 * @param confidence 0..1 — how much history backed the projection
 * @param exhaustionAtMillis when the limit is expected to be hit, or null if not within the period
 */
data class Forecast(
    val projectedBytes: Long,
    val lowerBytes: Long,
    val upperBytes: Long,
    val confidence: Float,
    val risk: RiskLevel,
    val exhaustionAtMillis: Long?,
    val safeBytesPerHourRemaining: Long,
)

/** An unusual usage event worth telling the user about. */
data class Anomaly(
    val epochMillis: Long,
    val observedBytes: Long,
    val expectedBytes: Long,
    /** Robust deviation score. >= 3.5 is the usual "this is genuinely weird" bar. */
    val score: Double,
)

object UsageIntelligence {

    private const val HOUR_MS = 3_600_000L

    // ---------------------------------------------------------------- profiles

    /**
     * Normalised usage profile across the 24 hours of a day.
     *
     * Each entry is the share of a typical day's traffic that lands in that hour, summing to 1.
     * Hours with no history fall back to a flat 1/24. A day's worth of samples is smoothed with
     * a small circular kernel so a single spike does not dominate one bucket.
     */
    fun hourlyProfile(samples: List<HourSample>): DoubleArray {
        val flat = DoubleArray(24) { 1.0 / 24.0 }
        if (samples.isEmpty()) return flat

        val sums = DoubleArray(24)
        val counts = IntArray(24)
        for (s in samples) {
            val h = s.hourOfDay
            if (h !in 0..23) continue
            sums[h] += s.totalBytes.toDouble()
            counts[h]++
        }

        val means = DoubleArray(24) { i -> if (counts[i] > 0) sums[i] / counts[i] else 0.0 }
        if (means.all { it <= 0.0 }) return flat

        // Circular 1-2-1 smoothing.
        val smoothed = DoubleArray(24) { i ->
            val prev = means[(i + 23) % 24]
            val next = means[(i + 1) % 24]
            (prev + 2.0 * means[i] + next) / 4.0
        }

        val total = smoothed.sum()
        if (total <= 0.0) return flat

        // Blend towards flat so an hour never gets a literally-zero weight.
        val epsilon = 0.15
        return DoubleArray(24) { i ->
            (1.0 - epsilon) * (smoothed[i] / total) + epsilon * (1.0 / 24.0)
        }
    }

    /**
     * Fraction of a day's traffic that has normally happened by [hoursElapsed] hours into the
     * period, given an hourly [profile] and the hour the period starts at.
     */
    fun expectedFractionElapsed(profile: DoubleArray, startHour: Int, hoursElapsed: Double): Double {
        if (hoursElapsed <= 0.0) return 0.0
        if (hoursElapsed >= 24.0) return 1.0

        var acc = 0.0
        val whole = hoursElapsed.toInt()
        for (i in 0 until whole) {
            acc += profile[(startHour + i) % 24]
        }
        val frac = hoursElapsed - whole
        if (frac > 0.0) acc += profile[(startHour + whole) % 24] * frac
        return acc.coerceIn(0.0001, 1.0)
    }

    // ---------------------------------------------------------------- forecast

    /**
     * Project the end-of-period total.
     *
     * Unlike a naive `used / elapsed * periodLength` extrapolation (which claims you will burn
     * 24 GB because you watched a video at 1 a.m.), this weights the remaining time by how the
     * user actually behaves at those hours, and blends in the historical period average.
     */
    fun forecast(
        usedBytes: Long,
        elapsedMillis: Long,
        periodMillis: Long,
        limitBytes: Long,
        hourlyProfile: DoubleArray,
        periodStartHour: Int,
        historicalPeriodTotals: List<Long> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Forecast {
        val period = max(periodMillis, 1L)
        val elapsed = elapsedMillis.coerceIn(0L, period)
        val remaining = period - elapsed

        val hoursElapsed = elapsed.toDouble() / HOUR_MS
        // Floored away from zero: at the very start of a period there is nothing to extrapolate
        // from, and dividing by it would produce NaN/Infinity that poisons every downstream value.
        val fractionElapsed = if (period <= 24 * HOUR_MS) {
            expectedFractionElapsed(hourlyProfile, periodStartHour, hoursElapsed)
        } else {
            elapsed.toDouble() / period
        }.coerceIn(0.0001, 1.0)

        // Shape-aware projection.
        val shapeProjection = usedBytes / fractionElapsed

        // Historical prior: trimmed mean of past periods.
        val prior = trimmedMean(historicalPeriodTotals)

        // Trust the live data more as the period progresses; early on, lean on history.
        val liveWeight = when {
            prior == null -> 1.0
            else -> (fractionElapsed * 1.4).coerceIn(0.25, 1.0)
        }
        val blended = if (prior == null) {
            shapeProjection
        } else {
            liveWeight * shapeProjection + (1.0 - liveWeight) * max(prior, usedBytes.toDouble())
        }

        // Never project less than what is already on the meter.
        val projected = max(blended, usedBytes.toDouble())

        // Uncertainty shrinks as the period completes and as history accumulates.
        val historyFactor = 1.0 / sqrt((historicalPeriodTotals.size + 1).toDouble())
        val spread = (0.55 * (1.0 - fractionElapsed) + 0.35 * historyFactor).coerceIn(0.05, 0.9)
        val lower = max(usedBytes.toDouble(), projected * (1.0 - spread))
        val upper = projected * (1.0 + spread)

        val confidence = (1.0 - spread).toFloat().coerceIn(0f, 1f)

        val risk = when {
            limitBytes <= 0L -> RiskLevel.SAFE
            usedBytes >= limitBytes -> RiskLevel.EXCEEDED
            lower >= limitBytes -> RiskLevel.AT_RISK
            projected >= limitBytes -> RiskLevel.WATCH
            upper >= limitBytes -> RiskLevel.ON_TRACK
            else -> RiskLevel.SAFE
        }

        // When will the limit be reached, walking forward through the profile?
        val exhaustion = if (limitBytes <= 0L || usedBytes >= limitBytes || projected < limitBytes) {
            if (limitBytes in 1..usedBytes) nowMillis else null
        } else {
            estimateExhaustion(
                usedBytes = usedBytes,
                limitBytes = limitBytes,
                projectedTotal = projected,
                hoursElapsed = hoursElapsed,
                hourlyProfile = hourlyProfile,
                periodStartHour = periodStartHour,
                remainingMillis = remaining,
                nowMillis = nowMillis,
                periodMillis = period,
            )
        }

        val hoursRemaining = max(remaining.toDouble() / HOUR_MS, 0.0167)
        val budgetLeft = (limitBytes - usedBytes).coerceAtLeast(0L)
        val safePerHour = if (limitBytes <= 0L) 0L else (budgetLeft / hoursRemaining).roundToLong()

        return Forecast(
            projectedBytes = projected.roundToLong(),
            lowerBytes = lower.roundToLong(),
            upperBytes = upper.roundToLong(),
            confidence = confidence,
            risk = risk,
            exhaustionAtMillis = exhaustion,
            safeBytesPerHourRemaining = safePerHour,
        )
    }

    private fun estimateExhaustion(
        usedBytes: Long,
        limitBytes: Long,
        projectedTotal: Double,
        hoursElapsed: Double,
        hourlyProfile: DoubleArray,
        periodStartHour: Int,
        remainingMillis: Long,
        nowMillis: Long,
        periodMillis: Long,
    ): Long? {
        val needed = (limitBytes - usedBytes).toDouble()
        if (needed <= 0.0) return nowMillis

        val isDaily = periodMillis <= 24 * HOUR_MS
        var acc = 0.0
        var cursor = 0.0
        val step = 0.25 // quarter-hour resolution
        val maxHours = remainingMillis.toDouble() / HOUR_MS

        while (cursor < maxHours) {
            val absHour = hoursElapsed + cursor
            val weight = if (isDaily) {
                hourlyProfile[((periodStartHour + absHour.toInt()) % 24 + 24) % 24]
            } else {
                1.0 / 24.0
            }
            acc += projectedTotal * weight * step
            if (acc >= needed) {
                return nowMillis + ((cursor + step) * HOUR_MS).toLong()
            }
            cursor += step
        }
        return null
    }

    // ---------------------------------------------------------------- anomalies

    /**
     * Robust anomaly detection over a series of samples using the median absolute deviation.
     *
     * MAD is used rather than a mean/stddev z-score because network usage is heavy-tailed: one
     * 4 GB system update would inflate a standard deviation so much that nothing else ever looks
     * anomalous again. Values are log-compressed first for the same reason.
     */
    fun detectAnomalies(
        values: List<Long>,
        timestamps: List<Long>,
        threshold: Double = 3.5,
        minimumBytes: Long = 20L * 1024 * 1024,
    ): List<Anomaly> {
        if (values.size < 6 || values.size != timestamps.size) return emptyList()

        val logs = values.map { ln(1.0 + it.toDouble()) }
        val median = median(logs) ?: return emptyList()
        val deviations = logs.map { abs(it - median) }
        val mad = median(deviations) ?: return emptyList()

        // 0.6745 makes MAD a consistent estimator of sigma for normal data.
        val scale = if (mad > 1e-9) mad / 0.6745 else {
            val mean = logs.average()
            val sd = sqrt(logs.sumOf { (it - mean) * (it - mean) } / logs.size)
            if (sd > 1e-9) sd else return emptyList()
        }

        val expected = kotlin.math.exp(median) - 1.0
        val out = ArrayList<Anomaly>()
        for (i in values.indices) {
            val score = (logs[i] - median) / scale
            if (score >= threshold && values[i] >= minimumBytes) {
                out.add(
                    Anomaly(
                        epochMillis = timestamps[i],
                        observedBytes = values[i],
                        expectedBytes = expected.roundToLong().coerceAtLeast(0L),
                        score = score,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Background-leak heuristic: sustained non-trivial traffic across consecutive quiet hours
     * (hours where the user historically uses almost nothing, e.g. overnight).
     * Returns the total leaked bytes, or 0 when nothing looks like a leak.
     */
    fun detectBackgroundLeak(
        recentHours: List<HourSample>,
        profile: DoubleArray,
        quietQuantile: Double = 0.25,
        minimumPerHourBytes: Long = 5L * 1024 * 1024,
    ): Long {
        if (recentHours.size < 3) return 0L
        val sorted = profile.sorted()
        val cutoffIndex = (sorted.size * quietQuantile).toInt().coerceIn(0, sorted.size - 1)
        val quietCutoff = sorted[cutoffIndex]

        var streak = 0
        var leaked = 0L
        var best = 0L
        for (s in recentHours) {
            val isQuietHour = profile[s.hourOfDay.coerceIn(0, 23)] <= quietCutoff
            if (isQuietHour && s.totalBytes >= minimumPerHourBytes) {
                streak++
                leaked += s.totalBytes
                if (streak >= 3) best = max(best, leaked)
            } else {
                streak = 0
                leaked = 0L
            }
        }
        return best
    }

    // ---------------------------------------------------------------- suggestions

    /**
     * Suggest a daily limit: comfortably above typical usage but still meaningful.
     * Uses the 80th percentile of recent daily totals, nudged up by 10% and rounded to a
     * human-friendly value.
     */
    fun suggestDailyLimit(dailyTotals: List<Long>): Long? {
        val usable = dailyTotals.filter { it > 0 }
        if (usable.size < 3) return null
        val p80 = percentile(usable, 0.80)
        return roundToFriendlyBytes((p80 * 1.10).roundToLong())
    }

    /**
     * Suggest a monthly limit from daily history, accounting for weekday/weekend split.
     */
    fun suggestMonthlyLimit(daily: List<DaySample>, daysInMonth: Int = 30): Long? {
        if (daily.size < 5) return null
        val weekend = daily.filter { it.dayOfWeek == 1 || it.dayOfWeek == 7 }.map { it.totalBytes }
        val weekday = daily.filter { it.dayOfWeek != 1 && it.dayOfWeek != 7 }.map { it.totalBytes }

        val weekendMean = trimmedMean(weekend) ?: trimmedMean(daily.map { it.totalBytes }) ?: return null
        val weekdayMean = trimmedMean(weekday) ?: weekendMean

        val weekendDays = daysInMonth * 2.0 / 7.0
        val weekdayDays = daysInMonth - weekendDays
        val estimate = weekendMean * weekendDays + weekdayMean * weekdayDays
        return roundToFriendlyBytes((estimate * 1.15).roundToLong())
    }

    /**
     * Adaptive polling interval for the foreground monitor.
     *
     * Fast when traffic is actively moving or the user is looking at the screen; progressively
     * lazier when the link is idle. This is the single biggest battery lever in the app: an
     * always-1 Hz TrafficStats loop wakes the CPU 86,400 times a day for nothing.
     */
    fun adaptivePollIntervalMillis(
        screenOn: Boolean,
        currentSpeedBytesPerSec: Long,
        idleTicks: Int,
        connected: Boolean = true,
    ): Long {
        if (!connected) return 30_000L
        if (!screenOn) {
            // Screen off: nobody can see the notification; only keep counters roughly warm.
            return if (currentSpeedBytesPerSec > 512 * 1024) 5_000L else 15_000L
        }
        if (currentSpeedBytesPerSec > 64 * 1024) return 1_000L
        // Screen-on backoff stays modest: the notification is visible, so it must still feel
        // live the moment traffic resumes.
        return when {
            idleTicks < 5 -> 1_000L
            idleTicks < 20 -> 1_500L
            idleTicks < 60 -> 2_000L
            else -> 3_000L
        }
    }

    // ---------------------------------------------------------------- math helpers

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /** Mean with the top and bottom 10% discarded, so a single outlier cannot dominate. */
    fun trimmedMean(values: List<Long>): Double? {
        val usable = values.filter { it >= 0 }
        if (usable.isEmpty()) return null
        if (usable.size < 5) return usable.average()
        val s = usable.sorted()
        val drop = (s.size * 0.1).toInt().coerceAtLeast(1)
        val kept = s.subList(drop, s.size - drop)
        return if (kept.isEmpty()) s.average() else kept.average()
    }

    fun percentile(values: List<Long>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        if (s.size == 1) return s[0].toDouble()
        val pos = (s.size - 1) * q.coerceIn(0.0, 1.0)
        val lo = pos.toInt()
        val hi = min(lo + 1, s.size - 1)
        val frac = pos - lo
        return s[lo] * (1 - frac) + s[hi] * frac
    }

    /** Round up to a value a human would actually pick (500 MB, 2 GB, 15 GB, ...). */
    fun roundToFriendlyBytes(bytes: Long): Long {
        if (bytes <= 0) return 0
        val mb = 1024L * 1024L
        val gb = 1024L * mb
        val steps = longArrayOf(
            50 * mb, 100 * mb, 200 * mb, 300 * mb, 500 * mb, 750 * mb,
            1 * gb, 2 * gb, 3 * gb, 5 * gb, 8 * gb, 10 * gb, 15 * gb, 20 * gb,
            25 * gb, 30 * gb, 40 * gb, 50 * gb, 75 * gb, 100 * gb, 150 * gb, 200 * gb,
        )
        return steps.firstOrNull { it >= bytes } ?: (((bytes + 49 * gb) / (50 * gb)) * 50 * gb)
    }
}
