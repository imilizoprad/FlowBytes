// Shared, coalescing NetworkStats query layer.
//
// Before this existed, the monitoring service, the Home ViewModel, the widgets and the
// app-usage screens each hammered NetworkStatsManager.querySummary() independently — the
// exact same (transport, start, end) window could be queried a dozen times per second.
// querySummary() is a binder round-trip into the system process and is by far the most
// expensive thing this app does.
//
// This object adds:
//   * a short-TTL memo cache keyed by (transport, start, end)
//   * request coalescing, so N concurrent callers asking for the same window share one query
//   * an "immutable window" fast path: windows that ended in the past never change, so they
//     are cached (essentially) forever instead of being re-queried
//   * a single reusable Bucket per query loop
package com.ray.flowmeter.utils

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Immutable rx/tx pair, in bytes. */
data class UsageBytes(val rx: Long, val tx: Long) {
    val total: Long get() = rx + tx

    operator fun plus(other: UsageBytes) = UsageBytes(rx + other.rx, tx + other.tx)

    companion object {
        val ZERO = UsageBytes(0L, 0L)
    }
}

object NetworkStatsCache {

    /** Live windows (those whose end is "now") are only worth re-querying this often. */
    private const val LIVE_TTL_MS = 2_000L

    /** A window that closed more than this long ago will not receive late-arriving buckets. */
    private const val SETTLE_MS = 60_000L

    /** Cap so a long-lived service cannot grow the cache without bound. */
    private const val MAX_ENTRIES = 512

    private data class Key(val transport: Int, val start: Long, val end: Long)

    private class Entry(
        @Volatile var value: UsageBytes,
        @Volatile var fetchedAt: Long,
        @Volatile var immutable: Boolean,
    )

    private val cache = LinkedHashMap<Key, Entry>(64, 0.75f, true)
    private val inFlight = HashMap<Key, CompletableDeferred<UsageBytes>>()
    private val lock = Mutex()

    /**
     * Total usage (rx + tx) for one transport over [start, end).
     * Returns cached data when it is fresh enough, otherwise performs (or joins) one query.
     */
    suspend fun summary(
        context: Context,
        transport: Int,
        start: Long,
        end: Long,
    ): UsageBytes {
        if (end <= start) return UsageBytes.ZERO

        val key = Key(transport, start, end)
        val now = System.currentTimeMillis()

        // Fast path + coalescing decision under the lock; the actual binder call is outside it.
        val waiter: CompletableDeferred<UsageBytes>
        val owner: Boolean
        lock.withLock {
            trim()
            val hit = cache[key]
            if (hit != null && (hit.immutable || (now - hit.fetchedAt) < LIVE_TTL_MS)) {
                return hit.value
            }

            val existing = inFlight[key]
            if (existing != null) {
                waiter = existing
                owner = false
            } else {
                waiter = CompletableDeferred()
                inFlight[key] = waiter
                owner = true
            }
        }

        if (!owner) return waiter.await()

        val result = try {
            querySummary(context, transport, start, end)
        } catch (_: Throwable) {
            UsageBytes.ZERO
        }

        lock.withLock {
            val settled = end < (System.currentTimeMillis() - SETTLE_MS)
            cache[key] = Entry(result, System.currentTimeMillis(), settled)
            inFlight.remove(key)
        }
        waiter.complete(result)
        return result
    }

    /** Combined Wi-Fi + cellular usage for a window. */
    suspend fun combined(context: Context, start: Long, end: Long): UsageBytes =
        summary(context, NetworkCapabilities.TRANSPORT_WIFI, start, end) +
            summary(context, NetworkCapabilities.TRANSPORT_CELLULAR, start, end)

    /** Drop everything; used when the reset time / billing day changes. */
    suspend fun invalidate() {
        lock.withLock { cache.clear() }
    }

    /** Drop only windows that are still open, keeping settled history. */
    suspend fun invalidateLive() {
        lock.withLock {
            val it = cache.entries.iterator()
            while (it.hasNext()) {
                if (!it.next().value.immutable) it.remove()
            }
        }
    }

    private fun trim() {
        if (cache.size <= MAX_ENTRIES) return
        val it = cache.entries.iterator() // access-ordered: eldest first
        while (cache.size > MAX_ENTRIES && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private suspend fun querySummary(
        context: Context,
        transport: Int,
        start: Long,
        end: Long,
    ): UsageBytes = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(NetworkStatsManager::class.java)
            ?: return@withContext UsageBytes.ZERO

        var rx = 0L
        var tx = 0L
        try {
            val stats = manager.querySummary(transport, null, start, end)
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                rx += bucket.rxBytes
                tx += bucket.txBytes
            }
            stats.close()
        } catch (_: SecurityException) {
            // Usage-access permission not granted yet.
        } catch (_: Exception) {
            // Vendor ROMs occasionally throw here; treat as no data.
        }
        UsageBytes(rx, tx)
    }
}
