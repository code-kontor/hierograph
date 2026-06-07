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
package io.hierograph.hierarchicalgraph.core.algorithms.impl

import io.hierograph.hierarchicalgraph.core.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.Hierarchy

class Tarjan {

    private var index = 0
    private val stack = mutableListOf<Int>()
    private val stronglyConnectedComponents = mutableListOf<List<HGNode>>()
    private lateinit var vlowlink: IntArray
    private lateinit var vindex: IntArray
    private lateinit var nodes: List<HGNode>

    fun detectStronglyConnectedComponents(artifacts: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>> {
        nodes = artifacts.toList()
        val adjacencyList = GraphUtils.computeAdjacencyList(artifacts, hierarchy)
        return executeTarjan(adjacencyList)
    }

    private fun executeTarjan(graph: Array<IntArray>): List<List<HGNode>> {
        stronglyConnectedComponents.clear()
        index = 0
        stack.clear()
        vlowlink = IntArray(graph.size) { -1 }
        vindex = IntArray(graph.size) { -1 }

        for (i in graph.indices) {
            if (vindex[i] == -1) {
                tarjan(i, graph)
            }
        }

        return stronglyConnectedComponents
    }

    private fun tarjan(v: Int, graph: Array<IntArray>) {
        vindex[v] = index
        vlowlink[v] = index
        index++
        stack.add(0, v)

        for (n in graph[v]) {
            if (vindex[n] == -1) {
                tarjan(n, graph)
                vlowlink[v] = minOf(vlowlink[v], vlowlink[n])
            } else if (n in stack) {
                vlowlink[v] = minOf(vlowlink[v], vindex[n])
            }
        }

        if (vlowlink[v] == vindex[v]) {
            val component = mutableListOf<HGNode>()
            do {
                val n = stack.removeAt(0)
                component.add(nodes[n])
            } while (component.last() !== nodes[v])
            stronglyConnectedComponents.add(component)
        }
    }
}
