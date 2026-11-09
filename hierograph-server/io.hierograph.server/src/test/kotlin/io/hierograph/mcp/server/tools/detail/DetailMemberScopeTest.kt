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

import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.INVOICE
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.MONEY
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Verifies that the detail level narrows a method or field scope to that member's own rows. */
@Suppress("UNCHECKED_CAST")
class DetailMemberScopeTest {

    private val f = DetailFixture()

    private fun edges(resp: Map<String, Any?>) = resp["edges"] as List<Map<String, Any?>>

    @Test
    fun `a method as fromId keeps only the rows that method produces`() {
        val net = f.id("$INVOICE#$MONEY net()")
        val resp = f.outgoing.outgoingDependencies(net, f.id(MONEY), "detail", null, null, null)
        assertThat(resp["error"]).isNull()
        assertThat(edges(resp)).hasSize(2).allSatisfy { assertThat(it["from"]).isEqualTo(net) }
        assertThat(resp["from_scope"]).isEqualTo(net)
    }

    @Test
    fun `a method as toId keeps only the rows that reach it`() {
        val plus = f.id("$MONEY#$MONEY plus($MONEY)")
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), plus, "detail", null, null, null)
        // Invoice#net() and, through the inheritance closure, AbstractDoc#total() call plus.
        assertThat(edges(resp)).hasSize(2).allSatisfy { assertThat(it["to"]).isEqualTo(plus) }
    }

    @Test
    fun `incoming at detail level accepts a member as the depended-upon side`() {
        val times = f.id("$MONEY#$MONEY times(int)")
        val resp = f.incoming.incomingDependencies(times, f.id(INVOICE), "detail", null, null, null)
        assertThat(edges(resp)).hasSize(1).allSatisfy { assertThat(it["to"]).isEqualTo(times) }
    }

    @Test
    fun `an unknown id is reported by parameter name, whichever direction`() {
        val known = f.id(INVOICE)
        for (resp in listOf(
            f.incoming.incomingDependencies(999L, known, "detail", null, null, null),
            f.outgoing.outgoingDependencies(999L, known, "detail", null, null, null),
        )) {
            val error = resp["error"] as Map<String, Any?>
            assertThat(error["code"]).isEqualTo("NODE_NOT_FOUND")
            assertThat(error["message"]).isEqualTo("No node with id 999 exists in the graph.")
        }
        val toMissing = f.incoming.incomingDependencies(known, 998L, "detail", null, null, null)["error"] as Map<String, Any?>
        assertThat(toMissing["message"]).isEqualTo("No node with id 998 exists in the graph.")
    }

    @Test
    fun `rows that only the inheritance closure brings in are marked inherited`() {
        val plus = f.id("$MONEY#$MONEY plus($MONEY)")
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), plus, "detail", null, null, null)
        val byFrom = edges(resp).associateBy { it["from_parent"] }
        assertThat(byFrom[f.id(INVOICE)]).doesNotContainKey("inherited")
        assertThat(byFrom[f.id(DetailFixture.DOC)]!!["inherited"]).isEqualTo(true)
        assertThat((resp["summary"] as Map<String, Any?>)["inherited"]).isEqualTo(1)
    }

    @Test
    fun `derived rows take their place in the row order`() {
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(DetailFixture.DOC), "detail", null, null, null)
        // net() < render() < validate(): the derived `overrides` row of render() sits between the stored rows.
        assertThat(edges(resp).map { it["relationship"] }).containsExactly("calls", "overrides", "calls")
        assertThat(edges(resp)[1]["from"]).isEqualTo(f.id("$INVOICE#void render()"))
    }

    @Test
    fun `an empty detail result still rejects a malformed cursor`() {
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "detail", null, null, "!!nope!!")
        assertThat((resp["error"] as Map<String, Any?>)["code"]).isEqualTo("INVALID_CURSOR_FORMAT")
        val fieldScope = f.outgoing.outgoingDependencies(f.id("$MONEY#int SCALE"), f.id(INVOICE), "detail", null, null, "!!nope!!")
        assertThat((fieldScope["error"] as Map<String, Any?>)["code"]).isEqualTo("INVALID_CURSOR_FORMAT")
    }

    @Test
    fun `the type-level nodes map names every ranked type`() {
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), null, "type", null, 1, null)
        val nodes = resp["nodes"] as Map<String, Any?>
        val ranked = ((resp["summary"] as Map<String, Any?>)["by_target"] as List<Map<String, Any?>>).map { it["id"].toString() }
        assertThat(nodes.keys).containsAll(ranked)
    }

    @Test
    fun `aggregated_dependencies needs both lists and ignores repeated ids`() {
        val tool = io.hierograph.mcp.server.tools.dependencyanalysis.AggregatedDependenciesTool(
            f.graphService, io.hierograph.mcp.server.tools.NodeRefFactory(f.graphService)
        )
        assertThat((tool.aggregatedDependencies(emptyList(), listOf(f.id(MONEY)))["error"] as Map<String, Any?>)["code"])
            .isEqualTo("INPUT_TOO_SMALL")
        val resp = tool.aggregatedDependencies(listOf(f.id(INVOICE), f.id(INVOICE)), listOf(f.id(MONEY)))
        assertThat(resp["edges"] as List<*>).hasSize(1)
        assertThat((resp["summary"] as Map<String, Any?>)["total_pairs_requested"]).isEqualTo(1)
    }
}
