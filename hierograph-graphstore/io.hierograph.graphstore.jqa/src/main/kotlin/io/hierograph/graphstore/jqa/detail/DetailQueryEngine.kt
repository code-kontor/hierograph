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
package io.hierograph.graphstore.jqa.detail

import java.nio.file.Path

/**
 * A resolved detail-dependency query over a Parquet snapshot. [fromTypes] / [toTypes] are the
 * already-expanded type-FQN sets from [DetailScope] (so inheritance has already been applied);
 * [toTypes] = null leaves the target side unconstrained ("all dependencies of the source").
 */
data class DetailQuery(
    val fromTypes: Set<String>,
    val toTypes: Set<String>? = null,
    val relationship: String? = null,
    val limit: Int = 100,
    val offset: Int = 0,
)

/**
 * One page of detail edges plus full-result-set summaries. [total] / [byRelationship] / [bySourceType]
 * are computed over the whole result set, independent of the page; [nextOffset] is null on the last page.
 */
data class DetailQueryResult(
    val edges: List<DetailRef>,
    val total: Int,
    val byRelationship: Map<String, Int>,
    val bySourceType: List<Pair<String, Int>>,
    val nextOffset: Int?,
)

/**
 * The Parquet-backed `detail_dependencies` engine (approach B). Scope resolution happens in memory via
 * [DetailScope]; this engine does the member-edge half: a scoped push-down read of `detail_refs.parquet`
 * (`from_type ∈ fromTypes`, optionally `relationship`), an in-Kotlin `to_type` filter, a stable sort, the
 * summaries, and offset paging. No Neo4j, no second engine.
 *
 * Not yet implemented (next increments): the reverse relationships `read_by` / `written_by` (the
 * `READS` / `WRITES` edges read target→source), member display enrichment (join to `members.parquet`),
 * and keyset cursors in place of offset paging.
 */
class DetailQueryEngine(private val snapshotDir: Path) {

    fun query(q: DetailQuery): DetailQueryResult {
        // Push `from_type` (sorted column → row-group skipping) and `relationship` into the reader.
        val scoped = DetailRefs.read(snapshotDir, fromTypes = q.fromTypes, relationship = q.relationship)
        // `to_type` is not the sort key, so constrain it in memory.
        val matched = if (q.toTypes == null) scoped else scoped.filter { it.toType in q.toTypes }

        val sorted = matched.sortedWith(
            compareBy({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.relationship }, { it.line ?: -1 }),
        )

        val byRelationship = sorted.groupingBy { it.relationship }.eachCount().toSortedMap()
        val bySourceType = sorted.groupingBy { it.fromType }.eachCount()
            .entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }

        val page = sorted.drop(q.offset).take(q.limit)
        val nextOffset = (q.offset + q.limit).takeIf { it < sorted.size }

        return DetailQueryResult(page, sorted.size, byRelationship, bySourceType, nextOffset)
    }
}
