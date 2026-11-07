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

class HierarchyCacheTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `predecessors are recomputed after move`() {
        assertThat(g.hierarchy.predecessorsOf(g.a3)).containsExactly(g.a2, g.a1, g.root)

        g.hierarchy.move(g.a3, g.a1)

        assertThat(g.hierarchy.predecessorsOf(g.a3)).containsExactly(g.a1, g.root)
    }

    @Test
    fun `accumulated deps recomputed after move`() {
        assertThat(g.hierarchy.accumulatedOutgoing(g.a2)).hasSize(2)

        // move a3 from a2 to a1 -- a2 loses a3's dependency
        g.hierarchy.move(g.a3, g.a1)

        assertThat(g.hierarchy.accumulatedOutgoing(g.a2)).hasSize(1)
        assertThat(g.hierarchy.accumulatedOutgoing(g.a1)).hasSize(4)
    }

    @Test
    fun `aggregated deps recomputed after move`() {
        val before = g.hierarchy.getAggregatedDependency(g.a2, g.b2)
        assertThat(before).isNotNull
        assertThat(before!!.coreDependencies).hasSize(2)

        // move a3 from a2 to a1 -- a2->b2 aggregated loses a3->b3 dep
        g.hierarchy.move(g.a3, g.a1)

        val after = g.hierarchy.getAggregatedDependency(g.a2, g.b2)
        assertThat(after).isNotNull
        assertThat(after!!.coreDependencies).hasSize(1)
    }

    @Test
    fun `aggregated weight reflects changed dependency weight after move`() {
        assertThat(g.hierarchy.getAggregatedDependency(g.a1, g.b1)!!.aggregatedWeight).isEqualTo(4)

        g.dep_a1_b1_uses.weight = 10

        // move triggers cache clearing so the new weight is picked up
        g.hierarchy.move(g.a3, g.a3.let { g.hierarchy.parentOf(it)!! })

        assertThat(g.hierarchy.getAggregatedDependency(g.a1, g.b1)!!.aggregatedWeight).isEqualTo(13)
    }

    @Test
    fun `addChild triggers cache invalidation`() {
        assertThat(g.hierarchy.accumulatedOutgoing(g.b1)).isEmpty()

        val newNode = g.hierarchy.createLocalNode(null) { DefaultNodeSource(identifier = 999L) }
        g.hierarchy.addChild(g.b1, newNode)

        // new node has no deps, but the addChild should have cleared caches
        // verify the hierarchy sees the new child
        assertThat(g.hierarchy.childrenOf(g.b1)).contains(newNode)
    }

    @Test
    fun `forked hierarchy has independent caches`() {
        // populate cache on original
        assertThat(g.hierarchy.predecessorsOf(g.a3)).containsExactly(g.a2, g.a1, g.root)

        val forked = g.hierarchy.fork()

        // move in forked hierarchy
        forked.move(g.a3, g.a1)

        // forked hierarchy has updated predecessors
        assertThat(forked.predecessorsOf(g.a3)).containsExactly(g.a1, g.root)

        // original hierarchy is unchanged
        assertThat(g.hierarchy.predecessorsOf(g.a3)).containsExactly(g.a2, g.a1, g.root)
    }
}
