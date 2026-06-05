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
import io.hierograph.graphql.model.DependencySetModel
import io.hierograph.graphql.model.FilteredDependenciesModel
import io.hierograph.graphql.model.NodeSelection
import io.hierograph.graphql.model.NodeType
import io.hierograph.graphql.model.PageInfoModel
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import kotlin.math.ceil
import kotlin.math.min

@Controller
class DependencySetController(private val provider: HierarchicalGraphProvider) {

    @SchemaMapping(typeName = "DependencySet")
    fun size(depSet: DependencySetModel): Int = depSet.dependencyList.size

    @SchemaMapping(typeName = "DependencySet")
    fun dependencies(depSet: DependencySetModel): List<HGCoreDependency> = depSet.dependencyList

    @SchemaMapping(typeName = "DependencySet")
    fun dependencyPage(
        depSet: DependencySetModel,
        @Argument pageNumber: Int,
        @Argument pageSize: Int
    ): DependencyPageModel {
        return createPage(depSet.dependencyList, pageNumber, pageSize)
    }

    @SchemaMapping(typeName = "DependencySet")
    fun filteredChildren(
        depSet: DependencySetModel,
        @Argument parentNode: String,
        @Argument parentNodeType: NodeType
    ): List<HGNode> {
        val parent = provider.rootNode().lookupNode(parentNode.toLong()) ?: return emptyList()
        val nodeIds = when (parentNodeType) {
            NodeType.SOURCE -> depSet.dependencyList.map { it.from.identifier }.toSet()
            NodeType.TARGET -> depSet.dependencyList.map { it.to.identifier }.toSet()
        }
        return parent.children.filter { it.identifier in nodeIds }
    }

    @SchemaMapping(typeName = "DependencySet")
    fun filteredChildrenIds(
        depSet: DependencySetModel,
        @Argument parentNode: String,
        @Argument parentNodeType: NodeType
    ): List<String> {
        return filteredChildren(depSet, parentNode, parentNodeType)
            .map { it.identifier.toString() }
    }

    @SchemaMapping(typeName = "DependencySet")
    fun filteredDependencies(
        depSet: DependencySetModel,
        @Argument nodeSelection: List<NodeSelection>
    ): FilteredDependenciesModel {
        val rootNode = provider.rootNode()
        val sourceIds = mutableSetOf<Any>()
        val targetIds = mutableSetOf<Any>()

        for (selection in nodeSelection) {
            val ids = selection.selectedNodeIds.mapNotNull { rootNode.lookupNode(it.toLong())?.identifier }
            when (selection.selectedNodesType) {
                NodeType.SOURCE -> sourceIds.addAll(ids)
                NodeType.TARGET -> targetIds.addAll(ids)
            }
        }

        val filtered = depSet.dependencyList.filter { dep ->
            (sourceIds.isEmpty() || dep.from.identifier in sourceIds) &&
                (targetIds.isEmpty() || dep.to.identifier in targetIds)
        }

        return FilteredDependenciesModel(filtered)
    }

    companion object {
        fun createPage(
            deps: List<HGCoreDependency>,
            pageNumber: Int,
            pageSize: Int
        ): DependencyPageModel {
            val totalCount = deps.size
            val maxPages = if (pageSize > 0) ceil(totalCount.toDouble() / pageSize).toInt() else 0
            val fromIndex = min((pageNumber - 1) * pageSize, totalCount)
            val toIndex = min(fromIndex + pageSize, totalCount)
            val pageDeps = if (fromIndex < toIndex) deps.subList(fromIndex, toIndex) else emptyList()

            return DependencyPageModel(
                pageInfo = PageInfoModel(
                    pageNumber = pageNumber,
                    maxPages = maxPages,
                    pageSize = pageSize,
                    totalCount = totalCount
                ),
                dependencies = pageDeps
            )
        }
    }
}
