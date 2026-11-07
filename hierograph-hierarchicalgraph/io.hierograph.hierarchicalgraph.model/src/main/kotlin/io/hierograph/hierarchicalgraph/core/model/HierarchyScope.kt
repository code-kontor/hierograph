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

    val coreGraph: HGGraph get() = hierarchy.coreGraph

    // structure
    val HGNode.parent: HGNode?
        get() = hierarchy.parentOf(this)

    val HGNode.children: List<HGNode>
        get() = hierarchy.childrenOf(this)

    val HGNode.predecessors: List<HGNode>
        get() = hierarchy.predecessorsOf(this)

    val HGNode.hasChildren: Boolean
        get() = hierarchy.childrenOf(this).isNotEmpty()

    fun HGNode.isPredecessorOf(other: HGNode): Boolean =
        hierarchy.isPredecessorOf(this, other)

    fun HGNode.isSuccessorOf(other: HGNode): Boolean =
        hierarchy.isSuccessorOf(this, other)

    // accumulated dependencies
    val HGNode.accumulatedOutgoingCoreDependencies: List<HGCoreDependency>
        get() = hierarchy.accumulatedOutgoing(this)

    val HGNode.accumulatedIncomingCoreDependencies: List<HGCoreDependency>
        get() = hierarchy.accumulatedIncoming(this)

    // aggregated dependencies
    fun HGNode.outgoingTo(target: HGNode): AggregatedDependency? =
        hierarchy.getAggregatedDependency(this, target)

    fun HGNode.outgoingTo(targets: List<HGNode>): List<AggregatedDependency> =
        hierarchy.getAggregatedDependencies(this, targets)

    fun HGNode.incomingFrom(source: HGNode): AggregatedDependency? =
        hierarchy.getAggregatedDependency(source, this)

    fun HGNode.incomingFrom(sources: List<HGNode>): List<AggregatedDependency> =
        hierarchy.getAggregatedDependenciesFrom(this, sources)

    // traversal
    fun HGNode.traverse(action: (HGNode) -> Unit) =
        hierarchy.traverse(this, action)

    // descendants
    fun HGNode.descendants(vararg kinds: Any): List<HGNode> =
        hierarchy.descendantsOf(this, *kinds)

    fun Iterable<HGNode>.descendants(vararg kinds: Any): List<HGNode> =
        hierarchy.descendantsOf(this, *kinds)
}
