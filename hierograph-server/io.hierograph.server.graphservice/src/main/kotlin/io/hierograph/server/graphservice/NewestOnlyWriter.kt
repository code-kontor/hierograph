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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs [write] on [executor] for the newest scheduled item only, once no newer item has arrived for
 * the quiet period [quietPeriodMs] returns. It is asked again at every check, so it may change meanwhile,
 * for example grow while a build is busy and shrink once it calms down.
 *
 * A single slot holds the item waiting to be written; [schedule] overwrites it, so an item that a newer
 * one replaces before its turn is dropped without being written, and nothing keeps it reachable. At
 * most one item is being written and at most one waits, however often [schedule] is called. With a
 * single-thread [executor], writes never overlap.
 *
 * While [hold] is true, for example because a rebuild is due that will make the waiting item outdated, no
 * item is taken either; the task checks again every [HOLD_RECHECK_MS].
 *
 * An item is taken only after it has stayed in the slot for [quietPeriodMs]; until then the task
 * re-arms itself for the remaining time. During a burst of [schedule] calls closer together than the
 * quiet period, nothing is written, and the last item is written once the burst ends. A quiet period
 * of `0` writes as soon as the executor is free.
 *
 * At most one task is queued, armed or running at any time. It captures no item: it takes whatever is
 * in the slot when it runs, and it ends only once the slot is empty. Each item is written in its own
 * `try`, so a failing write never leaves an item in the slot without a task to take it.
 */
internal class NewestOnlyWriter<T : Any>(
    private val executor: ScheduledExecutorService,
    private val quietPeriodMs: () -> Long = { 0L },
    private val hold: () -> Boolean = { false },
    private val nanoTime: () -> Long = System::nanoTime,
    private val write: (T) -> Unit,
) {

    /** An item and when it entered the slot. A fresh instance per [schedule], so a replacement is detected by identity. */
    private class Pending<T>(val item: T, val scheduledAt: Long)

    private val pending = AtomicReference<Pending<T>?>(null)

    /** Whether a task is queued, armed or running; it stays set until that task has seen an empty slot. */
    private val active = AtomicBoolean(false)

    /** Make [item] the next one to write, replacing any item that is still waiting. */
    fun schedule(item: T) {
        pending.set(Pending(item, nanoTime()))
        if (active.compareAndSet(false, true)) executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val next = pending.get()
            if (next == null) {
                active.set(false)
                // An item that arrived after the read above, but before the flag was cleared, found the task
                // still active and started none: take it here unless a new task already has.
                if (pending.get() != null && active.compareAndSet(false, true)) continue
                return
            }
            val remaining = TimeUnit.MILLISECONDS.toNanos(quietPeriodMs()) - (nanoTime() - next.scheduledAt)
            if (remaining > 0) {
                // Not quiet yet: re-arm for the rest of the period, but check at least every second, since
                // the period may shrink meanwhile; a newer item restarts its own period.
                executor.schedule(::drain, minOf(remaining, RECHECK_NANOS), TimeUnit.NANOSECONDS)
                return
            }
            if (hold()) {
                // A newer item is on its way: wait for it instead of writing this one.
                executor.schedule(::drain, HOLD_RECHECK_MS, TimeUnit.MILLISECONDS)
                return
            }
            if (!pending.compareAndSet(next, null)) continue // replaced meanwhile: check the newer item
            try {
                write(next.item)
            } catch (e: Exception) {
                log.error("Background write failed", e)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(NewestOnlyWriter::class.java)

        /** The longest a waiting task sleeps before it asks for the quiet period again. */
        private val RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1)

        /** How often a held task checks whether it may write. */
        private const val HOLD_RECHECK_MS = 200L
    }
}
