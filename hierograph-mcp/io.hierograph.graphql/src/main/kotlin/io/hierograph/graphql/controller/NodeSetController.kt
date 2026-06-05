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
import io.hierograph.graphql.model.CellModel
import io.hierograph.graphql.model.NodeSetModel
import io.hierograph.graphql.model.NodesToConsider
import io.hierograph.graphql.model.OrderedAdjacencyMatrixModel
import io.hierograph.graphql.model.StronglyConnectedComponentModel
import io.hierograph.hierarchicalgraph.core.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.core.model.HGNode
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

    @SchemaMapping(typeName = "NodeSet")
    fun orderedAdjacencyMatrix(nodeSet: NodeSetModel): OrderedAdjacencyMatrixModel {
        val dsm = GraphUtils.createDependencyStructureMatrix(nodeSet.nodeList)
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
        val sccs = dsm.cycles.map { cycle ->
            val positions = cycle.mapNotNull { nodeIndexMap[it] }.sorted()
            StronglyConnectedComponentModel(cycle, positions)
        }

        return OrderedAdjacencyMatrixModel(orderedNodes, cells, sccs)
    }

    @SchemaMapping(typeName = "NodeSet")
    fun referencedNodes(nodeSet: NodeSetModel, @Argument includePredecessors: Boolean?): NodeSetModel {
        return NodeSetModel(
            NodeController.collectReferencedNodes(nodeSet.nodeList, includePredecessors ?: false)
        )
    }

    @SchemaMapping(typeName = "NodeSet")
    fun referencingNodes(nodeSet: NodeSetModel, @Argument includePredecessors: Boolean?): NodeSetModel {
        return NodeSetModel(
            NodeController.collectReferencingNodes(nodeSet.nodeList, includePredecessors ?: false)
        )
    }

    @SchemaMapping(typeName = "NodeSet")
    fun filterReferencedNodes(
        nodeSet: NodeSetModel,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessorsInResult: Boolean?
    ): NodeSetModel {
        val rootNode = provider.rootNode()
        val targetSet = NodeController.expandNodes(rootNode, nodeIds, nodesToConsider)
        val referenced = mutableSetOf<HGNode>()
        for (node in nodeSet.nodeList) {
            for (dep in node.accumulatedOutgoingCoreDependencies) {
                if (dep.to in targetSet) referenced.add(dep.to)
            }
        }
        return NodeSetModel(
            NodeController.withOptionalPredecessors(referenced, includePredecessorsInResult ?: false)
        )
    }

    @SchemaMapping(typeName = "NodeSet")
    fun filterReferencingNodes(
        nodeSet: NodeSetModel,
        @Argument nodeIds: List<String>,
        @Argument nodesToConsider: NodesToConsider,
        @Argument includePredecessorsInResult: Boolean?
    ): NodeSetModel {
        val rootNode = provider.rootNode()
        val sourceSet = NodeController.expandNodes(rootNode, nodeIds, nodesToConsider)
        val referencing = mutableSetOf<HGNode>()
        for (node in nodeSet.nodeList) {
            for (dep in node.accumulatedIncomingCoreDependencies) {
                if (dep.from in sourceSet) referencing.add(dep.from)
            }
        }
        return NodeSetModel(
            NodeController.withOptionalPredecessors(referencing, includePredecessorsInResult ?: false)
        )
    }
}
