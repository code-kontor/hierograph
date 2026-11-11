/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.server.graphservice

/**
 * How busy the build is, judged by how many changes the watcher reported recently, and the two delays
 * that follow from it. A single edit finds the build calm: its rescan runs at once and its detail
 * snapshot follows after [baseQuietMs]. While a build changes one module after another, changes pile up
 * in the trailing [windowMs]:
 *
 *  - [rescanDelayMs] grows by [stepMs] per further change, up to [maxRescanDelayMs], so the coalescer
 *    collects the modules of a few seconds into one rescan instead of rebuilding the graph per module;
 *  - [quietPeriodMs] grows with it, so the detail writer waits out the gaps between those batched
 *    rescans instead of writing a snapshot the next one makes outdated.
 *
 * Both drop back once the changes leave the window. A [maxRescanDelayMs] of `0` turns batching off; the
 * quiet period then stays at [baseQuietMs].
 *
 * The [clock] is injectable for tests; all state is guarded by the instance monitor.
 */
class BuildActivity(
    private val maxRescanDelayMs: Long,
    private val baseQuietMs: Long,
    private val windowMs: Long = 20_000,
    private val stepMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val changes = ArrayDeque<Long>() // report timestamps within the trailing window

    /** Records that the watcher reported a change. */
    @Synchronized
    fun recordChange() {
        val now = clock()
        changes.addLast(now)
        evict(now)
    }

    /** How long, ms, to collect further changes before rescanning the ones pending: `0` when calm. */
    @Synchronized
    fun rescanDelayMs(): Long {
        evict(clock())
        return ((changes.size - 1).coerceAtLeast(0) * stepMs).coerceAtMost(maxRescanDelayMs)
    }

    /**
     * How long, ms, no new build must arrive before the detail snapshot is written: [baseQuietMs] when
     * calm; while busy, longer than the gap between two batched rescans (the batch window plus the rescan
     * itself), up to [baseQuietMs] + [maxRescanDelayMs] + [RESCAN_ALLOWANCE_MS].
     */
    @Synchronized
    fun quietPeriodMs(): Long {
        val delay = rescanDelayMs()
        return if (delay == 0L) baseQuietMs else baseQuietMs + delay + RESCAN_ALLOWANCE_MS
    }

    private fun evict(now: Long) {
        val cutoff = now - windowMs
        while (changes.isNotEmpty() && changes.first() < cutoff) changes.removeFirst()
    }

    private companion object {
        /** About how long one rescan of a large build takes; a batched rescan arrives this much after its window. */
        const val RESCAN_ALLOWANCE_MS = 2_000L
    }
}
