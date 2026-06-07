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

import io.hierograph.graphql.HierarchicalGraphProvider
import io.hierograph.graphql.model.DependencyPageModel
import io.hierograph.graphql.model.FilteredDependenciesModel
import io.hierograph.graphql.model.NodeType
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class FilteredDependenciesController(private val provider: HierarchicalGraphProvider) {

    @SchemaMapping(typeName = "FilteredDependencies")
    fun size(model: FilteredDependenciesModel): Int = model.dependencyList.size

    @SchemaMapping(typeName = "FilteredDependencies")
    fun dependencies(model: FilteredDependenciesModel): List<HGCoreDependency> = model.dependencyList

    @SchemaMapping(typeName = "FilteredDependencies")
    fun dependencyPage(
        model: FilteredDependenciesModel,
        @Argument pageNumber: Int,
        @Argument pageSize: Int
    ): DependencyPageModel {
        return DependencySetController.createPage(model.dependencyList, pageNumber, pageSize)
    }

    @SchemaMapping(typeName = "FilteredDependencies")
    fun nodes(
        model: FilteredDependenciesModel,
        @Argument nodeType: NodeType,
        @Argument includedPredecessors: Boolean
    ): List<HGNode> {
        val nodes = extractNodes(model.dependencyList, nodeType)
        return if (includedPredecessors) addPredecessors(nodes) else nodes.toList()
    }

    @SchemaMapping(typeName = "FilteredDependencies")
    fun nodeIds(
        model: FilteredDependenciesModel,
        @Argument nodeType: NodeType,
        @Argument includedPredecessors: Boolean
    ): List<String> {
        return nodes(model, nodeType, includedPredecessors).map { it.identifier.toString() }
    }

    @SchemaMapping(typeName = "FilteredDependencies")
    fun referencedNodes(
        model: FilteredDependenciesModel,
        @Argument nodeType: NodeType,
        @Argument includedPredecessors: Boolean
    ): List<HGNode> {
        val hierarchy = provider.hierarchy()
        val baseNodes = extractNodes(model.dependencyList, nodeType)
        val referenced = mutableSetOf<HGNode>()
        for (node in baseNodes) {
            for (dep in hierarchy.accumulatedOutgoing(node)) {
                referenced.add(dep.to)
            }
        }
        return if (includedPredecessors) addPredecessors(referenced) else referenced.toList()
    }

    @SchemaMapping(typeName = "FilteredDependencies")
    fun referencedNodeIds(
        model: FilteredDependenciesModel,
        @Argument nodeType: NodeType,
        @Argument includedPredecessors: Boolean
    ): List<String> {
        return referencedNodes(model, nodeType, includedPredecessors).map { it.identifier.toString() }
    }

    private fun extractNodes(deps: List<HGCoreDependency>, nodeType: NodeType): Set<HGNode> {
        return when (nodeType) {
            NodeType.SOURCE -> deps.map { it.from }.toSet()
            NodeType.TARGET -> deps.map { it.to }.toSet()
        }
    }

    private fun addPredecessors(nodes: Set<HGNode>): List<HGNode> {
        val hierarchy = provider.hierarchy()
        val result = mutableSetOf<HGNode>()
        for (node in nodes) {
            result.add(node)
            result.addAll(hierarchy.predecessorsOf(node))
        }
        return result.toList()
    }
}
