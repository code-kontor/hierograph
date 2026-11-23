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
package io.hierograph.server.graphql.model

import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.HGNode

data class NodeSetModel(val nodeList: List<HGNode>)

data class DependencySetModel(val dependencyList: List<HGCoreDependency>)

data class FilteredDependenciesModel(val dependencyList: List<HGCoreDependency>)

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

/** `Levelization`: the rows bottom first, the tangles, the feedback dependencies heaviest first. */
data class LevelizationModel(
    val levels: List<LevelModel>,
    val tangles: List<TangleModel>,
    val feedbackDependencies: List<FeedbackDependencyModel>
)

data class LevelModel(val index: Int, val nodes: List<HGNode>)

data class TangleModel(val level: Int, val nodes: List<HGNode>, val rows: List<LevelModel>)

data class FeedbackDependencyModel(
    val sourceNode: HGNode,
    val targetNode: HGNode,
    val weight: Int,
    val dependencies: DependencySetModel
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
    val dependencies: List<HGCoreDependency>
)

data class NodeSelection(
    val selectedNodeIds: List<String>,
    val selectedNodesType: NodeType
)

/** `NodePair`: a dependency's ends by id, each standing for its subtree. */
data class NodePair(
    val sourceId: String,
    val targetId: String
)

/** `NodePairDependency`: a pair's ends (`null` when an id names no node) and its aggregated weight. */
data class NodePairDependencyModel(
    val sourceId: String,
    val targetId: String,
    val sourceNode: HGNode?,
    val targetNode: HGNode?,
    val weight: Int
)

enum class NodeType {
    SOURCE, TARGET
}

enum class NodesToConsider {
    SELF, SELF_AND_CHILDREN, SELF_AND_SUCCESSORS
}
