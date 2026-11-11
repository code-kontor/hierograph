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
import io.hierograph.server.views.ViewGroups
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
    val validKindValues: List<String> get() = JavaKinds.ALL_KINDS.map { it.value } + JavaKinds.EXTERNAL_TYPE + ViewGroups.KIND
    val validAliases: List<String> get() = JavaKinds.ALL_ALIASES.toList()

    /** The entries of [kindFilter] that are neither a kind value nor a group alias. */
    fun invalidKinds(kindFilter: List<String>?): List<String> =
        kindFilter.orEmpty().filter { it !in validKindValues && it !in JavaKinds.ALL_ALIASES }

    /** The best [limit] matches for [name] and the number of all matches. */
    data class Result(val nodes: List<HGNode>, val total: Int)

    /** The best [limit] matches for [name]; [kindFilter] must already be valid (see [invalidKinds]). */
    fun search(name: String, kindFilter: List<String>?, limit: Int): List<HGNode> = find(name, kindFilter, limit).nodes

    /**
     * The best [limit] matches for [name], plus how many nodes match in all, so a caller can tell a
     * complete answer from a cut one. [kindFilter] must already be valid (see [invalidKinds]).
     */
    fun find(name: String, kindFilter: List<String>?, limit: Int): Result {
        val expandedKinds = expandKindFilter(kindFilter)
        val kindMatches: (HGNode) -> Boolean = when (expandedKinds) {
            null -> { node -> node.kind != JavaNodeKind.METHOD && node.kind != JavaNodeKind.FIELD }
            else -> { node -> node.kind in expandedKinds }
        }
        val query = CaseInsensitive(name)

        val matches = ArrayList<RankedNode>()
        for (entry in graphService.searchIndex.entries) {
            if (!kindMatches(entry.node)) continue
            if (!query.isIn(entry.name) && !query.isIn(entry.fqn)) continue
            val rank = when {
                query.equalsTo(entry.name) -> 0
                query.equalsTo(entry.fqn) -> 1
                query.startsOf(entry.name) -> 2
                else -> 3
            }
            matches.add(RankedNode(entry.node, rank, entry.fqn.length))
        }
        val best = matches.sortedWith(compareBy({ it.rank }, { it.fqnLength })).take(limit).map { it.node }
        return Result(best, matches.size)
    }

    /** Expands [kindFilter] into concrete kinds; `null` when no filter is supplied (structural-node default). */
    private fun expandKindFilter(kindFilter: List<String>?): Set<Any>? {
        if (kindFilter.isNullOrEmpty()) return null
        val result = mutableSetOf<Any>()
        for (entry in kindFilter) {
            val expanded = JavaKinds.expandAlias(entry)
            when {
                expanded != null -> result.addAll(expanded)
                entry == JavaKinds.EXTERNAL_TYPE -> result.add(JavaKinds.EXTERNAL_TYPE)
                entry == ViewGroups.KIND -> result.add(ViewGroups.KIND)
                else -> JavaNodeKind.fromValue(entry)?.let { result.add(it) }
            }
        }
        return result
    }

    private data class RankedNode(val node: HGNode, val rank: Int, val fqnLength: Int)

    /**
     * A query matched case-insensitively against the index's original strings, so the index needs no
     * lower-cased copies. The query's lower- and upper-case forms are prepared once; a candidate character
     * matches when it equals either, which avoids converting every character of every candidate.
     */
    internal class CaseInsensitive(query: String) {
        private val lower = CharArray(query.length) { Character.toLowerCase(query[it]) }
        private val upper = CharArray(query.length) { Character.toUpperCase(query[it]) }

        private fun matchesAt(text: String, start: Int): Boolean {
            for (j in lower.indices) {
                val c = text[start + j]
                if (c != lower[j] && c != upper[j]) return false
            }
            return true
        }

        /**
         * Whether the query occurs anywhere in [text]. The JVM's vectorized `indexOf` finds the candidate
         * positions of the query's first character in either case; only there is the rest compared.
         */
        fun isIn(text: String): Boolean {
            if (lower.isEmpty()) return true
            val last = text.length - lower.size
            if (last < 0) return false
            val first = lower[0]
            val firstUpper = upper[0]
            var nextLower = text.indexOf(first)
            var nextUpper = if (firstUpper == first) -1 else text.indexOf(firstUpper)
            while (true) {
                val i = when {
                    nextLower < 0 -> nextUpper
                    nextUpper < 0 -> nextLower
                    else -> minOf(nextLower, nextUpper)
                }
                if (i < 0 || i > last) return false
                if (matchesAt(text, i)) return true
                if (i == nextLower) nextLower = text.indexOf(first, i + 1) else nextUpper = text.indexOf(firstUpper, i + 1)
            }
        }

        fun equalsTo(text: String): Boolean = text.length == lower.size && matchesAt(text, 0)

        fun startsOf(text: String): Boolean = text.length >= lower.size && matchesAt(text, 0)
    }
}
