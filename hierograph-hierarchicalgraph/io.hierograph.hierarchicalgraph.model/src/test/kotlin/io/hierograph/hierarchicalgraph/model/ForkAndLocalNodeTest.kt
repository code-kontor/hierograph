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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Scenario support of the model (specification §7): forks, local nodes and `move`, alone and combined the
 * way a view materializes a grouping: fork the hierarchy, create a local group node, move members below it.
 *
 * [SimpleTestGraph]:
 * ```
 * root ─┬─ a1 ── a2 ── a3        a1 → b1 (×2), a2 → b2, a3 → b3, every weight 1
 *       └─ b1 ── b2 ── b3
 * ```
 */
class ForkAndLocalNodeTest {

    private lateinit var g: SimpleTestGraph

    private var nextLocalId = 1_000L

    @BeforeEach
    fun setup() {
        g = SimpleTestGraph()
    }

    private fun Hierarchy.group(name: String, parent: HGNode = rootNode): HGNode =
        createLocalNode("view.group") { SyntheticNodeSource(nextLocalId++, name, "view:$name") }
            .also { addChild(parent, it) }

    @Nested
    inner class LocalNodes {

        @Test
        fun `a local node is found by the hierarchy, not by the core graph`() {
            val group = g.hierarchy.group("g")

            assertThat(g.hierarchy.lookupNode(group.identifier)).isSameAs(group)
            assertThat(g.coreGraph.lookupNode(group.identifier)).isNull()
            assertThat(g.hierarchy.localNodes).containsExactly(group)
        }

        @Test
        fun `a local node keeps its kind and synthetic source`() {
            val group = g.hierarchy.group("g")

            assertThat(group.kind).isEqualTo("view.group")
            assertThat(NodeMetadata.getName(group)).isEqualTo("g")
            assertThat(NodeMetadata.getQualifiedName(group)).isEqualTo("view:g")
            assertThat(group.nodeSource.node).isSameAs(group)
            assertThat(group.identifier).isEqualTo(group.nodeSource.identifier)
        }

        @Test
        fun `a local node has no core dependencies of its own`() {
            val group = g.hierarchy.group("g")
            g.hierarchy.move(g.a1, group)

            assertThat(group.outgoingCoreDependencies).isEmpty()
            assertThat(group.incomingCoreDependencies).isEmpty()
        }

        @Test
        fun `a local node accumulates the dependencies of its members`() {
            val group = g.hierarchy.group("g")
            g.hierarchy.move(g.a1, group)

            assertThat(g.hierarchy.accumulatedOutgoing(group))
                .containsExactlyInAnyOrderElementsOf(g.hierarchy.accumulatedOutgoing(g.a1))
                .hasSize(4)
            assertThat(g.hierarchy.accumulatedIncoming(group)).isEmpty()
        }

        @Test
        fun `a local node aggregates to and from real nodes`() {
            val group = g.hierarchy.group("g")
            g.hierarchy.move(g.b1, group)

            val toGroup = g.hierarchy.getAggregatedDependency(g.a1, group)!!
            assertThat(toGroup.aggregatedWeight).isEqualTo(4)
            assertThat(toGroup.coreDependencies).containsExactlyInAnyOrder(
                g.dep_a1_b1_uses, g.dep_a1_b1_depends_on, g.dep_a2_b2_uses, g.dep_a3_b3_depends_on,
            )
            assertThat(g.hierarchy.getAggregatedDependency(g.a2, group)!!.coreDependencies)
                .containsExactlyInAnyOrder(g.dep_a2_b2_uses, g.dep_a3_b3_depends_on)
            assertThat(g.hierarchy.getAggregatedDependency(group, g.a1)).isNull()
        }

        @Test
        fun `two local nodes aggregate between each other like their members`() {
            val left = g.hierarchy.group("left")
            val right = g.hierarchy.group("right")
            g.hierarchy.move(g.a1, left)
            g.hierarchy.move(g.b1, right)

            assertThat(g.hierarchy.getAggregatedDependency(left, right)!!.aggregatedWeight)
                .isEqualTo(g.hierarchy.getAggregatedDependency(g.a1, g.b1)!!.aggregatedWeight)
                .isEqualTo(4)
            assertThat(g.hierarchy.getAggregatedDependency(right, left)).isNull()
        }

        @Test
        fun `a dependency between members of one local node stays inside it`() {
            val group = g.hierarchy.group("g")
            val other = g.hierarchy.group("other")
            g.hierarchy.move(g.a1, group)
            g.hierarchy.move(g.b1, group)

            // every dependency starts and ends inside the group: nothing crosses to a sibling
            assertThat(g.hierarchy.accumulatedOutgoing(group)).hasSize(4)
            assertThat(g.hierarchy.accumulatedIncoming(group)).hasSize(4)
            assertThat(g.hierarchy.getAggregatedDependency(group, other)).isNull()
            assertThat(g.hierarchy.getAggregatedDependency(other, group)).isNull()
            // the members still aggregate between each other below the group
            assertThat(g.hierarchy.getAggregatedDependency(g.a1, g.b1)!!.aggregatedWeight).isEqualTo(4)
        }

        @Test
        fun `nested local nodes form a chain of ancestors`() {
            val outer = g.hierarchy.group("outer")
            val inner = g.hierarchy.group("inner", parent = outer)
            g.hierarchy.move(g.a1, inner)

            assertThat(g.hierarchy.predecessorsOf(g.a3)).containsExactly(g.a2, g.a1, inner, outer, g.root)
            assertThat(g.hierarchy.isPredecessorOf(outer, g.a3)).isTrue()
            assertThat(g.hierarchy.accumulatedOutgoing(outer))
                .containsExactlyInAnyOrderElementsOf(g.hierarchy.accumulatedOutgoing(inner))
                .containsExactlyInAnyOrderElementsOf(g.hierarchy.accumulatedOutgoing(g.a1))
            assertThat(g.hierarchy.getAggregatedDependency(outer, g.b1)!!.aggregatedWeight).isEqualTo(4)
        }

        @Test
        fun `a local node with members is traversed like any subtree`() {
            val group = g.hierarchy.group("g")
            g.hierarchy.move(g.a1, group)

            assertThat(g.hierarchy.descendantsOf(group)).containsExactly(g.a1, g.a2, g.a3)
            assertThat(g.hierarchy.descendantsOf(g.root)).containsExactly(g.b1, g.b2, g.b3, group, g.a1, g.a2, g.a3)
            val visited = mutableListOf<HGNode>()
            g.hierarchy.traverse(group) { visited += it }
            assertThat(visited).containsExactly(g.a1, g.a2, g.a3)
        }
    }

