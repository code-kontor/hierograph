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

/**
 * Tarjan's strongly-connected-components algorithm, O(V + E).
 *
 * Stack membership is tracked with an [onStack] flag array (O(1) per lookup) and the working stack is
 * pushed/popped at its tail (O(1)).
 *
 * The traversal is recursive, so its depth is bounded by the input node count. That is fine for the
 * node-set sizes this runs on (the selected DSM nodes, capped well under any stack-overflow risk); it
 * is not intended to run directly over the full type graph.
 */
class Tarjan {

    private var index = 0
    private val stack = ArrayDeque<Int>()
    private val stronglyConnectedComponents = mutableListOf<List<HGNode>>()
    private lateinit var vlowlink: IntArray
    private lateinit var vindex: IntArray
    private lateinit var onStack: BooleanArray
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
        onStack = BooleanArray(graph.size)

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
        stack.addLast(v)
        onStack[v] = true

        for (n in graph[v]) {
            if (vindex[n] == -1) {
                tarjan(n, graph)
                vlowlink[v] = minOf(vlowlink[v], vlowlink[n])
            } else if (onStack[n]) {
                vlowlink[v] = minOf(vlowlink[v], vindex[n])
            }
        }

        if (vlowlink[v] == vindex[v]) {
            val component = mutableListOf<HGNode>()
            while (true) {
                val w = stack.removeLast()
                onStack[w] = false
                component.add(nodes[w])
                if (w == v) break
            }
            stronglyConnectedComponents.add(component)
        }
    }
}
