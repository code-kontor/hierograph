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
package io.hierograph.mcp.server.pagination

import org.springframework.ai.tool.execution.DefaultToolCallResultConverter

/**
 * Per-tool pagination configuration: the tool's name (stamped into issued cursors and checked on
 * incoming ones) and its page-size policy.
 *
 * @property tool the paginated tool's name, e.g. `"list_descendants"`.
 * @property defaultLimit the page size used when the caller supplies no `limit`.
 * @property maxLimit the server-side cap; a caller-supplied `limit` is clamped to `1..maxLimit`. A page holds
 *   at most this many items, and fewer when they would not fit [Paginator.RESPONSE_BUDGET] ([Paginator.respond]).
 */
data class PaginationSpec(
    val tool: String,
    val defaultLimit: Int,
    val maxLimit: Int,
) {
    init {
        require(defaultLimit in 1..maxLimit) { "$tool: defaultLimit $defaultLimit outside 1..$maxLimit" }
    }
}

/**
 * The outcome of a pagination request: either a [Page] to assemble into the tool's response, or a
 * [Failed] carrying the [CursorError] to return instead.
 */
sealed interface PageResult<out T> {

    /**
     * A single page of results plus the metadata a tool needs to build its `summary` and
     * `next_cursor`.
     *
     * @property items this page's slice of the full result list (already offset-and-limit applied).
     * @property total the true count of all matching results, across every page.
     * @property returned the number of items in this page (`items.size`).
     * @property truncated `true` when `total > returned` — a convenience flag; [nextCursor]'s
     *   presence is the authoritative "more pages exist" signal.
     * @property nextCursor the opaque cursor for the next page, or `null` when this is the last page.
     */
    data class Page<T>(
        val items: List<T>,
        val total: Int,
        val returned: Int,
        val truncated: Boolean,
        val nextCursor: String?
    ) : PageResult<T>

    /** The supplied cursor could not be used; [error] renders the structured response to return. */
    data class Failed(val error: CursorError) : PageResult<Nothing>
}

/**
 * Turns a fully-computed result list into one page, resolving any incoming cursor and minting the
 * next one. [respond] is the entry point the paginated tools call.
 *
 * The tool's job is to compute the complete, deterministically-ordered result list and to say how a page of
 * it is rendered; [respond] owns everything else — clamping the limit, decoding and validating the cursor,
 * slicing the page, keeping the rendered response within [RESPONSE_BUDGET], and encoding the next cursor.
 */
object Paginator {

    /**
     * Ceiling on a single response, in characters of its serialized form — what the MCP client receives.
     * Kept well under the point where a client stops putting a result into the conversation (Claude Code
     * saves a larger one to a file instead), so every page of a paged query arrives the same way.
     */
    const val RESPONSE_BUDGET = 50_000

    /** The converter Spring AI applies to a tool's return value: measuring with it counts what the client gets. */
    private val converter = DefaultToolCallResultConverter()

    /** The size of [response] as the client receives it, in characters. */
    fun sizeOf(response: Map<String, Any?>): Int = converter.convert(response, Map::class.java).length

    /**
     * Renders one page of [allItems] that fits [RESPONSE_BUDGET], or the error to return instead.
     *
     * The page starts where [cursor] points (or at the beginning) and holds up to `limit` items. [render] builds
     * the complete response for a [PageResult.Page] — the items with everything around them: the `nodes` map,
     * the summary, `next_cursor`. Its serialized size is measured, not estimated, and when it is over the budget
     * the page is shrunk to the largest item count that fits (a binary search over [render]); `next_cursor` then
     * points at the first item left out. A page may therefore hold fewer items than `limit`.
     *
     * When even a page without items is over the budget — the parts around them alone are too large — the
     * answer is a `RESPONSE_TOO_LARGE` error whose recovery is [tooLarge], the tool's advice on narrowing the
     * call. When a page without items fits but a single item does not, that single item is returned anyway
     * (over the budget) so that walking the cursor always makes progress.
     *
     * @param allItems the complete result list in the tool's stable iteration order.
     * @param spec the tool's pagination configuration.
     * @param queryHash the hash of the current request's query parameters ([QueryHash.of]).
     * @param dataHash the current graph data-snapshot fingerprint ([io.hierograph.server.graphservice.pagination.DataHashProvider]).
     * @param cursor the caller-supplied opaque cursor, or `null` to start from the beginning.
     * @param limit the caller-supplied page size, or `null` for [PaginationSpec.defaultLimit]; clamped to
     *   `1..`[PaginationSpec.maxLimit]. Not part of the query hash: the page size may vary between pages.
     * @param tooLarge the `recovery` text of the `RESPONSE_TOO_LARGE` error.
     * @param render builds the tool's response for a page.
     */
    fun <T> respond(
        allItems: List<T>,
        spec: PaginationSpec,
        queryHash: String,
        dataHash: String,
        cursor: String?,
        limit: Int?,
        tooLarge: String,
        render: (PageResult.Page<T>) -> Map<String, Any?>,
    ): Map<String, Any?> {
        val start = when (val offset = resolveOffset(cursor, spec, queryHash, dataHash)) {
            is Offset.Rejected -> return offset.error.toResponse()
            is Offset.At -> offset.value.coerceIn(0, allItems.size)
        }
        val most = minOf(effectiveLimit(spec, limit), allItems.size - start)
        fun response(count: Int) = render(pageOf(allItems, spec, queryHash, dataHash, start, count))

        val full = response(most)
        if (sizeOf(full) <= RESPONSE_BUDGET) return full
        // The largest count that fits, between 1 and most - 1 (most itself does not).
        var low = 1
        var high = most - 1
        var best: Map<String, Any?>? = null
        while (low <= high) {
            val count = (low + high) ushr 1
            val candidate = response(count)
            if (sizeOf(candidate) <= RESPONSE_BUDGET) {
                best = candidate
                low = count + 1
            } else {
                high = count - 1
            }
        }
        if (best != null) return best
        val empty = response(0)
        val emptySize = sizeOf(empty)
        if (emptySize > RESPONSE_BUDGET) return responseTooLarge(spec.tool, emptySize, tooLarge)
        return if (most >= 1) response(1) else empty
    }

