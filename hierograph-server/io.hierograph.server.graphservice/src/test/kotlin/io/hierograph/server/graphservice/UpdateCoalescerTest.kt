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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class UpdateCoalescerTest {

    /**
     * A storm of bursts arriving *while a rebuild is in flight* must not queue one rebuild each: they
     * accumulate and are swept by a single follow-up pass — ~2 rebuilds total, not N.
     */
    @Test
    fun `bursts during a rebuild collapse into a single follow-up pass`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val calls = CopyOnWriteArrayList<Set<String>>()
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        val coalescer = UpdateCoalescer(executor) { _, gavs ->
            calls.add(gavs)
            if (calls.size == 1) {
                firstStarted.countDown()
                release.await() // hold the first rebuild in flight while the storm arrives
            }
        }

        // Kick off the first rebuild and wait until it is actually running.
        coalescer.scheduleIncremental(setOf("a"))
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).`as`("first rebuild started").isTrue()

        // While it is held, fire a storm — these must fold into the pending set, not queue new tasks.
        repeat(50) { i -> coalescer.scheduleIncremental(setOf("m$i")) }

        // Let the first rebuild finish; the accumulated bursts drain in one follow-up pass.
        release.countDown()
        executor.shutdown()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).`as`("drain completed").isTrue()

        // Exactly two rebuilds: the in-flight one, then one sweep of everything that arrived during it.
        assertThat(calls).hasSize(2)
        assertThat(calls[0]).containsExactly("a")
        assertThat(calls[1]).containsExactlyInAnyOrderElementsOf((0 until 50).map { "m$it" })
    }

    /** A manifest change arriving during a rebuild supersedes the GAV rescans accumulated alongside it. */
    @Test
    fun `a manifest change during a rebuild supersedes pending GAV rescans`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val calls = CopyOnWriteArrayList<Pair<Boolean, Set<String>>>()
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        val coalescer = UpdateCoalescer(executor) { reload, gavs ->
            calls.add(reload to gavs)
            if (calls.size == 1) {
                firstStarted.countDown()
                release.await()
            }
        }

        coalescer.scheduleIncremental(setOf("a"))
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue()

        // A manifest reload lands between two incremental bursts, all while the first rebuild is held.
        coalescer.scheduleIncremental(setOf("b"))
        coalescer.scheduleManifestReload()
        coalescer.scheduleIncremental(setOf("c"))

        release.countDown()
        executor.shutdown()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()

        // The follow-up pass is a single manifest reload (b and c are subsumed), never a GAV rescan.
        assertThat(calls).hasSize(2)
        assertThat(calls[0]).isEqualTo(false to setOf("a"))
        assertThat(calls[1].first).`as`("follow-up is a manifest reload").isTrue()
        assertThat(calls[1].second).`as`("reload re-derives the module set; pending GAVs dropped").isEmpty()
    }

    /** A burst arriving after the drain has gone idle starts a fresh pass of its own. */
    @Test
    fun `a burst after the drain goes idle starts a fresh pass`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val calls = CopyOnWriteArrayList<Set<String>>()
        val coalescer = UpdateCoalescer(executor) { _, gavs -> calls.add(gavs) }

        coalescer.scheduleIncremental(setOf("a"))
        drain(executor) // let the first drain fully finish (go idle) before the next burst
        coalescer.scheduleIncremental(setOf("b"))
        drain(executor)

        assertThat(calls).containsExactly(setOf("a"), setOf("b"))
    }

    /**
     * A tripped saturation breaker makes the coalescer defer the rebuild (serve the last good graph) and
     * re-arm after the cooldown; once the window clears, the deferred work is applied with nothing lost.
     */
    @Test
    fun `a tripped breaker defers the rebuild, then applies it once the window clears`() {
        val clock = AtomicLong(0)
        // Trip after a single rebuild; a short cooldown so the re-armed drain fires quickly in the test.
        val breaker = SaturationBreaker(windowMs = 100, maxRebuildsInWindow = 1, cooldownMs = 5, clock = clock::get)
        val executor = Executors.newSingleThreadScheduledExecutor()
        val calls = CopyOnWriteArrayList<Set<String>>()
        val coalescer = UpdateCoalescer(executor, breaker) { _, gavs -> calls.add(gavs) }

        // First burst goes through and records one completion (clock frozen at 0).
        coalescer.scheduleIncremental(setOf("a"))
        drain(executor)
        assertThat(calls).containsExactly(setOf("a"))

        // Second burst: the breaker is now saturated, so the rebuild is deferred — `b` is NOT applied.
        coalescer.scheduleIncremental(setOf("b"))
        drain(executor) // the drain that saw saturation has run and only scheduled a retry
        assertThat(calls).`as`("rebuild deferred while tripped").containsExactly(setOf("a"))

        // Advance past the window so the recorded completion ages out; the re-armed drain then applies `b`.
        clock.set(500)
        awaitUntil { calls.size == 2 }
        assertThat(calls).containsExactly(setOf("a"), setOf("b"))

        executor.shutdownNow()
    }

    /** Blocks until the single-thread [executor] has processed everything submitted so far. */
    private fun drain(executor: java.util.concurrent.ExecutorService) {
        val done = CountDownLatch(1)
        executor.submit { done.countDown() }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
    }

    /** Polls [cond] until true or the timeout elapses (for the breaker's re-armed, time-delayed retry). */
    private fun awaitUntil(timeoutMs: Long = 2000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(2)
        assertThat(cond()).`as`("condition met within $timeoutMs ms").isTrue()
    }

    /** While the build is busy, changes arriving within the batch window share one rescan. */
    @Test
    fun `changes within the batch window share one rescan, counted from the first`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val calls = CopyOnWriteArrayList<Set<String>>()
        val callTimes = CopyOnWriteArrayList<Long>()
        val coalescer = UpdateCoalescer(executor, rescanDelayMs = { 400L }) { _, gavs ->
            calls.add(gavs)
            callTimes.add(System.nanoTime())
        }

        val start = System.nanoTime()
        coalescer.scheduleIncremental(setOf("a"))
        Thread.sleep(150)
        coalescer.scheduleIncremental(setOf("b"))
        Thread.sleep(150)
        coalescer.scheduleIncremental(setOf("c"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (calls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        Thread.sleep(300) // nothing else may follow
        executor.shutdownNow()

        assertThat(calls).containsExactly(setOf("a", "b", "c"))
        // The window counts from the first change: the rescan starts about 400 ms after "a", not after "c".
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(callTimes.single() - start)
        assertThat(waitedMs).isBetween(400L, 650L)
    }

    /** A manifest reload is never held back by the batch window. */
    @Test
    fun `a manifest reload is not delayed by the batch window`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val ran = CountDownLatch(1)
        val coalescer = UpdateCoalescer(executor, rescanDelayMs = { 10_000L }) { reload, _ -> if (reload) ran.countDown() }

        coalescer.scheduleManifestReload()

        assertThat(ran.await(1, TimeUnit.SECONDS)).isTrue()
        executor.shutdownNow()
    }
}