    @Nested
    inner class Move {

        @Test
        fun `a moved node leaves its old parent and is appended to its new parent`() {
            g.hierarchy.move(g.a2, g.root)

            assertThat(g.hierarchy.childrenOf(g.a1)).isEmpty()
            assertThat(g.hierarchy.childrenOf(g.root)).containsExactly(g.a1, g.b1, g.a2)
            assertThat(g.hierarchy.parentOf(g.a2)).isSameAs(g.root)
        }

        @Test
        fun `a moved node takes its subtree along`() {
            g.hierarchy.move(g.a2, g.b1)

            assertThat(g.hierarchy.descendantsOf(g.b1)).containsExactly(g.b2, g.b3, g.a2, g.a3)
            assertThat(g.hierarchy.predecessorsOf(g.a3)).containsExactly(g.a2, g.b1, g.root)
            assertThat(g.hierarchy.descendantsOf(g.a1)).isEmpty()
        }

        @Test
        fun `moving a node to its own parent moves it to the end`() {
            g.hierarchy.move(g.a1, g.root)

            assertThat(g.hierarchy.childrenOf(g.root)).containsExactly(g.b1, g.a1)
        }

        @Test
        fun `grouping all children of a node leaves only the group below it`() {
            val group = g.hierarchy.group("g")
            g.hierarchy.move(g.a1, group)
            g.hierarchy.move(g.b1, group)

            assertThat(g.hierarchy.childrenOf(g.root)).containsExactly(group)
            assertThat(g.hierarchy.childrenOf(group)).containsExactly(g.a1, g.b1)
            // the whole graph is still reachable from the root, plus the group
            assertThat(g.hierarchy.descendantsOf(g.root)).containsExactly(group, g.a1, g.a2, g.a3, g.b1, g.b2, g.b3)
        }
    }

