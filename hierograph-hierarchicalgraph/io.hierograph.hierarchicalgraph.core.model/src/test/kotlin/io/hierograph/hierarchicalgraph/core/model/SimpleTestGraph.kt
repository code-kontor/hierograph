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
package io.hierograph.hierarchicalgraph.core.model

class SimpleTestGraph {

    val coreGraph: HGGraph
    val hierarchy: Hierarchy
    val model: HGModel

    val root: HGNode
    val a1: HGNode
    val a2: HGNode
    val a3: HGNode
    val b1: HGNode
    val b2: HGNode
    val b3: HGNode

    val dep_a1_b1_uses: CoreDependency
    val dep_a1_b1_depends_on: CoreDependency
    val dep_a2_b2_uses: CoreDependency
    val dep_a3_b3_depends_on: CoreDependency

    private var nextId = 1L

    init {
        val graph = HGGraphFactory.createHGGraph()
        coreGraph = graph

        root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        a1 = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        b1 = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        a2 = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        b2 = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        a3 = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        b3 = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }

        val h = HierarchyFactory.createHierarchy(graph, root)
        hierarchy = h

        HierarchyFactory.addChild(h, root, a1)
        HierarchyFactory.addChild(h, root, b1)
        HierarchyFactory.addChild(h, a1, a2)
        HierarchyFactory.addChild(h, b1, b2)
        HierarchyFactory.addChild(h, a2, a3)
        HierarchyFactory.addChild(h, b2, b3)

        dep_a1_b1_uses = HGGraphFactory.createCoreDependency(a1, b1, "USES") { DefaultDependencySource(identifier = nextId++) }
        dep_a1_b1_depends_on = HGGraphFactory.createCoreDependency(a1, b1, "DEPENDS_ON") { DefaultDependencySource(identifier = nextId++) }
        dep_a2_b2_uses = HGGraphFactory.createCoreDependency(a2, b2, "USES") { DefaultDependencySource(identifier = nextId++) }
        dep_a3_b3_depends_on = HGGraphFactory.createCoreDependency(a3, b3, "DEPENDS_ON") { DefaultDependencySource(identifier = nextId++) }

        model = HGModel(coreGraph, hierarchy)
    }
}
