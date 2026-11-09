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

import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.graphql.model.DependencyPageModel
import io.hierograph.server.graphql.model.DependencySetModel
import io.hierograph.server.graphql.model.FilteredDependenciesModel
import io.hierograph.server.graphql.model.NodeSelection
import io.hierograph.server.graphql.model.NodeType
import io.hierograph.server.graphql.model.PageInfoModel
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.HGNode
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
        val hierarchy = provider.hierarchy()
        val parent = NodeController.lookup(hierarchy, parentNode) ?: return emptyList()
        val endpoints = when (parentNodeType) {
            NodeType.SOURCE -> depSet.dependencyList.map { it.from }
            NodeType.TARGET -> depSet.dependencyList.map { it.to }
        }
        val containing = endpoints.mapNotNullTo(HashSet()) { NodeController.childContaining(hierarchy, parent, it) }
        return hierarchy.childrenOf(parent).filter { it in containing }
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
        val hierarchy = provider.hierarchy()
        // A side is restricted as soon as a selection names ids for it, even if none of them resolves:
        // selecting only unknown nodes matches nothing rather than everything.
        var sourceIds: MutableSet<Any>? = null
        var targetIds: MutableSet<Any>? = null

        for (selection in nodeSelection) {
            if (selection.selectedNodeIds.isEmpty()) continue
            val ids = NodeController.resolveIds(hierarchy, selection.selectedNodeIds)
            when (selection.selectedNodesType) {
                NodeType.SOURCE -> sourceIds = (sourceIds ?: mutableSetOf()).apply { addAll(ids) }
                NodeType.TARGET -> targetIds = (targetIds ?: mutableSetOf()).apply { addAll(ids) }
            }
        }

        val sources = sourceIds
        val targets = targetIds
        val filtered = depSet.dependencyList.filter { dep ->
            (sources == null || NodeController.isWithin(hierarchy, dep.from, sources)) &&
                (targets == null || NodeController.isWithin(hierarchy, dep.to, targets))
        }

        return FilteredDependenciesModel(filtered)
    }

    companion object {
        /**
         * Slices [deps] into the 1-based page [pageNumber] of [pageSize] entries. A page past the end is
         * empty; a non-positive [pageNumber] or [pageSize] is rejected with [InvalidArgumentException].
         */
        fun createPage(
            deps: List<HGCoreDependency>,
            pageNumber: Int,
            pageSize: Int
        ): DependencyPageModel {
            if (pageNumber < 1) throw InvalidArgumentException("pageNumber must be >= 1 (pages are 1-based), was $pageNumber")
            if (pageSize < 1) throw InvalidArgumentException("pageSize must be >= 1, was $pageSize")
            val totalCount = deps.size
            val maxPages = ceil(totalCount.toDouble() / pageSize).toInt()
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
