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
package io.hierograph.mcp.server.tools.navigation

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.MemberModifiers
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.server.mapping.GraphStoreNodeSource

/**
 * The `modifierFilter` of `list_children` and `list_descendants`: keeps the methods and fields whose
 * modifiers include every requested value (AND logic). Other nodes carry no member modifiers and never
 * match, so an active filter restricts the result to members.
 *
 * Values are matched case-insensitively against the vocabulary [MemberModifiers] decodes: a visibility
 * (`public`, `protected`, `private`, `package-private`) plus the method or field modifiers.
 */
class ModifierFilter private constructor(private val required: Set<String>) {

    /** `true` when [node] is a method or field whose modifiers include every required value. */
    fun matches(node: HGNode): Boolean {
        val bits = (node.nodeSource as? GraphStoreNodeSource)?.modifiers ?: return false
        val modifiers = when (node.kind) {
            JavaKinds.METHOD -> MemberModifiers.methodModifiers(bits)
            JavaKinds.FIELD -> MemberModifiers.fieldModifiers(bits)
            else -> return false
        }
        return modifiers.containsAll(required)
    }

    companion object {

        /** Every value a filter may name: the modifiers [MemberModifiers] can decode for a method or field. */
        val VALID_MODIFIERS: List<String> = listOf(
            "public", "protected", "private", "package-private",
            "static", "final", "abstract", "synchronized", "native", "default", "transient", "volatile",
            "bridge", "synthetic"
        )

        /**
         * Parses the [modifierFilter] parameter. Returns `null` for no filter (absent or empty), or the
         * values that are not in [VALID_MODIFIERS] as [Invalid].
         */
        fun parse(modifierFilter: List<String>?): Parsed? {
            if (modifierFilter.isNullOrEmpty()) return null
            val normalized = modifierFilter.map { it.trim().lowercase() }
            val invalid = modifierFilter.filterIndexed { i, _ -> normalized[i] !in VALID_MODIFIERS }
            return if (invalid.isEmpty()) Valid(ModifierFilter(normalized.toSet())) else Invalid(invalid)
        }

        /** The canonical form of [modifierFilter] for a query hash: normalized, de-duplicated and sorted. */
        fun canonical(modifierFilter: List<String>?): List<String>? =
            modifierFilter?.takeIf { it.isNotEmpty() }?.map { it.trim().lowercase() }?.distinct()?.sorted()

        /** The structured `INVALID_PARAMETER` error for unknown modifier values. */
        fun invalidResponse(invalid: List<String>): Map<String, Any?> = mapOf(
            "error" to linkedMapOf<String, Any?>(
                "code" to "INVALID_PARAMETER",
                "message" to "Unknown modifier${if (invalid.size > 1) "s" else ""} ${invalid.joinToString { "'$it'" }}. " +
                        "Valid modifiers: ${VALID_MODIFIERS.joinToString()}.",
                "recovery" to "Use valid modifier values, or omit modifierFilter."
            )
        )
    }

    /** The outcome of [parse]. */
    sealed interface Parsed
    data class Valid(val filter: ModifierFilter) : Parsed
    data class Invalid(val values: List<String>) : Parsed
}
