package io.hierograph.hierarchicalgraph.core.algorithms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FastFasSorterTest {

    private lateinit var g: AlgorithmTestGraph

    @BeforeEach
    fun setup() { g = AlgorithmTestGraph() }

    @Test
    fun `sort returns all nodes`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(g.nodes)
        assertThat(result.orderedNodes).hasSize(8)
        assertThat(result.orderedNodes).containsExactlyInAnyOrderElementsOf(g.nodes)
    }

    @Test
    fun `sort of acyclic graph has no upward dependencies`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(listOf(g.n4, g.n5))
        assertThat(result.upwardDependencies).isEmpty()
    }

    @Test
    fun `sort of cyclic graph has upward dependencies`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(listOf(g.n1, g.n2, g.n3))
        assertThat(result.upwardDependencies).isNotEmpty()
    }

    @Test
    fun `sort preserves node identity`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(g.nodes)
        for (node in g.nodes) {
            assertThat(result.orderedNodes).contains(node)
        }
    }
}
