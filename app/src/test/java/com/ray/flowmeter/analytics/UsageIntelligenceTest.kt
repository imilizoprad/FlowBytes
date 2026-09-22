package com.ray.flowmeter.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageIntelligenceTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * mb
    private val hourMs = 3_600_000L

    // ------------------------------------------------------------------ profile

    @Test
    fun `hourly profile is flat with no samples`() {
        val profile = UsageIntelligence.hourlyProfile(emptyList())
        assertEquals(24, profile.size)
        profile.forEach { assertEquals(1.0 / 24.0, it, 1e-9) }
    }

    @Test
    fun `hourly profile sums to one and favours busy hours`() {
        val samples = (0 until 24).flatMap { h ->
            (0 until 5).map { d ->
                HourSample(
                    epochMillis = d * 24L * hourMs + h * hourMs,
                    hourOfDay = h,
                    mobileBytes = if (h == 20) 500 * mb else 10 * mb,
                    wifiBytes = 0,
                )
            }
        }
        val profile = UsageIntelligence.hourlyProfile(samples)
        assertEquals(1.0, profile.sum(), 1e-6)
        assertTrue("hour 20 should dominate", profile[20] > profile[3])
        profile.forEach { assertTrue("no hour may be zero", it > 0.0) }
    }

    @Test
    fun `expected fraction elapsed is monotonic and bounded`() {
        val profile = UsageIntelligence.hourlyProfile(emptyList())
        var previous = 0.0
        for (h in 0..24) {
            val f = UsageIntelligence.expectedFractionElapsed(profile, 0, h.toDouble())
            assertTrue(f >= previous - 1e-9)
            assertTrue(f in 0.0..1.0)
            previous = f
        }
        assertEquals(1.0, UsageIntelligence.expectedFractionElapsed(profile, 0, 24.0), 1e-9)
    }

    // ------------------------------------------------------------------ forecast

    @Test
    fun `forecast never projects below what is already used`() {
        val f = UsageIntelligence.forecast(
            usedBytes = 3 * gb,
            elapsedMillis = 23 * hourMs,
            periodMillis = 24 * hourMs,
            limitBytes = 1 * gb,
            hourlyProfile = UsageIntelligence.hourlyProfile(emptyList()),
            periodStartHour = 0,
        )
        assertTrue(f.projectedBytes >= 3 * gb)
        assertEquals(RiskLevel.EXCEEDED, f.risk)
    }

    @Test
    fun `flat profile halfway through doubles the projection`() {
        val f = UsageIntelligence.forecast(
            usedBytes = 500 * mb,
            elapsedMillis = 12 * hourMs,
            periodMillis = 24 * hourMs,
            limitBytes = 0,
            hourlyProfile = UsageIntelligence.hourlyProfile(emptyList()),
            periodStartHour = 0,
        )
        val ratio = f.projectedBytes.toDouble() / (1000 * mb).toDouble()
        assertTrue("expected ~1 GB projection, got ${f.projectedBytes}", ratio in 0.9..1.1)
    }

    @Test
    fun `night owl profile does not over-project after a quiet morning`() {
        // User does almost everything in the evening.
        val samples = (0 until 24).flatMap { h ->
            (0 until 7).map { d ->
                HourSample(
                    epochMillis = d * 24L * hourMs + h * hourMs,
                    hourOfDay = h,
                    mobileBytes = if (h >= 19) 400 * mb else 2 * mb,
                    wifiBytes = 0,
                )
            }
        }
        val profile = UsageIntelligence.hourlyProfile(samples)

        // 8 hours into the day they have used 100 MB. A naive model says 300 MB/day.
        val smart = UsageIntelligence.forecast(
            usedBytes = 100 * mb,
            elapsedMillis = 8 * hourMs,
            periodMillis = 24 * hourMs,
            limitBytes = 0,
            hourlyProfile = profile,
            periodStartHour = 0,
        )
        val naive = (100 * mb) * 3
        assertTrue(
            "shape-aware forecast (${smart.projectedBytes}) should exceed naive ($naive)",
            smart.projectedBytes > naive,
        )
    }

    @Test
    fun `confidence grows as the period progresses`() {
        val profile = UsageIntelligence.hourlyProfile(emptyList())
        val early = UsageIntelligence.forecast(
            100 * mb, 2 * hourMs, 24 * hourMs, 0, profile, 0,
        )
        val late = UsageIntelligence.forecast(
            900 * mb, 22 * hourMs, 24 * hourMs, 0, profile, 0,
        )
        assertTrue(late.confidence > early.confidence)
    }

    @Test
    fun `exhaustion time lands inside the remaining period when a limit will be hit`() {
        val now = 1_700_000_000_000L
        val f = UsageIntelligence.forecast(
            usedBytes = 800 * mb,
            elapsedMillis = 12 * hourMs,
            periodMillis = 24 * hourMs,
            limitBytes = 1 * gb,
            hourlyProfile = UsageIntelligence.hourlyProfile(emptyList()),
            periodStartHour = 0,
            nowMillis = now,
        )
        val at = f.exhaustionAtMillis
        assertNotNull("limit should be projected to be reached", at)
        assertTrue(at!! > now)
        assertTrue(at <= now + 12 * hourMs + hourMs)
    }

    @Test
    fun `no limit means safe and no exhaustion`() {
        val f = UsageIntelligence.forecast(
            500 * mb, 6 * hourMs, 24 * hourMs, 0, UsageIntelligence.hourlyProfile(emptyList()), 0,
        )
        assertEquals(RiskLevel.SAFE, f.risk)
        assertNull(f.exhaustionAtMillis)
    }

    @Test
    fun `forecast tolerates zero elapsed time`() {
        val f = UsageIntelligence.forecast(
            0L, 0L, 24 * hourMs, 1 * gb, UsageIntelligence.hourlyProfile(emptyList()), 0,
        )
        assertTrue(f.projectedBytes >= 0)
    }

    // ------------------------------------------------------------------ anomalies

    @Test
    fun `steady usage produces no anomalies`() {
        val values = List(20) { 300L * mb + it * mb }
        val stamps = List(20) { it * 24L * hourMs }
        assertTrue(UsageIntelligence.detectAnomalies(values, stamps).isEmpty())
    }

    @Test
    fun `a huge spike is detected`() {
        val values = MutableList(20) { 300L * mb }
        values[13] = 40L * gb
        val stamps = List(20) { it * 24L * hourMs }
        val found = UsageIntelligence.detectAnomalies(values, stamps)
        assertEquals(1, found.size)
        assertEquals(13 * 24L * hourMs, found.first().epochMillis)
        assertTrue(found.first().score >= 3.5)
    }

    @Test
    fun `tiny spikes below the byte floor are ignored`() {
        val values = MutableList(20) { 1L * mb }
        values[5] = 10L * mb
        val stamps = List(20) { it * 24L * hourMs }
        assertTrue(UsageIntelligence.detectAnomalies(values, stamps).isEmpty())
    }

    @Test
    fun `too little data yields no anomalies`() {
        assertTrue(UsageIntelligence.detectAnomalies(listOf(1L, 2L), listOf(0L, 1L)).isEmpty())
    }

    // ------------------------------------------------------------------ leaks

    @Test
    fun `overnight streak is reported as a background leak`() {
        val profile = UsageIntelligence.hourlyProfile(
            (0 until 24).flatMap { h ->
                (0 until 7).map { d ->
                    HourSample(d * 24L * hourMs + h * hourMs, h, if (h in 9..22) 300 * mb else 0L, 0L)
                }
            },
        )
        val night = listOf(2, 3, 4, 5).map {
            HourSample(it * hourMs, it, 60 * mb, 0L)
        }
        assertTrue(UsageIntelligence.detectBackgroundLeak(night, profile) > 0L)
    }

    @Test
    fun `normal daytime usage is not a leak`() {
        val profile = UsageIntelligence.hourlyProfile(
            (0 until 24).flatMap { h ->
                (0 until 7).map { d ->
                    HourSample(d * 24L * hourMs + h * hourMs, h, if (h in 9..22) 300 * mb else 0L, 0L)
                }
            },
        )
        val day = listOf(12, 13, 14, 15).map { HourSample(it * hourMs, it, 200 * mb, 0L) }
        assertEquals(0L, UsageIntelligence.detectBackgroundLeak(day, profile))
    }

    // ------------------------------------------------------------------ suggestions

    @Test
    fun `daily limit suggestion sits above typical usage`() {
        val totals = listOf(700L * mb, 800 * mb, 650 * mb, 900 * mb, 750 * mb, 820 * mb)
        val suggested = UsageIntelligence.suggestDailyLimit(totals)
        assertNotNull(suggested)
        assertTrue(suggested!! >= 900 * mb)
    }

    @Test
    fun `daily limit suggestion needs enough history`() {
        assertNull(UsageIntelligence.suggestDailyLimit(listOf(500L * mb)))
    }

    @Test
    fun `monthly suggestion scales daily habits`() {
        val days = (0 until 14).map {
            DaySample(it * 24L * hourMs, (it % 7) + 1, 500 * mb, 0L)
        }
        val suggested = UsageIntelligence.suggestMonthlyLimit(days)
        assertNotNull(suggested)
        assertTrue(suggested!! >= 15L * gb)
    }

    @Test
    fun `friendly rounding picks human numbers`() {
        assertEquals(500 * mb, UsageIntelligence.roundToFriendlyBytes(420 * mb))
        assertEquals(2 * gb, UsageIntelligence.roundToFriendlyBytes(1100 * mb))
        assertEquals(0L, UsageIntelligence.roundToFriendlyBytes(0))
    }

    // ------------------------------------------------------------------ polling

    @Test
    fun `adaptive polling backs off while idle and snaps back under load`() {
        val busy = UsageIntelligence.adaptivePollIntervalMillis(true, 5 * mb, 100)
        val idle = UsageIntelligence.adaptivePollIntervalMillis(true, 0, 100)
        val fresh = UsageIntelligence.adaptivePollIntervalMillis(true, 0, 0)
        val screenOff = UsageIntelligence.adaptivePollIntervalMillis(false, 0, 0)
        val offline = UsageIntelligence.adaptivePollIntervalMillis(true, 0, 0, connected = false)

        assertEquals(1_000L, busy)
        assertEquals(1_000L, fresh)
        assertTrue("idle=$idle fresh=$fresh", idle > fresh)
        assertTrue(screenOff >= idle)
        assertEquals(30_000L, offline)
    }

    // ------------------------------------------------------------------ math

    @Test
    fun `trimmed mean ignores extremes`() {
        val values = listOf(100L, 100, 100, 100, 100, 100, 100, 1_000_000)
        val trimmed = UsageIntelligence.trimmedMean(values)!!
        assertTrue("trimmed=$trimmed", trimmed < 1000.0)
    }

    @Test
    fun `percentile interpolates`() {
        val values = listOf(0L, 10, 20, 30, 40)
        assertEquals(20.0, UsageIntelligence.percentile(values, 0.5), 1e-9)
        assertEquals(40.0, UsageIntelligence.percentile(values, 1.0), 1e-9)
    }

    @Test
    fun `median handles even and odd sizes`() {
        assertEquals(2.0, UsageIntelligence.median(listOf(1.0, 2.0, 3.0))!!, 1e-9)
        assertEquals(2.5, UsageIntelligence.median(listOf(1.0, 2.0, 3.0, 4.0))!!, 1e-9)
        assertNull(UsageIntelligence.median(emptyList()))
    }
}
