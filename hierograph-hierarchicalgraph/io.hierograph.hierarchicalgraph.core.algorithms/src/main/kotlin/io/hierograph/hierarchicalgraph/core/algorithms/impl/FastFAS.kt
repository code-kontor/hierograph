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

/**
 * Eades–Lin–Smyth greedy feedback-arc-set ordering over a weighted adjacency matrix.
 *
 * Produces the same ordering and skipped-edge set as the straightforward formulation (repeatedly: emit
 * a sink, else a source, else the vertex of maximum weighted `out − in` degree, lowest index breaking
 * ties), but in O(n²) rather than O(n³): degrees are computed once and then maintained incrementally as
 * vertices are removed, instead of being recomputed by scanning the matrix on every step. O(n²) is the
 * floor for a dense-matrix input anyway, since the matrix itself is O(n²).
 *
 * Weighted degree drives the max-delta choice (so the heaviest dependencies are least likely to be cut),
 * while plain edge presence drives sink/source detection — matching the previous behavior exactly.
 */
class FastFAS(private val adjacencyMatrix: Array<IntArray>) {

    private val skippedEdges = mutableListOf<IntArray>()

    fun getOrderedSequence(): IntArray {
        skippedEdges.clear()
        val n = adjacencyMatrix.size

        val removed = BooleanArray(n)
        var remaining = n

        // Weighted in/out degree (drives delta) and unweighted in/out counts (drive sink/source),
        // each excluding the diagonal, maintained incrementally as vertices are removed.
        val weightedOut = IntArray(n)
        val weightedIn = IntArray(n)
        val countOut = IntArray(n)
        val countIn = IntArray(n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i == j) continue
                val w = adjacencyMatrix[i][j]
                if (w != 0) {
                    weightedOut[i] += w
                    countOut[i]++
                    weightedIn[j] += w
                    countIn[j]++
                }
            }
        }

        fun remove(u: Int) {
            removed[u] = true
            remaining--
            for (j in 0 until n) {
                if (removed[j] || j == u) continue
                val outEdge = adjacencyMatrix[u][j] // u -> j : j loses an incoming edge
                if (outEdge != 0) {
                    weightedIn[j] -= outEdge
                    countIn[j]--
                }
                val inEdge = adjacencyMatrix[j][u] // j -> u : j loses an outgoing edge
                if (inEdge != 0) {
                    weightedOut[j] -= inEdge
                    countOut[j]--
                }
            }
        }

        val s1 = mutableListOf<Int>()
        val s2 = mutableListOf<Int>()

        while (remaining > 0) {
            // Sink (no outgoing edges) — lowest index first; prepend to s2.
            val sink = (0 until n).firstOrNull { !removed[it] && countOut[it] == 0 }
            if (sink != null) {
                remove(sink)
                s2.add(0, sink)
                continue
            }

            // Source (no incoming edges) — lowest index first; append to s1.
            val source = (0 until n).firstOrNull { !removed[it] && countIn[it] == 0 }
            if (source != null) {
                remove(source)
                s1.add(source)
                continue
            }

            // Otherwise remove the vertex of maximum weighted delta (lowest index breaks ties).
            var maxDelta = Int.MIN_VALUE
            var maxVertex = -1
            for (i in 0 until n) {
                if (removed[i]) continue
                val delta = weightedOut[i] - weightedIn[i]
                if (maxVertex == -1 || delta > maxDelta) {
                    maxDelta = delta
                    maxVertex = i
                }
            }

            // Incoming edges to the removed vertex from still-present vertices become upward edges.
            for (j in 0 until n) {
                if (!removed[j] && j != maxVertex && adjacencyMatrix[j][maxVertex] != 0) {
                    skippedEdges.add(intArrayOf(j, maxVertex))
                }
            }

            remove(maxVertex)
            s1.add(maxVertex)
        }

        return IntArray(s1.size + s2.size).also { result ->
            var idx = 0
            for (v in s1) result[idx++] = v
            for (v in s2) result[idx++] = v
        }
    }

    fun getSkippedEdges(): List<IntArray> = skippedEdges

    companion object {
        fun reverse(sequence: IntArray): IntArray {
            return IntArray(sequence.size) { i -> sequence[sequence.size - 1 - i] }
        }
    }
}
