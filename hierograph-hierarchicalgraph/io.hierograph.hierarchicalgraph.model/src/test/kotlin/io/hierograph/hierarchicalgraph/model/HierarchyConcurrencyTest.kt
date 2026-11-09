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
package io.hierograph.hierarchicalgraph.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Concurrent reads on a hierarchy whose caches start empty: every thread gets the answers a single-threaded
 * read gives, and no read fails. A fork starts with empty caches, so each round races on a cold hierarchy.
 */
class HierarchyConcurrencyTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 0L
    private fun node() = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }

    private val root = node()
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
    private val modules = List(10) { node().also { HierarchyFactory.addChild(hierarchy, root, it) } }
    private val packages = modules.flatMap { m -> List(8) { node().also { HierarchyFactory.addChild(hierarchy, m, it) } } }
    private val types = packages.flatMap { p -> List(10) { node().also { HierarchyFactory.addChild(hierarchy, p, it) } } }
    private val model: HGModel

    init {
        val random = Random(42)
        repeat(4_000) {
            val from = types[random.nextInt(types.size)]
            val to = types[random.nextInt(types.size)]
            if (from !== to) HGGraphFactory.createCoreDependency(from, to, "USES") { DefaultDependencySource(identifier = nextId++) }
        }
        model = HGModel(graph, hierarchy)
    }

    /** Everything the caches back: aggregated dependencies (present and absent), accumulations, predecessors. */
    private fun answers(h: Hierarchy): List<Int> = buildList {
        for (a in packages) for (b in packages) add(h.getAggregatedDependency(a, b)?.coreDependencies?.size ?: -1)
        for (m in modules) add(h.accumulatedIncoming(m).size)
        for (m in modules) add(h.accumulatedOutgoing(m).size)
        for (t in types) add(h.predecessorsOf(t).size)
    }

    @Test
    fun `concurrent reads on a cold hierarchy agree with a single-threaded read`() {
        val reference = answers(model.fork().hierarchy)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val failures = Collections.synchronizedList(mutableListOf<String>())
        try {
            repeat(20) { round ->
                val cold = model.fork().hierarchy
                val start = CountDownLatch(1)
                val done = CountDownLatch(threads)
                repeat(threads) { t ->
                    pool.submit {
                        try {
                            start.await()
                            // each thread fills the caches in its own order before reading everything
                            val order = packages.shuffled(Random(t))
                            for (a in order) for (b in order) cold.getAggregatedDependency(a, b)
                            if (answers(cold) != reference) failures += "round $round, thread $t: answers differ"
                        } catch (e: Throwable) {
                            failures += "round $round, thread $t: $e"
                        } finally {
                            done.countDown()
                        }
                    }
                }
                start.countDown()
                assertThat(done.await(60, TimeUnit.SECONDS)).`as`("round $round finished").isTrue()
            }
        } finally {
            pool.shutdownNow()
        }
        assertThat(failures).isEmpty()
    }
}
