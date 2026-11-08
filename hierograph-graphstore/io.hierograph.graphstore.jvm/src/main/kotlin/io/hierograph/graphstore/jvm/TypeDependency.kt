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
package io.hierograph.graphstore.jvm

/**
 * An aggregated type-to-type dependency: all refs from [from] to [to] collapsed into one row with a
 * summed [weight] and the structural classification flags.
 *
 * The flags are not mutually exclusive: [extends] / [implements] / [annotatedBy] mark the structural ref
 * kinds, and [other] means the dependency *also* has at least one non-structural (signature or body) ref
 * (`kinds.any { it !in STRUCTURAL }`). A class that extends `Base` and calls one of its methods has both
 * [extends] and [other] set.
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
