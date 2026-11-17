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
package io.hierograph.graphstore.jvm.archive.model

/**
 * One declared parameter of a method/constructor: its [index] (0-based position), its source [name]
 * (when captured — javac `-parameters`), the fully-qualified [typeFqn] of its (erased) type, and the
 * fully-qualified types of the [annotations] on it, each once.
 *
 * Parameters are a detail-level concern persisted in `parameters.parquet`; they are not materialized in
 * the type-level in-memory graph.
 */
data class ParameterFacts(
    val index: Int,
    val name: String,
    val typeFqn: String,
    val annotations: List<String> = emptyList(),
)
