package io.hierograph.hierarchicalgraph.core.algorithms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TarjanTest {

    private lateinit var g: AlgorithmTestGraph

    @BeforeEach
    fun setup() { g = AlgorithmTestGraph() }

    @Test
    fun `detectStronglyConnectedComponents returns all SCCs including singletons`() {
        val sccs = GraphUtils.detectStronglyConnectedComponents(g.nodes)
        // 8 nodes: 2 cycles + 4 singletons (n0, n4, n5, and one of the cycle groupings)
        // Actually: {n1,n2,n3}, {n6,n7}, {n0}, {n4}, {n5} = 5 SCCs
        assertThat(sccs).hasSize(5)
    }

    @Test
    fun `detectCycles returns only multi-node SCCs`() {
        val cycles = GraphUtils.detectCycles(g.nodes)
        assertThat(cycles).hasSize(2)
    }

    @Test
    fun `cycle 1 contains n1, n2, n3`() {
        val cycles = GraphUtils.detectCycles(g.nodes)
        val cycle1 = cycles.find { it.size == 3 }
        assertThat(cycle1).isNotNull
        assertThat(cycle1).containsExactlyInAnyOrder(g.n1, g.n2, g.n3)
    }

    @Test
    fun `cycle 2 contains n6, n7`() {
        val cycles = GraphUtils.detectCycles(g.nodes)
        val cycle2 = cycles.find { it.size == 2 }
        assertThat(cycle2).isNotNull
        assertThat(cycle2).containsExactlyInAnyOrder(g.n6, g.n7)
    }

    @Test
    fun `no cycles in acyclic subgraph`() {
        val acyclicNodes = listOf(g.n0, g.n4, g.n5)
        val cycles = GraphUtils.detectCycles(acyclicNodes)
        assertThat(cycles).isEmpty()
    }

    @Test
    fun `all SCCs for acyclic graph are singletons`() {
        val acyclicNodes = listOf(g.n0, g.n4, g.n5)
        val sccs = GraphUtils.detectStronglyConnectedComponents(acyclicNodes)
        assertThat(sccs).hasSize(3)
        assertThat(sccs).allMatch { it.size == 1 }
    }
}
