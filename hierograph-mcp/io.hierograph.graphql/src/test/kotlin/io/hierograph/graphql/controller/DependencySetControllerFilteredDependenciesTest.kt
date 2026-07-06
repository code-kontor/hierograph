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
package io.hierograph.graphql.controller

import io.hierograph.graphql.HierarchicalGraphProvider
import io.hierograph.graphql.model.DependencySetModel
import io.hierograph.graphql.model.NodeSelection
import io.hierograph.graphql.model.NodeType
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A dependency's endpoints are leaf types (classes), but a selection can target
 * any node on the path down to them — an intermediate package or a whole module.
 * filteredDependencies must match an edge whenever a selected node is the
 * endpoint itself or an ancestor of it; selecting a container that has no
 * matching edge below it would otherwise come back empty.
 *
 * Hierarchy:
 *   root
 *   ├── srcModule → srcPkg → SrcClass
 *   └── tgtModule → tgtPkg → TgtClass
 * Dependency: SrcClass → TgtClass
 */
class DependencySetControllerFilteredDependenciesTest {

    private var nextId = 1L
    private val graph = HGGraphFactory.createHGGraph()

    private fun node(): HGNode =
        HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }

    private val root = node()
    private val srcModule = node()
    private val srcPkg = node()
    private val srcClass = node()
    private val tgtModule = node()
    private val tgtPkg = node()
    private val tgtClass = node()
    private val unrelated = node()

    private val hierarchy = HierarchyFactory.createHierarchy(graph, root).also { h ->
        HierarchyFactory.addChild(h, root, srcModule)
        HierarchyFactory.addChild(h, srcModule, srcPkg)
        HierarchyFactory.addChild(h, srcPkg, srcClass)
        HierarchyFactory.addChild(h, root, tgtModule)
        HierarchyFactory.addChild(h, tgtModule, tgtPkg)
        HierarchyFactory.addChild(h, tgtPkg, tgtClass)
        HierarchyFactory.addChild(h, root, unrelated)
    }

    private val dep =
        HGGraphFactory.createCoreDependency(srcClass, tgtClass, "USES") {
            DefaultDependencySource(identifier = nextId++)
        }

    private val controller =
        DependencySetController(HierarchicalGraphProvider { hierarchy })
    private val depSet = DependencySetModel(listOf(dep))

    private fun selectSource(node: HGNode) =
        controller.filteredDependencies(
            depSet,
            listOf(NodeSelection(listOf(node.identifier.toString()), NodeType.SOURCE))
        ).dependencyList

    private fun selectTarget(node: HGNode) =
        controller.filteredDependencies(
            depSet,
            listOf(NodeSelection(listOf(node.identifier.toString()), NodeType.TARGET))
        ).dependencyList

    @Test
    fun `selecting the endpoint class matches the edge`() {
        assertThat(selectSource(srcClass)).containsExactly(dep)
    }

    @Test
    fun `selecting the intermediate source package matches the edge`() {
        assertThat(selectSource(srcPkg)).containsExactly(dep)
    }

    @Test
    fun `selecting the source module matches the edge`() {
        assertThat(selectSource(srcModule)).containsExactly(dep)
    }

    @Test
    fun `selecting the intermediate target package matches the edge`() {
        assertThat(selectTarget(tgtPkg)).containsExactly(dep)
    }

    @Test
    fun `selecting an unrelated subtree matches nothing`() {
        assertThat(selectSource(unrelated)).isEmpty()
    }
}
