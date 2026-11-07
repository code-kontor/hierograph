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
package io.hierograph.graphstore.model

/**
 * One member-level ("detail") reference: an edge from a source **member** (or type, for class-level
 * annotations) to a target **symbol** — a member for `calls`/`reads_field`/…, a type for
 * `throws`/`returns`/`has_type`/`parameter_type`/`annotated_by`. [relationship] is the Hierograph
 * detail vocabulary; [toKey] equals [toType] when the target is a type reference.
 *
 * Prototype note: detail refs are persisted in a dedicated `detail_refs.parquet` so the validated
 * type-level snapshot stays untouched. In the production model these rows merge into the single `refs`
 * table, distinguished by kind (see the "Types vs Type References" model doc); at that point `DetailRef`
 * folds into an enriched [Ref].
 */
data class DetailRef(
    val fromKey: String,
    val fromType: String,
    val toKey: String,
    val toType: String,
    val relationship: String,
    val line: Int? = null,
)
