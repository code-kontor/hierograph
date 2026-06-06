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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DependencyStructureMatrixTest {

    private lateinit var g: AlgorithmTestGraph

    @BeforeEach
    fun setup() { g = AlgorithmTestGraph() }

    @Test
    fun `DSM contains all nodes`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        assertThat(dsm.orderedNodes).hasSize(8)
        assertThat(dsm.orderedNodes).containsExactlyInAnyOrderElementsOf(g.nodes)
    }

    @Test
    fun `DSM detects 2 cycles`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        assertThat(dsm.cycles).hasSize(2)
    }

    @Test
    fun `DSM cycle contains n1, n2, n3`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val cycle3 = dsm.cycles.find { it.size == 3 }
        assertThat(cycle3).isNotNull
        assertThat(cycle3).containsExactlyInAnyOrder(g.n1, g.n2, g.n3)
    }

    @Test
    fun `DSM cycle contains n6, n7`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val cycle2 = dsm.cycles.find { it.size == 2 }
        assertThat(cycle2).isNotNull
        assertThat(cycle2).containsExactlyInAnyOrder(g.n6, g.n7)
    }

    @Test
    fun `isCellInCycle for nodes in same cycle`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val ordered = dsm.orderedNodes
        val i1 = ordered.indexOf(g.n1)
        val i2 = ordered.indexOf(g.n2)
        assertThat(dsm.isCellInCycle(i1, i2)).isTrue()
    }

    @Test
    fun `isCellInCycle for nodes in different cycles`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val ordered = dsm.orderedNodes
        val i1 = ordered.indexOf(g.n1)
        val i6 = ordered.indexOf(g.n6)
        assertThat(dsm.isCellInCycle(i1, i6)).isFalse()
    }

    @Test
    fun `isRowInCycle for cyclic node`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val i6 = dsm.orderedNodes.indexOf(g.n6)
        assertThat(dsm.isRowInCycle(i6)).isTrue()
    }

    @Test
    fun `isRowInCycle for acyclic node`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val i0 = dsm.orderedNodes.indexOf(g.n0)
        assertThat(dsm.isRowInCycle(i0)).isFalse()
    }

    @Test
    fun `getWeight returns correct weight`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val ordered = dsm.orderedNodes
        val i4 = ordered.indexOf(g.n4)
        val i5 = ordered.indexOf(g.n5)
        assertThat(dsm.getWeight(i4, i5)).isEqualTo(1)
        assertThat(dsm.getWeight(i5, i4)).isEqualTo(0)
    }

    @Test
    fun `getWeight returns -1 for out of bounds`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        assertThat(dsm.getWeight(-1, 0)).isEqualTo(-1)
        assertThat(dsm.getWeight(0, 100)).isEqualTo(-1)
    }

    @Test
    fun `isCellInCycle returns false for out of bounds`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        assertThat(dsm.isCellInCycle(-1, 0)).isFalse()
    }

    @Test
    fun `getMatrix has correct dimensions`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val matrix = dsm.getMatrix()
        assertThat(matrix.size).isEqualTo(8)
        for (row in matrix) {
            assertThat(row.size).isEqualTo(8)
        }
    }

    @Test
    fun `getMatrix is consistent with getWeight`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        val matrix = dsm.getMatrix()
        for (i in matrix.indices) {
            for (j in matrix.indices) {
                assertThat(matrix[i][j]).isEqualTo(dsm.getWeight(i, j))
            }
        }
    }

    @Test
    fun `DSM has upward dependencies`() {
        val dsm = GraphUtils.createDependencyStructureMatrix(g.nodes, g.hierarchy)
        assertThat(dsm.upwardDependencies).isNotEmpty()
    }
}
