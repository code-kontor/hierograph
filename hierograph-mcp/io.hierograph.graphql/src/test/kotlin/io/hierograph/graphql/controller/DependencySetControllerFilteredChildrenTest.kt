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
import io.hierograph.graphql.model.NodeType
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A dependency's endpoints are leaf types (classes), but a dependency tree is
 * rooted at a module/package well above them. filteredChildren must therefore
 * return the intermediate packages on the path down to the endpoints — not only
 * children that are endpoints themselves.
 *
 * Hierarchy:
 *   root
 *   ├── srcModule → srcPkg → SrcClass
 *   └── tgtModule → tgtPkg → TgtClass
 * Dependency: SrcClass → TgtClass
 */
class DependencySetControllerFilteredChildrenTest {

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

    private val hierarchy = HierarchyFactory.createHierarchy(graph, root).also { h ->
        HierarchyFactory.addChild(h, root, srcModule)
        HierarchyFactory.addChild(h, srcModule, srcPkg)
        HierarchyFactory.addChild(h, srcPkg, srcClass)
        HierarchyFactory.addChild(h, root, tgtModule)
        HierarchyFactory.addChild(h, tgtModule, tgtPkg)
        HierarchyFactory.addChild(h, tgtPkg, tgtClass)
    }

    private val dep =
        HGGraphFactory.createCoreDependency(srcClass, tgtClass, "USES") {
            DefaultDependencySource(identifier = nextId++)
        }

    private val controller =
        DependencySetController(HierarchicalGraphProvider { hierarchy })
    private val depSet = DependencySetModel(listOf(dep))

    private fun children(parent: HGNode, type: NodeType): List<Any> =
        controller.filteredChildren(depSet, parent.identifier.toString(), type)
            .map { it.identifier }

    @Test
    fun `source side returns the intermediate package under the module`() {
        assertThat(children(srcModule, NodeType.SOURCE))
            .containsExactly(srcPkg.identifier)
    }

    @Test
    fun `source side descends from the package down to the endpoint class`() {
        assertThat(children(srcPkg, NodeType.SOURCE))
            .containsExactly(srcClass.identifier)
    }

    @Test
    fun `target side returns the intermediate package under the module`() {
        assertThat(children(tgtModule, NodeType.TARGET))
            .containsExactly(tgtPkg.identifier)
    }

    @Test
    fun `source filter excludes the unrelated target subtree`() {
        assertThat(children(root, NodeType.SOURCE))
            .containsExactly(srcModule.identifier)
    }
}
