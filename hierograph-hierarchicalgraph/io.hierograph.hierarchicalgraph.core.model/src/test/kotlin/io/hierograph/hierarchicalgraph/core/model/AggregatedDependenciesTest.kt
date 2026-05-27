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

class AggregatedDependenciesTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `a1 to b1 aggregated has 4 core deps`() {
        val agg = g.a1.getOutgoingDependenciesTo(g.b1)
        assertThat(agg).isNotNull
        assertThat(agg!!.coreDependencies).hasSize(4)
        assertThat(agg.aggregatedWeight).isEqualTo(4)
    }

    @Test
    fun `a2 to b2 aggregated has 2 core deps`() {
        val agg = g.a2.getOutgoingDependenciesTo(g.b2)
        assertThat(agg).isNotNull
        assertThat(agg!!.coreDependencies).hasSize(2)
        assertThat(agg.aggregatedWeight).isEqualTo(2)
    }

    @Test
    fun `a1 to b2 aggregated has 2 core deps`() {
        val agg = g.a1.getOutgoingDependenciesTo(g.b2)
        assertThat(agg).isNotNull
        assertThat(agg!!.coreDependencies).hasSize(2)
        assertThat(agg.aggregatedWeight).isEqualTo(2)
    }

    @Test
    fun `a3 to b3 aggregated has 1 core dep`() {
        val agg = g.a3.getOutgoingDependenciesTo(g.b3)
        assertThat(agg).isNotNull
        assertThat(agg!!.coreDependencies).hasSize(1)
        assertThat(agg.aggregatedWeight).isEqualTo(1)
    }

    @Test
    fun `no deps returns null`() {
        val agg = g.b1.getOutgoingDependenciesTo(g.a1)
        assertThat(agg).isNull()
    }

    @Test
    fun `aggregated dep symmetry - outgoing and incoming return same object`() {
        val outgoing = g.a1.getOutgoingDependenciesTo(g.b1)
        val incoming = g.b1.getIncomingDependenciesFrom(g.a1)
        assertThat(outgoing).isSameAs(incoming)
    }

    @Test
    fun `incoming then outgoing returns same object`() {
        val incoming = g.b2.getIncomingDependenciesFrom(g.a2)
        val outgoing = g.a2.getOutgoingDependenciesTo(g.b2)
        assertThat(incoming).isSameAs(outgoing)
    }

    @Test
    fun `batch getOutgoingDependenciesTo`() {
        val results = g.a1.getOutgoingDependenciesTo(listOf(g.b1, g.b2, g.a1))
        assertThat(results).hasSize(2)
    }

    @Test
    fun `batch getIncomingDependenciesFrom`() {
        val results = g.b1.getIncomingDependenciesFrom(listOf(g.a1, g.a2))
        assertThat(results).hasSize(2)
    }

    @Test
    fun `aggregated dep from and to are correct`() {
        val agg = g.a1.getOutgoingDependenciesTo(g.b1)!!
        assertThat(agg.from).isSameAs(g.a1)
        assertThat(agg.to).isSameAs(g.b1)
    }
}
