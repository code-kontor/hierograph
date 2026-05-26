/*
 * Copyright 2024 Gerd Wuetherich
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
        (parent as HGNodeImpl).childrenMutable().add(node)
        source.node = node
        (rootNode as HGRootNodeImpl).registerNodeInMap(node)
        return node
    }

    fun createOrphanNode(
        rootNode: HGRootNode,
        nodeSourceSupplier: () -> INodeSource
    ): HGNode {
        val source = nodeSourceSupplier()
        val node = HGNodeImpl(kind = null, nodeSource = source)
        source.node = node
        (rootNode as HGRootNodeImpl).registerNodeInMap(node)
        return node
    }

    fun createCoreDependency(
        source: HGNode,
        target: HGNode,
        type: String,
        depSourceSupplier: () -> IDependencySource
    ): HGCoreDependency {
        val depSource = depSourceSupplier()
        val dep = HGCoreDependencyImpl(from = source, to = target, type = type, dependencySource = depSource)
        depSource.dependency = dep

        (source as HGNodeImpl).outgoingMutable().add(dep)
        (target as HGNodeImpl).incomingMutable().add(dep)

        return dep
    }

    fun setParent(node: HGNode, parent: HGNode) {
        val impl = node as HGNodeImpl
        if (impl._parent != null) {
            (impl._parent as HGNodeImpl)._children?.remove(node)
        }
        impl._parent = parent
        (parent as HGNodeImpl).childrenMutable().add(node)
    }
}
