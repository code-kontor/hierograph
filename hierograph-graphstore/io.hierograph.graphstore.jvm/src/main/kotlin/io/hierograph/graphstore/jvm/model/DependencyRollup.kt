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
 * Rolls the detail dependencies of one type up into its aggregated references: one [Ref] per
 * (target type, [RefKind]) with the number of detail facts behind it as its count. This is the only
 * place the rollup rules live, so the aggregate can always be explained by detail facts:
 *
 * - only [counted][DependencyKind.counted] kinds count, one fact adding 1;
 * - self-dependencies (target type = the owner type) and primitive or `void` targets are left out;
 * - relationships outside [DependencyKind] (derived ones such as `overrides`) are left out.
 *
 * The facts must all belong to the one owner type ([DetailRef.fromType] = [ownerFqn]), as a class file's
 * facts do. Each counted kind maps onto the [RefKind] the stored aggregate uses; [DependencyKind.ANNOTATED_BY]
 * maps to [RefKind.ANNOTATED_BY] when the source is the type itself and to [RefKind.MEMBER_ANNOTATED_BY]
 * when it is a member. Refs are listed in the order their first fact appears.
 */
object DependencyRollup {

    fun refsOf(ownerFqn: String, facts: Iterable<DetailRef>): List<Ref> {
        val counts = LinkedHashMap<Pair<String, RefKind>, Int>()
        for (fact in facts) {
            val kind = DependencyKind.fromValue(fact.relationship) ?: continue
            if (!kind.counted) continue
            val target = fact.toType
            if (target == ownerFqn || target in JavaKinds.JAVA_PRIMITIVES) continue
            val refKind = refKindOf(kind, sourceIsType = fact.fromKey == ownerFqn) ?: continue
            counts.merge(target to refKind, 1, Int::plus)
        }
        return counts.map { (key, count) -> Ref(toKey = key.first, kind = key.second, count = count) }
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
