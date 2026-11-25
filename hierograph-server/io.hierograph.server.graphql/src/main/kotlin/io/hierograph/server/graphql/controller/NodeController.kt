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
package io.hierograph.server.graphql.controller

import graphql.GraphQLContext
import io.hierograph.server.graphql.GraphSnapshotPinning
import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.graphql.UnknownIds
import io.hierograph.server.graphservice.NodePropertiesProvider
import io.hierograph.server.graphql.model.MapEntryModel
import io.hierograph.server.graphql.model.DependencySetModel
import io.hierograph.server.graphql.model.NodeSetModel
import io.hierograph.server.graphql.model.NodesToConsider
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.PackageFolding
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/**
 * Resolvers for the GraphQL `Node` type. The parent object is an [HGNode]; structural navigation is
 * resolved through the [Hierarchy] supplied by [provider], since structural navigation lives on the
 * hierarchy, not the node.
 */
@Controller
class NodeController(
    private val provider: HierarchicalGraphProvider,
    private val propertiesProvider: NodePropertiesProvider? = null,
    private val pinning: GraphSnapshotPinning? = null,
) {

    @SchemaMapping(typeName = "Node")
    fun id(node: HGNode): String = node.identifier.toString()

    @SchemaMapping(typeName = "Node")
    fun text(node: HGNode): String = NodeMetadata.getQualifiedName(node)

    @SchemaMapping(typeName = "Node")
    fun type(node: HGNode): String = node.kind?.toString() ?: "Unknown"

    @SchemaMapping(typeName = "Node")
    fun parent(node: HGNode): HGNode? = provider.hierarchy().parentOf(node)

    @SchemaMapping(typeName = "Node")
    fun predecessors(node: HGNode, @Argument compact: Boolean): List<HGNode> =
        if (compact) PackageFolding.of(provider.hierarchy()).displayPredecessorsOf(node)
        else provider.hierarchy().predecessorsOf(node)

    @SchemaMapping(typeName = "Node")
    fun hasChildren(node: HGNode): Boolean = provider.hierarchy().childrenOf(node).isNotEmpty()

    @SchemaMapping(typeName = "Node")
    fun children(node: HGNode, @Argument compact: Boolean): NodeSetModel =
        if (compact) NodeSetModel(PackageFolding.of(provider.hierarchy()).displayChildrenOf(node).map { it.node })
        else NodeSetModel(provider.hierarchy().childrenOf(node))

    // Chain folding (algorithms specification, §8): what a folded tree shows of the node.

    @SchemaMapping(typeName = "Node")
    fun compactedFrom(node: HGNode): List<HGNode> = PackageFolding.of(provider.hierarchy()).skippedAbove(node)

    @SchemaMapping(typeName = "Node")
    fun displayText(node: HGNode): String = PackageFolding.label(node, PackageFolding.of(provider.hierarchy()))

    @SchemaMapping(typeName = "Node")
    fun displayNode(node: HGNode): HGNode = PackageFolding.of(provider.hierarchy()).displayNodeOf(node)

    @SchemaMapping(typeName = "Node")
    fun childrenFilteredByReferencedNodes(
        node: HGNode,
        @Argument referencedNodeIds: List<String>,
        @Argument compact: Boolean = false,
    ): NodeSetModel {
        val hierarchy = provider.hierarchy()
        val targets = resolveIds(hierarchy, referencedNodeIds)
        val filtered = childrenOf(node, compact).filter { child ->
            hierarchy.accumulatedOutgoing(child).any { isWithin(hierarchy, it.to, targets) }
        }
        return NodeSetModel(filtered)
    }

    @SchemaMapping(typeName = "Node")
    fun childrenFilteredByReferencingNodes(
        node: HGNode,
        @Argument referencingNodeIds: List<String>,
        @Argument compact: Boolean = false,
    ): NodeSetModel {
        val hierarchy = provider.hierarchy()
        val sources = resolveIds(hierarchy, referencingNodeIds)
        val filtered = childrenOf(node, compact).filter { child ->
            hierarchy.accumulatedIncoming(child).any { isWithin(hierarchy, it.from, sources) }
        }
        return NodeSetModel(filtered)
    }

    /** The children whose subtree holds one of [nodeIds] (or is one of them); folded with [compact]. */
    @SchemaMapping(typeName = "Node")
    fun childrenContainingNodes(
        node: HGNode,
        @Argument nodeIds: List<String>,
        @Argument compact: Boolean = false,
    ): NodeSetModel {
        val hierarchy = provider.hierarchy()
        val holding = holding(hierarchy, nodeIds.mapNotNull { lookup(hierarchy, it) })
        return NodeSetModel(childrenOf(node, compact).filter { it in holding })
    }

    /** The dependencies into [node]'s subtree from outside it (#79). */
    @SchemaMapping(typeName = "Node")
    fun incomingDependencies(node: HGNode): DependencySetModel {
        val hierarchy = provider.hierarchy()
        val self = setOf(node.identifier)
        return DependencySetModel(hierarchy.accumulatedIncoming(node).filter { !isWithin(hierarchy, it.from, self) })
    }

    /** The dependencies from [node]'s subtree to outside it (#79). */
    @SchemaMapping(typeName = "Node")
    fun outgoingDependencies(node: HGNode): DependencySetModel {
        val hierarchy = provider.hierarchy()
        val self = setOf(node.identifier)
        return DependencySetModel(hierarchy.accumulatedOutgoing(node).filter { !isWithin(hierarchy, it.to, self) })
    }

    private fun childrenOf(node: HGNode, compact: Boolean): List<HGNode> =
        displayChildren(provider.hierarchy(), node, compact)

    /**
     * Batched and on demand: runs only when `properties` is selected, once for all nodes of the response.
     * Node properties are resolved by the optional [propertiesProvider] in a single call. A batch loader runs
     * outside any data fetcher, so it pins the request's graph itself (see [GraphSnapshotPinning]).
     */
    @BatchMapping(typeName = "Node", field = "properties")
    fun properties(nodes: List<HGNode>, context: GraphQLContext): Map<HGNode, List<MapEntryModel>> {
        val provided = propertiesProvider?.let { provider ->
            pinning?.pinned(context) { provider.propertiesOf(nodes) } ?: provider.propertiesOf(nodes)
        }.orEmpty()
        return nodes.associateWith { node ->
            provided[node].orEmpty().map { (k, v) -> MapEntryModel(k, v) }
        }
    }

    @SchemaMapping(typeName = "Node")
    fun dependenciesTo(node: HGNode, @Argument targetNodes: List<String>): List<HGCoreDependency> {
        val hierarchy = provider.hierarchy()
        val targets = resolveIds(hierarchy, targetNodes)
        return hierarchy.accumulatedOutgoing(node).filter { isWithin(hierarchy, it.to, targets) }
    }

    @SchemaMapping(typeName = "Node")
    fun dependenciesFrom(node: HGNode, @Argument sourceNodes: List<String>): List<HGCoreDependency> {
        val hierarchy = provider.hierarchy()
        val sources = resolveIds(hierarchy, sourceNodes)
        return hierarchy.accumulatedIncoming(node).filter { isWithin(hierarchy, it.from, sources) }
    }

    @SchemaMapping(typeName = "Node")
    fun referencedNodes(node: HGNode, @Argument includePredecessors: Boolean): NodeSetModel {
        return NodeSetModel(
            collectReferencedNodes(provider.hierarchy(), listOf(node), includePredecessors)
        )
    }

    @SchemaMapping(typeName = "Node")
    fun referencingNodes(node: HGNode, @Argument includePredecessors: Boolean): NodeSetModel {
        return NodeSetModel(
            collectReferencingNodes(provider.hierarchy(), listOf(node), includePredecessors)
        )
    }

    @SchemaMapping(typeName = "Node")
    fun filterReferencedNodes(
        node: HGNode,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessors: Boolean
    ): NodeSetModel {
        val hierarchy = provider.hierarchy()
        val targetSet = expandNodes(hierarchy, nodeIds, nodesToConsider)
        val referenced = collectReferencedNodes(hierarchy, listOf(node), false).filterTo(LinkedHashSet()) { it in targetSet }
        return NodeSetModel(withOptionalPredecessors(hierarchy, referenced, includePredecessors))
    }

    @SchemaMapping(typeName = "Node")
    fun filterReferencingNodes(
        node: HGNode,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessors: Boolean
    ): NodeSetModel {
        val hierarchy = provider.hierarchy()
        val sourceSet = expandNodes(hierarchy, nodeIds, nodesToConsider)
        val referencing = collectReferencingNodes(hierarchy, listOf(node), false).filterTo(LinkedHashSet()) { it in sourceSet }
        return NodeSetModel(withOptionalPredecessors(hierarchy, referencing, includePredecessors))
    }

    companion object {
        /** Parses a node id argument; an id that is not a handle is the client's mistake, a `BAD_REQUEST`. */
        fun nodeId(id: String): Long =
            id.toLongOrNull() ?: throw InvalidArgumentException("Invalid node id '$id': a node id is a numeric handle")

        /** The node named by [id], or `null`; an id that names no node is reported to [UnknownIds]. */
        fun lookup(hierarchy: Hierarchy, id: String): HGNode? =
            hierarchy.lookupNode(nodeId(id)) ?: null.also { UnknownIds.node(id) }

        /** The identifiers of the nodes named by [ids]; ids that name no node are dropped and reported. */
        fun resolveIds(hierarchy: Hierarchy, ids: List<String>): Set<Any> =
            ids.mapNotNullTo(HashSet()) { lookup(hierarchy, it)?.identifier }

        /**
         * Subtree matching: whether [node] is one of [anchors] or a descendant of one. Dependency endpoints
         * are types, so this is what lets package and module ids match the dependencies of their types.
         */
        fun isWithin(hierarchy: Hierarchy, node: HGNode, anchors: Set<Any>): Boolean {
            var current: HGNode? = node
            while (current != null) {
                if (current.identifier in anchors) return true
                current = hierarchy.parentOf(current)
            }
            return false
        }

        /**
         * [node]'s children as a tree shows them: with [compact] the display children of chain folding, whose
         * subtrees are those of the chains they end (a skipped package holds nothing but the next one).
         */
        fun displayChildren(hierarchy: Hierarchy, node: HGNode, compact: Boolean): List<HGNode> =
            if (compact) PackageFolding.of(hierarchy).displayChildrenOf(node).map { it.node }
            else hierarchy.childrenOf(node)

        /**
         * [nodes] and all their ancestors: a node's subtree holds one of [nodes] exactly when the node is in
         * this set. Also for display children, since a folded chain's skipped packages hold nothing else.
         */
        fun holding(hierarchy: Hierarchy, nodes: Iterable<HGNode>): Set<HGNode> {
            val result = HashSet<HGNode>()
            for (node in nodes) {
                if (result.add(node)) result += hierarchy.predecessorsOf(node)
            }
            return result
        }

        /**
         * The nodes that [nodes] depend on: the targets of each node's accumulated outgoing dependencies,
         * leaving out targets inside that node's own subtree. Its internal dependencies are not references
         * to anything else.
         */
        fun collectReferencedNodes(
            hierarchy: Hierarchy,
            nodes: List<HGNode>,
            includePredecessors: Boolean
        ): List<HGNode> {
            val targets = mutableSetOf<HGNode>()
            for (node in nodes) {
                val self = setOf(node.identifier)
                for (dep in hierarchy.accumulatedOutgoing(node)) {
                    if (!isWithin(hierarchy, dep.to, self)) targets.add(dep.to)
                }
            }
            return withOptionalPredecessors(hierarchy, targets, includePredecessors)
        }

        /** The nodes that depend on [nodes], leaving out sources inside each node's own subtree. */
        fun collectReferencingNodes(
            hierarchy: Hierarchy,
            nodes: List<HGNode>,
            includePredecessors: Boolean
        ): List<HGNode> {
            val sources = mutableSetOf<HGNode>()
            for (node in nodes) {
                val self = setOf(node.identifier)
                for (dep in hierarchy.accumulatedIncoming(node)) {
                    if (!isWithin(hierarchy, dep.from, self)) sources.add(dep.from)
                }
            }
            return withOptionalPredecessors(hierarchy, sources, includePredecessors)
        }

        fun expandNodes(
            hierarchy: Hierarchy,
            nodeIds: List<String>,
            nodesToConsider: NodesToConsider
        ): Set<HGNode> {
            val result = mutableSetOf<HGNode>()
            for (id in nodeIds) {
                val node = lookup(hierarchy, id) ?: continue
                result.add(node)
                when (nodesToConsider) {
                    NodesToConsider.SELF -> {}
                    NodesToConsider.SELF_AND_CHILDREN -> result.addAll(hierarchy.childrenOf(node))
                    NodesToConsider.SELF_AND_SUCCESSORS ->
                        hierarchy.traverse(node) { result.add(it) }
                }
            }
            return result
        }

        fun withOptionalPredecessors(
            hierarchy: Hierarchy,
            nodes: Set<HGNode>,
            includePredecessors: Boolean
        ): List<HGNode> {
            if (!includePredecessors) return nodes.toList()
            val result = mutableSetOf<HGNode>()
            for (node in nodes) {
                result.add(node)
                result.addAll(hierarchy.predecessorsOf(node))
            }
            return result.toList()
        }
    }
}
