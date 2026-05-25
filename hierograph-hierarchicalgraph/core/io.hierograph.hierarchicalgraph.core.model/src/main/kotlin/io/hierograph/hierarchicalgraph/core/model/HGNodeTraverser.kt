package io.hierograph.hierarchicalgraph.core.model

object HGNodeTraverser {

    fun traverse(node: HGNode, action: (HGNode) -> Unit) {
        action(node)
        for (child in node.children) {
            traverse(child, action)
        }
    }

    fun traverse(node: HGNode, action: (HGNode) -> Unit, filter: (HGNode) -> Boolean) {
        if (filter(node)) {
            action(node)
        }
        for (child in node.children) {
            traverse(child, action, filter)
        }
    }

    fun traverseWithPruning(node: HGNode, action: (HGNode) -> Unit, descendInto: (HGNode) -> Boolean) {
        action(node)
        if (descendInto(node)) {
            for (child in node.children) {
                traverseWithPruning(child, action, descendInto)
            }
        }
    }
}
