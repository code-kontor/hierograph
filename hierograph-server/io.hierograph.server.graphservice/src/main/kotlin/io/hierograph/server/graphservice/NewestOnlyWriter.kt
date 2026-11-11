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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs [write] on [executor] for the newest scheduled item only.
 *
 * A single slot holds the item waiting to be written; [schedule] overwrites it, so an item that a newer
 * one replaces before its turn is dropped without being written, and nothing keeps it reachable. At
 * most one item is being written and at most one waits, however often [schedule] is called. With a
 * single-thread [executor], writes never overlap.
 *
 * The task handed to [executor] captures no item: it takes whatever is in the slot when it runs. Each
 * item is written in its own `try`, so a failing write never leaves an item in the slot without a task
 * to take it.
 */
internal class NewestOnlyWriter<T : Any>(
    private val executor: Executor,
    private val write: (T) -> Unit,
) {

    private val pending = AtomicReference<T?>(null)

    /** Make [item] the next one to write, replacing any item that is still waiting. */
    fun schedule(item: T) {
        // Only an empty slot needs a task: a non-empty one already has a task that will take the item.
        if (pending.getAndSet(item) == null) executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val next = pending.getAndSet(null) ?: return
            try {
                write(next)
            } catch (e: Exception) {
                log.error("Background write failed", e)
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(NewestOnlyWriter::class.java)
    }
}
