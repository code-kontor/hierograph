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

import io.hierograph.hierarchicalgraph.core.model.CoreNode
import io.hierograph.hierarchicalgraph.core.model.Hierarchy

/**
 * Returns this node's descendants in pre-order (parent before children, left-to-right
 * over `children`). The receiver itself is NOT included.
 *
 * When [kinds] is non-empty the result only contains nodes whose [CoreNode.kind] equals
 * one of the supplied [JavaNodeKind] values; the traversal itself still visits the
 * whole subtree so a filtered ancestor never prunes its filtered descendants. When
 * [kinds] is empty no filter is applied.
 */
fun CoreNode.descendants(hierarchy: Hierarchy, vararg kinds: JavaNodeKind): List<CoreNode> {
    val out = mutableListOf<CoreNode>()
    val filter = if (kinds.isEmpty()) null else kinds.toSet()
    for (child in hierarchy.childrenOf(this)) {
        collectDescendants(child, hierarchy, filter, out)
    }
    return out
}

/**
 * Returns the union of the descendants of every node in this collection (pre-order
 * per seed, in the iteration order of this collection). The seeds themselves are NOT
 * included. Duplicates are removed; if the same descendant is reachable from
 * multiple seeds it appears once, at its first occurrence.
 *
 * See [CoreNode.descendants] for the meaning of [kinds].
 */
fun Iterable<CoreNode>.descendants(hierarchy: Hierarchy, vararg kinds: JavaNodeKind): List<CoreNode> {
    val seen = LinkedHashSet<CoreNode>()
    val filter = if (kinds.isEmpty()) null else kinds.toSet()
    for (seed in this) {
        for (child in hierarchy.childrenOf(seed)) {
            collectDescendants(child, hierarchy, filter, seen)
        }
    }
    return seen.toList()
}

private fun collectDescendants(
    node: CoreNode,
    hierarchy: Hierarchy,
    filter: Set<JavaNodeKind>?,
    out: MutableCollection<CoreNode>
) {
    if (filter == null || node.kind in filter) {
        out.add(node)
    }
    for (child in hierarchy.childrenOf(node)) {
        collectDescendants(child, hierarchy, filter, out)
    }
}
