package io.hierograph.hierarchicalgraph.core.algorithms.impl

import io.hierograph.hierarchicalgraph.core.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.core.model.HGNode

class Tarjan {

    private var index = 0
    private val stack = mutableListOf<Int>()
    private val stronglyConnectedComponents = mutableListOf<List<HGNode>>()
    private lateinit var vlowlink: IntArray
    private lateinit var vindex: IntArray
    private lateinit var nodes: List<HGNode>

    fun detectStronglyConnectedComponents(artifacts: Collection<HGNode>): List<List<HGNode>> {
        nodes = artifacts.toList()
        val adjacencyList = GraphUtils.computeAdjacencyList(artifacts)
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
