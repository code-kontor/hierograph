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

import io.hierograph.server.graphservice.NodeSearch
import io.hierograph.server.graphql.model.DependencySetModel
import io.hierograph.server.graphql.model.NodeSetModel
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/**
 * Resolvers for the GraphQL `HierarchicalGraph` type, which is backed by a [Hierarchy] (the unit that
 * owns root, node lookup, and structural navigation).
 */
@Controller
class HierarchicalGraphController(private val nodeSearch: NodeSearch? = null) {

    /** Name search via the shared [NodeSearch] (same matching and ranking as `find_node`); see the schema. */
    @SchemaMapping(typeName = "HierarchicalGraph")
    fun findNodes(
        hierarchy: Hierarchy,
        @Argument name: String,
        @Argument kinds: List<String>?,
        @Argument limit: Int,
    ): List<HGNode> {
        if (limit !in 1..MAX_FIND_LIMIT) throw InvalidArgumentException("limit must be between 1 and $MAX_FIND_LIMIT, was $limit")
        if (name.isBlank()) throw InvalidArgumentException("name must not be blank")
        val search = nodeSearch ?: throw InvalidArgumentException("findNodes is not available: no search index is configured")
        val invalid = search.invalidKinds(kinds)
        if (invalid.isNotEmpty()) {
            throw InvalidArgumentException(
                "Unknown kind${if (invalid.size > 1) "s" else ""}: ${invalid.joinToString(", ") { "'$it'" }}. " +
                    "Valid kinds: ${search.validKindValues.joinToString(", ")}. " +
                    "Group aliases: ${search.validAliases.joinToString(", ")}."
            )
        }
        return search.search(name, kinds, limit)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun identifier(hierarchy: Hierarchy): String = hierarchy.rootNode.identifier.toString()

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun globalIdentifier(hierarchy: Hierarchy): String =
        hierarchy.name ?: hierarchy.rootNode.identifier.toString()

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun rootNode(hierarchy: Hierarchy): HGNode = hierarchy.rootNode

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun node(hierarchy: Hierarchy, @Argument id: String): HGNode? =
        hierarchy.lookupNode(NodeController.nodeId(id))

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun nodes(hierarchy: Hierarchy, @Argument ids: List<String>): NodeSetModel {
        val resolved = ids.mapNotNull { hierarchy.lookupNode(NodeController.nodeId(it)) }
        return NodeSetModel(resolved)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependency(hierarchy: Hierarchy, @Argument id: String): HGCoreDependency? {
        return findCoreDependencyById(hierarchy, id)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependencies(hierarchy: Hierarchy, @Argument ids: List<String>): DependencySetModel? {
        return DependencySetModel(ids.distinct().mapNotNull { findCoreDependencyById(hierarchy, it) })
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependencySetForAggregatedDependency(
        hierarchy: Hierarchy,
        @Argument sourceNodeId: String,
        @Argument targetNodeId: String
    ): DependencySetModel? {
        val sourceNode = hierarchy.lookupNode(NodeController.nodeId(sourceNodeId)) ?: return null
        val targetNode = hierarchy.lookupNode(NodeController.nodeId(targetNodeId)) ?: return null
        val aggregated = hierarchy.getAggregatedDependency(sourceNode, targetNode) ?: return null
        return DependencySetModel(aggregated.coreDependencies)
    }

    companion object {
        /** Upper bound for `findNodes(limit:)`; the result is a top-N, not a page. */
        const val MAX_FIND_LIMIT = 200

        fun coreDependencyId(dep: HGCoreDependency): String =
            "${dep.from.identifier}_${dep.to.identifier}_${dep.type}"

        /**
         * The core dependency named by [id] (see [coreDependencyId]), or `null` when there is none. The id
         * names both endpoints, so this looks only at the source's outgoing dependencies. Handles contain no
         * `_`, so the first two separate the endpoints and the rest is the type. A malformed id is a
         * `BAD_REQUEST`.
         */
        fun findCoreDependencyById(hierarchy: Hierarchy, id: String): HGCoreDependency? {
            val parts = id.split('_', limit = 3)
            val from = parts.getOrNull(0)?.toLongOrNull()
            val to = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size != 3 || from == null || to == null) {
                throw InvalidArgumentException("Invalid dependency id '$id': expected <source id>_<target id>_<type>")
            }
            val type = parts[2]
            return hierarchy.lookupNode(from)?.outgoingCoreDependencies
                ?.firstOrNull { it.to.identifier == to && it.type == type }
        }
    }
}
