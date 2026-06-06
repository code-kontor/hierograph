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
    val coreGraph: CoreGraph
    val rootNode: CoreNode
    var name: String?

    // structure
    fun parentOf(node: CoreNode): CoreNode?
    fun childrenOf(node: CoreNode): List<CoreNode>
    fun predecessorsOf(node: CoreNode): List<CoreNode>
    fun isPredecessorOf(ancestor: CoreNode, descendant: CoreNode): Boolean
    fun isSuccessorOf(descendant: CoreNode, ancestor: CoreNode): Boolean

    // accumulated dependencies (hierarchy-dependent)
    fun accumulatedOutgoing(node: CoreNode): List<CoreDependency>
    fun accumulatedIncoming(node: CoreNode): List<CoreDependency>

    // aggregated dependencies (hierarchy-dependent)
    fun getAggregatedDependency(from: CoreNode, to: CoreNode): AggregatedDependency?
    fun getAggregatedDependencies(from: CoreNode, targets: List<CoreNode>): List<AggregatedDependency>
    fun getAggregatedDependenciesFrom(to: CoreNode, sources: List<CoreNode>): List<AggregatedDependency>

    // traversal
    fun traverse(node: CoreNode, action: (CoreNode) -> Unit)
    fun traverse(node: CoreNode, action: (CoreNode) -> Unit, filter: (CoreNode) -> Boolean)

    // local nodes (scenario-only)
    val localNodes: Collection<CoreNode>
    fun createLocalNode(kind: Any?, nodeSourceSupplier: () -> INodeSource): CoreNode
    fun lookupNode(identifier: Any): CoreNode?

    // mutation (for scenarios)
    fun addChild(parent: CoreNode, child: CoreNode)
    fun move(node: CoreNode, newParent: CoreNode)
    fun fork(): Hierarchy
}
