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

import io.hierograph.graphstore.jvm.model.DependencyKind
import io.hierograph.graphstore.jvm.detail.DetailQuery
import io.hierograph.graphstore.jvm.detail.DetailQueryEngine
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import io.hierograph.itest.fwk.SpringFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [DetailQueryEngine] / `ParquetReader.readDependencies` behaviour on the real Spring snapshot, including
 * regressions for three bugs in the detail path:
 * the single-predicate push-down (hardwood AND mis-pruning), class-level `annotated_by` edges (bare
 * `from_key`), and scoped queries.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetailQueryRegressionIT {

    private val dir = SpringFixture.directory
    private val reader = ParquetReader()

    @Test
    fun `combining from, to and relationship filters drops no rows (single-predicate push-down)`() {
        val cls = "org.springframework.cache.annotation.ProxyCachingConfiguration"
        val cfg = "org.springframework.context.annotation.Configuration"
        // Ground truth: read by from_type only (one predicate, correct), filter the rest in memory.
        val expected = reader.readDependencies(dir, fromTypes = listOf(cls))
            .filter { it.toType == cfg && it.kind == DependencyKind.ANNOTATED_BY }
            .map { it.fromKey to it.toKey }.toSet()
        // The three-predicate call must return exactly the same set (not fewer — the bug dropped rows).
        val actual = reader.readDependencies(dir, fromTypes = listOf(cls), toTypes = listOf(cfg), kind = DependencyKind.ANNOTATED_BY)
            .map { it.fromKey to it.toKey }.toSet()
        assertThat(actual).isEqualTo(expected)
        assertThat(actual).isNotEmpty
    }

    @Test
    fun `a class-level annotated_by edge (bare from_key) is readable`() {
        val cls = "org.springframework.cache.annotation.ProxyCachingConfiguration"
        val edges = reader.readDependencies(dir, fromTypes = listOf(cls), kind = DependencyKind.ANNOTATED_BY)
        // The class itself (from_key == the type fqn, no '#') is annotated — not only its members.
        assertThat(edges).anyMatch { it.fromKey == cls }
    }

    @Test
    fun `a scoped forward query returns only the scope's edges of the requested kind`() {
        val engine = DetailQueryEngine(dir)
        val scope = setOf("org.springframework.web.servlet.DispatcherServlet")
        val edges = engine.query(DetailQuery(fromTypes = scope, kind = DependencyKind.CALLS))
        assertThat(edges.size).isGreaterThan(5)
        assertThat(edges).allSatisfy {
            assertThat(it.kind).isEqualTo(DependencyKind.CALLS)
            assertThat(it.fromType).isIn(scope)
        }
    }

    @Test
    fun `read_by is the reads_field edge viewed field to reader`() {
        val engine = DetailQueryEngine(dir)
        // Anchor on a real field read, then ask "who reads this field's owner's fields?" in reverse.
        val aRead = reader.readDependencies(dir, kind = DependencyKind.READS_FIELD).first()
        val fieldOwner = aRead.toType
        val reverse = engine.query(DetailQuery(fromTypes = setOf(fieldOwner), kind = DependencyKind.READ_BY))
        assertThat(reverse).isNotEmpty
        assertThat(reverse).allSatisfy { assertThat(it.kind).isEqualTo(DependencyKind.READ_BY) }
        // endpoints are swapped: the field side is now the source.
        assertThat(reverse).allSatisfy { assertThat(it.fromType).isEqualTo(fieldOwner) }
    }
}
