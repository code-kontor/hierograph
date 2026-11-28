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
import io.hierograph.server.graphql.model.CellModel
import io.hierograph.server.graphql.model.DependencySetModel
import io.hierograph.server.graphql.model.FeedbackDependencyModel
import io.hierograph.server.graphql.model.LevelModel
import io.hierograph.server.graphql.model.LevelizationModel
import io.hierograph.server.graphql.model.NodeSetModel
import io.hierograph.server.graphql.model.NodesToConsider
import io.hierograph.server.graphql.model.OrderedAdjacencyMatrixModel
import io.hierograph.server.graphql.model.StronglyConnectedComponentModel
import io.hierograph.server.graphql.model.TangleModel
import io.hierograph.hierarchicalgraph.algorithms.GraphAlgorithms
import io.hierograph.hierarchicalgraph.model.HGNode
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class NodeSetController(private val provider: HierarchicalGraphProvider) {

    @SchemaMapping(typeName = "NodeSet")
    fun nodes(nodeSet: NodeSetModel): List<HGNode> = nodeSet.nodeList

    @SchemaMapping(typeName = "NodeSet")
    fun nodeIds(nodeSet: NodeSetModel): List<String> =
        nodeSet.nodeList.map { it.identifier.toString() }

    /**
     * The dependencies into the members' subtrees from outside all of them: the set as one group (#85). A
     * dependency between two members is inside the group and left out; with nested members, the outer one's
     * subtree holds the inner one's. In member order, each dependency once.
     */
    @SchemaMapping(typeName = "NodeSet")
    fun incomingDependencies(nodeSet: NodeSetModel): DependencySetModel {
        val hierarchy = provider.hierarchy()
        val group = nodeSet.nodeList.map { it.identifier }.toSet<Any>()
        return DependencySetModel(
            nodeSet.nodeList.distinct()
                .flatMap { hierarchy.accumulatedIncoming(it) }
                .filter { !NodeController.isWithin(hierarchy, it.from, group) }
                .distinct(),
        )
    }

    /** The dependencies from the members' subtrees to outside all of them; the mirror image of [incomingDependencies]. */
    @SchemaMapping(typeName = "NodeSet")
    fun outgoingDependencies(nodeSet: NodeSetModel): DependencySetModel {
        val hierarchy = provider.hierarchy()
        val group = nodeSet.nodeList.map { it.identifier }.toSet<Any>()
        return DependencySetModel(
            nodeSet.nodeList.distinct()
                .flatMap { hierarchy.accumulatedOutgoing(it) }
                .filter { !NodeController.isWithin(hierarchy, it.to, group) }
                .distinct(),
        )
    }

    @SchemaMapping(typeName = "NodeSet")
    fun orderedAdjacencyMatrix(nodeSet: NodeSetModel): OrderedAdjacencyMatrixModel {
        val dsm = GraphAlgorithms.createDependencyStructureMatrix(nodeSet.nodeList, provider.hierarchy())
        val orderedNodes = dsm.orderedNodes
        val matrix = dsm.getMatrix()

        val cells = mutableListOf<CellModel>()
        for (row in matrix.indices) {
            for (col in matrix[row].indices) {
                if (matrix[row][col] != 0) {
                    cells.add(CellModel(row, col, matrix[row][col]))
                }
            }
        }

        val nodeIndexMap = orderedNodes.withIndex().associate { (i, n) -> n to i }
        // Members in DSM order, so nodes[k] sits at nodePositions[k].
        val sccs = dsm.cycles.map { cycle ->
            val members = cycle.filter { it in nodeIndexMap }.sortedBy { nodeIndexMap.getValue(it) }
            StronglyConnectedComponentModel(members, members.map { nodeIndexMap.getValue(it) })
        }

        return OrderedAdjacencyMatrixModel(orderedNodes, cells, sccs)
    }

    @SchemaMapping(typeName = "NodeSet")
    fun levelization(nodeSet: NodeSetModel): LevelizationModel {
        val levelization = GraphAlgorithms.levelize(nodeSet.nodeList, provider.hierarchy())
        fun rows(rows: List<List<HGNode>>) = rows.mapIndexed { index, nodes -> LevelModel(index, nodes) }
        return LevelizationModel(
            levels = rows(levelization.levels),
            tangles = levelization.tangles.map { TangleModel(it.level, it.members, rows(it.rows)) },
            feedbackDependencies = levelization.feedbackDependencies.map {
                FeedbackDependencyModel(it.from, it.to, it.aggregatedWeight, DependencySetModel(it.coreDependencies))
            },
        )
    }

    @SchemaMapping(typeName = "NodeSet")
    fun referencedNodes(nodeSet: NodeSetModel, @Argument includePredecessors: Boolean): NodeSetModel {
        return NodeSetModel(
            NodeController.collectReferencedNodes(provider.hierarchy(), nodeSet.nodeList, includePredecessors)
        )
    }

    @SchemaMapping(typeName = "NodeSet")
    fun referencingNodes(nodeSet: NodeSetModel, @Argument includePredecessors: Boolean): NodeSetModel {
        return NodeSetModel(
            NodeController.collectReferencingNodes(provider.hierarchy(), nodeSet.nodeList, includePredecessors)
        )
    }

    @SchemaMapping(typeName = "NodeSet")
    fun filterReferencedNodes(
        nodeSet: NodeSetModel,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessors: Boolean
    ): NodeSetModel {
        val hierarchy = provider.hierarchy()
        val targetSet = NodeController.expandNodes(hierarchy, nodeIds, nodesToConsider)
        val referenced = NodeController.collectReferencedNodes(hierarchy, nodeSet.nodeList, false)
            .filterTo(LinkedHashSet()) { it in targetSet }
        return NodeSetModel(NodeController.withOptionalPredecessors(hierarchy, referenced, includePredecessors))
    }

    @SchemaMapping(typeName = "NodeSet")
    fun filterReferencingNodes(
        nodeSet: NodeSetModel,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessors: Boolean
    ): NodeSetModel {
        val hierarchy = provider.hierarchy()
        val sourceSet = NodeController.expandNodes(hierarchy, nodeIds, nodesToConsider)
        val referencing = NodeController.collectReferencingNodes(hierarchy, nodeSet.nodeList, false)
            .filterTo(LinkedHashSet()) { it in sourceSet }
        return NodeSetModel(NodeController.withOptionalPredecessors(hierarchy, referencing, includePredecessors))
    }
}
