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
 * Rolls detail dependencies up into aggregated references: per owner type, one [Ref] per
 * (target type, [RefKind]) with the number of detail facts behind it as its count. This is the only
 * place the rollup rules live, so the aggregate can always be explained by detail facts:
 *
 * - only [counted][DependencyKind.counted] kinds count, one fact adding 1;
 * - self-dependencies (target type = the owner type) and primitive or `void` targets are left out;
 * - relationships outside [DependencyKind] (derived ones such as `overrides`) are left out.
 *
 * Each counted kind maps onto the [RefKind] of the aggregate; [DependencyKind.ANNOTATED_BY] maps to
 * [RefKind.ANNOTATED_BY] when the source is the type itself and to [RefKind.MEMBER_ANNOTATED_BY] when it
 * is a member. Refs are listed in the order their first fact appears.
 *
 * The scanner rolls up each class as it finishes it ([refsOf]); a snapshot reader feeds the rows of a
 * dependency table into an [Accumulator] without building a [DetailRef] per row.
 */
object DependencyRollup {

    /** The aggregated references of [ownerFqn], rolled up from its detail [facts]. */
    fun refsOf(ownerFqn: String, facts: Iterable<DetailRef>): List<Ref> {
        val acc = Accumulator()
        for (fact in facts) acc.add(ownerFqn, fact.fromKey, fact.toType, fact.relationship)
        return acc.refsOf(ownerFqn)
    }

    /** Collects detail facts of any number of owner types and rolls them up per owner. Not thread-safe. */
    class Accumulator {
        private val countsByOwner = LinkedHashMap<String, LinkedHashMap<Pair<String, RefKind>, Int>>()

        /** Adds one detail fact: from [fromKey] (the owner's FQN for a type-level fact) of [ownerFqn] to [toType]. */
        fun add(ownerFqn: String, fromKey: String, toType: String, relationship: String) {
            val kind = DependencyKind.fromValue(relationship) ?: return
            if (!kind.counted) return
            if (toType == ownerFqn || toType in JavaKinds.JAVA_PRIMITIVES) return
            val refKind = refKindOf(kind, sourceIsType = fromKey == ownerFqn) ?: return
            countsByOwner.getOrPut(ownerFqn) { LinkedHashMap() }.merge(toType to refKind, 1, Int::plus)
        }

        /** The owner types that have at least one counted fact, in the order their first fact appeared. */
        val owners: Set<String> get() = countsByOwner.keys

        /** The aggregated references of [ownerFqn]; empty when it has no counted fact. */
        fun refsOf(ownerFqn: String): List<Ref> =
            countsByOwner[ownerFqn].orEmpty().map { (key, count) -> Ref(toKey = key.first, kind = key.second, count = count) }
    }

    /** The [RefKind] a counted [kind] is stored under; `null` for a kind that is not counted. */
    fun refKindOf(kind: DependencyKind, sourceIsType: Boolean): RefKind? = when (kind) {
        DependencyKind.EXTENDS -> RefKind.EXTENDS
        DependencyKind.IMPLEMENTS -> RefKind.IMPLEMENTS
        DependencyKind.ANNOTATED_BY -> if (sourceIsType) RefKind.ANNOTATED_BY else RefKind.MEMBER_ANNOTATED_BY
        DependencyKind.PARAMETER_ANNOTATED_BY -> RefKind.MEMBER_ANNOTATED_BY
        DependencyKind.ANNOTATION_VALUE -> RefKind.ANNOTATION_VALUE
        DependencyKind.TYPE_ARGUMENT -> RefKind.TYPE_ARGUMENT
        DependencyKind.HAS_TYPE -> RefKind.FIELD_TYPE
        DependencyKind.PARAMETER_TYPE -> RefKind.PARAM_TYPE
        DependencyKind.RETURNS -> RefKind.RETURN_TYPE
        DependencyKind.THROWS -> RefKind.THROWS
        DependencyKind.CALLS -> RefKind.INVOKES
        DependencyKind.READS_FIELD -> RefKind.READS
        DependencyKind.WRITES_FIELD -> RefKind.WRITES
        DependencyKind.INSTANTIATES -> RefKind.INSTANTIATES
        DependencyKind.CASTS -> RefKind.CAST
        DependencyKind.CATCHES -> RefKind.CATCHES
        DependencyKind.CLASS_LITERAL -> RefKind.CLASS_LITERAL
        DependencyKind.RAISES -> null
    }
}
