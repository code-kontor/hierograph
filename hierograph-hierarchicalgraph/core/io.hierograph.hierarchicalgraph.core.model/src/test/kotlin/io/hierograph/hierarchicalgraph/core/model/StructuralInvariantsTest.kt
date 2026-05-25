package io.hierograph.hierarchicalgraph.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StructuralInvariantsTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `root parent is null`() {
        assertThat(g.root.parent).isNull()
    }

    @Test
    fun `bidirectional parent-children`() {
        for (child in g.root.children) {
            assertThat(child.parent).isSameAs(g.root)
        }
        assertThat(g.a2.parent).isSameAs(g.a1)
        assertThat(g.a1.children).contains(g.a2)
    }

    @Test
    fun `bidirectional nodeSource`() {
        assertThat(g.a1.nodeSource.node).isSameAs(g.a1)
        assertThat(g.root.nodeSource.node).isSameAs(g.root)
    }

    @Test
    fun `bidirectional dependencySource`() {
        assertThat(g.dep_a1_b1_uses.dependencySource.dependency).isSameAs(g.dep_a1_b1_uses)
    }

    @Test
    fun `dependency list membership`() {
        assertThat(g.dep_a1_b1_uses.from.outgoingCoreDependencies).contains(g.dep_a1_b1_uses)
        assertThat(g.dep_a1_b1_uses.to.incomingCoreDependencies).contains(g.dep_a1_b1_uses)
    }

    @Test
    fun `rootNode derivation`() {
        assertThat(g.a3.rootNode).isSameAs(g.root)
        assertThat(g.b3.rootNode).isSameAs(g.root)
        assertThat(g.root.rootNode).isSameAs(g.root)
    }

    @Test
    fun `identifier derivation`() {
        assertThat(g.a1.identifier).isEqualTo(g.a1.nodeSource.identifier)
    }

    @Test
    fun `getNodeSource with correct type`() {
        val source = g.a1.getNodeSource(DefaultNodeSource::class.java)
        assertThat(source).isNotNull
        assertThat(source).isSameAs(g.a1.nodeSource)
    }

    @Test
    fun `getNodeSource with wrong type returns null`() {
        val source = g.a1.getNodeSource(String::class.java)
        assertThat(source).isNull()
    }

    @Test
    fun `getDependencySource with correct type`() {
        val source = g.dep_a1_b1_uses.getDependencySource(DefaultDependencySource::class.java)
        assertThat(source).isNotNull
    }

    @Test
    fun `getDependencySource with wrong type returns null`() {
        val source = g.dep_a1_b1_uses.getDependencySource(String::class.java)
        assertThat(source).isNull()
    }
}
