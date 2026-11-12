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
package io.hierograph.graphstore.jvm.model

/**
 * One detail dependency: a fact from a source element — a member, or the type itself for type-level
 * facts such as `extends` or the type's own annotations — to a target element: a member for
 * `calls`/`reads_field`/`writes_field`/`overrides`, a type for every other kind. [kind] says what the fact
 * means; a [derived][DependencyKind.derived] kind marks a fact computed at load rather than read from a
 * class file. [toKey] equals [toType] when the target is a type. [fromArtifact] is the artifact
 * whose snapshot holds the row, which is the artifact that defines [fromType]; it is set when a row is
 * read from a per-artifact snapshot and lets a reader resolve both types in that artifact's context when
 * an FQN is defined more than once, and is `null` when the artifact is not known.
 *
 * The aggregated references of a type ([Ref]) are the rollup of its detail dependencies, see
 * [DependencyRollup].
 */
data class Dependency(
    val fromKey: String,
    val fromType: String,
    val toKey: String,
    val toType: String,
    val kind: DependencyKind,
    val line: Int? = null,
    val fromArtifact: String? = null,
)
