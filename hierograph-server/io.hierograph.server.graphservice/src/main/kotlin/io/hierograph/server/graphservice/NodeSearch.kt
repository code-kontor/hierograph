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
package io.hierograph.server.graphservice

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import org.springframework.stereotype.Component

/**
 * Name search over the snapshot's [NodeSearchIndex] — the single implementation behind the MCP
 * `find_node` tool and the GraphQL `findNodes` field, so both match and rank identically.
 *
 * Case-insensitive substring match on name and fully-qualified name, ranked by match quality: exact
 * name (0), exact fqn (1), name prefix (2), substring elsewhere (3); ties broken by the shorter fqn.
 * Without a kind filter only structural nodes (modules, packages, types) are searched; methods and
 * fields only when requested.
 */
@Component
class NodeSearch(private val graphService: HierarchicalGraphService) {

    /** The `kindFilter` values that [search] accepts: the [JavaKinds.ALL_KINDS] values plus the group aliases. */
    val validKindValues: List<String> get() = JavaKinds.ALL_KINDS.map { it.value }
    val validAliases: List<String> get() = JavaKinds.ALL_ALIASES.toList()

    /** The entries of [kindFilter] that are neither a kind value nor a group alias. */
    fun invalidKinds(kindFilter: List<String>?): List<String> =
        kindFilter.orEmpty().filter { it !in validKindValues && it !in JavaKinds.ALL_ALIASES }

    /** The best [limit] matches for [name]; [kindFilter] must already be valid (see [invalidKinds]). */
    fun search(name: String, kindFilter: List<String>?, limit: Int): List<HGNode> {
        val expandedKinds = expandKindFilter(kindFilter)
        val kindMatches: (HGNode) -> Boolean = when (expandedKinds) {
            null -> { node -> node.kind != JavaNodeKind.METHOD && node.kind != JavaNodeKind.FIELD }
            else -> { node -> node.kind in expandedKinds }
        }
        val query = name.lowercase()

        val matches = ArrayList<RankedNode>()
        for (entry in graphService.searchIndex.entries) {
            if (!kindMatches(entry.node)) continue
            if (!entry.nameLower.contains(query) && !entry.fqnLower.contains(query)) continue
            val rank = when {
                entry.nameLower == query -> 0
                entry.fqnLower == query -> 1
                entry.nameLower.startsWith(query) -> 2
                else -> 3
            }
            matches.add(RankedNode(entry.node, rank, entry.fqnLower.length))
        }
        return matches.sortedWith(compareBy({ it.rank }, { it.fqnLength })).take(limit).map { it.node }
    }

    /** Expands [kindFilter] into concrete kinds; `null` when no filter is supplied (structural-node default). */
    private fun expandKindFilter(kindFilter: List<String>?): Set<JavaNodeKind>? {
        if (kindFilter.isNullOrEmpty()) return null
        val result = mutableSetOf<JavaNodeKind>()
        for (entry in kindFilter) {
            val expanded = JavaKinds.expandAlias(entry)
            if (expanded != null) result.addAll(expanded) else JavaNodeKind.fromValue(entry)?.let { result.add(it) }
        }
        return result
    }

    private data class RankedNode(val node: HGNode, val rank: Int, val fqnLength: Int)
}
