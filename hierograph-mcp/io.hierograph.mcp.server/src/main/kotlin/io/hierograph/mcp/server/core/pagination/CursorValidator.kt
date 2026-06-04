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
package io.hierograph.mcp.server.core.pagination

/**
 * Decides whether a structurally valid [Cursor] may be used to resume the current request.
 *
 * A decoded cursor still has to match the context it is being replayed in: it must be a format this
 * server understands, it must belong to the tool being called, and it must describe the same query
 * over the same data snapshot. Any mismatch means resuming from the cursor's offset would return
 * incoherent results, so the validator fails fast with a [CursorError] that names the problem and its
 * recovery path rather than silently re-interpreting the request.
 *
 * Validation is intentionally separate from decoding ([CursorCodec]): the codec turns bytes into a
 * [Cursor]; the validator judges whether that cursor is usable here. Both must pass before a tool
 * trusts the offset.
 */
object CursorValidator {

    /** Cursor format versions this server can resume from. */
    val SUPPORTED_VERSIONS: List<Int> = listOf(Cursor.CURRENT_VERSION)

    /**
     * Validates [cursor] against the context of the current call.
     *
     * Checks run in order of decreasing fundamentality — version, then tool, then query, then data —
     * so the reported error is the most basic mismatch. Returns `null` when the cursor is usable and
     * the caller may resume from [Cursor.offset]; otherwise returns the [CursorError] to surface.
     *
     * @param cursor the decoded cursor supplied by the caller.
     * @param tool the name of the tool now being called.
     * @param queryHash the hash of the current request's query parameters.
     * @param dataHash the identifier of the current graph data snapshot.
     */
    fun validate(
        cursor: Cursor,
        tool: String,
        queryHash: String,
        dataHash: String
    ): CursorError? {
        if (cursor.version !in SUPPORTED_VERSIONS) {
            return CursorError.StaleVersion(cursor.version, SUPPORTED_VERSIONS)
        }
        if (cursor.tool != tool) {
            return CursorError.WrongTool(issuedBy = cursor.tool, calledOn = tool)
        }
        if (cursor.queryHash != queryHash) {
            return CursorError.StaleQuery
        }
        if (cursor.dataHash != dataHash) {
            return CursorError.StaleData
        }
        return null
    }
}
