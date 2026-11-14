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

import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.DOC
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.INVOICE
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.MONEY
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.RENDERABLE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Verifies the detail-level open form: `toId` omitted, so one side is unconstrained. */
@Suppress("UNCHECKED_CAST")
class DetailOpenFormTest {

    private val f = DetailFixture(withParameterMethod = true)

    private fun edges(resp: Map<String, Any?>) = resp["edges"] as List<Map<String, Any?>>
    private fun summary(resp: Map<String, Any?>) = resp["summary"] as Map<String, Any?>

    /** An edge without the scope-relative `inherited` flag, so edges of different scopes compare. */
    private fun key(edge: Map<String, Any?>) = edge - "inherited"

    @Test
    fun `incoming open form returns the union of the per-candidate queries`() {
        val open = f.incoming.incomingDependencies(f.id(MONEY), null, "detail", null, 90, null)
        assertThat(open["error"]).isNull()
        assertThat(open["from_scope"]).isNull()
        assertThat(open["to_scope"]).isEqualTo(f.id(MONEY))

        // The candidates a type-level query would return, each queried on its own.
        val perCandidate = listOf(INVOICE, DOC, RENDERABLE).flatMap {
            edges(f.incoming.incomingDependencies(f.id(MONEY), f.id(it), "detail", null, 90, null))
        }
        assertThat(edges(open).map(::key)).isNotEmpty.doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(perCandidate.map(::key).distinct())
        // An open depending side has no supertype closure, so nothing is inherited.
        assertThat(summary(open)["inherited"]).isEqualTo(0)
    }

    @Test
    fun `incoming open form with a parameter position answers the motivating question in one call`() {
        val resp = f.incoming.incomingDependencies(f.id(MONEY), null, "detail", "parameter_type", null, null, 0)
        assertThat(edges(resp)).hasSize(1)
        val edge = edges(resp).single()
        assertThat(edge["from"]).isEqualTo(f.id("$INVOICE#void transfer($MONEY,$MONEY)"))
        assertThat(edge["parameter"]).isEqualTo(mapOf("position" to 0L, "name" to "from"))
    }

    @Test
    fun `outgoing open form returns every reference of the source, to anywhere`() {
        val open = f.outgoing.outgoingDependencies(f.id(INVOICE), null, "detail", null, 90, null)
        assertThat(open["error"]).isNull()
        assertThat(open["from_scope"]).isEqualTo(f.id(INVOICE))
        assertThat(open["to_scope"]).isNull()

        val targets = edges(open).map { it["to_parent"] ?: it["to"] }.toSet()
        // Money, the supertypes (extends / implements, and the call to the inherited total()) and Invoice itself.
        assertThat(targets).contains(f.id(MONEY), f.id(DOC), f.id(RENDERABLE), f.id(INVOICE))
        // Every bounded query's rows are among the open form's.
        for (target in listOf(MONEY, DOC, RENDERABLE)) {
            val bounded = edges(f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(target), "detail", null, 90, null))
            assertThat(edges(open).map(::key)).`as`(target).containsAll(bounded.map(::key))
        }
        // The depending side keeps its supertype closure: AbstractDoc#total() calling Money#plus is inherited.
        assertThat(edges(open).filter { it["inherited"] == true }.map { it["from"] })
            .contains(f.id("$DOC#$MONEY total()"))
    }

    @Test
    fun `the open side's drill-down starts at the root and weighs against the whole graph`() {
        val incoming = summary(f.incoming.incomingDependencies(f.id(MONEY), null, "detail", null, 1, null))
        assertThat(incoming["by_source_nodes"] as List<*>).isNotEmpty
        val outgoing = summary(f.outgoing.outgoingDependencies(f.id(INVOICE), null, "detail", null, 1, null))
        assertThat(outgoing["by_target_nodes"] as List<*>).isNotEmpty
        assertThat((outgoing["by_target_nodes"] as List<Map<String, Any?>>).map { it["aggregated_weight"] as Int })
            .allMatch { it > 0 }
    }

    @Test
    fun `the open form pages with a cursor like any detail query`() {
        val first = f.incoming.incomingDependencies(f.id(MONEY), null, "detail", null, 1, null)
        val total = summary(first)["total"] as Int
        assertThat(total).isGreaterThan(1)
        var cursor = first["next_cursor"] as String?
        val seen = edges(first).toMutableList()
        while (cursor != null) {
            val page = f.incoming.incomingDependencies(f.id(MONEY), null, "detail", null, 1, cursor)
            assertThat(page["error"]).isNull()
            seen += edges(page)
            cursor = page["next_cursor"] as String?
        }
        assertThat(seen).hasSize(total)
        // A cursor of the open form does not continue a bounded query.
        val again = f.incoming.incomingDependencies(f.id(MONEY), null, "detail", null, 1, null)
        val bounded = f.incoming.incomingDependencies(f.id(MONEY), f.id(INVOICE), "detail", null, 1, again["next_cursor"] as String)
        assertThat(bounded["error"]).isNotNull
    }
}
