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
package io.hierograph.graphstore.jvm.detail

import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import java.nio.file.Path

/**
 * A resolved detail-dependency query over a Parquet snapshot. [fromTypes] / [toTypes] are the
 * already-expanded type-FQN sets of the caller's scopes, supertypes included (so inheritance has already been applied);
 * [toTypes] = null leaves the target side unconstrained ("all dependencies of the source").
 */
data class DetailQuery(
    val fromTypes: Set<String>,
    val toTypes: Set<String>? = null,
    val relationship: String? = null,
)

/**
 * The member-level dependency engine over a Parquet snapshot. Scope resolution happens in memory, in the
 * caller; this engine does the member-edge half: a scoped push-down read of `detail_refs.parquet`
 * (`from_type` / `to_type` ∈ scope, optionally `relationship`) and a stable sort. Paging and summaries
 * are the caller's.
 *
 * The reverse relationships `read_by` / `written_by` are the stored `reads_field` / `writes_field` edges
 * viewed target→source: the scope anchors on the field side (`to_type`) and the endpoints are swapped so
 * the field is the source and the reading/writing method is the target.
 */
class DetailQueryEngine(private val parquetDir: Path) {

    private val loader = ParquetReader()

    /** Every edge [q] matches, in a stable order. */
    fun query(q: DetailQuery): List<DetailRef> {
        val reverseStored = REVERSE[q.relationship]
        val edges = if (reverseStored != null) reverseEdges(q, reverseStored) else forwardEdges(q)
        return edges.sortedWith(
            compareBy({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.relationship }, { it.line ?: -1 }),
        )
    }

    /** Forward relationships: the scope is the source; `from_type` (sorted) drives row-group skipping. */
    private fun forwardEdges(q: DetailQuery): List<DetailRef> =
        loader.readDetailRefs(parquetDir, fromTypes = q.fromTypes, toTypes = q.toTypes, relationship = q.relationship)

    /**
     * Reverse relationships (`read_by` / `written_by`): the scope anchors on the field side, so it maps
     * to the stored edge's `to_type`; the optional target constraint maps to `from_type`. Endpoints are
     * swapped on the way out so the result reads field → reading/writing method under the requested kind.
     */
    private fun reverseEdges(q: DetailQuery, stored: String): List<DetailRef> =
        loader.readDetailRefs(parquetDir, fromTypes = q.toTypes, toTypes = q.fromTypes, relationship = stored)
            .map {
                DetailRef(
                    fromKey = it.toKey, fromType = it.toType,
                    toKey = it.fromKey, toType = it.fromType,
                    relationship = q.relationship!!, line = it.line,
                )
            }

    private companion object {
        /** Reverse relationship → the stored forward kind it reads target→source. */
        val REVERSE = mapOf("read_by" to "reads_field", "written_by" to "writes_field")
    }
}
