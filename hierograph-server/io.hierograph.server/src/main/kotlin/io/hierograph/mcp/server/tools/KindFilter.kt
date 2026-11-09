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
package io.hierograph.mcp.server.tools

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind

/**
 * The `kindFilter` parameter shared by `find_node`, `list_children`, `list_descendants` and
 * `affected_by`: one parser and one `INVALID_KIND` error, so every tool accepts the same values and
 * rejects the same mistakes the same way.
 *
 * A filter holds kind values ([JavaKinds.ALL_KINDS]) and group aliases ([JavaKinds.ALL_ALIASES]), mixed
 * freely. An absent or empty filter is no filter.
 */
object KindFilter {

    /** The outcome of [parse] for a non-empty filter. */
    sealed interface Parsed

    /** Every entry is a kind or an alias; [kinds] is the expanded set. */
    data class Valid(val kinds: Set<JavaNodeKind>) : Parsed

    /** The entries that are neither a kind value nor an alias, in input order. */
    data class Invalid(val values: List<String>) : Parsed

    val validKinds: List<String> get() = JavaKinds.ALL_KINDS.map { it.value }
    val validAliases: List<String> get() = JavaKinds.ALL_ALIASES.toList()

    /** Parses [kindFilter]; `null` means no filter (absent or empty). */
    fun parse(kindFilter: List<String>?): Parsed? {
        if (kindFilter.isNullOrEmpty()) return null
        val kinds = mutableSetOf<JavaNodeKind>()
        val invalid = mutableListOf<String>()
        for (entry in kindFilter) {
            val expanded = JavaKinds.expandAlias(entry)
            when {
                expanded != null -> kinds.addAll(expanded)
                else -> JavaNodeKind.fromValue(entry)?.takeIf { it in JavaKinds.ALL_KINDS }?.let { kinds.add(it) }
                    ?: invalid.add(entry)
            }
        }
        return if (invalid.isEmpty()) Valid(kinds) else Invalid(invalid)
    }

    /** The `INVALID_KIND` error for [invalid] entries, listing what is valid instead. */
    fun invalidResponse(invalid: List<String>): Map<String, Any?> = mapOf(
        "error" to linkedMapOf<String, Any?>(
            "code" to "INVALID_KIND",
            "message" to "Unknown kind${if (invalid.size > 1) "s" else ""}: ${invalid.joinToString { "'$it'" }}. " +
                    "Valid kinds: ${validKinds.joinToString()}. Group aliases: ${validAliases.joinToString()}.",
            "invalid_values" to invalid,
            "valid_kinds" to validKinds,
            "valid_aliases" to validAliases,
            "recovery" to "Use values from valid_kinds or valid_aliases, or omit kindFilter."
        )
    )
}
