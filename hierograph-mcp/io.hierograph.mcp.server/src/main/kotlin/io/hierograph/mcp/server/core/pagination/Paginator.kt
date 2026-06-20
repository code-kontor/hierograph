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
 * Per-tool pagination configuration: the tool's name (stamped into issued cursors and checked on
 * incoming ones) and its page-size policy.
 *
 * @property tool the paginated tool's name, e.g. `"list_descendants"`.
 * @property defaultLimit the page size used when the caller supplies no `limit`.
 * @property maxLimit the server-side cap; a caller-supplied `limit` is clamped to `1..maxLimit`.
 * @property bytesPerItem rough wire-size estimate for one result item, used by [Paginator] to reject
 *   a page that would overflow the caller's context before the harness truncates it
 *   ([Paginator.RESPONSE_BYTE_BUDGET]).
 */
data class PaginationSpec(
    val tool: String,
    val defaultLimit: Int,
    val maxLimit: Int,
    val bytesPerItem: Int
)

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
 * next one. This is the single pagination entry point the paginated tools call.
 *
 * The tool's job is to compute the complete, deterministically-ordered result list and hand it here;
 * [paginate] owns everything cursor-related — clamping the limit, decoding and validating the cursor,
 * slicing the page, and encoding the next cursor. The tool then maps [Page.items] to its wire shape
 * and splices [Page.total]/[Page.returned]/[Page.truncated] into its `summary`, adding `next_cursor`
 * when [Page.nextCursor] is non-null.
 */
object Paginator {

    /**
     * Soft ceiling on a single page's estimated wire size. A page whose `returned * bytesPerItem`
     * exceeds this is rejected with [CursorError.ResultTooLarge] rather than handed back to overflow
     * the caller's context — at which point the harness would truncate it and offer generic
     * "save-to-file and slice" advice. Kept well under that truncation point so a maxed-out page on a
     * dense node fails fast with a domain-specific recovery (use `limit=1` + the full-set summary, or
     * a smaller page) instead. A single item (`limit=1`) is always allowed through, so the
     * summary-only escape hatch can never itself be blocked.
     */
    const val RESPONSE_BYTE_BUDGET = 50_000

    /**
     * Resolves a page of [allItems].
     *
     * @param allItems the complete result list in the tool's stable iteration order.
     * @param spec the tool's pagination configuration.
     * @param queryHash the hash of the current request's query parameters ([QueryHash.of]).
     * @param dataHash the current graph data-snapshot fingerprint ([DataHash]).
     * @param cursor the caller-supplied opaque cursor, or `null` to start from the beginning.
     * @param limit the caller-supplied page size, or `null` to use [PaginationSpec.defaultLimit];
     *   clamped to `1..`[PaginationSpec.maxLimit]. Deliberately not covered by the query hash, so the
     *   page size may legitimately vary between pages of the same query.
     */
    fun <T> paginate(
        allItems: List<T>,
        spec: PaginationSpec,
        queryHash: String,
        dataHash: String,
        cursor: String?,
        limit: Int?
    ): PageResult<T> {

        // ── resolve the starting offset from the cursor (if any) ──────────
        val offset: Int = if (cursor != null) {
            val decoded = try {
                CursorCodec.decode(cursor)
            } catch (e: CursorFormatException) {
                return PageResult.Failed(CursorError.InvalidFormat)
            }
            CursorValidator.validate(decoded, spec.tool, queryHash, dataHash)
                ?.let { return PageResult.Failed(it) }
            decoded.offset
        } else {
            0
        }

        val total = allItems.size
        val effectiveLimit = (limit ?: spec.defaultLimit).coerceIn(1, spec.maxLimit)

        // An offset past the end (e.g. data shrank under a hash collision) yields an empty page
        // rather than an exception.
        val start = offset.coerceIn(0, total)
        val end = minOf(start + effectiveLimit, total)

        val items = allItems.subList(start, end).toList()
        val returned = items.size

        // ── response-size guard ───────────────────────────────────────────
        // Reject an oversized page up front (but never a single item, so limit=1 always succeeds and
        // the full-set summary stays reachable). suggestedLimit is the largest page that fits the
        // budget, and is strictly smaller than `returned` whenever this fires.
        val estimatedBytes = returned.toLong() * spec.bytesPerItem
        if (returned > 1 && estimatedBytes > RESPONSE_BYTE_BUDGET) {
            val suggestedLimit = (RESPONSE_BYTE_BUDGET / spec.bytesPerItem).coerceAtLeast(1)
            return PageResult.Failed(
                CursorError.ResultTooLarge(
                    tool = spec.tool,
                    returned = returned,
                    estimatedBytes = estimatedBytes,
                    budgetBytes = RESPONSE_BYTE_BUDGET,
                    suggestedLimit = suggestedLimit
                )
            )
        }

        val hasMore = end < total

        val nextCursor = if (hasMore) {
            CursorCodec.encode(Cursor(Cursor.CURRENT_VERSION, spec.tool, queryHash, dataHash, end))
        } else {
            null
        }

        return PageResult.Page(
            items = items,
            total = total,
            returned = returned,
            truncated = total > returned,
            nextCursor = nextCursor
        )
    }
}
