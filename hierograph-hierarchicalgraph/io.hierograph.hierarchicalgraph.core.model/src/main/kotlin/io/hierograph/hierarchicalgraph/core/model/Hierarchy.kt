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

interface Hierarchy {
    val coreGraph: HGGraph
    val rootNode: HGNode
    var name: String?

    // structure
    fun parentOf(node: HGNode): HGNode?
    fun childrenOf(node: HGNode): List<HGNode>
    fun predecessorsOf(node: HGNode): List<HGNode>
    fun isPredecessorOf(ancestor: HGNode, descendant: HGNode): Boolean
    fun isSuccessorOf(descendant: HGNode, ancestor: HGNode): Boolean

    // accumulated dependencies (hierarchy-dependent)
    fun accumulatedOutgoing(node: HGNode): List<CoreDependency>
    fun accumulatedIncoming(node: HGNode): List<CoreDependency>

    // aggregated dependencies (hierarchy-dependent)
    fun getAggregatedDependency(from: HGNode, to: HGNode): AggregatedDependency?
    fun getAggregatedDependencies(from: HGNode, targets: List<HGNode>): List<AggregatedDependency>
    fun getAggregatedDependenciesFrom(to: HGNode, sources: List<HGNode>): List<AggregatedDependency>

    // traversal
    fun traverse(node: HGNode, action: (HGNode) -> Unit)
    fun traverse(node: HGNode, action: (HGNode) -> Unit, filter: (HGNode) -> Boolean)

    // local nodes (scenario-only)
    val localNodes: Collection<HGNode>
    fun createLocalNode(kind: Any?, nodeSourceSupplier: () -> INodeSource): HGNode
    fun lookupNode(identifier: Any): HGNode?

    // mutation (for scenarios)
    fun addChild(parent: HGNode, child: HGNode)
    fun move(node: HGNode, newParent: HGNode)
    fun fork(): Hierarchy
}
