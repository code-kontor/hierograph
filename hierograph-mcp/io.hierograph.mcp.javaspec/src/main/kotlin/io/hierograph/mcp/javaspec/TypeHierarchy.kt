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
package io.hierograph.mcp.javaspec

import io.hierograph.hierarchicalgraph.core.model.HGNode

/**
 * Returns the transitive supertype closure of this type node — every type reachable
 * by following `outgoingCoreDependencies` whose attribute bitmap has either
 * [JavaEdgeAttributes.IS_EXTENDS] or [JavaEdgeAttributes.IS_IMPLEMENTS] set.
 *
 * When [includeSelf] is `true` the receiver is included in the result; when it is
 * `false` (the default) the receiver only appears if it is reachable from itself
 * through a cycle. Cycles and diamond inheritance are handled via a visited-set,
 * so each type appears at most once.
 */
fun HGNode.supertypes(includeSelf: Boolean = false): Set<HGNode> =
    walkHierarchy(listOf(this), includeSelf, upward = true)

/**
 * Returns the transitive subtype / implementor closure of this type node — every
 * type reachable by following `incomingCoreDependencies` whose attribute bitmap has
 * either [JavaEdgeAttributes.IS_EXTENDS] or [JavaEdgeAttributes.IS_IMPLEMENTS] set.
 *
 * See [supertypes] for the semantics of [includeSelf].
 */
fun HGNode.subtypes(includeSelf: Boolean = false): Set<HGNode> =
    walkHierarchy(listOf(this), includeSelf, upward = false)

/**
 * Returns the union of the transitive supertype closures of every node in this
 * collection. Visited state is shared across the seeds, so overlapping ancestor
 * chains are traversed only once.
 *
 * When [includeSelf] is `true` all seeds are included in the result; when `false`
 * (the default) a seed appears only if it is reachable via the closure starting
 * from another seed (or via a cycle).
 */
fun Iterable<HGNode>.supertypes(includeSelf: Boolean = false): Set<HGNode> =
    walkHierarchy(this, includeSelf, upward = true)

/**
 * Returns the union of the transitive subtype closures of every node in this
 * collection. See [Iterable.supertypes] for [includeSelf] semantics.
 */
fun Iterable<HGNode>.subtypes(includeSelf: Boolean = false): Set<HGNode> =
    walkHierarchy(this, includeSelf, upward = false)

private fun walkHierarchy(seeds: Iterable<HGNode>, includeSelf: Boolean, upward: Boolean): Set<HGNode> {
    val visited = LinkedHashSet<HGNode>()
    val stack = ArrayDeque<HGNode>()
    for (seed in seeds) {
        if (includeSelf) visited.add(seed)
        stack.addLast(seed)
    }
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        val edges = if (upward) current.outgoingCoreDependencies else current.incomingCoreDependencies
        for (dep in edges) {
            if (!isExtendsOrImplements(dep.attributesBitmap)) continue
            val neighbour = if (upward) dep.to else dep.from
            if (visited.add(neighbour)) {
                stack.addLast(neighbour)
            }
        }
    }
    return visited
}

private fun isExtendsOrImplements(bitmap: Int): Boolean =
    JavaEdgeAttributes.isSet(bitmap, JavaEdgeAttributes.IS_EXTENDS) ||
        JavaEdgeAttributes.isSet(bitmap, JavaEdgeAttributes.IS_IMPLEMENTS)
