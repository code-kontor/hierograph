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

open class HierarchyScope(val hierarchy: Hierarchy) {

    val coreGraph: CoreGraph get() = hierarchy.coreGraph

    // structure
    val CoreNode.parent: CoreNode?
        get() = hierarchy.parentOf(this)

    val CoreNode.children: List<CoreNode>
        get() = hierarchy.childrenOf(this)

    val CoreNode.predecessors: List<CoreNode>
        get() = hierarchy.predecessorsOf(this)

    val CoreNode.hasChildren: Boolean
        get() = hierarchy.childrenOf(this).isNotEmpty()

    fun CoreNode.isPredecessorOf(other: CoreNode): Boolean =
        hierarchy.isPredecessorOf(this, other)

    fun CoreNode.isSuccessorOf(other: CoreNode): Boolean =
        hierarchy.isSuccessorOf(this, other)

    // accumulated dependencies
    val CoreNode.accumulatedOutgoingCoreDependencies: List<CoreDependency>
        get() = hierarchy.accumulatedOutgoing(this)

    val CoreNode.accumulatedIncomingCoreDependencies: List<CoreDependency>
        get() = hierarchy.accumulatedIncoming(this)

    // aggregated dependencies
    fun CoreNode.outgoingTo(target: CoreNode): AggregatedDependency? =
        hierarchy.getAggregatedDependency(this, target)

    fun CoreNode.outgoingTo(targets: List<CoreNode>): List<AggregatedDependency> =
        hierarchy.getAggregatedDependencies(this, targets)

    fun CoreNode.incomingFrom(source: CoreNode): AggregatedDependency? =
        hierarchy.getAggregatedDependency(source, this)

    fun CoreNode.incomingFrom(sources: List<CoreNode>): List<AggregatedDependency> =
        hierarchy.getAggregatedDependenciesFrom(this, sources)

    // traversal
    fun CoreNode.traverse(action: (CoreNode) -> Unit) =
        hierarchy.traverse(this, action)
}