    /**
     * Resolves a page of [allItems] by count alone, without rendering or measuring it. The tools use [respond];
     * this remains for checking a cursor where there is nothing to page.
     *
     * @param allItems the complete result list in the tool's stable iteration order.
     * @param spec the tool's pagination configuration.
     * @param queryHash the hash of the current request's query parameters ([QueryHash.of]).
     * @param dataHash the current graph data-snapshot fingerprint.
     * @param cursor the caller-supplied opaque cursor, or `null` to start from the beginning.
     * @param limit the caller-supplied page size, or `null` to use [PaginationSpec.defaultLimit];
     *   clamped to `1..`[PaginationSpec.maxLimit].
     */
    fun <T> paginate(
        allItems: List<T>,
        spec: PaginationSpec,
        queryHash: String,
        dataHash: String,
        cursor: String?,
        limit: Int?
    ): PageResult<T> {
        val start = when (val offset = resolveOffset(cursor, spec, queryHash, dataHash)) {
            is Offset.Rejected -> return PageResult.Failed(offset.error)
            // An offset past the end (e.g. data shrank under a hash collision) yields an empty page.
            is Offset.At -> offset.value.coerceIn(0, allItems.size)
        }
        val count = minOf(effectiveLimit(spec, limit), allItems.size - start)
        return pageOf(allItems, spec, queryHash, dataHash, start, count)
    }

    private sealed interface Offset {
        data class At(val value: Int) : Offset
        data class Rejected(val error: CursorError) : Offset
    }

    /** The offset [cursor] resumes from (0 without a cursor), or why the cursor cannot be used here. */
    private fun resolveOffset(cursor: String?, spec: PaginationSpec, queryHash: String, dataHash: String): Offset {
        if (cursor == null) return Offset.At(0)
        val decoded = try {
            CursorCodec.decode(cursor)
        } catch (e: CursorFormatException) {
            return Offset.Rejected(CursorError.InvalidFormat)
        }
        CursorValidator.validate(decoded, spec.tool, queryHash, dataHash)?.let { return Offset.Rejected(it) }
        return Offset.At(decoded.offset)
    }

    private fun effectiveLimit(spec: PaginationSpec, limit: Int?): Int = (limit ?: spec.defaultLimit).coerceIn(1, spec.maxLimit)

    /** The page of [count] items from [start], with the cursor to the item after it when there is one. */
    private fun <T> pageOf(
        allItems: List<T>, spec: PaginationSpec, queryHash: String, dataHash: String, start: Int, count: Int,
    ): PageResult.Page<T> {
        val total = allItems.size
        val end = start + count
        val nextCursor = if (end < total) {
            CursorCodec.encode(Cursor(Cursor.CURRENT_VERSION, spec.tool, queryHash, dataHash, end))
        } else {
            null
        }
        return PageResult.Page(
            items = allItems.subList(start, end).toList(),
            total = total,
            returned = count,
            truncated = total > count,
            nextCursor = nextCursor,
        )
    }

    private fun responseTooLarge(tool: String, size: Int, recovery: String): Map<String, Any?> = mapOf(
        "error" to linkedMapOf<String, Any?>(
            "code" to "RESPONSE_TOO_LARGE",
            "message" to "This $tool response would be $size characters even without any results, over the " +
                "$RESPONSE_BUDGET-character budget.",
            "response_size" to size,
            "budget" to RESPONSE_BUDGET,
            "recovery" to recovery,
        ),
    )
}
