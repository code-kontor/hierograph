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

import io.hierograph.graphql.NodeSearchProvider
import io.hierograph.graphql.model.DependencySetModel
import io.hierograph.graphql.model.NodeSetModel
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.Hierarchy
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/**
 * Resolvers for the GraphQL `HierarchicalGraph` type, which is backed by a [Hierarchy] (the unit that
 * now owns root, node lookup, and structural navigation).
 */
@Controller
class HierarchicalGraphController(private val searchProvider: NodeSearchProvider? = null) {

    /** Name search, delegated to the host's [NodeSearchProvider]; see the schema for matching and ranking. */
    @SchemaMapping(typeName = "HierarchicalGraph")
    fun findNodes(
        hierarchy: Hierarchy,
        @Argument name: String,
        @Argument kinds: List<String>?,
        @Argument limit: Int,
    ): List<HGNode> {
        if (limit !in 1..MAX_FIND_LIMIT) throw InvalidArgumentException("limit must be between 1 and $MAX_FIND_LIMIT, was $limit")
        if (name.isBlank()) throw InvalidArgumentException("name must not be blank")
        val provider = searchProvider ?: throw InvalidArgumentException("findNodes is not available: no search index is configured")
        return provider.findNodes(name, kinds, limit)
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
        hierarchy.lookupNode(id.toLong())

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun nodes(hierarchy: Hierarchy, @Argument ids: List<String>): NodeSetModel {
        val resolved = ids.mapNotNull { hierarchy.lookupNode(it.toLong()) }
        return NodeSetModel(resolved)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependency(hierarchy: Hierarchy, @Argument id: String): HGCoreDependency? {
        return findCoreDependencyById(hierarchy, id)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependencies(hierarchy: Hierarchy, @Argument ids: List<String>): DependencySetModel? {
        val idSet = ids.toSet()
        val deps = collectAllCoreDependencies(hierarchy).filter { coreDependencyId(it) in idSet }
        return DependencySetModel(deps)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependencySetForAggregatedDependency(
        hierarchy: Hierarchy,
        @Argument sourceNodeId: String,
        @Argument targetNodeId: String
    ): DependencySetModel? {
        val sourceNode = hierarchy.lookupNode(sourceNodeId.toLong()) ?: return null
        val targetNode = hierarchy.lookupNode(targetNodeId.toLong()) ?: return null
        val aggregated = hierarchy.getAggregatedDependency(sourceNode, targetNode) ?: return null
        return DependencySetModel(aggregated.coreDependencies)
    }

    companion object {
        /** Upper bound for `findNodes(limit:)`; the result is a top-N, not a page. */
        const val MAX_FIND_LIMIT = 200

        fun coreDependencyId(dep: HGCoreDependency): String =
            "${dep.from.identifier}_${dep.to.identifier}_${dep.type}"

        fun findCoreDependencyById(hierarchy: Hierarchy, id: String): HGCoreDependency? {
            return collectAllCoreDependencies(hierarchy).firstOrNull { coreDependencyId(it) == id }
        }

        fun collectAllCoreDependencies(hierarchy: Hierarchy): List<HGCoreDependency> {
            val deps = mutableListOf<HGCoreDependency>()
            fun walk(node: HGNode) {
                deps.addAll(node.outgoingCoreDependencies)
                for (child in hierarchy.childrenOf(node)) walk(child)
            }
            walk(hierarchy.rootNode)
            return deps
        }
    }
}
