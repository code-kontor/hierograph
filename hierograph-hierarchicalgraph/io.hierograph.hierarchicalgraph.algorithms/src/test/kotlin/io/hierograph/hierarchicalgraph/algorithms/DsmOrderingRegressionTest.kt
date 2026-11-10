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

import io.hierograph.hierarchicalgraph.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression tests for two DSM ordering defects:
 *
 * 1. Container nodes (modules, packages) have no core dependencies of their own, so they were all
 *    mistaken for sinks and every cycle was moved to the top of the order.
 * 2. `upwardDependencies` was taken from FastFAS's skipped edges before the bubble refinement, so it
 *    could name a dependency that points down in the final order.
 *
 * Both are covered by the invariant checked in [assertUpwardMatchesBelowDiagonal]: the upward
 * dependencies are exactly the non-zero cells below the diagonal.
 */
class DsmOrderingRegressionTest {

    private class Builder {
        private var nextId = 1L
        private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        private val depSource = { DefaultDependencySource(identifier = nextId++) }
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        val names = HashMap<HGNode, String>()

        fun node(name: String, parent: HGNode = root): HGNode =
            HGGraphFactory.createNode(graph, nodeSource).also {
                HierarchyFactory.addChild(hierarchy, parent, it)
                names[it] = name
            }

        /** A package node containing a single type; returns `package to type`. */
        fun pkg(name: String): Pair<HGNode, HGNode> {
            val p = node(name)
            return p to node(name.uppercase(), p)
        }

        fun dep(from: HGNode, to: HGNode, weight: Int = 1) {
            repeat(weight) { HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON", depSource) }
        }

        fun names(nodes: List<HGNode>) = nodes.map { names.getValue(it) }
    }

    @Test
    fun `type-level chain into a cycle keeps the cycle last`() {
        val b = Builder()
        val (_, a) = b.pkg("a"); val (_, bb) = b.pkg("b"); val (_, c) = b.pkg("c")
        val (_, p) = b.pkg("p"); val (_, q) = b.pkg("q")
        b.dep(a, bb); b.dep(bb, c); b.dep(c, p); b.dep(p, q); b.dep(q, p)

        val dsm = GraphUtils.createDependencyStructureMatrix(listOf(a, bb, c, p, q), b.hierarchy)

        assertThat(b.names(dsm.orderedNodes).take(3)).containsExactly("A", "B", "C")
        assertThat(b.names(dsm.orderedNodes).drop(3)).containsExactlyInAnyOrder("P", "Q")
        assertUpwardMatchesBelowDiagonal(dsm)
    }

    @Test
    fun `package-level chain into a cycle keeps the cycle last`() {
        val b = Builder()
        val (pa, a) = b.pkg("a"); val (pb, bb) = b.pkg("b"); val (pc, c) = b.pkg("c")
        val (pp, p) = b.pkg("p"); val (pq, q) = b.pkg("q")
        b.dep(a, bb); b.dep(bb, c); b.dep(c, p); b.dep(p, q); b.dep(q, p)

        val dsm = GraphUtils.createDependencyStructureMatrix(listOf(pa, pb, pc, pp, pq), b.hierarchy)

        assertThat(b.names(dsm.orderedNodes).take(3)).containsExactly("a", "b", "c")
        assertThat(b.names(dsm.orderedNodes).drop(3)).containsExactlyInAnyOrder("p", "q")
        assertUpwardMatchesBelowDiagonal(dsm)
    }

    @Test
    fun `package-level billing example places the cycle between report and money`() {
        val b = Builder()
        val (pReport, report) = b.pkg("report")
        val (pBilling, billing) = b.pkg("billing")
        val (pLedger, ledger) = b.pkg("ledger")
        val (pMoney, money) = b.pkg("money")
        b.dep(billing, ledger); b.dep(ledger, billing)
        b.dep(report, ledger); b.dep(report, money)
        b.dep(billing, money); b.dep(ledger, money)

        val dsm = GraphUtils.createDependencyStructureMatrix(
            listOf(pReport, pBilling, pLedger, pMoney), b.hierarchy
        )

        val order = b.names(dsm.orderedNodes)
        assertThat(order.first()).isEqualTo("report")
        assertThat(order.last()).isEqualTo("money")
        assertThat(order.subList(1, 3)).containsExactlyInAnyOrder("billing", "ledger")
        assertUpwardMatchesBelowDiagonal(dsm)
    }

    @Test
    fun `upward dependencies match the cells below the diagonal for random graphs`() {
        val random = java.util.Random(42)
        repeat(300) {
            val b = Builder()
            val types = (0 until 7).map { b.node("v$it") }
            for (i in types.indices) for (j in types.indices) {
                if (i != j && random.nextInt(4) == 0) b.dep(types[i], types[j], 1 + random.nextInt(5))
            }
            assertUpwardMatchesBelowDiagonal(GraphUtils.createDependencyStructureMatrix(types, b.hierarchy))
        }
    }

    @Test
    fun `upward dependencies match the cells below the diagonal for random package graphs`() {
        val random = java.util.Random(4711)
        repeat(300) {
            val b = Builder()
            val packages = (0 until 6).map { b.node("p$it") }
            val types = packages.flatMap { p -> (0 until 2).map { t -> b.node("${b.names[p]}.T$t", p) } }
            for (i in types.indices) for (j in types.indices) {
                if (b.hierarchy.parentOf(types[i]) != b.hierarchy.parentOf(types[j]) &&
                    random.nextInt(8) == 0
                ) b.dep(types[i], types[j], 1 + random.nextInt(5))
            }
            assertUpwardMatchesBelowDiagonal(GraphUtils.createDependencyStructureMatrix(packages, b.hierarchy))
        }
    }

    private fun assertUpwardMatchesBelowDiagonal(dsm: IDependencyStructureMatrix) {
        val nodes = dsm.orderedNodes
        val matrix = dsm.getMatrix()
        val belowDiagonal = buildSet {
            for (i in nodes.indices) for (j in 0 until i) {
                if (matrix[i][j] > 0) add(nodes[i].identifier to nodes[j].identifier)
            }
        }
        val upward = dsm.upwardDependencies.map { it.from.identifier to it.to.identifier }
        assertThat(upward).doesNotHaveDuplicates()
        assertThat(upward.toSet()).isEqualTo(belowDiagonal)
        // Every below-diagonal cell lies inside a cycle block.
        for (i in nodes.indices) for (j in 0 until i) {
            if (matrix[i][j] > 0) assertThat(dsm.isCellInCycle(i, j)).isTrue()
        }
    }
}
