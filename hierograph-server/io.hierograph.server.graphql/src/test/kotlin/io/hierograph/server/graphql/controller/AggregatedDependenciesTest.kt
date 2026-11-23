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

import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.graphql.model.NodePair
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `HierarchicalGraph.aggregatedDependencies`: each pair's weight, subtree into subtree; unknown ids per pair. */
class AggregatedDependenciesTest {

    /** root ─ app ─ appType; root ─ core ─ coreA, coreB. appType → coreA (2), appType → coreB (1). */
    private class Example {
        val graph = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val root = node()
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun child(parent: HGNode) = node().also { HierarchyFactory.addChild(hierarchy, parent, it) }
        val app = child(root)
        val core = child(root)
        val appType = child(app)
        val coreA = child(core)
        val coreB = child(core)
        private fun dep(from: HGNode, to: HGNode, weight: Int = 1) = repeat(weight) {
            HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource(nextId++) }
        }
        init {
            dep(appType, coreA, 2)
            dep(appType, coreB)
        }
    }

    private val example = Example()
    private val controller = HierarchicalGraphController()

    private fun id(node: HGNode) = node.identifier.toString()

    @Test
    fun `each pair's weight from the source subtree into the target subtree, in argument order`() {
        val result = controller.aggregatedDependencies(
            example.hierarchy,
            listOf(
                NodePair(id(example.app), id(example.core)),
                NodePair(id(example.appType), id(example.coreA)),
                NodePair(id(example.app), id(example.core)),
            ),
        )

        assertThat(result.map { it.weight }).containsExactly(3, 2, 3)
        assertThat(result[1].sourceNode).isSameAs(example.appType)
        assertThat(result[1].targetNode).isSameAs(example.coreA)
    }

    @Test
    fun `a pair without a dependency has weight 0`() {
        val result = controller.aggregatedDependencies(
            example.hierarchy,
            listOf(NodePair(id(example.core), id(example.app))),
        )

        assertThat(result.single().weight).isEqualTo(0)
        assertThat(result.single().sourceNode).isSameAs(example.core)
    }

    @Test
    fun `an id that names no node leaves its end null, with weight 0`() {
        val result = controller.aggregatedDependencies(
            example.hierarchy,
            listOf(NodePair(id(example.app), "424242")),
        )

        val pair = result.single()
        assertThat(pair.sourceNode).isSameAs(example.app)
        assertThat(pair.targetNode).isNull()
        assertThat(pair.targetId).isEqualTo("424242")
        assertThat(pair.weight).isEqualTo(0)
    }

    @Test
    fun `an id that is not a number is a bad request`() {
        assertThatThrownBy {
            controller.aggregatedDependencies(example.hierarchy, listOf(NodePair("x", id(example.app))))
        }.isInstanceOf(InvalidArgumentException::class.java)
    }

    @Test
    fun `more than the maximum number of pairs is a bad request`() {
        val pairs = List(HierarchicalGraphController.MAX_NODE_PAIRS + 1) { NodePair(id(example.app), id(example.core)) }
        assertThatThrownBy { controller.aggregatedDependencies(example.hierarchy, pairs) }
            .isInstanceOf(InvalidArgumentException::class.java)
            .hasMessageContaining("At most 1000 pairs")
    }
}
