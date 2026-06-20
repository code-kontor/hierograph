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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaginatorTest {

    private val spec = PaginationSpec(tool = "list_descendants", defaultLimit = 4, maxLimit = 10, bytesPerItem = 250)
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

    // ── response-size guard ─────────────────────────────────────────────────

    // 10_000 bytes/item against the 50_000 budget => a page of 6+ items overflows; the largest
    // page that fits (the suggested limit) is 5.
    private val heavySpec = PaginationSpec(tool = "outgoing_dependencies", defaultLimit = 100, maxLimit = 50, bytesPerItem = 10_000)

    @Test
    fun `a page over the byte budget is rejected with ResultTooLarge`() {
        val result = Paginator.paginate(items(100), heavySpec, qh, dh, cursor = null, limit = 6)

        assertThat(result).isInstanceOf(PageResult.Failed::class.java)
        val error = (result as PageResult.Failed).error
        assertThat(error).isInstanceOf(CursorError.ResultTooLarge::class.java)
        error as CursorError.ResultTooLarge
        assertThat(error.code).isEqualTo("RESULT_TOO_LARGE")
        assertThat(error.returned).isEqualTo(6)
        assertThat(error.estimatedBytes).isEqualTo(60_000L)
        assertThat(error.budgetBytes).isEqualTo(Paginator.RESPONSE_BYTE_BUDGET)
        assertThat(error.suggestedLimit).isEqualTo(5)
    }

    @Test
    fun `the suggested limit yields a page that fits the budget`() {
        val p = page(Paginator.paginate(items(100), heavySpec, qh, dh, null, limit = 5))
        assertThat(p.returned).isEqualTo(5) // 5 * 10_000 == 50_000, at the budget, not over
    }

    @Test
    fun `a single item is never rejected even when one item exceeds the budget`() {
        // limit=1 is the summary escape hatch — it must always come back, even for a huge item.
        val hugeSpec = heavySpec.copy(bytesPerItem = Paginator.RESPONSE_BYTE_BUDGET + 1)
        val p = page(Paginator.paginate(items(100), hugeSpec, qh, dh, null, limit = 1))
        assertThat(p.returned).isEqualTo(1)
        assertThat(p.nextCursor).isNotNull()
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
