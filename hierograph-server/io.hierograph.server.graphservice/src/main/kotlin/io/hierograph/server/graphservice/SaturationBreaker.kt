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

/**
 * A rate guard for graph rebuilds: once [UpdateCoalescer] has coalesced away the redundant work, this is
 * the second line of defence against *sustained* churn — a watch-mode compile loop, a `git bisect`, a
 * branch that keeps flipping — where even coalesced rebuilds arrive faster than they complete.
 *
 * It counts rebuild completions in a trailing [windowMs]. While that count is at or above
 * [maxRebuildsInWindow] the breaker is *tripped*: the coalescer defers rebuilding and keeps serving the
 * last good graph (the graph is a read model, so a slightly stale graph beats a server pinned at 100%
 * CPU or heading for OOM), retrying after [cooldownMs]. Because no rebuilds are recorded while tripped,
 * the window drains on its own and the breaker resets once the churn stops — and under *continued* churn
 * the recorded completions bound the rebuild rate to roughly one burst per window.
 *
 * An explicit `reload_graph` does not pass through here, so the operator always has a manual override.
 *
 * The [clock] is injectable for tests; all state is guarded by the instance monitor.
 */
class SaturationBreaker(
    private val windowMs: Long,
    private val maxRebuildsInWindow: Int,
    private val cooldownMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(SaturationBreaker::class.java)

    private val completions = ArrayDeque<Long>() // rebuild-completion timestamps within the trailing window
    private var tripped = false

    /** Records that a rebuild just completed. */
    @Synchronized
    fun recordRebuild() {
        val now = clock()
        completions.addLast(now)
        evict(now)
    }

    /**
     * Consulted before each rebuild: the delay (ms) to wait before retrying when the rebuild rate has
     * saturated the trailing window, or `null` to proceed now. Logs once on tripping and once on resuming.
     */
    @Synchronized
    fun pauseDelayMs(): Long? {
        val now = clock()
        evict(now)
        if (maxRebuildsInWindow > 0 && completions.size >= maxRebuildsInWindow) {
            if (!tripped) {
                tripped = true
                log.warn(
                    "Graph updates paused: {} rebuild(s) within {} ms exceeds the {} allowed; serving the last " +
                        "good graph and retrying every {} ms until the build settles.",
                    completions.size, windowMs, maxRebuildsInWindow, cooldownMs,
                )
            }
            return cooldownMs
        }
        if (tripped) {
            tripped = false
            log.info("Graph updates resumed: rebuild rate back under {} per {} ms.", maxRebuildsInWindow, windowMs)
        }
        return null
    }

    private fun evict(now: Long) {
        val cutoff = now - windowMs
        while (completions.isNotEmpty() && completions.first() < cutoff) completions.removeFirst()
    }
}
