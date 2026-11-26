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
import io.hierograph.server.graphql.model.FilteredChildModel
import io.hierograph.server.graphql.model.FilteredDependenciesModel
import io.hierograph.server.graphql.model.NodeSelection
import io.hierograph.server.graphql.model.NodeType
import io.hierograph.server.graphql.model.PageInfoModel
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
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
        @Argument parentNodeType: NodeType,
        @Argument compact: Boolean = false,
    ): List<HGNode> {
        return filteredChildrenOf(provider.hierarchy(), depSet.dependencyList, parentNode, parentNodeType, compact)
    }

    @SchemaMapping(typeName = "DependencySet")
    fun filteredChildrenIds(
        depSet: DependencySetModel,
        @Argument parentNode: String,
        @Argument parentNodeType: NodeType,
        @Argument compact: Boolean = false,
    ): List<String> {
        return filteredChildren(depSet, parentNode, parentNodeType, compact)
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

    @SchemaMapping(typeName = "DependencySet")
    fun filteredChildrenWithCounts(
        depSet: DependencySetModel,
        @Argument parentNode: String,
        @Argument parentNodeType: NodeType,
        @Argument compact: Boolean = false,
    ): List<FilteredChildModel> =
        countedChildrenOf(provider.hierarchy(), depSet.dependencyList, parentNode, parentNodeType, compact)

    companion object {
        /**
         * [filteredChildrenOf] with counts: for each child, the dependencies of [deps] whose end on
         * [parentNodeType]'s side lies in its subtree, and their summed weight. Empty when [parentNode] names
         * no node.
         */
        fun countedChildrenOf(
            hierarchy: Hierarchy,
            deps: List<HGCoreDependency>,
            parentNode: String,
            parentNodeType: NodeType,
            compact: Boolean,
        ): List<FilteredChildModel> {
            val parent = NodeController.lookup(hierarchy, parentNode) ?: return emptyList()
            val children = NodeController.displayChildren(hierarchy, parent, compact)
            val childSet = children.toHashSet()
            val counts = HashMap<HGNode, Int>()
            val weights = HashMap<HGNode, Int>()
            for (dep in deps) {
                var current: HGNode? = if (parentNodeType == NodeType.SOURCE) dep.from else dep.to
                // The child holding the end: a folded child's skipped packages hold nothing else.
                while (current != null && current !in childSet) current = hierarchy.parentOf(current)
                if (current == null) continue
                counts.merge(current, 1, Int::plus)
                weights.merge(current, dep.weight, Int::plus)
            }
            return children.mapNotNull { child ->
                counts[child]?.let { FilteredChildModel(child, it, weights.getValue(child)) }
            }
        }

        /**
         * The children of [parentNode] whose subtree contains a source ([NodeType.SOURCE]) or a target of [deps],
         * in child order; with [compact], of the display children. Empty when [parentNode] names no node.
         */
        fun filteredChildrenOf(
            hierarchy: Hierarchy,
            deps: List<HGCoreDependency>,
            parentNode: String,
            parentNodeType: NodeType,
            compact: Boolean,
        ): List<HGNode> {
            val parent = NodeController.lookup(hierarchy, parentNode) ?: return emptyList()
            val endpoints = when (parentNodeType) {
                NodeType.SOURCE -> deps.mapTo(HashSet()) { it.from }
                NodeType.TARGET -> deps.mapTo(HashSet()) { it.to }
            }
            val holding = NodeController.holding(hierarchy, endpoints)
            return NodeController.displayChildren(hierarchy, parent, compact).filter { it in holding }
        }

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
