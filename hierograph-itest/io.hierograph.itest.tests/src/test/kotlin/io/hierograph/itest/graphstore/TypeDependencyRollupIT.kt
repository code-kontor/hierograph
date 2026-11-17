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
package io.hierograph.itest.graphstore

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.TypeDependency
import io.hierograph.graphstore.jvm.SnapshotLoader
import io.hierograph.itest.fwk.SpringFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Type-level dependency roll-up over the real Spring snapshot: refs aggregate into weighted, flagged
 * edges (`extends` / `implements` / `annotatedBy` / `other`). Unit tests assert this on synthetic pairs;
 * here we confirm every flavour occurs and that aggregation actually sums multiple refs on real data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypeDependencyRollupIT {

    private lateinit var deps: List<TypeDependency>

    @BeforeAll
    fun load() {
        val store = GraphStore().also { SnapshotLoader().load(SpringFixture.directory, it) }
        deps = store.typeDependencies().toList()
    }

    @Test
    fun `all four structural flavours of type dependency occur`() {
        assertThat(deps).anyMatch { it.extends }
        assertThat(deps).anyMatch { it.implements }
        assertThat(deps).anyMatch { it.annotatedBy }
        assertThat(deps).anyMatch { it.other }
    }

    @Test
    fun `aggregation sums multiple references into a single weighted edge`() {
        // A busy source references some target many times (e.g. repeated calls/field reads) -> weight > 1.
        assertThat(deps).anyMatch { it.weight > 1 }
        assertThat(deps).allSatisfy { assertThat(it.weight).isGreaterThanOrEqualTo(1) }
    }

    @Test
    fun `at most one aggregated edge per (from, to) pair`() {
        val dup = deps.groupingBy { it.fromFqn to it.toFqn }.eachCount().filterValues { it > 1 }
        assertThat(dup).describedAs("duplicate (from,to) edges").isEmpty()
    }

    @Test
    fun `a known subtype carries the extends edge to its superclass`() {
        // ClassPathResource extends AbstractFileResolvingResource.
        val from = "org.springframework.core.io.ClassPathResource"
        val extendsEdges = deps.filter { it.fromFqn == from && it.extends }
        assertThat(extendsEdges).describedAs("extends edges from $from").isNotEmpty
        assertThat(extendsEdges).anyMatch { it.toFqn == "org.springframework.core.io.AbstractFileResolvingResource" }
    }
}
