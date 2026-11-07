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
package io.hierograph.graphstore

/**
 * An aggregated type-to-type dependency: all refs from [from] to [to] collapsed into one row with a
 * summed [weight] and the structural classification flags.
 *
 * **Open semantic decision (see the scanner spec):** [other] here means "this dependency *also* has a
 * non-structural ref" (`kinds.any { it !in STRUCTURAL }`). The current jQAssistant `DependencyRule`
 * instead sets its fourth flag to "*none* of extends / implements / annotatedBy". The two differ
 * whenever a structural edge coexists with a `DEPENDS_ON`/other edge, which is the documented expected
 * difference when diffing the two backends. Resolve which semantic Hierograph exposes before relying
 * on this flag across backends.
 */
data class TypeDependency(
    val from: String,
    val to: String,
    val weight: Int,
    val extends: Boolean,
    val implements: Boolean,
    val annotatedBy: Boolean,
    val other: Boolean,
)
