/*
 * Copyright 2024 Gerd Wuetherich
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

import io.hierograph.hierarchicalgraph.core.model.internal.HGCoreDependencyImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HGCacheInvalidatorTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `accumulated outgoing reflects new dependency after invalidation`() {
        assertThat(g.a3.accumulatedOutgoingCoreDependencies).hasSize(1)
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)

        val depSource = DefaultDependencySource(identifier = "extra-dep")
        HierarchicalGraphFactory.createCoreDependency(g.a3, g.b3, "USES") { depSource }

        // stale cache still returns previous size
        assertThat(g.a3.accumulatedOutgoingCoreDependencies).hasSize(1)
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)

        HGCacheInvalidator.invalidate(g.root)

        assertThat(g.a3.accumulatedOutgoingCoreDependencies).hasSize(2)
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(5)
    }

    @Test
    fun `aggregated dependency cache is cleared so a new instance is created`() {
        val before = g.a1.getOutgoingDependenciesTo(g.b1)
        HGCacheInvalidator.invalidate(g.root)
        val after = g.a1.getOutgoingDependenciesTo(g.b1)

        assertThat(after).isNotSameAs(before)
        assertThat(after!!.coreDependencies).hasSize(4)
    }

    @Test
    fun `freshly-fetched aggregated dependency reflects new weight after invalidation`() {
        assertThat(g.a1.getOutgoingDependenciesTo(g.b1)!!.aggregatedWeight).isEqualTo(4)

        (g.dep_a1_b1_uses as HGCoreDependencyImpl).weight = 10

        HGCacheInvalidator.invalidate(g.root)

        assertThat(g.a1.getOutgoingDependenciesTo(g.b1)!!.aggregatedWeight).isEqualTo(13)
    }

    @Test
    fun `predecessors are recomputed after re-parenting`() {
        assertThat(g.a3.predecessors).containsExactly(g.a2, g.a1, g.root)

        HierarchicalGraphFactory.setParent(g.a3, g.a1)

        // stale cache
        assertThat(g.a3.predecessors).containsExactly(g.a2, g.a1, g.root)

        HGCacheInvalidator.invalidate(g.root)

        assertThat(g.a3.predecessors).containsExactly(g.a1, g.root)
    }

    @Test
    fun `invalidating a subtree only clears that subtree`() {
        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(4)
        assertThat(g.b1.accumulatedIncomingCoreDependencies).hasSize(4)

        val depSource = DefaultDependencySource(identifier = "sub-dep")
        HierarchicalGraphFactory.createCoreDependency(g.a3, g.b3, "USES") { depSource }

        HGCacheInvalidator.invalidate(g.a1)

        assertThat(g.a1.accumulatedOutgoingCoreDependencies).hasSize(5)
        // b-subtree cache was NOT cleared
        assertThat(g.b1.accumulatedIncomingCoreDependencies).hasSize(4)
    }
}
