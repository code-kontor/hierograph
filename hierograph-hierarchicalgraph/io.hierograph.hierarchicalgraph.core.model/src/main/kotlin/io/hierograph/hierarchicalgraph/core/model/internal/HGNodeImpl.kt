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
package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.*

open class HGNodeImpl(
    kind: Any?,
    override val nodeSource: INodeSource
) : HGNode {

    override var kind: Any? = kind

    internal var _parent: HGNode? = null
    internal var _children: MutableList<HGNode>? = null

    internal var _outgoingCoreDependencies: MutableList<HGCoreDependency>? = null
    internal var _incomingCoreDependencies: MutableList<HGCoreDependency>? = null

    private var _cachedAggregatedOutgoing: MutableMap<HGNode, HGAggregatedDependency>? = null
    private var _cachedAggregatedIncoming: MutableMap<HGNode, HGAggregatedDependency>? = null

    // -- stored properties --

    override val parent: HGNode? get() = _parent
    override val children: List<HGNode> get() = _children ?: emptyList()

    // -- derived properties --

    override val identifier: Any get() = nodeSource.identifier

    override val rootNode: HGRootNode
        get() {
            if (_parent == null) {
                if (this is HGRootNode) return this
                throw IllegalStateException("No root set for $identifier")
            }
            return _parent!!.rootNode
        }

    override val predecessors: List<HGNode> by lazy {
        val p = _parent ?: return@lazy emptyList()
        buildList {
            add(p)
            addAll(p.predecessors)
        }
    }

    override val outgoingCoreDependencies: List<HGCoreDependency>
        get() = _outgoingCoreDependencies ?: emptyList()

    override val incomingCoreDependencies: List<HGCoreDependency>
        get() = _incomingCoreDependencies ?: emptyList()

    override val accumulatedOutgoingCoreDependencies: List<HGCoreDependency> by lazy {
        buildList {
            _outgoingCoreDependencies?.let { addAll(it) }
            _children?.forEach { child ->
                addAll(child.accumulatedOutgoingCoreDependencies)
            }
        }
    }

    override val accumulatedIncomingCoreDependencies: List<HGCoreDependency> by lazy {
        buildList {
            _incomingCoreDependencies?.let { addAll(it) }
            _children?.forEach { child ->
                addAll(child.accumulatedIncomingCoreDependencies)
            }
        }
    }

    // -- operations --

    override fun isPredecessorOf(node: HGNode?): Boolean {
        if (node == null) return false
        return node.predecessors.contains(this)
    }

    override fun isSuccessorOf(node: HGNode?): Boolean {
        if (node == null) return false
        return node.isPredecessorOf(this)
    }

    override fun getOutgoingDependenciesTo(target: HGNode): HGAggregatedDependency? {
        val map = cachedAggregatedOutgoing()
        val dep = map.getOrPut(target) {
            HGAggregatedDependencyImpl(from = this, to = target).also {
                (target as HGNodeImpl).cachedAggregatedIncoming()[this] = it
            }
        }
        return if (dep.aggregatedWeight > 0) dep else null
    }

    override fun getOutgoingDependenciesTo(targets: List<HGNode>): List<HGAggregatedDependency> {
        return targets.mapNotNull { getOutgoingDependenciesTo(it) }
    }

    override fun getIncomingDependenciesFrom(source: HGNode): HGAggregatedDependency? {
        val map = cachedAggregatedIncoming()
        val dep = map.getOrPut(source) {
            HGAggregatedDependencyImpl(from = source, to = this).also {
                (source as HGNodeImpl).cachedAggregatedOutgoing()[this] = it
            }
        }
        return if (dep.aggregatedWeight > 0) dep else null
    }

    override fun getIncomingDependenciesFrom(sources: List<HGNode>): List<HGAggregatedDependency> {
        return sources.mapNotNull { getIncomingDependenciesFrom(it) }
    }

    override fun <T : Any> getNodeSource(clazz: Class<T>): T? {
        return if (clazz.isInstance(nodeSource)) clazz.cast(nodeSource) else null
    }

    // -- mutable list access (lazy allocation) --

    internal fun childrenMutable(): MutableList<HGNode> {
        if (_children == null) _children = mutableListOf()
        return _children!!
    }

    internal fun outgoingMutable(): MutableList<HGCoreDependency> {
        if (_outgoingCoreDependencies == null) _outgoingCoreDependencies = mutableListOf()
        return _outgoingCoreDependencies!!
    }

    internal fun incomingMutable(): MutableList<HGCoreDependency> {
        if (_incomingCoreDependencies == null) _incomingCoreDependencies = mutableListOf()
        return _incomingCoreDependencies!!
    }

    private fun cachedAggregatedOutgoing(): MutableMap<HGNode, HGAggregatedDependency> {
        if (_cachedAggregatedOutgoing == null) _cachedAggregatedOutgoing = mutableMapOf()
        return _cachedAggregatedOutgoing!!
    }

    private fun cachedAggregatedIncoming(): MutableMap<HGNode, HGAggregatedDependency> {
        if (_cachedAggregatedIncoming == null) _cachedAggregatedIncoming = mutableMapOf()
        return _cachedAggregatedIncoming!!
    }
}
