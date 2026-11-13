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

import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind

/**
 * Rolls detail dependencies up into aggregated references: per owner type, one [Ref] per
 * (target type, [DependencyKind], [type-level][Ref.typeLevel]) with the number of detail facts behind it as
 * its count. This is the only place the rollup rules live, so the aggregate can always be explained by
 * detail facts:
 *
 * - only [counted][DependencyKind.counted] kinds count, one fact adding 1;
 * - self-dependencies (target type = the owner type) and primitive or `void` targets are left out;
 * - a fact whose source is the owner type itself rolls up into a type-level ref, a member's into a
 *   member-level one.
 *
 * Refs are listed in the order their first fact appears.
 *
 * The rollup is the loaders' job, not the scanner's: the snapshot reader feeds the rows of a dependency
 * table into an [Accumulator] without building a [Dependency] per row, and the server's in-memory ingest
 * of a fresh scan feeds the scan's [Dependency] facts into one. [refsOf] rolls up one class's facts.
 */
object DependencyRollup {

    /** The aggregated references of [ownerFqn], rolled up from its detail [facts]. */
    fun refsOf(ownerFqn: String, facts: Iterable<Dependency>): List<Ref> {
        val acc = Accumulator()
        for (fact in facts) acc.add(ownerFqn, fact.fromKey, fact.toType, fact.kind)
        return acc.refsOf(ownerFqn)
    }

    /** Collects detail facts of any number of owner types and rolls them up per owner. Not thread-safe. */
    class Accumulator {
        private val countsByOwner = LinkedHashMap<String, LinkedHashMap<RefKey, Int>>()

        /** Adds one detail fact: from [fromKey] (the owner's FQN for a type-level fact) of [ownerFqn] to [toType]. */
        fun add(ownerFqn: String, fromKey: String, toType: String, kind: DependencyKind) {
            if (!kind.counted) return
            if (toType == ownerFqn || toType in JavaTypeNames.PRIMITIVES) return
            countsByOwner.getOrPut(ownerFqn) { LinkedHashMap() }
                .merge(RefKey(toType, kind, typeLevel = fromKey == ownerFqn), 1, Int::plus)
        }

        /** The owner types that have at least one counted fact, in the order their first fact appeared. */
        val owners: Set<String> get() = countsByOwner.keys

        /** The aggregated references of [ownerFqn]; empty when it has no counted fact. */
        fun refsOf(ownerFqn: String): List<Ref> =
            countsByOwner[ownerFqn].orEmpty().map { (key, count) ->
                Ref(toKey = key.toType, kind = key.kind, count = count, typeLevel = key.typeLevel)
            }
    }

    private data class RefKey(val toType: String, val kind: DependencyKind, val typeLevel: Boolean)
}
