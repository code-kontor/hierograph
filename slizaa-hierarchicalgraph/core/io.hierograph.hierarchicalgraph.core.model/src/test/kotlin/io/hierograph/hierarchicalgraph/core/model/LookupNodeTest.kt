package io.hierograph.hierarchicalgraph.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LookupNodeTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `lookupNode returns correct node`() {
        val found = g.root.lookupNode(g.a1.identifier)
        assertThat(found).isSameAs(g.a1)
    }

    @Test
    fun `lookupNode returns correct leaf node`() {
        val found = g.root.lookupNode(g.b3.identifier)
        assertThat(found).isSameAs(g.b3)
    }

    @Test
    fun `lookupNode returns null for unknown identifier`() {
        val found = g.root.lookupNode(-1L)
        assertThat(found).isNull()
    }
}
