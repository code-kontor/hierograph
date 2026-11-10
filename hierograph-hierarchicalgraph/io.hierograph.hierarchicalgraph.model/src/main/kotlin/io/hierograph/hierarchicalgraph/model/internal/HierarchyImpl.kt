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
package io.hierograph.hierarchicalgraph.model.internal

import io.hierograph.hierarchicalgraph.model.*
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

class HierarchyImpl(
    override val coreGraph: HGGraph,
    override val rootNode: HGNode,
    internal val parentMap: MutableMap<Any, Any>,
    internal val childrenMap: MutableMap<Any, MutableList<Any>>,
) : Hierarchy {

    override var name: String? = null

    // local nodes (scenario-only, not in shared HGGraph)
    private val localNodeMap: MutableMap<Any, HGNode> = mutableMapOf()

    override val localNodes: Collection<HGNode> get() = localNodeMap.values

    override fun createLocalNode(kind: Any?, nodeSourceSupplier: () -> INodeSource): HGNode {
        val source = nodeSourceSupplier()
        // lookupNode searches the local nodes first: a colliding identifier would hide the other node.
        require(lookupNode(source.identifier) == null) {
            "A node with identifier ${source.identifier} already exists in this hierarchy or its core graph"
        }
        val node = HGNodeImpl(nodeSource = source)
        node.kind = kind
        source.node = node
        localNodeMap[node.identifier] = node
        return node
    }

    override fun lookupNode(identifier: Any): HGNode? =
        localNodeMap[identifier] ?: coreGraph.lookupNode(identifier)

    // Caches, filled lazily by reads and cleared on structural mutation. Concurrent reads are safe: an entry
    // is computed outside the map and published with putIfAbsent, so two threads racing on one key may both
    // compute it, but every reader sees one complete value. (computeIfAbsent is not an option: the
    // computations recurse into the same map.) An absent aggregated dependency is cached as Optional.empty().
    private val predecessorCache = ConcurrentHashMap<Any, List<HGNode>>()
    private val accOutCache = ConcurrentHashMap<Any, List<HGCoreDependency>>()
    private val accInCache = ConcurrentHashMap<Any, List<HGCoreDependency>>()
    private val aggDepCache = ConcurrentHashMap<Pair<Any, Any>, Optional<AggregatedDependency>>()

    private inline fun <K : Any, V : Any> ConcurrentHashMap<K, V>.memo(key: K, compute: () -> V): V =
        get(key) ?: compute().let { putIfAbsent(key, it) ?: it }

    // structure

    override fun parentOf(node: HGNode): HGNode? {
        val parentId = parentMap[node.identifier] ?: return null
        return lookupNode(parentId)
    }

    override fun childrenOf(node: HGNode): List<HGNode> {
        val childIds = childrenMap[node.identifier] ?: return emptyList()
        return childIds.mapNotNull { lookupNode(it) }
    }

    override fun predecessorsOf(node: HGNode): List<HGNode> {
        return predecessorCache.memo(node.identifier) {
            val parent = parentOf(node) ?: return@memo emptyList()
            buildList {
                add(parent)
                addAll(predecessorsOf(parent))
            }
        }
    }

    override fun isPredecessorOf(ancestor: HGNode, descendant: HGNode): Boolean =
        predecessorsOf(descendant).contains(ancestor)

    override fun isSuccessorOf(descendant: HGNode, ancestor: HGNode): Boolean =
        isPredecessorOf(ancestor, descendant)

    // accumulated dependencies

    override fun accumulatedOutgoing(node: HGNode): List<HGCoreDependency> {
        return accOutCache.memo(node.identifier) {
            buildList {
                addAll(node.outgoingCoreDependencies)
                for (child in childrenOf(node)) {
                    addAll(accumulatedOutgoing(child))
                }
            }
        }
    }

    override fun accumulatedIncoming(node: HGNode): List<HGCoreDependency> {
        return accInCache.memo(node.identifier) {
            buildList {
                addAll(node.incomingCoreDependencies)
                for (child in childrenOf(node)) {
                    addAll(accumulatedIncoming(child))
                }
            }
        }
    }

    // aggregated dependencies

    override fun getAggregatedDependency(from: HGNode, to: HGNode): AggregatedDependency? {
        return aggDepCache.memo(from.identifier to to.identifier) {
            val coreDeps = accumulatedIncoming(to).filter { dep ->
                dep.from === from || isPredecessorOf(from, dep.from)
            }
            if (coreDeps.isEmpty()) Optional.empty()
            else Optional.of(AggregatedDependencyImpl(from, to, coreDeps))
        }.orElse(null)
    }

    override fun getAggregatedDependencies(
        from: HGNode,
        targets: List<HGNode>,
    ): List<AggregatedDependency> = targets.mapNotNull { getAggregatedDependency(from, it) }

    override fun getAggregatedDependenciesFrom(
        to: HGNode,
        sources: List<HGNode>,
    ): List<AggregatedDependency> = sources.mapNotNull { getAggregatedDependency(it, to) }

    // traversal

    override fun traverse(node: HGNode, action: (HGNode) -> Unit) {
        for (child in childrenOf(node)) {
            action(child)
            traverse(child, action)
        }
    }

    override fun traverse(node: HGNode, action: (HGNode) -> Unit, filter: (HGNode) -> Boolean) {
        for (child in childrenOf(node)) {
            if (filter(child)) {
                action(child)
                traverse(child, action, filter)
            }
        }
    }

    // descendants

    override fun descendantsOf(node: HGNode, vararg kinds: Any): List<HGNode> {
        val out = mutableListOf<HGNode>()
        val filter = if (kinds.isEmpty()) null else kinds.toSet()
        for (child in childrenOf(node)) {
            collectDescendants(child, filter, out)
        }
        return out
    }

    override fun descendantsOf(nodes: Iterable<HGNode>, vararg kinds: Any): List<HGNode> {
        val seen = LinkedHashSet<HGNode>()
        val filter = if (kinds.isEmpty()) null else kinds.toSet()
        for (seed in nodes) {
            for (child in childrenOf(seed)) {
                collectDescendants(child, filter, seen)
            }
        }
        return seen.toList()
    }

    private fun collectDescendants(node: HGNode, filter: Set<Any>?, out: MutableCollection<HGNode>) {
        if (filter == null || node.kind in filter) {
            out.add(node)
        }
        for (child in childrenOf(node)) {
            collectDescendants(child, filter, out)
        }
    }

    // mutation

    override fun addChild(parent: HGNode, child: HGNode) {
        placeChild(parent.identifier, child.identifier)
        clearCaches()
    }

    /**
     * Appends [childId] to [parentId]'s children after checking that this keeps the hierarchy a tree: the
     * child is not placed yet, is not the root, and does not contain the parent. Shared with
     * [HierarchyFactory.addChild], which places nodes without clearing caches.
     */
    internal fun placeChild(parentId: Any, childId: Any) {
        require(childId != rootNode.identifier) { "The root node $childId cannot be added as a child" }
        parentMap[childId]?.let { placed ->
            throw IllegalArgumentException("Node $childId is already a child of $placed; use move to re-parent it")
        }
        requireNotInSubtree(parentId, childId) { "Adding $childId below $parentId would make it its own ancestor" }
        parentMap[childId] = parentId
        childrenMap.getOrPut(parentId) { mutableListOf() }.add(childId)
    }

    /**
     * Fails when [targetId] is [subtreeRootId] or lies below it. Walks the target's parent chain in
     * [parentMap], not the cached predecessors, so it costs the target's depth and works before and between
     * cache refills.
     */
    private inline fun requireNotInSubtree(targetId: Any, subtreeRootId: Any, message: () -> String) {
        var current: Any? = targetId
        while (current != null) {
            require(current != subtreeRootId, message)
            current = parentMap[current]
        }
    }

    override fun move(node: HGNode, newParent: HGNode) {
        val id = node.identifier
        require(id != rootNode.identifier) { "The root node $id cannot be moved" }
        requireNotInSubtree(newParent.identifier, id) { "Moving $id below ${newParent.identifier} would make it its own ancestor" }
        val oldParentId = parentMap[id]
        if (oldParentId != null) {
            childrenMap[oldParentId]?.remove(id)
        }
        parentMap[id] = newParent.identifier
        childrenMap.getOrPut(newParent.identifier) { mutableListOf() }.add(id)
        clearCaches()
    }

    override fun fork(): Hierarchy {
        val forked = HierarchyImpl(
            coreGraph = coreGraph,
            rootNode = rootNode,
            parentMap = HashMap(parentMap),
            childrenMap = HashMap(childrenMap.mapValues { ArrayList(it.value) }),
        )
        forked.name = name
        forked.localNodeMap.putAll(localNodeMap)
        return forked
    }

    private fun clearCaches() {
        predecessorCache.clear()
        accOutCache.clear()
        accInCache.clear()
        aggDepCache.clear()
    }
}
