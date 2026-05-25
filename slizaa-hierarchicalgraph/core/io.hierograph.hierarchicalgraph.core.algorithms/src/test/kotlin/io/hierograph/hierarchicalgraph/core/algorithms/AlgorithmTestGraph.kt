package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.model.*

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

    val root: HGRootNode
    val nodes: List<HGNode>  // n0..n7

    val n0: HGNode
    val n1: HGNode
    val n2: HGNode
    val n3: HGNode
    val n4: HGNode
    val n5: HGNode
    val n6: HGNode
    val n7: HGNode

    private var nextId = 1L

    init {
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val depSource = { DefaultDependencySource(identifier = nextId++) }

        root = HierarchicalGraphFactory.createRootNode(nodeSource)

        n0 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        n1 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        n2 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        n3 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        n4 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        n5 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        n6 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        n7 = HierarchicalGraphFactory.createNode(root, root, nodeSource)

        nodes = listOf(n0, n1, n2, n3, n4, n5, n6, n7)

        // Cycle 1: n1 -> n2 -> n3 -> n1
        HierarchicalGraphFactory.createCoreDependency(n1, n2, "DEPENDS_ON", depSource)
        HierarchicalGraphFactory.createCoreDependency(n2, n3, "DEPENDS_ON", depSource)
        HierarchicalGraphFactory.createCoreDependency(n3, n1, "DEPENDS_ON", depSource)

        // Linear: n4 -> n5
        HierarchicalGraphFactory.createCoreDependency(n4, n5, "DEPENDS_ON", depSource)

        // Cycle 2: n6 <-> n7
        HierarchicalGraphFactory.createCoreDependency(n6, n7, "DEPENDS_ON", depSource)
        HierarchicalGraphFactory.createCoreDependency(n7, n6, "DEPENDS_ON", depSource)
    }
}
