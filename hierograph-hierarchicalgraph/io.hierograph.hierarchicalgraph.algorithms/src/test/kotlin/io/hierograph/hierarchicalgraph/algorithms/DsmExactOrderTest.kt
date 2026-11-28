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
package io.hierograph.hierarchicalgraph.algorithms

import io.hierograph.hierarchicalgraph.algorithms.internal.FastFAS
import io.hierograph.hierarchicalgraph.algorithms.internal.FastFasSorter
import io.hierograph.hierarchicalgraph.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A cycle of up to [FastFasSorter.EXACT_LIMIT] nodes gets the order with the least upward weight; a larger one keeps
 * the heuristic order and says so. The cycles list their members in matrix order.
 */
class DsmExactOrderTest {

    private class Builder {
        private var nextId = 1L
        private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        private val depSource = { DefaultDependencySource(identifier = nextId++) }
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        val names = HashMap<HGNode, String>()

        fun node(name: String): HGNode =
            HGGraphFactory.createNode(graph, nodeSource).also {
                HierarchyFactory.addChild(hierarchy, root, it)
                names[it] = name
            }

        fun dep(from: HGNode, to: HGNode, weight: Int = 1) {
            repeat(weight) { HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON", depSource) }
        }

        fun names(nodes: List<HGNode>) = nodes.map { names.getValue(it) }
    }

    /** The weight of the cells below the diagonal: the edges from a node to an earlier one. */
    private fun upwardWeight(dsm: IDependencyStructureMatrix): Long {
        val m = dsm.getMatrix()
        var weight = 0L
        for (i in m.indices) for (j in 0 until i) weight += m[i][j]
        return weight
    }

    private fun permutations(n: Int): Sequence<IntArray> = sequence {
        val a = IntArray(n) { it }
        suspend fun SequenceScope<IntArray>.permute(k: Int) {
            if (k == n) { yield(a.copyOf()); return }
            for (i in k until n) {
                a[k] = a[i].also { a[i] = a[k] }
                permute(k + 1)
                a[k] = a[i].also { a[i] = a[k] }
            }
        }
        permute(0)
    }

    @Test
    fun `a ring the heuristic cuts at its second-lightest edge gets the order that cuts the lightest`() {
        // FastFAS and the bubble pass put c → b (2) above the diagonal; cutting d → c (1) is enough.
        val b = Builder()
        val a = b.node("a"); val bb = b.node("b"); val c = b.node("c"); val d = b.node("d")
        b.dep(a, d, 5); b.dep(bb, a, 5); b.dep(c, bb, 2); b.dep(d, c, 1)
        val matrix = DependencyAggregation.computeAdjacencyMatrix(listOf(a, bb, c, d), b.hierarchy)
        val heuristic = FastFAS(matrix).getOrderedSequence()
        assertThat(FastFasSorter.upwardWeight(heuristic, matrix)).isEqualTo(2)

        val dsm = GraphAlgorithms.createDependencyStructureMatrix(listOf(a, bb, c, d), b.hierarchy)

        assertThat(upwardWeight(dsm)).isEqualTo(1)
        assertThat(b.names(dsm.orderedNodes)).containsExactly("c", "b", "a", "d")
        assertThat(b.names(dsm.upwardDependencies.map { it.from })).containsExactly("d")
        assertThat(dsm.isOrderExact).isTrue()
    }

    @Test
    fun `the exact order is the cheapest of all orders`() {
        val random = java.util.Random(7)
        repeat(300) {
            val n = 2 + random.nextInt(6)
            val matrix = Array(n) { i -> IntArray(n) { j -> if (i != j && random.nextInt(2) == 0) 1 + random.nextInt(9) else 0 } }
            val best = permutations(n).minOf { FastFasSorter.upwardWeight(it, matrix) }
            assertThat(FastFasSorter.upwardWeight(FastFasSorter.exactOrder(matrix), matrix)).isEqualTo(best)
        }
    }

    @Test
    fun `the DSM of a small set puts the least possible weight above the diagonal`() {
        val random = java.util.Random(11)
        repeat(200) {
            val b = Builder()
            val nodes = (0 until 7).map { b.node("v$it") }
            for (i in nodes.indices) for (j in nodes.indices) {
                if (i != j && random.nextInt(3) == 0) b.dep(nodes[i], nodes[j], 1 + random.nextInt(5))
            }
            val dsm = GraphAlgorithms.createDependencyStructureMatrix(nodes, b.hierarchy)
            val matrix = DependencyAggregation.computeAdjacencyMatrix(nodes, b.hierarchy)
            val best = permutations(nodes.size).minOf { FastFasSorter.upwardWeight(it, matrix) }

            assertThat(upwardWeight(dsm)).isEqualTo(best)
            assertThat(dsm.isOrderExact).isTrue()
        }
    }

    @Test
    fun `a cycle larger than the limit keeps the heuristic order and is not exact`() {
        val b = Builder()
        val ring = (0..FastFasSorter.EXACT_LIMIT).map { b.node("r$it") }
        for (i in ring.indices) b.dep(ring[i], ring[(i + 1) % ring.size])

        val dsm = GraphAlgorithms.createDependencyStructureMatrix(ring, b.hierarchy)

        assertThat(dsm.cycles).hasSize(1)
        assertThat(dsm.cycles.single()).hasSize(FastFasSorter.EXACT_LIMIT + 1)
        assertThat(dsm.isOrderExact).isFalse()
    }

    @Test
    fun `cycles list their members in matrix order, sorted by their first member`() {
        val b = Builder()
        val top = b.node("top"); val p = b.node("p"); val q = b.node("q")
        val mid = b.node("mid"); val x = b.node("x"); val y = b.node("y"); val z = b.node("z")
        b.dep(top, p); b.dep(p, q, 3); b.dep(q, p)
        b.dep(q, mid); b.dep(mid, x); b.dep(x, y, 2); b.dep(y, z, 2); b.dep(z, x)

        val dsm = GraphAlgorithms.createDependencyStructureMatrix(listOf(z, y, x, mid, q, p, top), b.hierarchy)
        val position = dsm.orderedNodes.withIndex().associate { (i, n) -> n to i }

        assertThat(b.names(dsm.orderedNodes)).containsExactly("top", "p", "q", "mid", "x", "y", "z")
        assertThat(dsm.cycles.map { b.names(it) }).containsExactly(listOf("p", "q"), listOf("x", "y", "z"))
        for (cycle in dsm.cycles) assertThat(cycle.map { position.getValue(it) }).isSorted()
    }
}
