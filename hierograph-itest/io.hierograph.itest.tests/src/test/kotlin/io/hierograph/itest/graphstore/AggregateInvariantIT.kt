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
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.model.JavaTypeNames
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.graphstore.jvm.SnapshotLoader
import io.hierograph.itest.fwk.SpringFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The invariant between the two levels, over the real Spring snapshot: every aggregated dependency is
 * exactly the detail facts behind it. For each (source type, target type, kind), the count in the
 * GraphStore's `typeDependencies()` equals the number of counted `dependencies` rows of the source type
 * to the target type — no weight without a fact, no counted fact without weight.
 */
class AggregateInvariantIT {

    private val dir = SpringFixture.directory

    @Test
    fun `every aggregated weight is the number of counted detail facts behind it`() {
        val store = GraphStore().also { SnapshotLoader().load(dir, it) }
        val aggregate = HashMap<Triple<String, String, DependencyKind>, Int>()
        for (dep in store.typeDependencies()) {
            for ((kind, count) in dep.kinds) aggregate.merge(Triple(dep.fromFqn, dep.toFqn, kind), count, Int::plus)
        }

        // The rollup rules applied to the raw rows, counted independently of the reader's load path.
        val facts = HashMap<Triple<String, String, DependencyKind>, Int>()
        val rows = ParquetReader().readDependencies(dir)
        for (row in rows) {
            if (!row.kind.counted || row.toType == row.fromType || row.toType in JavaTypeNames.PRIMITIVES) continue
            facts.merge(Triple(row.fromType, row.toType, row.kind), 1, Int::plus)
        }

        assertThat(rows).describedAs("detail rows").hasSizeGreaterThan(100_000)
        assertThat(aggregate.keys - facts.keys).describedAs("aggregated kinds without detail facts").isEmpty()
        assertThat(facts.keys - aggregate.keys).describedAs("counted detail facts without aggregated weight").isEmpty()
        assertThat(aggregate.filter { (key, count) -> facts[key] != count })
            .describedAs("aggregated counts that differ from the facts").isEmpty()
        // every kind a class file carries contributes somewhere
        assertThat(facts.keys.map { it.third }.toSet()).containsAll(DependencyKind.AGGREGATED)
        assertThat(rows.map { it.kind }.toSet()).doesNotContain(DependencyKind.OVERRIDES, DependencyKind.READ_BY, DependencyKind.WRITTEN_BY)
    }
}
