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
package io.hierograph.server.mapping

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.IDependencySource

/**
 * The dependency source for a GraphStore-backed [io.hierograph.hierarchicalgraph.model.HGModel]: the
 * type-level dependency's [kinds], the count of detail facts per [DependencyKind] behind it (see
 * [io.hierograph.graphstore.jvm.TypeDependency]). The core dependency's weight is their sum; its
 * attribute bitmap is the flag view of them.
 */
class GraphStoreDependencySource(
    override val identifier: Any,
    val kinds: Map<DependencyKind, Int>,
) : IDependencySource {
    override var dependency: HGCoreDependency? = null

    companion object {
        /** The [kinds] of [dependency], or none when it was not mapped from a GraphStore. */
        fun kindsOf(dependency: HGCoreDependency): Map<DependencyKind, Int> =
            (dependency.dependencySource as? GraphStoreDependencySource)?.kinds.orEmpty()

        /** The summed [kinds] of [dependencies], as one map. */
        fun kindsOf(dependencies: Iterable<HGCoreDependency>): Map<DependencyKind, Int> {
            val sum = java.util.EnumMap<DependencyKind, Int>(DependencyKind::class.java)
            for (dep in dependencies) for ((kind, count) in kindsOf(dep)) sum.merge(kind, count, Int::plus)
            return sum
        }
    }
}
