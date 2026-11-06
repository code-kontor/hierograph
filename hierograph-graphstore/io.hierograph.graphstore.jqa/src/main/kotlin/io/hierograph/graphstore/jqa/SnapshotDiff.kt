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
package io.hierograph.graphstore.jqa

import io.hierograph.graphstore.HierarchyRow
import io.hierograph.graphstore.TypeDependency

/**
 * Knobs for the MVP diff that absorb the documented, expected differences between the GraphStore and
 * the jQAssistant `GraphDbMapping` projection (spec §7 step 4):
 *
 * - [ignoreDependencyOther]: the `other` flag has divergent semantics on the two sides (see
 *   [TypeDependency]). The jQA rule means "none of extends/implements/annotatedBy"; the GraphStore
 *   means "also has a non-structural ref".
 * - [ignoreDependencyWeight]: in the MVP the GraphStore sums the per-ref `count`s, so a type-to-type
 *   edge that is both a structural edge (count 1) and a jQA `DEPENDS_ON` (count = weight) totals
 *   weight + 1, whereas the reference uses the `DEPENDS_ON` weight alone.
 */
data class DiffOptions(
    val ignoreDependencyOther: Boolean = false,
    val ignoreDependencyWeight: Boolean = false,
)

/** The rows present on exactly one side of a comparison. Empty on both sides means equivalence. */
data class SetDiff<T>(val onlyInGraphStore: List<T>, val onlyInReference: List<T>) {
    val isEmpty: Boolean get() = onlyInGraphStore.isEmpty() && onlyInReference.isEmpty()
}

data class SnapshotDiffResult(
    val hierarchy: SetDiff<HierarchyRow>,
    val dependencies: SetDiff<TypeDependency>,
) {
    val isEmpty: Boolean get() = hierarchy.isEmpty && dependencies.isEmpty

    /** A deterministic, human-readable listing of every difference; empty string when equivalent. */
    fun render(limitPerSection: Int = 100): String = buildString {
        renderSection("HIERARCHY only in GraphStore", hierarchy.onlyInGraphStore, limitPerSection) {
            "${kindLabel(it.kind)}  ${it.fqn}  (parent=${it.parentFqn})"
        }
        renderSection("HIERARCHY only in GraphDbMapping", hierarchy.onlyInReference, limitPerSection) {
            "${kindLabel(it.kind)}  ${it.fqn}  (parent=${it.parentFqn})"
        }
        renderSection("DEPENDENCIES only in GraphStore", dependencies.onlyInGraphStore, limitPerSection, ::renderDep)
        renderSection("DEPENDENCIES only in GraphDbMapping", dependencies.onlyInReference, limitPerSection, ::renderDep)
        if (isEmpty) append("No differences.\n")
    }

    private fun <T> StringBuilder.renderSection(title: String, rows: List<T>, limit: Int, line: (T) -> String) {
        if (rows.isEmpty()) return
        append(title).append(" (").append(rows.size).append("):\n")
        rows.map(line).sorted().take(limit).forEach { append("  ").append(it).append('\n') }
        if (rows.size > limit) append("  … ").append(rows.size - limit).append(" more\n")
    }

    private fun renderDep(d: TypeDependency): String =
        "${d.from} -> ${d.to}  [ext=${d.extends} impl=${d.implements} ann=${d.annotatedBy} other=${d.other} w=${d.weight}]"

    private fun kindLabel(kind: Any): String = (kind as? Enum<*>)?.name ?: kind.toString()
}

/**
 * Compares the GraphStore projection against the jQAssistant `GraphDbMapping` reference projection by
 * **value** (FQN-keyed rows), since the two backends share no node identity. Both sides must supply the
 * same row shapes: [HierarchyRow] `(kind, fqn, parentFqn)` and [TypeDependency] `(from, to, flags, weight)`.
 */
object SnapshotDiff {

    fun diff(
        graphStoreHierarchy: List<HierarchyRow>,
        graphStoreDependencies: List<TypeDependency>,
        referenceHierarchy: List<HierarchyRow>,
        referenceDependencies: List<TypeDependency>,
        options: DiffOptions = DiffOptions(),
    ): SnapshotDiffResult {
        val gsHierarchy = graphStoreHierarchy.toSet()
        val refHierarchy = referenceHierarchy.toSet()
        val gsDeps = graphStoreDependencies.mapTo(HashSet()) { it.normalize(options) }
        val refDeps = referenceDependencies.mapTo(HashSet()) { it.normalize(options) }
        return SnapshotDiffResult(
            hierarchy = SetDiff((gsHierarchy - refHierarchy).toList(), (refHierarchy - gsHierarchy).toList()),
            dependencies = SetDiff((gsDeps - refDeps).toList(), (refDeps - gsDeps).toList()),
        )
    }

    private fun TypeDependency.normalize(options: DiffOptions): TypeDependency = copy(
        other = if (options.ignoreDependencyOther) false else other,
        weight = if (options.ignoreDependencyWeight) 0 else weight,
    )
}
