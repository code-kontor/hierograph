package io.hierograph.hierarchicalgraph.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CacheInvalidationTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `adding a dependency updates accumulated deps after cache invalidation`() {
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)

        val newDep = HierarchicalGraphFactory.createCoreDependency(
            g.a1, g.b2, "NEW_TYPE",
            { DefaultDependencySource(identifier = 999L) }
        )

        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(5)
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).contains(newDep)
    }

    @Test
    fun `removing a dependency updates accumulated deps after cache invalidation`() {
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)

        HierarchicalGraphFactory.removeDependency(g.dep_a1_b1_uses)

        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(3)
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).doesNotContain(g.dep_a1_b1_uses)
    }

    @Test
    fun `invalidateAllCaches recomputes everything`() {
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)
        val agg = g.a1.getOutgoingDependenciesTo(g.b1)
        assertThat(agg).isNotNull

        g.root.invalidateAllCaches()

        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)
        assertThat(g.a1.getOutgoingDependenciesTo(g.b1)!!.aggregatedWeight).isEqualTo(4)
    }

    @Test
    fun `aggregated deps update after adding dependency and invalidation`() {
        val agg1 = g.a1.getOutgoingDependenciesTo(g.b1)
        assertThat(agg1!!.aggregatedWeight).isEqualTo(4)

        HierarchicalGraphFactory.createCoreDependency(
            g.a2, g.b1, "EXTRA",
            { DefaultDependencySource(identifier = 888L) }
        )

        val agg2 = g.a1.getOutgoingDependenciesTo(g.b1)
        assertThat(agg2!!.aggregatedWeight).isEqualTo(5)
    }
}
