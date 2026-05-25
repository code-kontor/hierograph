package io.hierograph.hierarchicalgraph.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccumulatedDependenciesTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `root accumulated outgoing has all 4 deps`() {
        assertThat(g.root.accumulatedOutgoingCoreDependencies).hasSize(4)
    }

    @Test
    fun `a1 accumulated outgoing has 4 deps`() {
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).contains(
            g.dep_a1_b1_uses, g.dep_a1_b1_depends_on, g.dep_a2_b2_uses, g.dep_a3_b3_depends_on
        )
    }

    @Test
    fun `a2 accumulated outgoing has 2 deps`() {
        assertThat(g.a2.accumulatedOutgoingCoreDependencies).hasSize(2)
        assertThat(g.a2.accumulatedOutgoingCoreDependencies).contains(g.dep_a2_b2_uses, g.dep_a3_b3_depends_on)
    }

    @Test
    fun `a3 accumulated outgoing has 1 dep`() {
        assertThat(g.a3.accumulatedOutgoingCoreDependencies).hasSize(1)
    }

    @Test
    fun `b1 accumulated incoming has 4 deps`() {
        assertThat(g.b1.accumulatedIncomingCoreDependencies).hasSize(4)
    }

    @Test
    fun `b2 accumulated incoming has 2 deps`() {
        assertThat(g.b2.accumulatedIncomingCoreDependencies).hasSize(2)
    }

    @Test
    fun `b3 accumulated incoming has 1 dep`() {
        assertThat(g.b3.accumulatedIncomingCoreDependencies).hasSize(1)
    }

    @Test
    fun `leaf node with no deps has empty accumulated`() {
        assertThat(g.b3.accumulatedOutgoingCoreDependencies).isEmpty()
    }

    @Test
    fun `accumulated outgoing includes own deps`() {
        val accumulated = g.a1.accumulatedOutgoingCoreDependencies
        for (dep in g.a1.outgoingCoreDependencies) {
            assertThat(accumulated).contains(dep)
        }
    }

    @Test
    fun `accumulated outgoing includes children deps`() {
        val accumulated = g.a1.accumulatedOutgoingCoreDependencies
        for (dep in g.a2.accumulatedOutgoingCoreDependencies) {
            assertThat(accumulated).contains(dep)
        }
    }
}
