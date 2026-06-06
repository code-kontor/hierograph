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

import io.hierograph.hierarchicalgraph.core.model.internal.CoreDependencyImpl
import io.hierograph.hierarchicalgraph.core.model.internal.CoreGraphImpl
import io.hierograph.hierarchicalgraph.core.model.internal.CoreNodeImpl

object CoreGraphFactory {

    fun createCoreGraph(): CoreGraphImpl = CoreGraphImpl()

    fun createNode(
        graph: CoreGraphImpl,
        nodeSourceSupplier: () -> INodeSource,
    ): CoreNode {
        val source = nodeSourceSupplier()
        val node = CoreNodeImpl(nodeSource = source)
        source.node = node
        graph.registerNode(node)
        return node
    }

    fun createCoreDependency(
        source: CoreNode,
        target: CoreNode,
        type: String,
        depSourceSupplier: () -> IDependencySource,
    ): CoreDependency {
        val depSource = depSourceSupplier()
        val dep = CoreDependencyImpl(from = source, to = target, type = type, dependencySource = depSource)
        depSource.dependency = dep
        (source as CoreNodeImpl)._outgoing.add(dep)
        (target as CoreNodeImpl)._incoming.add(dep)
        return dep
    }
}
