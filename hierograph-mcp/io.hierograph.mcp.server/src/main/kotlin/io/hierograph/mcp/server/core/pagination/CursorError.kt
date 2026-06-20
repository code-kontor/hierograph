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
 * The ways a paginated request can be rejected, each carrying the data needed to render the
 * structured error response specified for pagination. Every case maps to one error `code` and
 * includes an explicit `recovery` path so the caller never has to guess what went wrong or what to
 * do next.
 *
 * [INVALID_CURSOR_FORMAT] is produced when [CursorCodec.decode] fails ([CursorFormatException]); the
 * cursor-validity cases are produced by [CursorValidator] when a structurally valid cursor is not
 * usable in the current context; [ResultTooLarge] is produced by [Paginator] when the resolved page
 * would overflow the caller's context.
 *
 * Each case renders via [toResponse] to the `{"error": { ... }}` map the tools return.
 */
sealed class CursorError {

    /** The error `code` string, e.g. `"STALE_CURSOR_DATA"`. */
    abstract val code: String

    /** Builds the structured `{"error": { ... }}` response for this error. */
    fun toResponse(): Map<String, Any?> = mapOf("error" to errorBody())

    protected abstract fun errorBody(): Map<String, Any?>

    /**
     * The cursor is corrupted or malformed — bad base64, non-JSON content, or a missing/mistyped field.
     */
    object InvalidFormat : CursorError() {
        override val code = "INVALID_CURSOR_FORMAT"
        override fun errorBody() = linkedMapOf<String, Any?>(
            "code" to code,
            "message" to "The cursor is corrupted or malformed.",
            "recovery" to RESTART
        )
    }

    /**
     * The cursor's [version] is a format this server no longer supports.
     */
    data class StaleVersion(
        val cursorVersion: Int,
        val supportedVersions: List<Int>
    ) : CursorError() {
        override val code = "STALE_CURSOR_VERSION"
        override fun errorBody() = linkedMapOf<String, Any?>(
            "code" to code,
            "message" to "The cursor was created with format version $cursorVersion, " +
                    "but this server only supports version${if (supportedVersions.size == 1) "" else "s"} " +
                    "${supportedVersions.joinToString()}.",
            "cursor_version" to cursorVersion,
            "supported_versions" to supportedVersions,
            "recovery" to RESTART
        )
    }

    /**
     * The cursor was issued by [issuedBy] but supplied to a call on [calledOn].
     */
    data class WrongTool(
        val issuedBy: String,
        val calledOn: String
    ) : CursorError() {
        override val code = "WRONG_TOOL_CURSOR"
        override fun errorBody() = linkedMapOf<String, Any?>(
            "code" to code,
            "message" to "This cursor was issued by '$issuedBy' but you called '$calledOn'.",
            "issued_by" to issuedBy,
            "called_on" to calledOn,
            "recovery" to "Use the cursor on the correct tool, or restart pagination on this tool " +
                    "by calling without a cursor."
        )
    }

    /**
     * The request's query parameters differ from those the cursor was issued for.
     */
    object StaleQuery : CursorError() {
        override val code = "STALE_CURSOR_QUERY"
        override fun errorBody() = linkedMapOf<String, Any?>(
            "code" to code,
            "message" to "The query parameters differ from those used when this cursor was issued. " +
                    "Pagination cannot continue with different parameters.",
            "recovery" to "To get more results for the new parameters, call without a cursor. " +
                    "To continue with the original parameters, restore them and retry."
        )
    }

    /**
     * The underlying graph data has changed since the cursor was issued, so its offset is meaningless.
     */
    object StaleData : CursorError() {
        override val code = "STALE_CURSOR_DATA"
        override fun errorBody() = linkedMapOf<String, Any?>(
            "code" to code,
            "message" to "The underlying graph data has changed since this cursor was issued. " +
                    "The cursor's position is no longer valid.",
            "recovery" to "Reissue your original query (without the cursor) to get results from the current data."
        )
    }

    /**
     * The resolved page's estimated wire size exceeds [Paginator.RESPONSE_BYTE_BUDGET] and would
     * overflow the caller's context. Not a cursor fault — the requested (or default) page is simply
     * too big for the node's edge/result density.
     */
    data class ResultTooLarge(
        val tool: String,
        val returned: Int,
        val estimatedBytes: Long,
        val budgetBytes: Int,
        val suggestedLimit: Int
    ) : CursorError() {
        override val code = "RESULT_TOO_LARGE"
        override fun errorBody() = linkedMapOf<String, Any?>(
            "code" to code,
            "message" to "This page (~$estimatedBytes bytes for $returned items) exceeds the " +
                    "$budgetBytes-byte response budget and would overflow the caller's context.",
            "returned" to returned,
            "estimated_bytes" to estimatedBytes,
            "budget_bytes" to budgetBytes,
            "suggested_limit" to suggestedLimit,
            "recovery" to "If you only need the ranking or breakdown, re-query with limit=1 and read " +
                    "the summary — these tools compute their summaries (e.g. by_target, by_source_type, " +
                    "by_kind) over the FULL result set, independent of page size, so a 1-item page still " +
                    "carries the complete answer. If you genuinely need the edges, lower the page size " +
                    "(e.g. limit=$suggestedLimit) and walk next_cursor. Prefer either over dumping the " +
                    "result to a file and slicing it."
        )
    }

    protected companion object {
        const val RESTART = "Restart pagination by calling the tool without a cursor parameter."
    }
}
