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
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.graphql.model.NodePair
import io.hierograph.server.graphql.model.NodeSetModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * GraphQL in a hierarchy with removed nodes, as a view with exclusions is (views specification, §5.4): the
 * removed nodes and the dependencies to and from them are gone from every answer.
 */
class RemovedNodesTest {

    /** root ─ app (a ─ typeA, b ─ typeB), tests (testType); app ⇄ tests only through testType. */
    private class Example {
        val graph = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val root = node()
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun child(parent: HGNode) = node().also { HierarchyFactory.addChild(hierarchy, parent, it) }
        val app = child(root)
        val tests = child(root)
        val typeA = child(child(app))
        val typeB = child(child(app))
        val testType = child(tests)
        private fun dep(from: HGNode, to: HGNode, weight: Int = 1) = repeat(weight) {
            HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource(nextId++) }
        }
        init {
            dep(typeA, typeB, 2)
            dep(testType, typeA)
            dep(typeB, testType)
        }

        /** A fork without the `tests` module, as `"exclude": ["tests"]` leaves it. */
        val view: Hierarchy = hierarchy.fork().apply {
            remove(testType)
            remove(tests)
        }
    }

    private val example = Example()

    @Test
    fun `a cycle only through the removed nodes is gone from the levelization`() {
        fun levelization(hierarchy: Hierarchy) = NodeSetController { hierarchy }
            .levelization(NodeSetModel(hierarchy.childrenOf(hierarchy.rootNode).toList()))

        assertThat(levelization(example.hierarchy).tangles.single().nodes)
            .containsExactlyInAnyOrder(example.app, example.tests)

        val inView = levelization(example.view)
        assertThat(inView.levels.flatMap { it.nodes }).containsExactly(example.app)
        assertThat(inView.tangles).isEmpty()
        assertThat(inView.feedbackDependencies).isEmpty()
    }

    @Test
    fun `a removed node is neither a child nor found, and nothing refers to it`() {
        val nodes = NodeController(HierarchicalGraphProvider { example.view })

        assertThat(nodes.children(example.root, compact = false).nodeList).containsExactly(example.app)
        assertThat(example.view.lookupNode(example.testType.identifier)).isNull()
        assertThat(nodes.referencedNodes(example.typeB, includePredecessors = false).nodeList).isEmpty()
        assertThat(nodes.referencingNodes(example.typeA, includePredecessors = false).nodeList).isEmpty()
        assertThat(nodes.referencedNodes(example.typeA, includePredecessors = false).nodeList)
            .containsExactly(example.typeB)
    }

    @Test
    fun `a dependency to a removed node is unknown, and aggregates without it`() {
        val toTest = "${example.typeB.identifier}_${example.testType.identifier}_DEPENDS_ON"
        assertThat(HierarchicalGraphController.findCoreDependencyById(example.hierarchy, toTest)).isNotNull()
        assertThat(HierarchicalGraphController.findCoreDependencyById(example.view, toTest)).isNull()

        val pairs = listOf(
            NodePair(example.app.identifier.toString(), example.tests.identifier.toString()),
            NodePair(example.typeA.identifier.toString(), example.typeB.identifier.toString()),
        )
        val base = HierarchicalGraphController().aggregatedDependencies(example.hierarchy, pairs)
        val inView = HierarchicalGraphController().aggregatedDependencies(example.view, pairs)

        assertThat(base.map { it.weight }).containsExactly(1, 2)
        assertThat(inView.map { it.weight }).containsExactly(0, 2)
        assertThat(inView.first().targetNode).isNull()
    }
}
