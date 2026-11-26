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
package io.hierograph.server.graphql.controller

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.graphql.model.NodeSelection
import io.hierograph.server.graphql.model.NodeType
import io.hierograph.server.mapping.GraphStoreNodeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `Node.incomingDependencies` / `outgoingDependencies` (#79): the dependencies crossing a node's boundary as a
 * dependency set, and drilling that set down, folded with `compact`.
 */
class BoundaryDependenciesTest {

    /**
     * ```
     * root ├ module m1 ├ p (P1, P2)
     *      │           └ x › x.y (Y)       a chain: x holds nothing but x.y
     *      └ module m2 └ q (Q)
     * ```
     * P1 → P2 (inside p), P1 → Q, Y → P2, Q → P1, Q → Y.
     */
    private class Example {
        val graph = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        val root: HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val hierarchy: Hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun add(parent: HGNode, kind: Any, name: String, fqn: String): HGNode =
            HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, fqn, fqn) }.also {
                it.kind = kind
                HierarchyFactory.addChild(hierarchy, parent, it)
            }
        private fun depend(from: HGNode, to: HGNode) {
            HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource(nextId++) }
        }
        val m1 = add(root, JavaNodeKind.MODULE, "m1.jar", "com.acme:m1:1.0")
        val p = add(m1, JavaNodeKind.PACKAGE, "p", "p")
        val p1 = add(p, JavaNodeKind.CLASS, "P1", "p.P1")
        val p2 = add(p, JavaNodeKind.CLASS, "P2", "p.P2")
        val x = add(m1, JavaNodeKind.PACKAGE, "x", "x")
        val xy = add(x, JavaNodeKind.PACKAGE, "y", "x.y")
        val y = add(xy, JavaNodeKind.CLASS, "Y", "x.y.Y")
        val m2 = add(root, JavaNodeKind.MODULE, "m2.jar", "com.acme:m2:1.0")
        val q = add(m2, JavaNodeKind.PACKAGE, "q", "q")
        val qType = add(q, JavaNodeKind.CLASS, "Q", "q.Q")
        init {
            depend(p1, p2)
            depend(p1, qType)
            depend(y, p2)
            depend(qType, p1)
            depend(qType, y)
        }
    }

    private val example = Example()
    private val provider = HierarchicalGraphProvider { example.hierarchy }
    private val nodes = NodeController(provider)
    private val sets = DependencySetController(provider)

    private fun ends(deps: List<HGCoreDependency>) = deps.map { it.from to it.to }

    private fun id(node: HGNode) = node.identifier.toString()

    @Test
    fun `the incoming set holds the dependencies from outside the subtree, not those inside it`() {
        with(example) {
            assertThat(ends(nodes.incomingDependencies(p).dependencyList))
                .containsExactlyInAnyOrder(y to p2, qType to p1)
            // One level up, Y → P2 is inside the module.
            assertThat(ends(nodes.incomingDependencies(m1).dependencyList))
                .containsExactlyInAnyOrder(qType to p1, qType to y)
            assertThat(nodes.incomingDependencies(root).dependencyList).isEmpty()
        }
    }

    @Test
    fun `the outgoing set is the mirror image`() {
        with(example) {
            assertThat(ends(nodes.outgoingDependencies(p).dependencyList)).containsExactly(p1 to qType)
            assertThat(ends(nodes.outgoingDependencies(xy).dependencyList)).containsExactly(y to p2)
            assertThat(ends(nodes.outgoingDependencies(m1).dependencyList)).containsExactly(p1 to qType)
            // A type's own set: P1 → P2 crosses P1's boundary.
            assertThat(ends(nodes.outgoingDependencies(p1).dependencyList))
                .containsExactlyInAnyOrder(p1 to p2, p1 to qType)
        }
    }

    @Test
    fun `filtered children drill either side of the set down, folded with compact`() {
        with(example) {
            val incoming = nodes.incomingDependencies(p)

            // The sources: Y in m1 and Q in m2.
            assertThat(sets.filteredChildren(incoming, id(root), NodeType.SOURCE, compact = true))
                .containsExactly(m1, m2)
            // Below m1, the chain end x.y, not x; without compact, x.
            assertThat(sets.filteredChildren(incoming, id(m1), NodeType.SOURCE, compact = true))
                .containsExactly(xy)
            assertThat(sets.filteredChildren(incoming, id(m1), NodeType.SOURCE)).containsExactly(x)
            assertThat(sets.filteredChildrenIds(incoming, id(xy), NodeType.SOURCE, compact = true))
                .containsExactly(id(y))
            // The targets: both types of p.
            assertThat(sets.filteredChildren(incoming, id(p), NodeType.TARGET, compact = true))
                .containsExactly(p1, p2)
            assertThat(sets.filteredChildren(incoming, id(m2), NodeType.TARGET, compact = true)).isEmpty()
        }
    }

    @Test
    fun `the set narrows by a selection on either side, and pages`() {
        with(example) {
            val incoming = nodes.incomingDependencies(p)

            val fromM2 = sets.filteredDependencies(incoming, listOf(NodeSelection(listOf(id(m2)), NodeType.SOURCE)))
            assertThat(ends(fromM2.dependencyList)).containsExactly(qType to p1)
            val intoP2 = sets.filteredDependencies(incoming, listOf(NodeSelection(listOf(id(p2)), NodeType.TARGET)))
            assertThat(ends(intoP2.dependencyList)).containsExactly(y to p2)

            // A narrowed set drills down like the whole one: from m2, only P1 is reached.
            val filtered = FilteredDependenciesController(provider)
            assertThat(filtered.filteredChildren(fromM2, id(p), NodeType.TARGET, compact = true)).containsExactly(p1)
            assertThat(filtered.filteredChildrenIds(intoP2, id(m1), NodeType.SOURCE, compact = true))
                .containsExactly(id(xy))

            val page = sets.dependencyPage(incoming, pageNumber = 2, pageSize = 1)
            assertThat(page.pageInfo.totalCount).isEqualTo(2)
            assertThat(page.pageInfo.maxPages).isEqualTo(2)
            assertThat(page.dependencies).hasSize(1)
            assertThat(sets.size(incoming)).isEqualTo(2)
            // Each dependency here stands for one reference.
            assertThat(sets.references(incoming)).isEqualTo(incoming.dependencyList.sumOf { it.weight })
            assertThat(sets.references(incoming)).isEqualTo(2)
            assertThat(filtered.references(fromM2)).isEqualTo(1)
        }
    }

    @Test
    fun `filtered children come with the dependencies and references their subtrees hold`() {
        with(example) {
            val incoming = nodes.incomingDependencies(p)
            val counted = { parent: HGNode, side: NodeType, compact: Boolean ->
                sets.filteredChildrenWithCounts(incoming, id(parent), side, compact)
                    .map { Triple(it.node, it.dependencies, it.references) }
            }

            // The sources: Y → P2 in m1, Q → P1 in m2, one reference each.
            assertThat(counted(root, NodeType.SOURCE, true)).containsExactly(Triple(m1, 1, 1), Triple(m2, 1, 1))
            // Folded: the chain end x.y holds Y.
            assertThat(counted(m1, NodeType.SOURCE, true)).containsExactly(Triple(xy, 1, 1))
            assertThat(counted(m1, NodeType.SOURCE, false)).containsExactly(Triple(x, 1, 1))
            // The targets, both in p.
            assertThat(counted(m1, NodeType.TARGET, true)).containsExactly(Triple(p, 2, 2))
        }
    }
}
