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
package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.algorithms.impl.DependencyStructureMatrixImpl
import io.hierograph.hierarchicalgraph.core.algorithms.impl.FastFasSorter
import io.hierograph.hierarchicalgraph.core.algorithms.impl.Tarjan
import io.hierograph.hierarchicalgraph.core.model.HGNode

object GraphUtils {

    fun detectStronglyConnectedComponents(nodes: Collection<HGNode>): List<List<HGNode>> {
        return Tarjan().detectStronglyConnectedComponents(nodes)
    }

    fun detectCycles(nodes: Collection<HGNode>): List<List<HGNode>> {
        return Tarjan().detectStronglyConnectedComponents(nodes).filter { it.size > 1 }
    }

    fun createDependencyStructureMatrix(nodes: Collection<HGNode>): IDependencyStructureMatrix {
        return DependencyStructureMatrixImpl(nodes)
    }

    fun computeAdjacencyMatrix(nodes: List<HGNode>): Array<IntArray> {
        val n = nodes.size
        return Array(n) { i ->
            IntArray(n) { j ->
                val dep = nodes[i].getOutgoingDependenciesTo(nodes[j])
                dep?.aggregatedWeight ?: 0
            }
        }
    }

    fun computeAdjacencyList(nodes: Collection<HGNode>): Array<IntArray> {
        val nodeList = nodes.toList()
        val indexMap = nodeList.withIndex().associate { (i, node) -> node to i }

        return Array(nodeList.size) { i ->
            val deps = nodeList[i].getOutgoingDependenciesTo(nodeList)
            IntArray(deps.size) { j -> indexMap[deps[j].to]!! }
        }
    }

    fun createFasNodeSorter(): INodeSorter {
        return FastFasSorter()
    }
}
