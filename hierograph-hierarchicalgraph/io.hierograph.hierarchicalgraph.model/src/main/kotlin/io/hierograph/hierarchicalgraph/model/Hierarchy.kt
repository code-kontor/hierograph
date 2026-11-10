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
package io.hierograph.hierarchicalgraph.model

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
    fun accumulatedOutgoing(node: HGNode): List<HGCoreDependency>
    fun accumulatedIncoming(node: HGNode): List<HGCoreDependency>

    // aggregated dependencies (hierarchy-dependent)
    fun getAggregatedDependency(from: HGNode, to: HGNode): AggregatedDependency?
    fun getAggregatedDependencies(from: HGNode, targets: List<HGNode>): List<AggregatedDependency>
    fun getAggregatedDependenciesFrom(to: HGNode, sources: List<HGNode>): List<AggregatedDependency>

    // traversal
    fun traverse(node: HGNode, action: (HGNode) -> Unit)
    fun traverse(node: HGNode, action: (HGNode) -> Unit, filter: (HGNode) -> Boolean)

    // descendants
    /**
     * Returns [node]'s descendants in pre-order (parent before children, left-to-right over
     * `children`). [node] itself is NOT included. When [kinds] is non-empty the result only
     * contains nodes whose [HGNode.kind] equals one of the supplied values; the traversal still
     * visits the whole subtree so a filtered ancestor never prunes its filtered descendants. When
     * [kinds] is empty no filter is applied.
     */
    fun descendantsOf(node: HGNode, vararg kinds: Any): List<HGNode>

    /**
     * Returns the union of the descendants of every node in [nodes] (pre-order per seed, in
     * iteration order). The seeds themselves are NOT included. Duplicates are removed; a descendant
     * reachable from multiple seeds appears once, at its first occurrence. See [descendantsOf] for
     * the meaning of [kinds].
     */
    fun descendantsOf(nodes: Iterable<HGNode>, vararg kinds: Any): List<HGNode>

    // local nodes (scenario-only)
    val localNodes: Collection<HGNode>

    /**
     * Creates a node known only to this hierarchy and its later forks. Fails with
     * [IllegalArgumentException] when the source's identifier is already taken by a core node or a local
     * node of this hierarchy, because [lookupNode] would otherwise resolve only one of them.
     */
    fun createLocalNode(kind: Any?, nodeSourceSupplier: () -> INodeSource): HGNode
    fun lookupNode(identifier: Any): HGNode?

    // mutation (for scenarios)

    /**
     * Appends [child], a node not yet placed in this hierarchy, to [parent]'s children. Fails with
     * [IllegalArgumentException] when [child] already has a parent (use [move]), is the root, or contains
     * [parent] in its subtree.
     */
    fun addChild(parent: HGNode, child: HGNode)

    /**
     * Detaches [node] from its parent and appends it, with its subtree, to [newParent]'s children. Fails
     * with [IllegalArgumentException] when [node] is the root, or [newParent] is [node] or lies in its
     * subtree: the parent chain would become a cycle.
     */
    fun move(node: HGNode, newParent: HGNode)

    /**
     * Takes [node], which must have no children, out of this hierarchy: it is detached from its parent, and
     * [lookupNode] no longer finds it here (a local node is dropped, a core node stays in the core graph and
     * in other hierarchies). Its own dependencies drop out of every accumulation in this hierarchy. Fails
     * with [IllegalArgumentException] when [node] is the root or has children: move them first.
     */
    fun remove(node: HGNode)
    fun fork(): Hierarchy
}
