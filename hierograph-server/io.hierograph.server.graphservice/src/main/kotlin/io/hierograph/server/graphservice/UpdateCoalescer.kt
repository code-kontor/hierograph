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

import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Merges watch-triggered rebuilds so a storm of settled bursts never queues one rebuild each. While a
 * rebuild runs, newly-changed GAVs accumulate and a manifest change is remembered; the running drain
 * then sweeps whatever arrived in a single follow-up pass. At most one rebuild is ever in flight, and
 * the executor never accumulates a backlog — so N overlapping bursts cost ~2 rebuilds, not N.
 *
 * [apply] performs one rebuild and is always invoked on [executor]'s thread, one call at a time:
 *  - `reload = true` (GAVs empty): a manifest reload, which re-derives the whole module set and so
 *    supersedes any pending GAV rescans accumulated so far;
 *  - `reload = false`: an incremental rescan of the accumulated GAVs.
 *
 * [apply] is expected to handle its own failures; a throw that escapes it is caught and logged here so
 * the drain keeps going and `draining` is never left wedged.
 *
 * [rescanDelayMs] batches incremental rescans while a build is busy: pending GAVs are rescanned only once
 * that long has passed since the first of them arrived, so the modules a build compiles within it share
 * one rescan. The window counts from the first pending change, not the last, so a long build delays a
 * rescan by at most the window. A manifest reload is never delayed.
 *
 * When a [breaker] is supplied it throttles *sustained* churn: before each rebuild the drain asks the
 * breaker whether the rebuild rate has saturated; if so it leaves the pending work untouched (the last
 * good graph keeps serving) and re-arms itself after the breaker's cooldown instead of rebuilding now.
 */
class UpdateCoalescer(
    private val executor: ScheduledExecutorService,
    private val breaker: SaturationBreaker? = null,
    private val rescanDelayMs: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
    private val apply: (reload: Boolean, gavs: Set<String>) -> Unit,
) {
    private val log = LoggerFactory.getLogger(UpdateCoalescer::class.java)

    private val lock = Any()
    private val pendingGavs = HashSet<String>()
    private var manifestDirty = false     // a manifest change supersedes pending GAV rescans
    private var draining = false          // a drain task is queued or running
    private var firstPendingAt = 0L       // when the oldest pending GAV arrived (valid while any is pending)

    /**
     * Whether a rebuild is pending or running: from a change until the drain finds no more work, including
     * a batch wait and a breaker pause. A rebuild that is due makes the current graph outdated, so a detail
     * snapshot of it would be wasted.
     */
    fun isBusy(): Boolean = synchronized(lock) { draining }

    /** Fold a settled burst into the pending set; start the drain only if one isn't already pending. */
    fun scheduleIncremental(gavs: Set<String>) {
        synchronized(lock) {
            if (pendingGavs.isEmpty()) firstPendingAt = clock()
            pendingGavs += gavs
            if (draining) return            // in-flight drain will sweep these; don't queue another
            draining = true
        }
        executor.submit { drain() }
    }

    /** Remember a manifest change; it supersedes pending GAV rescans when the drain next picks up. */
    fun scheduleManifestReload() {
        synchronized(lock) {
            manifestDirty = true
            if (draining) return
            draining = true
        }
        executor.submit { drain() }
    }

    /**
     * Applies accumulated changes until none remain, then clears `draining` under the lock so the next
     * burst starts a fresh drain. Everything that arrives during an [apply] collapses into the next loop
     * iteration.
     */
    private fun drain() {
        while (true) {
            // Is there work? Peek without taking it, so a batch wait or a breaker pause can leave it pending.
            val batchWait: Long
            synchronized(lock) {
                if (!manifestDirty && pendingGavs.isEmpty()) {
                    draining = false
                    return
                }
                batchWait = if (manifestDirty) 0L else firstPendingAt + rescanDelayMs() - clock()
            }
            // Busy build: let further modules join this rescan. `draining` stays true meanwhile.
            if (batchWait > 0) {
                executor.schedule({ drain() }, batchWait, TimeUnit.MILLISECONDS)
                return
            }
            // Throttle sustained churn: if the breaker is tripped, keep serving the last good graph and
            // re-arm after the cooldown. `draining` stays true, so bursts arriving meanwhile just
            // accumulate into the pending set rather than queuing more work.
            val pause = breaker?.pauseDelayMs()
            if (pause != null) {
                executor.schedule({ drain() }, pause, TimeUnit.MILLISECONDS)
                return
            }
            val reload: Boolean
            val batch: Set<String>
            synchronized(lock) {
                reload = manifestDirty
                manifestDirty = false
                batch = if (reload) emptySet() else HashSet(pendingGavs)
                pendingGavs.clear()         // a manifest reload re-derives the module set wholesale
            }
            try {
                apply(reload, batch)
            } catch (e: Exception) {
                // apply is expected to swallow its own errors; this only guards the drain loop itself.
                log.error("Coalesced update failed (reload={}, {} module(s)); continuing.", reload, batch.size, e)
            }
            breaker?.recordRebuild()
            // loop: anything that changed *during* that apply is now pending and gets one more pass
        }
    }
}
