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
import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.graphql.model.NodeSetModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `NodeSet.levelization`: a container's children in rows by level, with the tangles and their feedback dependencies. */
class LevelizationControllerTest {

    /** root ─ module ─ api, core, util, app; core ⇄ util a tangle (core → util heavier), app → api → core. */
    private class Example {
        val graph = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val root = node()
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun child(parent: HGNode) = node().also { HierarchyFactory.addChild(hierarchy, parent, it) }
        val module = child(root)
        val api = child(module)
        val core = child(module)
        val util = child(module)
        val app = child(module)
        // The types the dependencies run between.
        val coreType = child(core)
        val utilType = child(util)
        val utilOther = child(util)
        private fun dep(from: HGNode, to: HGNode, weight: Int = 1) = repeat(weight) {
            HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource(nextId++) }
        }
        init {
            dep(child(app), child(api))
            dep(hierarchy.childrenOf(api).single(), coreType)
            dep(coreType, utilType, 4)
            dep(utilType, coreType)
            dep(utilOther, coreType, 2)
        }
        val provider = HierarchicalGraphProvider { hierarchy }
    }

    private val example = Example()

    @Test
    fun `a container's children in rows bottom first, a tangle on one row with its feedback dependencies`() {
        val levelization = NodeSetController(example.provider)
            .levelization(NodeSetModel(example.hierarchy.childrenOf(example.module).toList()))

        assertThat(levelization.levels.map { it.index }).containsExactly(0, 1, 2)
        assertThat(levelization.levels.map { it.nodes.toSet() })
            .containsExactly(setOf(example.core, example.util), setOf(example.api), setOf(example.app))

        val tangle = levelization.tangles.single()
        assertThat(tangle.level).isEqualTo(0)
        // core → util (4) points down; util → core (3, from two types) is the feedback.
        assertThat(tangle.nodes).containsExactly(example.core, example.util)
        assertThat(tangle.rows.map { it.nodes }).containsExactly(listOf(example.util), listOf(example.core))

        val feedback = levelization.feedbackDependencies.single()
        assertThat(feedback.sourceNode).isSameAs(example.util)
        assertThat(feedback.targetNode).isSameAs(example.core)
        assertThat(feedback.weight).isEqualTo(3)
        assertThat(feedback.dependencies.dependencyList.map { it.from }.toSet())
            .containsExactlyInAnyOrder(example.utilType, example.utilOther)
    }
}
