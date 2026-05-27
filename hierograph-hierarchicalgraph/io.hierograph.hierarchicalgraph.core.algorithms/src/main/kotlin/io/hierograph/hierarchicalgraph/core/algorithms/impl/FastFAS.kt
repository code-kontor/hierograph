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

class FastFAS(private val adjacencyMatrix: Array<IntArray>) {

    private val vertices = mutableSetOf<Int>()
    private val s1 = mutableListOf<Int>()
    private val s2 = mutableListOf<Int>()
    private val skippedEdges = mutableListOf<IntArray>()

    fun getOrderedSequence(): IntArray {
        skippedEdges.clear()

        vertices.clear()
        for (i in adjacencyMatrix.indices) {
            vertices.add(i)
        }

        s1.clear()
        s2.clear()

        while (vertices.isNotEmpty()) {
            if (findSink()) continue
            if (findSource()) continue
            findVertexToRemove()
        }

        return IntArray(s1.size + s2.size).also { result ->
            var idx = 0
            for (v in s1) result[idx++] = v
            for (v in s2) result[idx++] = v
        }
    }

    fun getSkippedEdges(): List<IntArray> = skippedEdges

    private fun findSink(): Boolean {
        for (i in vertices) {
            var isSink = true
            for (j in vertices) {
                if (i != j && adjacencyMatrix[i][j] != 0) {
                    isSink = false
                    break
                }
            }
            if (isSink) {
                vertices.remove(i)
                s2.add(0, i)
                return true
            }
        }
        return false
    }

    private fun findSource(): Boolean {
        for (i in vertices) {
            var isSource = true
            for (j in vertices) {
                if (i != j && adjacencyMatrix[j][i] != 0) {
                    isSource = false
                    break
                }
            }
            if (isSource) {
                vertices.remove(i)
                s1.add(i)
                return true
            }
        }
        return false
    }

    private fun findVertexToRemove(): Boolean {
        var maxDelta = Int.MIN_VALUE
        var maxVertex = Int.MIN_VALUE

        for (vertex in vertices) {
            val delta = getDelta(vertex)
            if (maxVertex == Int.MIN_VALUE || maxDelta < delta) {
                maxDelta = delta
                maxVertex = vertex
            }
        }

        vertices.remove(maxVertex)

        for (j in vertices) {
            if (maxVertex != j && adjacencyMatrix[j][maxVertex] != 0) {
                skippedEdges.add(intArrayOf(j, maxVertex))
            }
        }

        s1.add(maxVertex)
        return false
    }

    private fun getDelta(vertex: Int): Int {
        var inDeg = 0
        var outDeg = 0
        for (j in vertices) {
            if (vertex != j) {
                inDeg += adjacencyMatrix[j][vertex]
                outDeg += adjacencyMatrix[vertex][j]
            }
        }
        return outDeg - inDeg
    }

    companion object {
        fun reverse(sequence: IntArray): IntArray {
            return IntArray(sequence.size) { i -> sequence[sequence.size - 1 - i] }
        }
    }
}
