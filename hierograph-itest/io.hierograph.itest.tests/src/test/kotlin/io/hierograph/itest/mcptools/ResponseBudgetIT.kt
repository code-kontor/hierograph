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
package io.hierograph.itest.mcptools

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.mcp.server.pagination.Paginator
import io.hierograph.mcp.server.tools.dependencyanalysis.IncomingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.PairwiseDependenciesTool
import io.hierograph.mcp.server.tools.navigation.GraphOverviewTool
import io.hierograph.mcp.server.tools.navigation.ListDescendantsTool
import io.hierograph.mcp.server.tools.reachability.AffectedByTool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Every paginated tool keeps every page within [Paginator.RESPONSE_BUDGET], measured as the client receives it,
 * at the largest page size it allows and on the largest scopes the Spring fixture has — the module with the most
 * types, and all top-level modules at once.
 */
@Suppress("UNCHECKED_CAST")
class ResponseBudgetIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired private lateinit var graphOverviewTool: GraphOverviewTool
    @Autowired private lateinit var pairwiseTool: PairwiseDependenciesTool
    @Autowired private lateinit var listDescendantsTool: ListDescendantsTool
    @Autowired private lateinit var outgoingTool: OutgoingDependenciesTool
    @Autowired private lateinit var incomingTool: IncomingDependenciesTool
    @Autowired private lateinit var affectedByTool: AffectedByTool

    private val hierarchy: List<Map<String, Any?>> by lazy {
        graphOverviewTool.graphOverview()["hierarchy"] as List<Map<String, Any?>>
    }

    private val modules: List<Map<String, Any?>> by lazy { hierarchy.filter { it["name"] != "External Types" } }

    /** The module with the most types: the largest scope the fixture offers. */
    private val largest: Long by lazy { modules.maxBy { it["descendant_type_count"] as Int }["id"] as Long }

    /**
     * Walks up to [maxPages] pages of a paged call, asserting each is within the budget and an answer rather than
     * an error; returns the number of items seen.
     */
    private fun walk(itemsKey: String, maxPages: Int = 5, call: (String?) -> Map<String, Any?>): Int {
        var cursor: String? = null
        var seen = 0
        var pages = 0
        do {
            val page = call(cursor)
            assertThat(page).doesNotContainKey("error")
            assertThat(Paginator.sizeOf(page)).isLessThanOrEqualTo(Paginator.RESPONSE_BUDGET)
            seen += (page[itemsKey] as List<*>).size
            cursor = page["next_cursor"] as String?
        } while (cursor != null && ++pages < maxPages)
        return seen
    }

    @Test
    fun `graph_overview fits the budget`() {
        assertThat(Paginator.sizeOf(graphOverviewTool.graphOverview())).isLessThanOrEqualTo(Paginator.RESPONSE_BUDGET)
    }

    @Test
    fun `pairwise_dependencies over all top-level modules fits on every page and returns every edge`() {
        val ids = modules.map { it["id"] as Long }
        val first = pairwiseTool.pairwiseDependencies(ids, "weight_desc", 1, 500, null)
        val total = (first["summary"] as Map<String, Any?>)["total"] as Int
        val seen = walk("edges", maxPages = 100) { cursor ->
            if (cursor == null) first else pairwiseTool.pairwiseDependencies(ids, "weight_desc", 1, 500, cursor)
        }
        assertThat(seen).isEqualTo(total)
    }

    @Test
    fun `list_descendants of the largest module fits on every page`() {
        walk("results") { cursor -> listDescendantsTool.listDescendants(largest, null, null, null, 300, cursor) }
    }

    @Test
    fun `outgoing_dependencies of the largest module fits on every page, at type and at detail level`() {
        walk("edges") { cursor -> outgoingTool.outgoingDependencies(largest, null, "type", null, 300, cursor, null) }
        walk("edges") { cursor -> outgoingTool.outgoingDependencies(largest, null, "detail", null, 300, cursor, null) }
    }

    @Test
    fun `incoming_dependencies of the largest module fits on every page`() {
        walk("edges") { cursor -> incomingTool.incomingDependencies(largest, null, "type", null, 300, cursor, null) }
    }

    @Test
    fun `affected_by of the largest module fits on every page`() {
        walk("results") { cursor -> affectedByTool.affectedBy(largest, null, null, null, 300, cursor) }
    }
}
