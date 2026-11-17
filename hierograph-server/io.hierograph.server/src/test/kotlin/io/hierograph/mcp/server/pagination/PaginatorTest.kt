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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaginatorTest {

    private val spec = PaginationSpec(tool = "list_descendants", defaultLimit = 4, maxLimit = 10)
    private val qh = "query-hash"
    private val dh = "data-hash"

    private fun items(n: Int): List<Int> = (0 until n).toList()

    private fun page(result: PageResult<Int>): PageResult.Page<Int> {
        assertThat(result).isInstanceOf(PageResult.Page::class.java)
        return result as PageResult.Page<Int>
    }

    @Test
    fun `first page slices from the start and offers a next cursor`() {
        val p = page(Paginator.paginate(items(10), spec, qh, dh, cursor = null, limit = null))
        assertThat(p.items).containsExactly(0, 1, 2, 3)
        assertThat(p.total).isEqualTo(10)
        assertThat(p.returned).isEqualTo(4)
        assertThat(p.truncated).isTrue()
        assertThat(p.nextCursor).isNotNull()

        // the next cursor resumes at offset 4 and carries this tool + query + data identity
        val decoded = CursorCodec.decode(p.nextCursor!!)
        assertThat(decoded.offset).isEqualTo(4)
        assertThat(decoded.tool).isEqualTo(spec.tool)
        assertThat(decoded.queryHash).isEqualTo(qh)
        assertThat(decoded.dataHash).isEqualTo(dh)
    }

    @Test
    fun `following the cursor walks the whole list exactly once`() {
        val all = items(10)
        val seen = mutableListOf<Int>()
        var cursor: String? = null
        var pages = 0
        do {
            val p = page(Paginator.paginate(all, spec, qh, dh, cursor, limit = null))
            seen.addAll(p.items)
            cursor = p.nextCursor
            pages++
        } while (cursor != null)

        assertThat(seen).isEqualTo(all)
        assertThat(pages).isEqualTo(3) // 4 + 4 + 2
    }

    @Test
    fun `the last page omits the next cursor`() {
        // offset 8 of 10 with limit 4 -> items [8,9], nothing after
        val first = page(Paginator.paginate(items(10), spec, qh, dh, null, null))
        val second = page(Paginator.paginate(items(10), spec, qh, dh, first.nextCursor, null))
        val third = page(Paginator.paginate(items(10), spec, qh, dh, second.nextCursor, null))
        assertThat(third.items).containsExactly(8, 9)
        assertThat(third.nextCursor).isNull()
    }

    @Test
    fun `a result set that fits in one page has no cursor and is not truncated`() {
        val p = page(Paginator.paginate(items(4), spec, qh, dh, null, limit = 4))
        assertThat(p.returned).isEqualTo(4)
        assertThat(p.truncated).isFalse()
        assertThat(p.nextCursor).isNull()
    }

    @Test
    fun `an empty result set yields an empty, untruncated page`() {
        val p = page(Paginator.paginate(items(0), spec, qh, dh, null, null))
        assertThat(p.items).isEmpty()
        assertThat(p.total).isEqualTo(0)
        assertThat(p.truncated).isFalse()
        assertThat(p.nextCursor).isNull()
    }

    @Test
    fun `limit defaults to the spec default and is capped at the spec max`() {
        assertThat(page(Paginator.paginate(items(100), spec, qh, dh, null, limit = null)).returned)
            .isEqualTo(spec.defaultLimit)
        assertThat(page(Paginator.paginate(items(100), spec, qh, dh, null, limit = 999)).returned)
            .isEqualTo(spec.maxLimit)
        // non-positive limits clamp up to 1 rather than producing an empty page
        assertThat(page(Paginator.paginate(items(100), spec, qh, dh, null, limit = 0)).returned)
            .isEqualTo(1)
    }

    @Test
    fun `the page size may change between pages of the same query`() {
        val first = page(Paginator.paginate(items(20), spec, qh, dh, null, limit = 4))
        assertThat(first.returned).isEqualTo(4)
        // resume the same query with a larger page — limit is not part of the cursor's identity
        val second = page(Paginator.paginate(items(20), spec, qh, dh, first.nextCursor, limit = 10))
        assertThat(second.items.first()).isEqualTo(4)
        assertThat(second.returned).isEqualTo(10)
    }

    // ── response budget ─────────────────────────────────────────────────────

    /** A tool's render: a fixed header, the page's items and the page counts, like the paginated tools. */
    private fun render(header: Int) = { page: PageResult.Page<String> ->
        linkedMapOf<String, Any?>(
            "header" to "h".repeat(header),
            "items" to page.items,
            "summary" to mapOf("total" to page.total, "returned" to page.returned, "truncated" to page.truncated),
        ).apply { page.nextCursor?.let { put("next_cursor", it) } }
    }

    private val wide = PaginationSpec(tool = "pairwise_dependencies", defaultLimit = 100, maxLimit = 500)

    /** [n] items of [width] characters each, numbered so they stay distinguishable. */
    private fun strings(n: Int, width: Int): List<String> = (0 until n).map { i -> i.toString().padStart(width, 'x') }

    @Suppress("UNCHECKED_CAST")
    private fun itemsOf(response: Map<String, Any?>) = response["items"] as List<String>

    @Test
    fun `a page within the budget is returned whole`() {
        val response = Paginator.respond(strings(10, 100), wide, qh, dh, null, 10, "narrow it", render(header = 100))
        assertThat(itemsOf(response)).hasSize(10)
        assertThat(response).doesNotContainKey("next_cursor")
    }

    @Test
    fun `a page over the budget is shrunk to the most items that fit, counted with everything around them`() {
        // 100 items of 1,000 characters are twice the budget; the 10,000-character header counts too.
        val all = strings(100, 1_000)
        val response = Paginator.respond(all, wide, qh, dh, null, 100, "narrow it", render(header = 10_000))
        val returned = itemsOf(response).size

        assertThat(Paginator.sizeOf(response)).isLessThanOrEqualTo(Paginator.RESPONSE_BUDGET)
        assertThat(returned).isBetween(30, 40)
        assertThat(itemsOf(response)).isEqualTo(all.take(returned))
        // One more item would not have fitted.
        val oneMore = render(header = 10_000)(PageResult.Page(all.take(returned + 1), 100, returned + 1, true, "c"))
        assertThat(Paginator.sizeOf(oneMore)).isGreaterThan(Paginator.RESPONSE_BUDGET)
        // The summary reports the shrunk page, and the cursor continues after it.
        assertThat((response["summary"] as Map<*, *>)["returned"]).isEqualTo(returned)
        assertThat(CursorCodec.decode(response["next_cursor"] as String).offset).isEqualTo(returned)
    }

    @Test
    fun `walking the cursor through shrunk pages returns every item exactly once, each page within the budget`() {
        val all = strings(300, 700)
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val response = Paginator.respond(all, wide, qh, dh, cursor, 500, "narrow it", render(header = 2_000))
            assertThat(Paginator.sizeOf(response)).isLessThanOrEqualTo(Paginator.RESPONSE_BUDGET)
            seen += itemsOf(response)
            cursor = response["next_cursor"] as String?
        } while (cursor != null)
        assertThat(seen).isEqualTo(all)
    }

    @Test
    fun `a response whose fixed part alone is over the budget is refused with the tool's advice`() {
        val response = Paginator.respond(strings(5, 10), wide, qh, dh, null, 5, "Pass includeNodes=false.", render(header = 60_000))
        val error = response["error"] as Map<*, *>
        assertThat(error["code"]).isEqualTo("RESPONSE_TOO_LARGE")
        assertThat(error["recovery"]).isEqualTo("Pass includeNodes=false.")
        assertThat(error["budget"]).isEqualTo(Paginator.RESPONSE_BUDGET)
        assertThat(error["response_size"] as Int).isGreaterThan(Paginator.RESPONSE_BUDGET)
    }

    @Test
    fun `a single item larger than the budget is still returned, so the walk makes progress`() {
        val all = listOf("a".repeat(60_000), "b")
        val response = Paginator.respond(all, wide, qh, dh, null, 2, "narrow it", render(header = 10))
        assertThat(itemsOf(response)).containsExactly(all[0])
        assertThat(CursorCodec.decode(response["next_cursor"] as String).offset).isEqualTo(1)
    }

    @Test
    fun `an unusable cursor is answered with its error`() {
        val response = Paginator.respond(strings(5, 10), wide, qh, dh, "!!!not-base64!!!", null, "narrow it", render(header = 10))
        assertThat((response["error"] as Map<*, *>)["code"]).isEqualTo("INVALID_CURSOR_FORMAT")
    }

    @Test
    fun `a limit above the cap is clamped to the cap`() {
        val p = page(Paginator.paginate(items(100), spec, qh, dh, null, limit = 1_000))
        assertThat(p.returned).isEqualTo(spec.maxLimit)
    }

    @Test
    fun `a malformed cursor fails with InvalidFormat`() {
        val result = Paginator.paginate(items(10), spec, qh, dh, cursor = "!!!not-base64!!!", limit = null)
        assertThat(result).isEqualTo(PageResult.Failed(CursorError.InvalidFormat))
    }

    @Test
    fun `a cursor from another tool fails with WrongTool`() {
        val foreign = CursorCodec.encode(Cursor(Cursor.CURRENT_VERSION, "affected_by", qh, dh, 4))
        val result = Paginator.paginate(items(10), spec, qh, dh, foreign, null)
        assertThat(result).isInstanceOf(PageResult.Failed::class.java)
        assertThat((result as PageResult.Failed).error).isInstanceOf(CursorError.WrongTool::class.java)
    }

    @Test
    fun `a cursor for different query parameters fails with StaleQuery`() {
        val first = page(Paginator.paginate(items(10), spec, qh, dh, null, null))
        val result = Paginator.paginate(items(10), spec, "different-query", dh, first.nextCursor, null)
        assertThat((result as PageResult.Failed).error).isEqualTo(CursorError.StaleQuery)
    }

    @Test
    fun `a cursor against changed data fails with StaleData`() {
        val first = page(Paginator.paginate(items(10), spec, qh, dh, null, null))
        val result = Paginator.paginate(items(10), spec, qh, "different-data", first.nextCursor, null)
        assertThat((result as PageResult.Failed).error).isEqualTo(CursorError.StaleData)
    }
}
