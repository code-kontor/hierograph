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

/** Verifies the parameter position and name on parameter edges, and the `parameterPosition` filter. */
@Suppress("UNCHECKED_CAST")
class DetailParameterPositionTest {

    private val f = DetailFixture(withParameterMethod = true)

    private fun edges(resp: Map<String, Any?>) = resp["edges"] as List<Map<String, Any?>>
    private fun error(resp: Map<String, Any?>) = resp["error"] as Map<String, Any?>?
    private fun parameters(resp: Map<String, Any?>) = edges(resp).map { it["parameter"] }

    @Test
    fun `parameter_type edges carry the parameter's position and name, one per parameter`() {
        val resp = f.incoming.incomingDependencies(f.id(MONEY), f.id(INVOICE), "detail", "parameter_type", null, null)
        assertThat(error(resp)).isNull()
        assertThat(parameters(resp)).containsExactly(
            mapOf("position" to 0L, "name" to "from"),
            mapOf("position" to 1L, "name" to "to"),
        )
        assertThat((resp["summary"] as Map<String, Any?>)["by_relationship"]).isEqualTo(mapOf("parameter_type" to 2))
    }

    @Test
    fun `edges of other relationships have no parameter field`() {
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "detail", "calls", null, null)
        assertThat(edges(resp)).isNotEmpty.allSatisfy { assertThat(it).doesNotContainKey("parameter") }
    }

    @Test
    fun `parameterPosition keeps the edges of the parameter at that position`() {
        val first = f.incoming.incomingDependencies(f.id(MONEY), f.id(INVOICE), "detail", "parameter_type", null, null, 0)
        assertThat(parameters(first)).containsExactly(mapOf("position" to 0L, "name" to "from"))
        assertThat(edges(first).single()["from"]).isEqualTo(f.id("$INVOICE#void transfer($MONEY,$MONEY)"))

        val outgoing = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "detail", "parameter_type", null, null, 1)
        assertThat(parameters(outgoing)).containsExactly(mapOf("position" to 1L, "name" to "to"))

        val none = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "detail", "parameter_type", null, null, 2)
        assertThat(edges(none)).isEmpty()
    }

    @Test
    fun `without a relationship, parameterPosition selects only parameter edges`() {
        val resp = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "detail", null, null, null, 0)
        assertThat(edges(resp).map { it["relationship"] }).containsExactly("parameter_type")
    }

    @Test
    fun `parameterPosition is rejected at type level, when negative, and with a non-parameter relationship`() {
        val typeLevel = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "type", null, null, null, 0)
        assertThat(error(typeLevel)!!["code"]).isEqualTo("INVALID_PARAMETER")
        assertThat(error(typeLevel)!!["message"] as String).contains("parameterPosition")

        val incomingTypeLevel = f.incoming.incomingDependencies(f.id(MONEY), f.id(INVOICE), null, null, null, null, 0)
        assertThat(error(incomingTypeLevel)!!["code"]).isEqualTo("INVALID_PARAMETER")

        val negative = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "detail", null, null, null, -1)
        assertThat(error(negative)!!["code"]).isEqualTo("INVALID_PARAMETER")

        val calls = f.outgoing.outgoingDependencies(f.id(INVOICE), f.id(MONEY), "detail", "calls", null, null, 0)
        assertThat(error(calls)!!["code"]).isEqualTo("INVALID_PARAMETER")
        assertThat(error(calls)!!["message"] as String).contains("'calls'")
    }

    @Test
    fun `a cursor issued for one position is rejected for another`() {
        val page = f.incoming.incomingDependencies(f.id(MONEY), f.id(INVOICE), "detail", null, 1, null)
        val cursor = page["next_cursor"] as String
        val other = f.incoming.incomingDependencies(f.id(MONEY), f.id(INVOICE), "detail", null, 1, cursor, 0)
        assertThat(error(other)).isNotNull
    }
}
