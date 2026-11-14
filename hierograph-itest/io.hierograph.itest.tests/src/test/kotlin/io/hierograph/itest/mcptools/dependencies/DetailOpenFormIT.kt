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
package io.hierograph.itest.mcptools.dependencies

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.mcp.server.tools.dependencyanalysis.IncomingDependenciesTool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import kotlin.time.measureTimedValue

/**
 * The detail-level open form of `incoming_dependencies` on the Spring Framework snapshot: one call with
 * `toId` omitted returns exactly the rows the candidate workflow collects with one bounded call per
 * depending type, for the motivating question of issue #3, "which methods take `ResourceLoader` as their
 * first parameter".
 */
@Suppress("UNCHECKED_CAST")
class DetailOpenFormIT : AbstractMcpApplicationIntegrationTest() {

    private val log = LoggerFactory.getLogger(DetailOpenFormIT::class.java)

    @Autowired
    private lateinit var incomingDependenciesTool: IncomingDependenciesTool

    private val targetFqn = "org.springframework.core.io.ResourceLoader"

    /** Every page of an incoming query, followed through its cursors. */
    private fun allPages(fromId: Long, toId: Long?, detailLevel: String, relationship: String?, position: Int?): List<Map<String, Any?>> {
        val edges = ArrayList<Map<String, Any?>>()
        var cursor: String? = null
        do {
            val page = incomingDependenciesTool.incomingDependencies(fromId, toId, detailLevel, relationship, null, cursor, position)
            assertThat(page["error"]).`as`("toId=$toId").isNull()
            edges += page["edges"] as List<Map<String, Any?>>
            cursor = page["next_cursor"] as String?
        } while (cursor != null)
        return edges
    }

    /** An edge without the scope-relative `inherited` flag, so rows of different scopes compare. */
    private fun key(edge: Map<String, Any?>) = edge - "inherited"

    @Test
    fun `the open form returns the union of the per-candidate queries, in one call`() {
        val target = resolveNodeId("ResourceLoader", targetFqn, "java.interface")

        val (open, openTime) = measureTimedValue { allPages(target, null, "detail", "parameter_type", 0) }

        val (union, candidateTime) = measureTimedValue {
            // The candidate workflow: the depending types from a type-level query, plus the type itself, whose
            // own methods may take it as a parameter but which a type-level query never lists.
            val candidates = allPages(target, null, "type", null, null).map { it["from"] as Long }.toSet() + target
            log.info("{} candidate types depend on {}", candidates.size, targetFqn)
            candidates.flatMap { allPages(target, it, "detail", "parameter_type", 0) }.map(::key).distinct()
        }
        log.info("open form: {} rows in {}; per-candidate queries: {} rows in {}", open.size, openTime, union.size, candidateTime)

        assertThat(open).isNotEmpty
        assertThat(open).allSatisfy {
            assertThat(it["relationship"]).isEqualTo("parameter_type")
            assertThat((it["parameter"] as Map<String, Any?>)["position"]).isEqualTo(0L)
            assertThat(it["to"]).isEqualTo(target)
        }
        assertThat(open.map(::key)).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(union)
    }

    @Test
    fun `the open form on a hub type returns its summaries over every depender`() {
        // Assert is used throughout Spring: the open form reads every snapshot's dependencies (to_type is not
        // the sort key), the slowest case. limit = 1 keeps the response to the summaries.
        val hub = resolveNodeId("Assert", "org.springframework.util.Assert", "java.class")
        val (resp, time) = measureTimedValue { incomingDependenciesTool.incomingDependencies(hub, null, "detail", null, 1, null) }
        val summary = resp["summary"] as Map<String, Any?>
        val types = (summary["by_source_type"] as List<*>).size + ((summary["others_count"] as Int?) ?: 0)
        log.info("open form on Assert: {} rows from {} types in {}", summary["total"], types, time)

        assertThat(resp["error"]).isNull()
        assertThat(resp["from_scope"]).isNull()
        assertThat(summary["total"] as Int).isGreaterThan(1000)
        assertThat(summary["by_source_nodes"] as List<*>).isNotEmpty
        assertThat(resp["edges"] as List<*>).hasSize(1)
    }
}
