package com.org.notifyme.net

import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object Limits {
    const val MIN_GAP_MS = 10_000L
    const val MAX_PER_HOUR = 60
    const val INTERVAL_FLOOR_SEC = 120
    const val INTERVAL_DEFAULT_SEC = 180
    const val BLOCK_STEP_MS = 15 * 60_000L
    const val PAUSE_CAP_MS = 60 * 60_000L
    const val MAX_BODY_BYTES = 2_000_000L
    const val BATTERY_STOP_PCT = 20
    val HOURS_CHOICES = listOf(1, 2, 4, 8)
}

/** Shared singleton (AppContainer) so WorkManager and the watch service obey ONE budget. */
class HostGate(private val clock: () -> Long = System::currentTimeMillis) {

    private class State {
        var nextAllowedAt = 0L
        var pausedUntil = 0L
        var failures = 0
        var blocks = 0
        val recent = ArrayDeque<Long>()
    }

    private val states = ConcurrentHashMap<String, State>()
    private fun st(host: String) = states.getOrPut(host) { State() }

    /** Epoch ms at which a request to [host] is allowed; <= now means go. */
    @Synchronized fun allowedAt(host: String): Long {
        val now = clock(); val s = st(host)
        while (s.recent.isNotEmpty() && now - s.recent.first() > 3_600_000L) s.recent.removeFirst()
        var t = maxOf(s.nextAllowedAt, s.pausedUntil)
        if (s.recent.size >= Limits.MAX_PER_HOUR) t = maxOf(t, s.recent.first() + 3_600_000L)
        return t
    }

    @Synchronized fun recordRequest(host: String) {
        val now = clock(); val s = st(host)
        s.recent.addLast(now)
        s.nextAllowedAt = now + Limits.MIN_GAP_MS + Random.nextLong(0, 3_000)
    }

    /** code == null means a network/IO failure. */
    @Synchronized fun recordOutcome(host: String, code: Int?, retryAfterMs: Long?) {
        val now = clock(); val s = st(host)
        when {
            code != null && code in 200..399 -> { s.failures = 0; s.blocks = 0 }
            code == 403 || code == 429 -> {
                s.blocks++; s.failures++
                val pause = maxOf(retryAfterMs ?: 0L, Limits.BLOCK_STEP_MS * s.blocks)
                s.pausedUntil = now + minOf(pause, Limits.PAUSE_CAP_MS)
            }
            else -> {
                s.failures++
                val pause = minOf(Limits.PAUSE_CAP_MS, 60_000L shl minOf(s.failures, 10))
                s.pausedUntil = maxOf(s.pausedUntil, now + pause)
            }
        }
    }

    @Synchronized fun pausedUntil(host: String): Long = st(host).pausedUntil.takeIf { it > clock() } ?: 0L
    @Synchronized fun consecutiveBlocks(host: String): Int = st(host).blocks

    @Synchronized fun getActivePauses(now: Long = clock()): Map<String, Long> {
        return states.mapNotNull { (host, state) ->
            val until = state.pausedUntil
            if (until > now) host to until else null
        }.toMap()
    }

    @Synchronized fun requestCountThisHour(host: String, now: Long = clock()): Int {
        val s = states[host] ?: return 0
        while (s.recent.isNotEmpty() && now - s.recent.first() > 3_600_000L) s.recent.removeFirst()
        return s.recent.size
    }
}

object Plan {
    /** [itemsPerHost] = largest number of watched items sharing one host. */
    fun effectiveIntervalSec(itemsPerHost: Int, userSec: Int): Int =
        maxOf(
            Limits.INTERVAL_FLOOR_SEC,
            userSec,
            (itemsPerHost * 3600.0 / Limits.MAX_PER_HOUR).toInt(),
        )
}
