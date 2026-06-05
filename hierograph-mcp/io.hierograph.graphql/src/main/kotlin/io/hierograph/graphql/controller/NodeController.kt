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
package io.hierograph.graphql.controller

import io.hierograph.graphql.model.MapEntryModel
import io.hierograph.graphql.model.NodeSetModel
import io.hierograph.graphql.model.NodesToConsider
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGNodeTraverser
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class NodeController {

    @SchemaMapping(typeName = "Node")
    fun id(node: HGNode): String = node.identifier.toString()

    @SchemaMapping(typeName = "Node")
    fun text(node: HGNode): String = JQAssistantNodeMetadataProvider.getQualifiedName(node)

    @SchemaMapping(typeName = "Node")
    fun type(node: HGNode): String = node.kind?.toString() ?: "Unknown"

    @SchemaMapping(typeName = "Node")
    fun parent(node: HGNode): HGNode? = node.parent

    @SchemaMapping(typeName = "Node")
    fun predecessors(node: HGNode): List<HGNode> = node.predecessors

    @SchemaMapping(typeName = "Node")
    fun hasChildren(node: HGNode): Boolean = node.children.isNotEmpty()

    @SchemaMapping(typeName = "Node")
    fun children(node: HGNode): NodeSetModel = NodeSetModel(node.children)

    @SchemaMapping(typeName = "Node")
    fun childrenFilteredByReferencedNodes(
        node: HGNode,
        @Argument referencedNodeIds: List<String>
    ): NodeSetModel {
        val targetIds = referencedNodeIds.map { it.toLong() }.toSet()
        val filtered = node.children.filter { child ->
            child.accumulatedOutgoingCoreDependencies.any { it.to.identifier in targetIds }
        }
        return NodeSetModel(filtered)
    }

    @SchemaMapping(typeName = "Node")
    fun childrenFilteredByReferencingNodes(
        node: HGNode,
        @Argument referencingNodeIds: List<String>
    ): NodeSetModel {
        val sourceIds = referencingNodeIds.map { it.toLong() }.toSet()
        val filtered = node.children.filter { child ->
            child.accumulatedIncomingCoreDependencies.any { it.from.identifier in sourceIds }
        }
        return NodeSetModel(filtered)
    }

    @SchemaMapping(typeName = "Node")
    fun properties(node: HGNode): List<MapEntryModel> {
        val source = node.nodeSource as? GraphDbNodeSource ?: return emptyList()
        return source.properties.map { (k, v) -> MapEntryModel(k, v) }
    }

    @SchemaMapping(typeName = "Node")
    fun dependenciesTo(node: HGNode, @Argument targetNodes: List<String>): List<HGCoreDependency> {
        val targetIds = targetNodes.map { it.toLong() }.toSet()
        return node.accumulatedOutgoingCoreDependencies.filter { it.to.identifier in targetIds }
    }

    @SchemaMapping(typeName = "Node")
    fun dependenciesFrom(node: HGNode, @Argument sourceNodes: List<String>): List<HGCoreDependency> {
        val sourceIds = sourceNodes.map { it.toLong() }.toSet()
        return node.accumulatedIncomingCoreDependencies.filter { it.from.identifier in sourceIds }
    }

    @SchemaMapping(typeName = "Node")
    fun referencedNodes(node: HGNode, @Argument includePredecessors: Boolean?): NodeSetModel {
        return NodeSetModel(collectReferencedNodes(listOf(node), includePredecessors ?: false))
    }

    @SchemaMapping(typeName = "Node")
    fun referencingNodes(node: HGNode, @Argument includePredecessors: Boolean?): NodeSetModel {
        return NodeSetModel(collectReferencingNodes(listOf(node), includePredecessors ?: false))
    }

    @SchemaMapping(typeName = "Node")
    fun filterReferencedNodes(
        node: HGNode,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessorsInResult: Boolean?
    ): NodeSetModel {
        val targetSet = expandNodes(node.rootNode, nodeIds, nodesToConsider)
        val referenced = node.accumulatedOutgoingCoreDependencies
            .map { it.to }
            .filter { it in targetSet }
            .toSet()
        return NodeSetModel(withOptionalPredecessors(referenced, includePredecessorsInResult ?: false))
    }

    @SchemaMapping(typeName = "Node")
    fun filterReferencingNodes(
        node: HGNode,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessorsInResult: Boolean?
    ): NodeSetModel {
        val sourceSet = expandNodes(node.rootNode, nodeIds, nodesToConsider)
        val referencing = node.accumulatedIncomingCoreDependencies
            .map { it.from }
            .filter { it in sourceSet }
            .toSet()
        return NodeSetModel(withOptionalPredecessors(referencing, includePredecessorsInResult ?: false))
    }

    companion object {
        fun collectReferencedNodes(nodes: List<HGNode>, includePredecessors: Boolean): List<HGNode> {
            val targets = mutableSetOf<HGNode>()
            for (node in nodes) {
                for (dep in node.accumulatedOutgoingCoreDependencies) {
                    targets.add(dep.to)
                }
            }
            return withOptionalPredecessors(targets, includePredecessors)
        }

        fun collectReferencingNodes(nodes: List<HGNode>, includePredecessors: Boolean): List<HGNode> {
            val sources = mutableSetOf<HGNode>()
            for (node in nodes) {
                for (dep in node.accumulatedIncomingCoreDependencies) {
                    sources.add(dep.from)
                }
            }
            return withOptionalPredecessors(sources, includePredecessors)
        }

        fun expandNodes(
            rootNode: io.hierograph.hierarchicalgraph.core.model.HGRootNode,
            nodeIds: List<String>,
            nodesToConsider: NodesToConsider
        ): Set<HGNode> {
            val result = mutableSetOf<HGNode>()
            for (id in nodeIds) {
                val node = rootNode.lookupNode(id.toLong()) ?: continue
                result.add(node)
                when (nodesToConsider) {
                    NodesToConsider.SELF -> {}
                    NodesToConsider.SELF_AND_CHILDREN -> result.addAll(node.children)
                    NodesToConsider.SELF_AND_SUCCESSORS ->
                        HGNodeTraverser.traverse(node) { result.add(it) }
                }
            }
            return result
        }

        fun withOptionalPredecessors(nodes: Set<HGNode>, includePredecessors: Boolean): List<HGNode> {
            if (!includePredecessors) return nodes.toList()
            val result = mutableSetOf<HGNode>()
            for (node in nodes) {
                result.add(node)
                result.addAll(node.predecessors)
            }
            return result.toList()
        }
    }
}
