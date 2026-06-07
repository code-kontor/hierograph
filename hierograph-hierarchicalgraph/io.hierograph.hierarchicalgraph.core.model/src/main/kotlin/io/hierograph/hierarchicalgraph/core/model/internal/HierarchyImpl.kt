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
package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.*

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
        val node = HGNodeImpl(nodeSource = source)
        node.kind = kind
        source.node = node
        localNodeMap[node.identifier] = node
        return node
    }

    override fun lookupNode(identifier: Any): HGNode? =
        localNodeMap[identifier] ?: coreGraph.lookupNode(identifier)

    // caches (cleared on structural mutation)
    private var predecessorCache: MutableMap<Any, List<HGNode>>? = null
    private var accOutCache: MutableMap<Any, List<HGCoreDependency>>? = null
    private var accInCache: MutableMap<Any, List<HGCoreDependency>>? = null
    private var aggDepCache: MutableMap<Pair<Any, Any>, AggregatedDependency?>? = null

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
        val cache = predecessorCache ?: mutableMapOf<Any, List<HGNode>>().also { predecessorCache = it }
        return cache.getOrPut(node.identifier) {
            val parent = parentOf(node) ?: return@getOrPut emptyList()
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
        val cache = accOutCache ?: mutableMapOf<Any, List<HGCoreDependency>>().also { accOutCache = it }
        return cache.getOrPut(node.identifier) {
            buildList {
                addAll(node.outgoingCoreDependencies)
                for (child in childrenOf(node)) {
                    addAll(accumulatedOutgoing(child))
                }
            }
        }
    }

    override fun accumulatedIncoming(node: HGNode): List<HGCoreDependency> {
        val cache = accInCache ?: mutableMapOf<Any, List<HGCoreDependency>>().also { accInCache = it }
        return cache.getOrPut(node.identifier) {
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
        val cache = aggDepCache ?: mutableMapOf<Pair<Any, Any>, AggregatedDependency?>().also { aggDepCache = it }
        val key = from.identifier to to.identifier
        return cache.getOrPut(key) {
            val coreDeps = accumulatedIncoming(to).filter { dep ->
                dep.from === from || isPredecessorOf(from, dep.from)
            }
            if (coreDeps.isEmpty()) null
            else AggregatedDependencyImpl(from, to, coreDeps)
        }
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

    // mutation

    override fun addChild(parent: HGNode, child: HGNode) {
        parentMap[child.identifier] = parent.identifier
        childrenMap.getOrPut(parent.identifier) { mutableListOf() }.add(child.identifier)
        clearCaches()
    }

    override fun move(node: HGNode, newParent: HGNode) {
        val id = node.identifier
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
        predecessorCache = null
        accOutCache = null
        accInCache = null
        aggDepCache = null
    }
}
