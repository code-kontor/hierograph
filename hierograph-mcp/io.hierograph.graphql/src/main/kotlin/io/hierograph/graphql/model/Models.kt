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
package io.hierograph.graphql.model

import io.hierograph.hierarchicalgraph.core.model.CoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode

data class NodeSetModel(val nodeList: List<HGNode>)

data class DependencySetModel(val dependencyList: List<CoreDependency>)

data class FilteredDependenciesModel(val dependencyList: List<CoreDependency>)

data class CellModel(val row: Int, val column: Int, val value: Int)

class StronglyConnectedComponentModel(
    private val hgNodes: List<HGNode>,
    val nodePositions: List<Int>
) {
    val nodes: List<HGNode> get() = hgNodes
    val nodeIds: List<String> get() = hgNodes.map { it.identifier.toString() }
}

data class OrderedAdjacencyMatrixModel(
    val orderedNodes: List<HGNode>,
    val cells: List<CellModel>,
    val stronglyConnectedComponents: List<StronglyConnectedComponentModel>
)

data class MapEntryModel(val key: String, val value: String?)

data class PageInfoModel(
    val pageNumber: Int,
    val maxPages: Int,
    val pageSize: Int,
    val totalCount: Int
)

data class DependencyPageModel(
    val pageInfo: PageInfoModel,
    val dependencies: List<CoreDependency>
)

data class NodeSelection(
    val selectedNodeIds: List<String>,
    val selectedNodesType: NodeType
)

enum class NodeType {
    SOURCE, TARGET
}

enum class NodesToConsider {
    SELF, SELF_AND_CHILDREN, SELF_AND_SUCCESSORS
}
