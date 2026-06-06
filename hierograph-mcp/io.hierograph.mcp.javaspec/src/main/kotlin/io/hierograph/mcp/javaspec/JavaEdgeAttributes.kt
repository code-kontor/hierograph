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

/**
 * Bit positions and helpers for the Java provider's type-level edge attributes,
 * stored as a bitmap in `CoreDependency.attributesBitmap`.
 *
 * Each constant defines one bit position. Use the helper methods to read/write
 * individual flags or to convert between the bitmap and a `Map<String, Boolean>`
 * for the MCP response.
 */
object JavaEdgeAttributes {

    // ── bit positions ─────────────────────────────────────────────────
    const val IS_EXTENDS = 0
    const val IS_IMPLEMENTS = 1
    const val IS_ANNOTATED_BY = 2
    const val IS_DEPENDS_ON_OTHER = 3

    /** All defined attribute entries: bit position to JSON key name. */
    @JvmField
    val ALL: List<Pair<Int, String>> = listOf(
        IS_EXTENDS to "is_extends",
        IS_IMPLEMENTS to "is_implements",
        IS_ANNOTATED_BY to "is_annotated_by",
        IS_DEPENDS_ON_OTHER to "is_depends_on_other"
    )

    // ── single-bit operations ─────────────────────────────────────────

    /** Returns `true` if the bit at [position] is set in [bitmap]. */
    @JvmStatic
    fun isSet(bitmap: Int, position: Int): Boolean =
        (bitmap and (1 shl position)) != 0

    /** Returns a new bitmap with the bit at [position] set to 1. */
    @JvmStatic
    fun set(bitmap: Int, position: Int): Int =
        bitmap or (1 shl position)

    /** Returns a new bitmap with the bit at [position] cleared to 0. */
    @JvmStatic
    fun clear(bitmap: Int, position: Int): Int =
        bitmap and (1 shl position).inv()

    /** Returns a new bitmap with the bit at [position] set to [value]. */
    @JvmStatic
    fun set(bitmap: Int, position: Int, value: Boolean): Int =
        if (value) set(bitmap, position) else clear(bitmap, position)

    // ── bulk operations ───────────────────────────────────────────────

    /** Converts [bitmap] to a `Map<String, Boolean>` with all defined attributes. */
    @JvmStatic
    fun toMap(bitmap: Int): Map<String, Boolean> =
        ALL.associate { (pos, name) -> name to isSet(bitmap, pos) }

    /** Converts [bitmap] to a `Map<String, Boolean>` containing only the `true` entries. */
    @JvmStatic
    fun toTrueMap(bitmap: Int): Map<String, Boolean> =
        ALL.filter { (pos, _) -> isSet(bitmap, pos) }
            .associate { (_, name) -> name to true }
}
