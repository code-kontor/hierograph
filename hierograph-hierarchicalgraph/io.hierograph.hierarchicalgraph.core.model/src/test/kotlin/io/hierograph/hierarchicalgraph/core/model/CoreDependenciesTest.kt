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

class CoreDependenciesTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `a1 has 2 outgoing core dependencies`() {
        assertThat(g.a1.outgoingCoreDependencies).hasSize(2)
        assertThat(g.a1.outgoingCoreDependencies).containsExactly(g.dep_a1_b1_uses, g.dep_a1_b1_depends_on)
    }

    @Test
    fun `a1 has 0 incoming core dependencies`() {
        assertThat(g.a1.incomingCoreDependencies).isEmpty()
    }

    @Test
    fun `b1 has 2 incoming core dependencies`() {
        assertThat(g.b1.incomingCoreDependencies).hasSize(2)
    }

    @Test
    fun `a2 has 1 outgoing core dependency`() {
        assertThat(g.a2.outgoingCoreDependencies).hasSize(1)
        assertThat(g.a2.outgoingCoreDependencies).containsExactly(g.dep_a2_b2_uses)
    }

    @Test
    fun `a3 has 1 outgoing core dependency`() {
        assertThat(g.a3.outgoingCoreDependencies).hasSize(1)
        assertThat(g.a3.outgoingCoreDependencies).containsExactly(g.dep_a3_b3_depends_on)
    }

    @Test
    fun `dependency from and to are correct`() {
        assertThat(g.dep_a1_b1_uses.from).isSameAs(g.a1)
        assertThat(g.dep_a1_b1_uses.to).isSameAs(g.b1)
        assertThat(g.dep_a1_b1_uses.type).isEqualTo("USES")
    }

    @Test
    fun `dependency weight defaults to 1`() {
        assertThat(g.dep_a1_b1_uses.weight).isEqualTo(1)
    }

    @Test
    fun `dependency attributesBitmap defaults to 0`() {
        assertThat(g.dep_a1_b1_uses.attributesBitmap).isEqualTo(0)
    }

    @Test
    fun `dependency rootNode`() {
        assertThat(g.dep_a1_b1_uses.rootNode).isSameAs(g.root)
    }
}
