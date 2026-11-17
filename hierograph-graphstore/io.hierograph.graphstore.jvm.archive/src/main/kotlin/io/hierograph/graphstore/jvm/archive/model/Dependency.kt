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
 * One detail dependency: a fact from a source element — a member, or the type itself for type-level
 * facts such as `extends` or the type's own annotations — to a target element: a member for
 * `calls`/`reads_field`/`writes_field`/`overrides`, a type for every other kind. [kind] says what the fact
 * means; a [derived][DependencyKind.derived] kind marks a fact computed at load rather than read from a
 * class file. [toKey] equals [toType] when the target is a type. [fromArtifact] is the artifact whose
 * class file the fact comes from, which is the artifact that defines [fromType]. The scanner sets it, so a
 * writer files the fact with its artifact; a reader sets it when it reads a row from a per-artifact
 * snapshot, so that both types can be resolved in that artifact's context. It matters when an FQN is
 * defined more than once, and is `null` when the artifact is not known. It is not a column: the archive a
 * row sits in names its artifact.
 *
 * [parameterIndex] and [parameterName] identify the parameter a [DependencyKind.PARAMETER_TYPE] or
 * [DependencyKind.PARAMETER_ANNOTATED_BY] fact comes from: its 0-based position in the method descriptor
 * (as `ParameterFacts.index`) and its name (as `ParameterFacts.name`). Both are set exactly for those two
 * kinds and `null` for every other, so a method that takes one type twice has two facts that differ only
 * by position.
 *
 * The aggregated references of a type (`Ref`) are the rollup of its detail dependencies, see
 * `DependencyRollup` (both in the store module).
 */
data class Dependency(
    val fromKey: String,
    val fromType: String,
    val toKey: String,
    val toType: String,
    val kind: DependencyKind,
    val line: Int? = null,
    val fromArtifact: String? = null,
    val parameterIndex: Int? = null,
    val parameterName: String? = null,
) {
    companion object {
        /**
         * The total order of detail facts: depending type, depending member, target type, target member,
         * kind, line, parameter position. The writer, the query engine and the detail tools all sort by
         * it, so facts that tie on everything but the parameter position (two parameters of one type) still
         * come out in a deterministic order.
         */
        val ORDER: Comparator<Dependency> = compareBy(
            { it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.kind.value },
            { it.line ?: -1 }, { it.parameterIndex ?: -1 },
        )
    }
}
