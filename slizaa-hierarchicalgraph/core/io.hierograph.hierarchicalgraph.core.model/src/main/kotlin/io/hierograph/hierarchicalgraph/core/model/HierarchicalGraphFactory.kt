package io.hierograph.hierarchicalgraph.core.model

import io.hierograph.hierarchicalgraph.core.model.internal.HGCoreDependencyImpl
import io.hierograph.hierarchicalgraph.core.model.internal.HGNodeImpl
import io.hierograph.hierarchicalgraph.core.model.internal.HGRootNodeImpl

object HierarchicalGraphFactory {

    fun createRootNode(nodeSourceSupplier: () -> INodeSource): HGRootNode {
        val source = nodeSourceSupplier()
        val root = HGRootNodeImpl(kind = null, nodeSource = source)
        source.node = root
        return root
    }

    fun createNode(
        rootNode: HGRootNode,
        parent: HGNode,
        nodeSourceSupplier: () -> INodeSource
    ): HGNode {
        val source = nodeSourceSupplier()
        val node = HGNodeImpl(kind = null, nodeSource = source)
        node._parent = parent
        (parent as HGNodeImpl)._children.add(node)
        source.node = node
        (rootNode as HGRootNodeImpl).registerNodeInMap(node)
        return node
    }

    fun createCoreDependency(
        source: HGNode,
        target: HGNode,
        type: String,
        depSourceSupplier: () -> IDependencySource,
        reinitializeCaches: Boolean = false
    ): HGCoreDependency {
        val depSource = depSourceSupplier()
        val dep = HGCoreDependencyImpl(from = source, to = target, type = type, dependencySource = depSource)
        depSource.dependency = dep

        (source as HGNodeImpl)._outgoingCoreDependencies.add(dep)
        (target as HGNodeImpl)._incomingCoreDependencies.add(dep)

        source.rootNode.invalidateCaches(listOf(source, target))
        if (reinitializeCaches) {
            source.rootNode.initializeCaches(listOf(source, target))
        }

        return dep
    }

    fun setParent(node: HGNode, parent: HGNode) {
        val impl = node as HGNodeImpl
        if (impl._parent != null) {
            (impl._parent as HGNodeImpl)._children.remove(node)
        }
        impl._parent = parent
        (parent as HGNodeImpl)._children.add(node)
    }

    fun removeDependency(dependency: HGCoreDependency, invalidateCaches: Boolean = true) {
        (dependency.from as HGNodeImpl)._outgoingCoreDependencies.remove(dependency)
        (dependency.to as HGNodeImpl)._incomingCoreDependencies.remove(dependency)

        if (invalidateCaches) {
            dependency.from.rootNode.invalidateCaches(listOf(dependency.from, dependency.to))
        }
    }
}
