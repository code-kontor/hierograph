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

import io.hierograph.graphstore.detail.DetailQuery
import io.hierograph.graphstore.detail.DetailQueryEngine
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.itest.fwk.SpringFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [DetailQueryEngine] / `ParquetReader.readDetailRefs` behaviour on the real Spring snapshot, including
 * regressions for the three bugs the jQA retirement surfaced in the Neo4j-free detail path:
 * the single-predicate push-down (hardwood AND mis-pruning), class-level `annotated_by` edges (bare
 * `from_key`), and scoped query summaries/paging.
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
        val expected = reader.readDetailRefs(dir, fromTypes = listOf(cls))
            .filter { it.toType == cfg && it.relationship == "annotated_by" }
            .map { it.fromKey to it.toKey }.toSet()
        // The three-predicate call must return exactly the same set (not fewer — the bug dropped rows).
        val actual = reader.readDetailRefs(dir, fromTypes = listOf(cls), toTypes = listOf(cfg), relationship = "annotated_by")
            .map { it.fromKey to it.toKey }.toSet()
        assertThat(actual).isEqualTo(expected)
        assertThat(actual).isNotEmpty
    }

    @Test
    fun `a class-level annotated_by edge (bare from_key) is readable`() {
        val cls = "org.springframework.cache.annotation.ProxyCachingConfiguration"
        val edges = reader.readDetailRefs(dir, fromTypes = listOf(cls), relationship = "annotated_by")
        // The class itself (from_key == the type fqn, no '#') is annotated — not only its members.
        assertThat(edges).anyMatch { it.fromKey == cls }
    }

    @Test
    fun `a scoped forward query reports summaries over the full set and pages the result`() {
        val engine = DetailQueryEngine(dir)
        val scope = setOf("org.springframework.web.servlet.DispatcherServlet")
        val full = engine.query(DetailQuery(fromTypes = scope, relationship = "calls", limit = Int.MAX_VALUE))
        assertThat(full.total).isGreaterThan(5)
        assertThat(full.edges).hasSize(full.total)
        assertThat(full.byRelationship.keys).containsExactly("calls")
        assertThat(full.byRelationship["calls"]).isEqualTo(full.total)

        val firstPage = engine.query(DetailQuery(fromTypes = scope, relationship = "calls", limit = 5))
        assertThat(firstPage.edges).hasSize(5)
        assertThat(firstPage.nextOffset).isEqualTo(5)
        assertThat(firstPage.total).isEqualTo(full.total)
    }

    @Test
    fun `read_by is the reads_field edge viewed field to reader`() {
        val engine = DetailQueryEngine(dir)
        // Anchor on a real field read, then ask "who reads this field's owner's fields?" in reverse.
        val aRead = reader.readDetailRefs(dir, relationship = "reads_field").first()
        val fieldOwner = aRead.toType
        val reverse = engine.query(DetailQuery(fromTypes = setOf(fieldOwner), relationship = "read_by"))
        assertThat(reverse.edges).isNotEmpty
        assertThat(reverse.edges).allSatisfy { assertThat(it.relationship).isEqualTo("read_by") }
        // endpoints are swapped: the field side is now the source.
        assertThat(reverse.edges).allSatisfy { assertThat(it.fromType).isEqualTo(fieldOwner) }
    }
}
