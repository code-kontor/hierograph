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
        assertThat(g.hierarchy.parentOf(g.root)).isNull()
    }

    @Test
    fun `bidirectional parent-children`() {
        for (child in g.hierarchy.childrenOf(g.root)) {
            assertThat(g.hierarchy.parentOf(child)).isSameAs(g.root)
        }
        assertThat(g.hierarchy.parentOf(g.a2)).isSameAs(g.a1)
        assertThat(g.hierarchy.childrenOf(g.a1)).contains(g.a2)
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
    fun `root node is hierarchy root`() {
        assertThat(g.hierarchy.rootNode).isSameAs(g.root)
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
