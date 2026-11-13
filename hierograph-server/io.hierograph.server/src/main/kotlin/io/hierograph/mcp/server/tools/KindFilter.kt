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

import io.hierograph.server.views.KindAliases
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.server.views.ViewGroups

/**
 * The `kindFilter` parameter shared by `find_node`, `list_children`, `list_descendants` and
 * `affected_by`: one parser and one `INVALID_KIND` error, so every tool accepts the same values and
 * rejects the same mistakes the same way.
 *
 * A filter holds kind values ([FILTER_KINDS]: the Java kinds, `external.type` and a view's `view.group`) and group aliases
 * ([KindAliases.ALL]), mixed freely. An absent or empty filter is no filter. A tool whose results
 * can only be of some kinds passes them as `allowed`; a value or alias outside them is rejected rather
 * than silently matching nothing.
 */
object KindFilter {

    /** Every kind a filter can name: the Java node kinds, the external-type kind and the group kind of views. */
    val FILTER_KINDS: Set<Any> = JavaNodeKind.NODE_KINDS + JavaNodeKind.EXTERNAL_TYPE + ViewGroups.KIND

    /** The kinds a type-level result can have: the Java type kinds and `external.type`. */
    val TYPE_RESULT_KINDS: Set<Any> = JavaNodeKind.TYPES + JavaNodeKind.EXTERNAL_TYPE

    /** The outcome of [parse] for a non-empty filter. */
    sealed interface Parsed

    /** Every entry is an allowed kind or alias; [kinds] is the expanded set (JavaNodeKind values and strings). */
    data class Valid(val kinds: Set<Any>) : Parsed

    /** The entries that are not allowed kind values or aliases, in input order. */
    data class Invalid(val values: List<String>, val allowed: Set<Any>) : Parsed

    /** Parses [kindFilter] against [allowed]; `null` means no filter (absent or empty). */
    fun parse(kindFilter: List<String>?, allowed: Set<Any> = FILTER_KINDS): Parsed? {
        if (kindFilter.isNullOrEmpty()) return null
        val kinds = mutableSetOf<Any>()
        val invalid = mutableListOf<String>()
        for (entry in kindFilter) {
            val expanded = KindAliases.expand(entry) ?: kindOf(entry)?.let { listOf(it) }
            if (expanded != null && allowed.containsAll(expanded)) kinds.addAll(expanded) else invalid.add(entry)
        }
        return if (invalid.isEmpty()) Valid(kinds) else Invalid(invalid, allowed)
    }

    /** The kind object for a filter value: a JavaNodeKind, the external-type or view-group string, or `null`. */
    private fun kindOf(value: String): Any? = when (value) {
        JavaNodeKind.EXTERNAL_TYPE -> JavaNodeKind.EXTERNAL_TYPE
        ViewGroups.KIND -> ViewGroups.KIND
        else -> JavaNodeKind.fromValue(value)?.takeIf { it in JavaNodeKind.NODE_KINDS }
    }

    /** The values [allowed] admits, in the order of [FILTER_KINDS]. */
    fun validKinds(allowed: Set<Any> = FILTER_KINDS): List<String> = FILTER_KINDS.filter { it in allowed }.map { it.toString() }

    /** The aliases whose whole expansion lies in [allowed]. */
    fun validAliases(allowed: Set<Any> = FILTER_KINDS): List<String> =
        KindAliases.ALL.filter { allowed.containsAll(KindAliases.expand(it)!!) }

    /** The `INVALID_KIND` error for [invalid] entries, listing what is valid instead. */
    fun invalidResponse(invalid: List<String>, allowed: Set<Any> = FILTER_KINDS): Map<String, Any?> = mapOf(
        "error" to linkedMapOf<String, Any?>(
            "code" to "INVALID_KIND",
            "message" to "Unknown kind${if (invalid.size > 1) "s" else ""}: ${invalid.joinToString { "'$it'" }}. " +
                    "Valid kinds: ${validKinds(allowed).joinToString()}. Group aliases: ${validAliases(allowed).joinToString()}.",
            "invalid_values" to invalid,
            "valid_kinds" to validKinds(allowed),
            "valid_aliases" to validAliases(allowed),
            "recovery" to "Use values from valid_kinds or valid_aliases, or omit kindFilter."
        )
    )

    /** The `INVALID_KIND` error for an [Invalid] parse. */
    fun invalidResponse(invalid: Invalid): Map<String, Any?> = invalidResponse(invalid.values, invalid.allowed)
}
