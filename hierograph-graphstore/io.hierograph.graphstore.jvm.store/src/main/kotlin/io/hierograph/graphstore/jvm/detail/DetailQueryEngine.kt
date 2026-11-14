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

import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.ParquetReader
import java.nio.file.Path

/**
 * A resolved detail-dependency query over a Parquet snapshot. [fromTypes] / [toTypes] are the type-FQN
 * sets of the caller's scopes, already final: the engine matches a row's `fromType` / `toType` against
 * them and follows no inheritance. Whether a side includes supertypes is the caller's decision;
 * [toTypes] = null leaves the target side unconstrained ("all dependencies of the source"), and
 * [fromTypes] = null the depending side ("everything that depends on the target"); at most one of them
 * is meant to be null, since leaving both open reads the whole table.
 * [parameterIndex] keeps only the facts of the parameter at that 0-based position; only `parameter_type`
 * and `parameter_annotated_by` facts carry a position, so it implies those kinds.
 */
data class DetailQuery(
    val fromTypes: Set<String>?,
    val toTypes: Set<String>? = null,
    val kind: DependencyKind? = null,
    val parameterIndex: Int? = null,
)

/**
 * The member-level dependency engine over a Parquet snapshot. Scope resolution happens in memory, in the
 * caller; this engine does the member-edge half: a scoped push-down read of `dependencies.parquet`
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
    fun query(q: DetailQuery): List<Dependency> {
        val reverseStored = REVERSE[q.kind]
        val edges = if (reverseStored != null) reverseEdges(q, reverseStored) else forwardEdges(q)
        val positioned = q.parameterIndex?.let { i -> edges.filter { it.parameterIndex == i } } ?: edges
        return positioned.sortedWith(Dependency.ORDER)
    }

    /** Forward relationships: the scope is the source; `from_type` (sorted) drives row-group skipping. */
    private fun forwardEdges(q: DetailQuery): List<Dependency> =
        loader.readDependencies(parquetDir, fromTypes = q.fromTypes, toTypes = q.toTypes, kind = q.kind)

    /**
     * Reverse relationships (`read_by` / `written_by`): the scope anchors on the field side, so it maps
     * to the stored edge's `to_type`; the optional target constraint maps to `from_type`. Endpoints are
     * swapped on the way out so the result reads field → reading/writing method under the requested kind.
     */
    private fun reverseEdges(q: DetailQuery, stored: DependencyKind): List<Dependency> =
        loader.readDependencies(parquetDir, fromTypes = q.toTypes, toTypes = q.fromTypes, kind = stored)
            .map {
                Dependency(
                    fromKey = it.toKey, fromType = it.toType,
                    toKey = it.fromKey, toType = it.fromType,
                    kind = q.kind!!, line = it.line,
                    parameterIndex = it.parameterIndex, parameterName = it.parameterName,
                )
            }

    private companion object {
        /** Reverse relationship → the stored forward kind it reads target→source. */
        val REVERSE = mapOf(DependencyKind.READ_BY to DependencyKind.READS_FIELD, DependencyKind.WRITTEN_BY to DependencyKind.WRITES_FIELD)
    }
}
