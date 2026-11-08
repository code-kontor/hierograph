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
 * An aggregated type-to-type dependency with its endpoints **resolved to artifact-scoped node keys**
 * ([fromKey], [toKey]) — the key-level counterpart of the FQN-keyed [TypeDependency].
 *
 * This is what the hierarchical-graph bridge consumes: because a reference is resolved (see
 * [TypeResolver]) to a concrete node rather than a bare FQN, two same-FQN types in different artifacts
 * yield edges to the correct distinct nodes. The structural flags and [weight] carry the same meaning as
 * on [TypeDependency].
 */
data class TypeEdge(
    val fromKey: String,
    val toKey: String,
    val weight: Int,
    val extends: Boolean,
    val implements: Boolean,
    val annotatedBy: Boolean,
    val other: Boolean,
)
