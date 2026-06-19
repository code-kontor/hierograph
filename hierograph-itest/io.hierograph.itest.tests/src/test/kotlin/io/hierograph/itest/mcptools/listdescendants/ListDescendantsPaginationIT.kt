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
package io.hierograph.itest.mcptools.listdescendants

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `list_descendants` — pagination: how a result set larger than the page limit is split into pages,
 * and how following `next_cursor` walks the whole set exactly once.
 */
class ListDescendantsPaginationIT : AbstractListDescendantsIT() {

    @Test
    fun `a subtree larger than the page limit is paginated`() {
        val packageId = classloadingPackageId()

        val firstPage = listDescendantsTool.listDescendants(
            nodeId = packageId, kindFilter = null, namePattern = null,
            modifierFilter = null, limit = 3, cursor = null
        )

        @Suppress("UNCHECKED_CAST")
        val firstSummary = firstPage["summary"] as Map<String, Any?>
        assertThat(firstSummary["total"]).isEqualTo(145)
        assertThat(firstSummary["truncated"]).isEqualTo(true)

        @Suppress("UNCHECKED_CAST")
        val firstResults = firstPage["results"] as List<Map<String, Any?>>
        assertThat(firstResults).hasSize(3)

        val cursor = firstPage["next_cursor"] as? String
        assertThat(cursor).isNotBlank()

        // Following the cursor yields a disjoint next page (parameters kept identical).
        val secondPage = listDescendantsTool.listDescendants(
            nodeId = packageId, kindFilter = null, namePattern = null,
            modifierFilter = null, limit = 3, cursor = cursor
        )

        @Suppress("UNCHECKED_CAST")
        val secondResults = secondPage["results"] as List<Map<String, Any?>>
        assertThat(secondResults).hasSize(3)
        assertThat(secondResults.map { it["qualified_name"] })
            .doesNotContainAnyElementsOf(firstResults.map { it["qualified_name"] })
    }

    @Test
    fun `the name-pattern match is paged through with the cursor`() {
        // Same setup as the name-pattern test (namePattern = 'types'), but with limit = 10 so the
        // 30 matches span three pages. Walking next_cursor must visit every match exactly once.
        val beansId = largestPackageNode("org.springframework.beans")

        val pages = mutableListOf<List<Map<String, Any?>>>()
        var cursor: String? = null
        var total: Int? = null
        do {
            val response = listDescendantsTool.listDescendants(
                nodeId = beansId,
                kindFilter = null,
                namePattern = "types",
                modifierFilter = null,
                limit = 10,
                cursor = cursor
            )

            @Suppress("UNCHECKED_CAST")
            val summary = response["summary"] as Map<String, Any?>
            total = summary["total"] as Int

            @Suppress("UNCHECKED_CAST")
            val results = response["results"] as List<Map<String, Any?>>
            pages.add(results)

            cursor = response["next_cursor"] as? String
            // A present cursor means a full page and more to come; its absence means the last page.
            if (cursor != null) {
                assertThat(results).hasSize(10)
                assertThat(summary["truncated"]).isEqualTo(true)
            }
        } while (cursor != null)

        assertThat(total).isEqualTo(30)
        assertThat(pages).hasSize(3)
        assertThat(pages.map { it.size }).containsExactly(10, 10, 10)

        // Pages partition the result set: every match appears exactly once, and every name matches.
        val allIds = pages.flatten().map { it["id"] }
        assertThat(allIds).hasSize(30).doesNotHaveDuplicates()
        assertThat(pages.flatten().map { it["name"] as String }).allMatch { it.lowercase().contains("types") }
    }
}
