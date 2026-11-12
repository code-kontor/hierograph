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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphstore.jvm.model.DependencyKind
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.AggregatedDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.PairwiseDependenciesTool
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.DOC
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.INVOICE
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.MONEY
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.RENDERABLE
import io.hierograph.mcp.server.tools.navigation.GraphOverviewTool
import io.hierograph.server.graphservice.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Type-level results carry `kinds`: how many detail facts of each kind make up a weight, named like the
 * detail-level relationships. The fixture's facts give the expected counts (see [DetailFixture]):
 * Invoice → AbstractDoc is one `extends` and two `calls`, Invoice → Money one `returns` and two `calls`,
 * Invoice → Renderable one `implements`, AbstractDoc → Money one `calls`.
 */
@Suppress("UNCHECKED_CAST")
class TypeLevelKindsTest {

    private val f = DetailFixture()

    private fun edges(resp: Map<String, Any?>) = resp["edges"] as List<Map<String, Any?>>
    private fun summary(resp: Map<String, Any?>) = resp["summary"] as Map<String, Any?>

    @Test
    fun `outgoing type-level edges carry their kinds, summed over the result in by_kind`() {
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), null, "type", null, null, null)
        val byTarget = edges(resp).associate { it["to"] to it }

        assertThat(byTarget.getValue(f.id(DOC))["kinds"]).isEqualTo(mapOf("extends" to 1, "calls" to 2))
        assertThat(byTarget.getValue(f.id(MONEY))["kinds"]).isEqualTo(mapOf("returns" to 1, "calls" to 2))
        assertThat(byTarget.getValue(f.id(RENDERABLE))["kinds"]).isEqualTo(mapOf("implements" to 1))
        for (edge in edges(resp)) {
            assertThat((edge["kinds"] as Map<String, Int>).values.sum()).describedAs("kinds sum to the weight").isEqualTo(edge["weight"])
        }
        assertThat(summary(resp)["by_kind"]).isEqualTo(mapOf("extends" to 1, "implements" to 1, "returns" to 1, "calls" to 4))
    }

    @Test
    fun `a kind name leads to the detail facts behind it`() {
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(DOC), "detail", "extends", null, null)
        val rows = edges(resp)
        assertThat(rows.map { it["relationship"] }).containsExactly("extends")
        assertThat(rows.single()["from"]).isEqualTo(f.id(INVOICE))
        // every detail kind is a valid filter, also one with no rows here
        assertThat(f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(DOC), "detail", "casts", null, null)).doesNotContainKey("error")
    }

    @Test
    fun `pairwise and aggregated edges sum the kinds of the type-level dependencies under them`() {
        val billing = f.id("com.example.billing")
        val doc = f.id("com.example.doc")
        val money = f.id("com.example.money")

        val pairwise = PairwiseDependenciesTool(f.graphService, NodeRefFactory(f.graphService), DataHashProvider(f.graphService))
            .pairwiseDependencies(listOf(billing, doc, money), null, null, null, null)
        val pairs = edges(pairwise).associate { (it["from"] to it["to"]) to it["kinds"] }
        assertThat(pairs[billing to doc]).isEqualTo(mapOf("extends" to 1, "implements" to 1, "calls" to 2))
        assertThat(pairs[billing to money]).isEqualTo(mapOf("returns" to 1, "calls" to 2))
        assertThat(pairs[doc to money]).isEqualTo(mapOf("calls" to 1))

        val aggregated = AggregatedDependenciesTool(f.graphService, NodeRefFactory(f.graphService))
            .aggregatedDependencies(listOf(billing), listOf(money))
        assertThat(edges(aggregated).single()["kinds"]).isEqualTo(mapOf("returns" to 1, "calls" to 2))
    }

    @Test
    fun `graph_overview lists every kind and the weight per kind`() {
        val overview = GraphOverviewTool(f.graphService, NodeRefFactory(f.graphService)).graphOverview()
        val relationships = overview["relationships"] as Map<String, Any?>
        val detail = (relationships["detail_level"] as List<Map<String, Any?>>).associateBy { it["kind"] }

        assertThat(detail.keys).containsExactlyElementsOf(DependencyKind.entries.map { it.value })
        assertThat(detail.getValue("overrides")["derived"]).isEqualTo(true)
        assertThat(detail.getValue("raises")["counted"]).isEqualTo(false)
        assertThat(detail.getValue("casts")).doesNotContainKeys("derived", "counted")
        assertThat((relationships["type_level_kinds"] as List<Map<String, Any?>>).map { it["kind"] }).contains("member_annotated_by", "calls")

        val stats = overview["stats"] as Map<String, Any?>
        assertThat(stats["type_level_weight_by_kind"]).isEqualTo(mapOf("extends" to 1, "implements" to 1, "returns" to 1, "calls" to 5))
    }
}
