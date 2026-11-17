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
 * The DSM and the cycle detection on a forked hierarchy whose top level is local group nodes — the
 * shape a view materializes. Four packages `p1`…`p4`, one type each, directly below the root; the
 * groups are `left = {p1, p2}` and `right = {p3, p4}`.
 */
class DsmOnForkTest {

    private var nextId = 1L
    private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
    private val depSource = { DefaultDependencySource(identifier = nextId++) }
    private val graph = HGGraphFactory.createHGGraph()
    private val root = HGGraphFactory.createNode(graph, nodeSource)
    private val base = HierarchyFactory.createHierarchy(graph, root)

    private fun node(parent: HGNode = root): HGNode =
        HGGraphFactory.createNode(graph, nodeSource).also { HierarchyFactory.addChild(base, parent, it) }

    private val p1 = node(); private val t1 = node(p1)
    private val p2 = node(); private val t2 = node(p2)
    private val p3 = node(); private val t3 = node(p3)
    private val p4 = node(); private val t4 = node(p4)

    private fun dep(from: HGNode, to: HGNode, weight: Int = 1) {
        HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON", depSource).weight = weight
    }

    private class Grouped(val hierarchy: Hierarchy, val left: HGNode, val right: HGNode)

    private fun grouped(): Grouped {
        val fork = base.fork()
        fun group(name: String, vararg members: HGNode) =
            fork.createLocalNode("view.group") { SyntheticNodeSource(nextId++, name) }.also { g ->
                fork.addChild(root, g)
                members.forEach { fork.move(it, g) }
            }
        return Grouped(fork, group("left", p1, p2), group("right", p3, p4))
    }

    @Test
    fun `group cells are the sums of their members' cells, internal weight on the diagonal`() {
        dep(t1, t2, 2)   // inside left
        dep(t1, t3, 3)   // left → right
        dep(t2, t4, 5)   // left → right
        dep(t3, t4, 7)   // inside right
        val v = grouped()

        val dsm = GraphUtils.createDependencyStructureMatrix(listOf(v.left, v.right), v.hierarchy)

        assertThat(dsm.orderedNodes).containsExactly(v.left, v.right)
        val l = dsm.orderedNodes.indexOf(v.left)
        val r = dsm.orderedNodes.indexOf(v.right)
        assertThat(dsm.getWeight(l, r)).isEqualTo(8)
        assertThat(dsm.getWeight(r, l)).isZero()
        assertThat(dsm.getWeight(l, l)).isEqualTo(2)
        assertThat(dsm.getWeight(r, r)).isEqualTo(7)
        assertThat(dsm.cycles).isEmpty()
        assertThat(dsm.upwardDependencies).isEmpty()
    }

    @Test
    fun `grouping can turn an acyclic package graph into a cycle between groups`() {
        dep(t1, t3)   // left → right
        dep(t4, t2)   // right → left
        val v = grouped()

        assertThat(GraphUtils.detectCycles(listOf(p1, p2, p3, p4), base)).isEmpty()
        assertThat(GraphUtils.detectCycles(listOf(v.left, v.right), v.hierarchy).map { it.toSet() })
            .containsExactly(setOf(v.left, v.right))

        val dsm = GraphUtils.createDependencyStructureMatrix(listOf(v.left, v.right), v.hierarchy)
        assertThat(dsm.isCellInCycle(0, 1)).isTrue()
        assertThat(dsm.isCellInCycle(1, 0)).isTrue()
        assertThat(dsm.upwardDependencies).hasSize(1)
    }

    @Test
    fun `grouping can absorb a package cycle into one group`() {
        dep(t1, t2)   // p1 ↔ p2, both in left
        dep(t2, t1)
        dep(t2, t3)   // left → right
        val v = grouped()

        assertThat(GraphUtils.detectCycles(listOf(p1, p2, p3, p4), base)).hasSize(1)
        assertThat(GraphUtils.detectCycles(listOf(v.left, v.right), v.hierarchy)).isEmpty()
        // the cycle is still visible one level down, inside the group
        assertThat(GraphUtils.detectCycles(v.hierarchy.childrenOf(v.left), v.hierarchy).map { it.toSet() })
            .containsExactly(setOf(p1, p2))
    }

    @Test
    fun `the DSM of the base hierarchy is unchanged by a grouping in a fork`() {
        dep(t1, t3, 3)
        dep(t4, t2, 1)
        val before = GraphUtils.computeAdjacencyMatrix(listOf(p1, p2, p3, p4), base).map { it.toList() }

        grouped()

        assertThat(base.childrenOf(root)).containsExactly(p1, p2, p3, p4)
        assertThat(GraphUtils.computeAdjacencyMatrix(listOf(p1, p2, p3, p4), base).map { it.toList() })
            .isEqualTo(before)
    }

    @Test
    fun `a DSM can mix groups and ungrouped nodes`() {
        dep(t1, t3, 3)
        dep(t4, t2, 1)
        val fork = base.fork()
        val left = fork.createLocalNode("view.group") { SyntheticNodeSource(nextId++, "left") }
        fork.addChild(root, left)
        fork.move(p1, left)
        fork.move(p2, left)

        val top = fork.childrenOf(root)
        assertThat(top).containsExactlyInAnyOrder(p3, p4, left)
        val matrix = GraphUtils.computeAdjacencyMatrix(listOf(left, p3, p4), fork)
        assertThat(matrix[0][1]).isEqualTo(3)   // left → p3
        assertThat(matrix[2][0]).isEqualTo(1)   // p4 → left
        assertThat(matrix[0][2]).isZero()
    }
}
