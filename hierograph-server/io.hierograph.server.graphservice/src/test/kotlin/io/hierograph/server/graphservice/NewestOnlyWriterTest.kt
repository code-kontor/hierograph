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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NewestOnlyWriterTest {

    /** A generation's data; a large payload makes keeping it alive noticeable, as a GraphStore would. */
    private class Item(val generation: Int) {
        @Suppress("unused") val payload = ByteArray(1024 * 1024)
    }

    private val executor = Executors.newSingleThreadScheduledExecutor()

    @AfterEach
    fun shutdown() {
        executor.shutdownNow()
    }

    /** Waits until everything scheduled so far has run on the single-thread executor. */
    private fun awaitIdle() {
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `items scheduled while a write runs are replaced by the newest`() {
        val written = Collections.synchronizedList(ArrayList<Int>())
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = NewestOnlyWriter<Item>(executor) { item ->
            if (item.generation == 1) {
                firstStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            written += item.generation
        }

        writer.schedule(Item(1))
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue()
        for (generation in 2..10) writer.schedule(Item(generation))
        release.countDown()
        awaitIdle()

        assertThat(written).containsExactly(1, 10)
    }

    @Test
    fun `a replaced item is not kept reachable`() {
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = NewestOnlyWriter<Item>(executor) { item ->
            if (item.generation == 1) {
                firstStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        writer.schedule(Item(1))
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue()

        // Generation 5 waits in the slot until generation 6 replaces it, while generation 1 is still written.
        val replaced = scheduleAndForget(writer, 5)
        writer.schedule(Item(6))

        assertThat(collected(replaced)).isTrue()
        release.countDown()
        awaitIdle()
    }

    /** Schedules a new item in a separate frame, so the test itself keeps no strong reference to it. */
    private fun scheduleAndForget(writer: NewestOnlyWriter<Item>, generation: Int): WeakReference<Item> {
        val item = Item(generation)
        writer.schedule(item)
        return WeakReference(item)
    }

    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(50) {
            System.gc()
            if (ref.get() == null) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `items scheduled while idle are each written`() {
        val written = Collections.synchronizedList(ArrayList<Int>())
        val writer = NewestOnlyWriter<Item>(executor) { written += it.generation }

        for (generation in 1..3) {
            writer.schedule(Item(generation))
            awaitIdle()
        }

        assertThat(written).containsExactly(1, 2, 3)
    }

    @Test
    fun `a failing write does not stop later writes`() {
        val written = Collections.synchronizedList(ArrayList<Int>())
        val writer = NewestOnlyWriter<Item>(executor) { item ->
            check(item.generation != 1) { "write failed" }
            written += item.generation
        }

        writer.schedule(Item(1))
        awaitIdle()
        writer.schedule(Item(2))
        awaitIdle()

        assertThat(written).containsExactly(2)
    }

    @Test
    fun `concurrent scheduling always ends with the newest item written`() {
        val written = Collections.synchronizedList(ArrayList<Int>())
        val writer = NewestOnlyWriter<Item>(executor) { written += it.generation }
        val producers = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val done = (0 until 4).map { p ->
                producers.submit {
                    start.await()
                    for (i in 0 until 250) writer.schedule(Item(p * 1000 + i))
                }
            }
            start.countDown()
            done.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            producers.shutdownNow()
        }
        val last = 9999
        writer.schedule(Item(last))
        awaitIdle()

        assertThat(written.last()).isEqualTo(last)
        assertThat(written).doesNotHaveDuplicates()
    }

    @Test
    fun `a burst closer together than the quiet period writes only its last item, once the burst ends`() {
        val written = Collections.synchronizedList(ArrayList<Int>())
        val writtenAt = Collections.synchronizedList(ArrayList<Long>())
        val writer = NewestOnlyWriter<Item>(executor, quietPeriodMs = 300) { item ->
            writtenAt += System.nanoTime()
            written += item.generation
        }

        var lastScheduledAt = 0L
        for (generation in 1..5) {
            lastScheduledAt = System.nanoTime()
            writer.schedule(Item(generation))
            Thread.sleep(50)
        }
        awaitWritten(written, 1)
        Thread.sleep(500) // nothing else may follow

        assertThat(written).containsExactly(5)
        assertThat(TimeUnit.NANOSECONDS.toMillis(writtenAt.single() - lastScheduledAt)).isGreaterThanOrEqualTo(300)
    }

    @Test
    fun `items separated by more than the quiet period are each written`() {
        val written = Collections.synchronizedList(ArrayList<Int>())
        val writer = NewestOnlyWriter<Item>(executor, quietPeriodMs = 100) { written += it.generation }

        writer.schedule(Item(1))
        awaitWritten(written, 1)
        writer.schedule(Item(2))
        awaitWritten(written, 2)

        assertThat(written).containsExactly(1, 2)
    }

    @Test
    fun `an item scheduled during a write is written after its own quiet period`() {
        val written = Collections.synchronizedList(ArrayList<Int>())
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = NewestOnlyWriter<Item>(executor, quietPeriodMs = 200) { item ->
            if (item.generation == 1) {
                firstStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            written += item.generation
        }

        writer.schedule(Item(1))
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue()
        writer.schedule(Item(2))
        release.countDown()
        Thread.sleep(50)
        assertThat(written).`as`("item 2 has not been quiet for 200 ms yet").containsExactly(1)
        awaitWritten(written, 2)

        assertThat(written).containsExactly(1, 2)
    }

    private fun awaitWritten(written: List<Int>, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (written.size < count && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(written).hasSizeGreaterThanOrEqualTo(count)
    }
}
