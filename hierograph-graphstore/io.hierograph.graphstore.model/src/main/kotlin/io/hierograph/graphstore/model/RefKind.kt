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
 * The kind of a single reference (edge) between a source and a target key.
 *
 * Structural and signature kinds are produced in both scanner modes; body kinds only in FULL mode.
 * [DEPENDS_ON] is MVP-only: it carries jQAssistant's coarse, pre-aggregated type-level dependency
 * until the own scanner replaces it with the fine-grained kinds.
 */
enum class RefKind {
    // structural
    EXTENDS, IMPLEMENTS, ANNOTATED_BY,

    // signature
    FIELD_TYPE, PARAM_TYPE, RETURN_TYPE, THROWS,

    // body (FULL mode only)
    INVOKES, READS, WRITES, INSTANTIATES, CAST, CATCHES,

    // MVP only: coarse, pre-aggregated by jQAssistant
    DEPENDS_ON;

    companion object {
        /** The structural kinds that drive the extends / implements / annotatedBy dependency flags. */
        val STRUCTURAL: Set<RefKind> = setOf(EXTENDS, IMPLEMENTS, ANNOTATED_BY)
    }
}
