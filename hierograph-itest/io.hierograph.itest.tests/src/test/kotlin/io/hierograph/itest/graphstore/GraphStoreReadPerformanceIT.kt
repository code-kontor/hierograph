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
import io.hierograph.graphstore.jqa.GraphDbMappingReference
import io.hierograph.graphstore.jqa.JqaSnapshotExporter
import io.hierograph.graphstore.parquet.ParquetSnapshotLoader
import io.hierograph.graphstore.parquet.SnapshotSchema
import io.hierograph.itest.fwk.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.system.measureNanoTime

/**
 * Measures the core promise of the migration: **how long does it take to build the type-level in-memory
 * graph from a Parquet snapshot versus from the Neo4j database?**
 *
 * Both paths produce the equivalent type-level graph (proven by [GraphStoreVsGraphDbMappingIT]):
 *  - **Parquet:** `ParquetSnapshotLoader` reads the `types`/`members`/`refs` tables into a [GraphStore].
 *  - **Neo4j:** `GraphDbMappingReference` runs the existing `jQAssistantMapping` Cypher over Bolt and
 *    assembles the same hierarchy + type-dependency rows.
 *
 * The snapshot is produced once up front (that Neo4j→Parquet export is a one-time cost, reported for
 * context but not part of the measured read). Timings are printed, not asserted — wall-clock thresholds
 * would be flaky in CI; the test only guards that both paths produce a non-empty, comparable graph.
 */
class GraphStoreReadPerformanceIT : AbstractIntegrationTest() {

    @Test
    fun `build the in-memory graph from Parquet vs from Neo4j`(@TempDir snapshotDir: Path) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val boltClient = IBoltClientFactory.newInstance(executor).createBoltClient(container.boltUri)
        boltClient.connect()
        try {
            // ── one-time: export the Neo4j store to a Parquet snapshot ────────────────────
            val exportNanos = measureNanoTime { JqaSnapshotExporter(boltClient).exportTo(snapshotDir) }

            // ── warm up both paths (JVM JIT, Neo4j page cache, driver) ────────────────────
            repeat(WARMUP) { loadFromParquet(snapshotDir) }
            repeat(WARMUP) { GraphDbMappingReference(boltClient).build() }

            // ── measure ───────────────────────────────────────────────────────────────────
            var parquetGraph: GraphStore? = null
            val parquetNanos = LongArray(RUNS) { measureNanoTime { parquetGraph = loadFromParquet(snapshotDir) } }

            var neo4jTypes = 0
            var neo4jDeps = 0
            val neo4jNanos = LongArray(RUNS) {
                measureNanoTime {
                    val ref = GraphDbMappingReference(boltClient).build()
                    neo4jTypes = ref.hierarchy.size
                    neo4jDeps = ref.dependencies.size
                }
            }

            // ── report ──────────────────────────────────────────────────────────────────
            val store = parquetGraph!!
            val parquet = Stats.of(parquetNanos)
            val neo4j = Stats.of(neo4jNanos)

            println(buildString {
                appendLine("=== GraphStore read performance: Parquet vs Neo4j ===")
                appendLine("snapshot files (bytes):")
                for (file in listOf(SnapshotSchema.TYPES_FILE, SnapshotSchema.MEMBERS_FILE, SnapshotSchema.REFS_FILE)) {
                    appendLine(String.format(Locale.ROOT, "  %-16s %,12d", file, Files.size(snapshotDir.resolve(file))))
                }
                appendLine("one-time Neo4j -> Parquet export: ${exportNanos.ms()} ms")
                appendLine("graph size: Parquet nodes=${store.nodeCount()}, type-deps=${store.typeDependencies().count()}; " +
                        "Neo4j hierarchy-rows=$neo4jTypes, type-deps=$neo4jDeps")
                appendLine("runs=$RUNS (after $WARMUP warmup)   [min / median / mean / max ms]")
                appendLine("  Parquet -> GraphStore : ${parquet.render()}")
                appendLine("  Neo4j   -> graph rows : ${neo4j.render()}")
                appendLine(String.format(Locale.ROOT, "  speedup (median Neo4j / median Parquet): %.1fx", neo4j.medianMs / parquet.medianMs))
            })

            // ── correctness guard (timings themselves are not asserted) ──────────────────
            assertThat(store.nodeCount()).isGreaterThan(0)
            assertThat(neo4jTypes).isGreaterThan(0)
        } finally {
            boltClient.disconnect()
            executor.shutdownNow()
        }
    }

    private fun loadFromParquet(dir: Path): GraphStore {
        val store = GraphStore(PlaceholderPolicy.JQA_ALIGNED)
        ParquetSnapshotLoader().load(dir, store)
        return store
    }

    private class Stats(val minMs: Double, val medianMs: Double, val meanMs: Double, val maxMs: Double) {
        fun render(): String = String.format(Locale.ROOT, "%.1f / %.1f / %.1f / %.1f", minMs, medianMs, meanMs, maxMs)

        companion object {
            fun of(nanos: LongArray): Stats {
                val ms = nanos.map { it / 1_000_000.0 }.sorted()
                val median = if (ms.size % 2 == 1) ms[ms.size / 2] else (ms[ms.size / 2 - 1] + ms[ms.size / 2]) / 2
                return Stats(ms.first(), median, ms.average(), ms.last())
            }
        }
    }

    private companion object {
        // The Neo4j path is query-bound (~tens of seconds per build), not JIT-bound, so a single warmup
        // and a handful of runs are plenty — more just multiplies the slow path's wall-clock.
        const val WARMUP = 1
        const val RUNS = 3
        fun Long.ms(): Long = this / 1_000_000
    }
}
