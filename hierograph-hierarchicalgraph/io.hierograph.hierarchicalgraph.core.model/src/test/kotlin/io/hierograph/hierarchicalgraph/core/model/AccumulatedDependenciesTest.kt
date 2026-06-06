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

class AccumulatedDependenciesTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `root accumulated outgoing has all 4 deps`() {
        assertThat(g.hierarchy.accumulatedOutgoing(g.root)).hasSize(4)
    }

    @Test
    fun `a1 accumulated outgoing has 4 deps`() {
        assertThat(g.hierarchy.accumulatedOutgoing(g.a1)).hasSize(4)
        assertThat(g.hierarchy.accumulatedOutgoing(g.a1)).contains(
            g.dep_a1_b1_uses, g.dep_a1_b1_depends_on, g.dep_a2_b2_uses, g.dep_a3_b3_depends_on
        )
    }

    @Test
    fun `a2 accumulated outgoing has 2 deps`() {
        assertThat(g.hierarchy.accumulatedOutgoing(g.a2)).hasSize(2)
        assertThat(g.hierarchy.accumulatedOutgoing(g.a2)).contains(g.dep_a2_b2_uses, g.dep_a3_b3_depends_on)
    }

    @Test
    fun `a3 accumulated outgoing has 1 dep`() {
        assertThat(g.hierarchy.accumulatedOutgoing(g.a3)).hasSize(1)
    }

    @Test
    fun `b1 accumulated incoming has 4 deps`() {
        assertThat(g.hierarchy.accumulatedIncoming(g.b1)).hasSize(4)
    }

    @Test
    fun `b2 accumulated incoming has 2 deps`() {
        assertThat(g.hierarchy.accumulatedIncoming(g.b2)).hasSize(2)
    }

    @Test
    fun `b3 accumulated incoming has 1 dep`() {
        assertThat(g.hierarchy.accumulatedIncoming(g.b3)).hasSize(1)
    }

    @Test
    fun `leaf node with no deps has empty accumulated`() {
        assertThat(g.hierarchy.accumulatedOutgoing(g.b3)).isEmpty()
    }

    @Test
    fun `accumulated outgoing includes own deps`() {
        val accumulated = g.hierarchy.accumulatedOutgoing(g.a1)
        for (dep in g.a1.outgoingCoreDependencies) {
            assertThat(accumulated).contains(dep)
        }
    }

    @Test
    fun `accumulated outgoing includes children deps`() {
        val accumulated = g.hierarchy.accumulatedOutgoing(g.a1)
        for (dep in g.hierarchy.accumulatedOutgoing(g.a2)) {
            assertThat(accumulated).contains(dep)
        }
    }
}
