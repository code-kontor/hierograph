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

import io.hierograph.hierarchicalgraph.core.model.internal.HGCoreDependencyImpl
import io.hierograph.hierarchicalgraph.core.model.internal.HGGraphImpl
import io.hierograph.hierarchicalgraph.core.model.internal.HGNodeImpl

object HGGraphFactory {

    fun createHGGraph(): HGGraphImpl = HGGraphImpl()

    fun createNode(
        graph: HGGraphImpl,
        nodeSourceSupplier: () -> INodeSource,
    ): HGNode {
        val source = nodeSourceSupplier()
        val node = HGNodeImpl(nodeSource = source)
        source.node = node
        graph.registerNode(node)
        return node
    }

    /**
     * Removes [node] from [graph]'s node index. Used during construction to prune nodes that were
     * created while resolving parent-child rows but never attached to the hierarchy root, so the
     * core graph's node set equals the hierarchy tree's. Callers must ensure the node has no
     * dependencies (a pruned node should never be an edge endpoint).
     */
    fun removeNode(graph: HGGraphImpl, node: HGNode) {
        graph.unregisterNode(node.identifier)
    }

    fun createCoreDependency(
        source: HGNode,
        target: HGNode,
        type: String,
        depSourceSupplier: () -> IDependencySource,
    ): HGCoreDependency {
        val depSource = depSourceSupplier()
        val dep = HGCoreDependencyImpl(from = source, to = target, type = type, dependencySource = depSource)
        depSource.dependency = dep
        (source as HGNodeImpl)._outgoing.add(dep)
        (target as HGNodeImpl)._incoming.add(dep)
        return dep
    }
}
