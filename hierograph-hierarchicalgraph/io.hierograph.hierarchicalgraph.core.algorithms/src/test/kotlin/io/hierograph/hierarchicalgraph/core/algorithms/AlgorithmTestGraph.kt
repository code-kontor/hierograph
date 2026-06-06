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
package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.model.*
import io.hierograph.hierarchicalgraph.core.model.internal.CoreGraphImpl

/**
 * Test graph for algorithm tests.
 *
 * ```
 * root
 *  +-- n0   (no deps - isolated)
 *  +-- n1   deps: n1 -> n2
 *  +-- n2   deps: n2 -> n3
 *  +-- n3   deps: n3 -> n1  (cycle: n1 -> n2 -> n3 -> n1)
 *  +-- n4   deps: n4 -> n5
 *  +-- n5   (no outgoing)
 *  +-- n6   deps: n6 -> n7
 *  +-- n7   deps: n7 -> n6  (cycle: n6 <-> n7)
 * ```
 *
 * Cycles: {n1, n2, n3} and {n6, n7}
 * Acyclic: n0 (isolated), n4 -> n5 (linear)
 */
class AlgorithmTestGraph {

    val coreGraph: CoreGraph
    val hierarchy: Hierarchy
    val nodes: List<CoreNode>  // n0..n7

    val root: CoreNode
    val n0: CoreNode
    val n1: CoreNode
    val n2: CoreNode
    val n3: CoreNode
    val n4: CoreNode
    val n5: CoreNode
    val n6: CoreNode
    val n7: CoreNode

    private var nextId = 1L

    init {
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val depSource = { DefaultDependencySource(identifier = nextId++) }

        val graph = CoreGraphFactory.createCoreGraph()
        coreGraph = graph

        root = CoreGraphFactory.createNode(graph, nodeSource)
        n0 = CoreGraphFactory.createNode(graph, nodeSource)
        n1 = CoreGraphFactory.createNode(graph, nodeSource)
        n2 = CoreGraphFactory.createNode(graph, nodeSource)
        n3 = CoreGraphFactory.createNode(graph, nodeSource)
        n4 = CoreGraphFactory.createNode(graph, nodeSource)
        n5 = CoreGraphFactory.createNode(graph, nodeSource)
        n6 = CoreGraphFactory.createNode(graph, nodeSource)
        n7 = CoreGraphFactory.createNode(graph, nodeSource)

        val h = HierarchyFactory.createHierarchy(graph, root)
        hierarchy = h

        HierarchyFactory.addChild(h, root, n0)
        HierarchyFactory.addChild(h, root, n1)
        HierarchyFactory.addChild(h, root, n2)
        HierarchyFactory.addChild(h, root, n3)
        HierarchyFactory.addChild(h, root, n4)
        HierarchyFactory.addChild(h, root, n5)
        HierarchyFactory.addChild(h, root, n6)
        HierarchyFactory.addChild(h, root, n7)

        nodes = listOf(n0, n1, n2, n3, n4, n5, n6, n7)

        // Cycle 1: n1 -> n2 -> n3 -> n1
        CoreGraphFactory.createCoreDependency(n1, n2, "DEPENDS_ON", depSource)
        CoreGraphFactory.createCoreDependency(n2, n3, "DEPENDS_ON", depSource)
        CoreGraphFactory.createCoreDependency(n3, n1, "DEPENDS_ON", depSource)

        // Linear: n4 -> n5
        CoreGraphFactory.createCoreDependency(n4, n5, "DEPENDS_ON", depSource)

        // Cycle 2: n6 <-> n7
        CoreGraphFactory.createCoreDependency(n6, n7, "DEPENDS_ON", depSource)
        CoreGraphFactory.createCoreDependency(n7, n6, "DEPENDS_ON", depSource)
    }
}