    @Nested
    inner class Guards {

        private fun Hierarchy.snapshot() = descendantsOf(rootNode).map { it to parentOf(it) }

        @Test
        fun `moving a node into its own subtree is rejected and leaves the hierarchy unchanged`() {
            val before = g.hierarchy.snapshot()

            assertThatThrownBy { g.hierarchy.move(g.a1, g.a3) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("own ancestor")
            assertThatThrownBy { g.hierarchy.move(g.a2, g.a2) }
                .isInstanceOf(IllegalArgumentException::class.java)

            assertThat(g.hierarchy.snapshot()).isEqualTo(before)
            assertThat(g.hierarchy.predecessorsOf(g.a3)).containsExactly(g.a2, g.a1, g.root)
        }

        @Test
        fun `moving a node into a local node below it is rejected`() {
            val group = g.hierarchy.group("g", parent = g.a2)

            assertThatThrownBy { g.hierarchy.move(g.a1, group) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(g.hierarchy.parentOf(g.a1)).isSameAs(g.root)
        }

        @Test
        fun `the root cannot be moved or added as a child`() {
            assertThatThrownBy { g.hierarchy.move(g.root, g.a1) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { g.hierarchy.addChild(g.a1, g.root) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(g.hierarchy.parentOf(g.root)).isNull()
        }

        @Test
        fun `adding a node that is already placed is rejected`() {
            assertThatThrownBy { g.hierarchy.addChild(g.b1, g.a2) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("use move")
            assertThatThrownBy { HierarchyFactory.addChild(g.hierarchy, g.b1, g.a2) }
                .isInstanceOf(IllegalArgumentException::class.java)

            assertThat(g.hierarchy.parentOf(g.a2)).isSameAs(g.a1)
            assertThat(g.hierarchy.childrenOf(g.b1)).containsExactly(g.b2)
        }

        @Test
        fun `adding the same local node twice is rejected`() {
            val group = g.hierarchy.group("g")

            assertThatThrownBy { g.hierarchy.addChild(g.b1, group) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(g.hierarchy.childrenOf(g.root)).containsExactly(g.a1, g.b1, group)
        }

        @Test
        fun `adding an unplaced node below its own subtree is rejected`() {
            val outer = g.hierarchy.createLocalNode("view.group") { SyntheticNodeSource(nextLocalId++, "outer") }
            val inner = g.hierarchy.createLocalNode("view.group") { SyntheticNodeSource(nextLocalId++, "inner") }
            g.hierarchy.addChild(outer, inner)

            assertThatThrownBy { g.hierarchy.addChild(inner, outer) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(g.hierarchy.parentOf(outer)).isNull()
        }

        @Test
        fun `a local node may not reuse the identifier of a core node`() {
            assertThatThrownBy {
                g.hierarchy.createLocalNode("view.group") { SyntheticNodeSource(g.a1.identifier, "clash") }
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThat(g.hierarchy.lookupNode(g.a1.identifier)).isSameAs(g.a1)
            assertThat(g.hierarchy.localNodes).isEmpty()
        }

        @Test
        fun `a local node may not reuse the identifier of another local node, also one inherited by a fork`() {
            val group = g.hierarchy.group("g")
            val fork = g.hierarchy.fork()

            assertThatThrownBy {
                g.hierarchy.createLocalNode("view.group") { SyntheticNodeSource(group.identifier, "again") }
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                fork.createLocalNode("view.group") { SyntheticNodeSource(group.identifier, "again") }
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThat(fork.lookupNode(group.identifier)).isSameAs(group)
        }

        @Test
        fun `separate forks may each create a local node with the same identifier`() {
            val first = g.hierarchy.fork()
            val second = g.hierarchy.fork()

            val a = first.createLocalNode("view.group") { SyntheticNodeSource(4711L, "g") }
            val b = second.createLocalNode("view.group") { SyntheticNodeSource(4711L, "g") }

            assertThat(first.lookupNode(4711L)).isSameAs(a)
            assertThat(second.lookupNode(4711L)).isSameAs(b)
        }
    }

    @Nested
    inner class Fork {

        @Test
        fun `a fork starts with the same structure and name`() {
            g.hierarchy.name = "base"

            val fork = g.hierarchy.fork()

            assertThat(fork.name).isEqualTo("base")
            assertThat(fork.rootNode).isSameAs(g.root)
            assertThat(fork.coreGraph).isSameAs(g.coreGraph)
            assertThat(fork.childrenOf(g.root)).containsExactly(g.a1, g.b1)
            assertThat(fork.descendantsOf(g.root)).containsExactlyElementsOf(g.hierarchy.descendantsOf(g.root))
            assertThat(fork.getAggregatedDependency(g.a1, g.b1)!!.aggregatedWeight).isEqualTo(4)
        }

        @Test
        fun `restructuring a fork leaves the original unchanged`() {
            val fork = g.hierarchy.fork()
            val group = fork.group("g")
            fork.move(g.a1, group)
            fork.move(g.b2, g.a3)

            assertThat(g.hierarchy.childrenOf(g.root)).containsExactly(g.a1, g.b1)
            assertThat(g.hierarchy.parentOf(g.a1)).isSameAs(g.root)
            assertThat(g.hierarchy.childrenOf(g.b1)).containsExactly(g.b2)
            assertThat(g.hierarchy.descendantsOf(g.root)).containsExactly(g.a1, g.a2, g.a3, g.b1, g.b2, g.b3)
            assertThat(g.hierarchy.accumulatedOutgoing(g.a1)).hasSize(4)
            assertThat(g.hierarchy.accumulatedIncoming(g.b1)).hasSize(4)
            assertThat(g.hierarchy.getAggregatedDependency(g.a2, g.b2)!!.coreDependencies).hasSize(2)
        }

        @Test
        fun `restructuring a fork keeps the original's warm caches valid`() {
            // warm the original's caches before forking
            val predecessors = g.hierarchy.predecessorsOf(g.b3)
            val accumulated = g.hierarchy.accumulatedIncoming(g.b1)
            val aggregated = g.hierarchy.getAggregatedDependency(g.a1, g.b2)!!

            val fork = g.hierarchy.fork()
            fork.move(g.b2, g.a1)
            fork.move(g.b3, g.root)

            assertThat(g.hierarchy.predecessorsOf(g.b3)).isEqualTo(predecessors).containsExactly(g.b2, g.b1, g.root)
            assertThat(g.hierarchy.accumulatedIncoming(g.b1)).isEqualTo(accumulated).hasSize(4)
            assertThat(g.hierarchy.getAggregatedDependency(g.a1, g.b2)!!.coreDependencies)
                .containsExactlyInAnyOrderElementsOf(aggregated.coreDependencies)
            // the fork answers from its own structure
            assertThat(fork.predecessorsOf(g.b3)).containsExactly(g.root)
            assertThat(fork.getAggregatedDependency(g.a1, g.b2)!!.coreDependencies).containsExactly(g.dep_a2_b2_uses)
        }

        @Test
        fun `restructuring the original leaves an existing fork unchanged`() {
            val fork = g.hierarchy.fork()

            g.hierarchy.move(g.a3, g.b1)

            assertThat(fork.childrenOf(g.a2)).containsExactly(g.a3)
            assertThat(fork.predecessorsOf(g.a3)).containsExactly(g.a2, g.a1, g.root)
            assertThat(fork.accumulatedOutgoing(g.a2)).hasSize(2)
        }

        @Test
        fun `a local node created in a fork is invisible to the original`() {
            val fork = g.hierarchy.fork()
            val group = fork.group("g")

            assertThat(fork.lookupNode(group.identifier)).isSameAs(group)
            assertThat(g.hierarchy.lookupNode(group.identifier)).isNull()
            assertThat(g.hierarchy.localNodes).isEmpty()
            assertThat(g.coreGraph.lookupNode(group.identifier)).isNull()
        }

        @Test
        fun `a local node created after forking is invisible to the fork`() {
            val fork = g.hierarchy.fork()
            val group = g.hierarchy.group("g")

            assertThat(fork.lookupNode(group.identifier)).isNull()
            assertThat(fork.localNodes).isEmpty()
            assertThat(fork.childrenOf(g.root)).containsExactly(g.a1, g.b1)
        }

        @Test
        fun `a fork of a fork inherits its local nodes and structure`() {
            val first = g.hierarchy.fork()
            val group = first.group("g")
            first.move(g.a1, group)

            val second = first.fork()

            assertThat(second.localNodes).containsExactly(group)
            assertThat(second.lookupNode(group.identifier)).isSameAs(group)
            assertThat(second.parentOf(g.a1)).isSameAs(group)
            assertThat(second.getAggregatedDependency(group, g.b1)!!.aggregatedWeight).isEqualTo(4)
        }

        @Test
        fun `a fork of a fork restructures independently of its parent fork`() {
            val first = g.hierarchy.fork()
            val group = first.group("g")
            first.move(g.a1, group)
            val second = first.fork()

            second.move(g.a1, g.root)
            val inner = second.group("inner", parent = group)
            second.move(g.b1, inner)

            assertThat(first.parentOf(g.a1)).isSameAs(group)
            assertThat(first.childrenOf(group)).containsExactly(g.a1)
            assertThat(first.localNodes).containsExactly(group)
            assertThat(first.lookupNode(inner.identifier)).isNull()
            assertThat(second.childrenOf(group)).containsExactly(inner)
        }

        @Test
        fun `a grouping in a fork aggregates exactly like the members do in the original`() {
            val fork = g.hierarchy.fork()
            val left = fork.group("left")
            val right = fork.group("right")
            fork.move(g.a1, left)
            fork.move(g.b1, right)

            val inFork = fork.getAggregatedDependency(left, right)!!
            val inOriginal = g.hierarchy.getAggregatedDependency(g.a1, g.b1)!!
            assertThat(inFork.coreDependencies).containsExactlyInAnyOrderElementsOf(inOriginal.coreDependencies)
            assertThat(inFork.aggregatedWeight).isEqualTo(inOriginal.aggregatedWeight)
            // real nodes are the same objects, with the same identifiers, in both hierarchies
            assertThat(fork.lookupNode(g.a3.identifier)).isSameAs(g.hierarchy.lookupNode(g.a3.identifier))
        }

        @Test
        fun `facts are shared between a fork and the original`() {
            val fork = g.hierarchy.fork()
            fork.move(g.a3, g.root)

            assertThat(fork.getAggregatedDependency(g.a3, g.b3)!!.coreDependencies)
                .containsExactly(g.dep_a3_b3_depends_on)
            assertThat(g.hierarchy.getAggregatedDependency(g.a3, g.b3)!!.coreDependencies)
                .containsExactly(g.dep_a3_b3_depends_on)
            assertThat(g.a3.outgoingCoreDependencies).containsExactly(g.dep_a3_b3_depends_on)
        }
    }
}
