package io.hierograph.hierarchicalgraph.core.algorithms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphUtilsTest {

    private lateinit var g: AlgorithmTestGraph

    @BeforeEach
    fun setup() { g = AlgorithmTestGraph() }

    @Test
    fun `computeAdjacencyMatrix has correct dimensions`() {
        val matrix = GraphUtils.computeAdjacencyMatrix(g.nodes)
        assertThat(matrix.size).isEqualTo(8)
        for (row in matrix) {
            assertThat(row.size).isEqualTo(8)
        }
    }

    @Test
    fun `computeAdjacencyMatrix has correct weights`() {
        val matrix = GraphUtils.computeAdjacencyMatrix(g.nodes)
        // n1 -> n2 (index 1 -> 2)
        assertThat(matrix[1][2]).isEqualTo(1)
        // n2 -> n3 (index 2 -> 3)
        assertThat(matrix[2][3]).isEqualTo(1)
        // n0 has no outgoing
        for (j in g.nodes.indices) {
            assertThat(matrix[0][j]).isEqualTo(0)
        }
    }

    @Test
    fun `computeAdjacencyMatrix diagonal is zero`() {
        val matrix = GraphUtils.computeAdjacencyMatrix(g.nodes)
        for (i in g.nodes.indices) {
            assertThat(matrix[i][i]).isEqualTo(0)
        }
    }

    @Test
    fun `computeAdjacencyList has correct dimensions`() {
        val adjList = GraphUtils.computeAdjacencyList(g.nodes)
        assertThat(adjList.size).isEqualTo(8)
    }

    @Test
    fun `computeAdjacencyList n0 has no neighbors`() {
        val adjList = GraphUtils.computeAdjacencyList(g.nodes)
        assertThat(adjList[0]).isEmpty()
    }

    @Test
    fun `computeAdjacencyList n1 has one neighbor (n2)`() {
        val adjList = GraphUtils.computeAdjacencyList(g.nodes)
        // n1 is index 1, n2 is index 2
        assertThat(adjList[1]).hasSize(1)
        assertThat(adjList[1][0]).isEqualTo(2)
    }

    @Test
    fun `computeAdjacencyList n4 has one neighbor (n5)`() {
        val adjList = GraphUtils.computeAdjacencyList(g.nodes)
        // n4 is index 4, n5 is index 5
        assertThat(adjList[4]).hasSize(1)
        assertThat(adjList[4][0]).isEqualTo(5)
    }
}
