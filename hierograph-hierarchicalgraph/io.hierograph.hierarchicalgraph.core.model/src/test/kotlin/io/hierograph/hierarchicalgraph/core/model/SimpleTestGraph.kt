package io.hierograph.hierarchicalgraph.core.model

class SimpleTestGraph {

    val root: HGRootNode
    val a1: HGNode
    val a2: HGNode
    val a3: HGNode
    val b1: HGNode
    val b2: HGNode
    val b3: HGNode

    val dep_a1_b1_uses: HGCoreDependency
    val dep_a1_b1_depends_on: HGCoreDependency
    val dep_a2_b2_uses: HGCoreDependency
    val dep_a3_b3_depends_on: HGCoreDependency

    private var nextId = 1L

    init {
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val depSource = { DefaultDependencySource(identifier = nextId++) }

        root = HierarchicalGraphFactory.createRootNode(nodeSource)

        a1 = HierarchicalGraphFactory.createNode(root, root, nodeSource)
        b1 = HierarchicalGraphFactory.createNode(root, root, nodeSource)

        a2 = HierarchicalGraphFactory.createNode(root, a1, nodeSource)
        b2 = HierarchicalGraphFactory.createNode(root, b1, nodeSource)

        a3 = HierarchicalGraphFactory.createNode(root, a2, nodeSource)
        b3 = HierarchicalGraphFactory.createNode(root, b2, nodeSource)

        dep_a1_b1_uses = HierarchicalGraphFactory.createCoreDependency(a1, b1, "USES", depSource)
        dep_a1_b1_depends_on = HierarchicalGraphFactory.createCoreDependency(a1, b1, "DEPENDS_ON", depSource)
        dep_a2_b2_uses = HierarchicalGraphFactory.createCoreDependency(a2, b2, "USES", depSource)
        dep_a3_b3_depends_on = HierarchicalGraphFactory.createCoreDependency(a3, b3, "DEPENDS_ON", depSource)
    }
}
