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
package io.hierograph.itest.graphstore

import io.hierograph.boltclient.IBoltClientFactory
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.PlaceholderPolicy
import io.hierograph.graphstore.detail.DetailEnricher
import io.hierograph.graphstore.detail.DetailQuery
import io.hierograph.graphstore.detail.DetailQueryEngine
import io.hierograph.graphstore.detail.DetailScope
import io.hierograph.graphstore.jqa.JqaParquetExporter
import io.hierograph.graphstore.jqa.detail.JqaDetailExporter
import io.hierograph.graphstore.jqa.detail.JqaDetailReference
import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.itest.fwk.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.system.measureNanoTime

/**
 * Detail-layer acceptance + performance: for several real scopes, the Parquet engine's edge set must
 * equal the Neo4j baseline (`JqaDetailReference`, the same member-edge Cypher scoped to the identical
 * inheritance-expanded FQN set), and we time Parquet-read vs. live-Cypher. The diff isolates export
 * fidelity + the engine's push-down filtering; timings are printed, not asserted.
 */
class DetailDiffAndBenchmarkIT : AbstractIntegrationTest() {

    private val runs = 3

    @Test
    fun `Parquet detail engine matches the Neo4j baseline and is faster`(
        @TempDir typeDir: Path,
        @TempDir detailDir: Path,
    ) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val boltClient = IBoltClientFactory.newInstance(executor).createBoltClient(container.boltUri)
        boltClient.connect()
        try {
            // Type-level snapshot → GraphStore (for scope resolution); detail-refs snapshot (for edges).
            JqaParquetExporter(boltClient).exportTo(typeDir)
            val store = GraphStore(PlaceholderPolicy.JQA_ALIGNED)
            ParquetReader().load(typeDir, store)
            JqaDetailExporter(boltClient).exportTo(detailDir)

            val engine = DetailQueryEngine(detailDir)
            val reference = JqaDetailReference(boltClient)

            // Scopes: the busiest source types that are present in the type graph (so scope resolves).
            val scopes = ParquetReader().readDetailRefs(detailDir)
                .groupingBy { it.fromType }.eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key }.filter { store.node(it) != null }
                .take(3)
            assertThat(scopes).isNotEmpty

            println("=== detail engine: Parquet vs Neo4j ===")
            for (scope in scopes) {
                val from = DetailScope.expand(store, scope)

                val engineEdges = engine.query(DetailQuery(fromTypes = from, limit = Int.MAX_VALUE)).edges.toSet()
                val refEdges = reference.query(from).toSet()

                val onlyEngine = engineEdges - refEdges
                val onlyRef = refEdges - engineEdges

                val engineMs = medianMs { engine.query(DetailQuery(fromTypes = from, limit = Int.MAX_VALUE)).edges.size }
                val refMs = medianMs { reference.query(from).size }

                println(
                    String.format(
                        Locale.ROOT,
                        "  %-70s types=%-3d edges=%-5d  diff=%d/%d  Parquet=%.1fms  Neo4j=%.1fms  (%.0fx)",
                        scope.takeLast(70), from.size, engineEdges.size,
                        onlyEngine.size, onlyRef.size, engineMs, refMs, refMs / engineMs,
                    ),
                )

                assertThat(engineEdges).isNotEmpty
                assertThat(onlyEngine).withFailMessage { "Only in Parquet engine for $scope:\n${onlyEngine.sample()}" }.isEmpty()
                assertThat(onlyRef).withFailMessage { "Only in Neo4j baseline for $scope:\n${onlyRef.sample()}" }.isEmpty()
            }

            // Relationship push-down must agree with the baseline filtered to the same kind.
            val scope = scopes.first()
            val from = DetailScope.expand(store, scope)
            val engineCalls = engine.query(DetailQuery(fromTypes = from, relationship = "calls", limit = Int.MAX_VALUE)).edges.toSet()
            val refCalls = reference.query(from).filter { it.relationship == "calls" }.toSet()
            assertThat(engineCalls).isEqualTo(refCalls)

            // Member display enrichment: a page's edges -> a nodes map (name/kind) from members.parquet
            // (in the type snapshot) + the in-memory type graph.
            val enricher = DetailEnricher(store, typeDir)
            val page = engine.query(DetailQuery(fromTypes = from, limit = 50)).edges
            val nodes = enricher.nodesFor(page)
            for (e in page) {
                assertThat(nodes).containsKeys(e.fromKey, e.fromType, e.toKey, e.toType)
            }
            assertThat(nodes.values).allMatch { it.name.isNotEmpty() }
            // the scope is a class with method calls, so both kinds must appear
            assertThat(nodes.values.map { it.kind }).contains("java.method", "java.class")
            println("  sample nodes: " + nodes.entries.take(6).joinToString { "${it.value.name}:${it.value.kind}" })
        } finally {
            boltClient.disconnect()
            executor.shutdownNow()
        }
    }

    private fun medianMs(block: () -> Unit): Double {
        val samples = (1..runs).map { measureNanoTime { block() } / 1_000_000.0 }.sorted()
        return samples[samples.size / 2]
    }

    private fun Set<DetailRef>.sample(): String =
        sortedWith(compareBy({ it.fromKey }, { it.toKey }, { it.relationship })).take(10)
            .joinToString("\n") { "    ${it.relationship}  ${it.fromKey} -> ${it.toKey} @${it.line}" }
}
