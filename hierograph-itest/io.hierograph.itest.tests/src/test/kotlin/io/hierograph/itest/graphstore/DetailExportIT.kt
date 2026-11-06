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
import io.hierograph.graphstore.jqa.detail.JqaDetailExporter
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.graphstore.parquet.ParquetSchema
import io.hierograph.itest.fwk.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * First increment of the detail-layer prototype: the jQAssistant stand-in export of member-level
 * ("detail") references into `detail_refs.parquet`, then reading them back with Hardwood predicate
 * push-down. Proves the data path end-to-end against the live store; the scoped query engine
 * (summaries, pagination, diff vs the Neo4j detail tool, benchmark) is the next increment.
 */
class DetailExportIT : AbstractIntegrationTest() {

    @Test
    fun `export member-level detail refs to Parquet and read them back scoped`(@TempDir dir: Path) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val boltClient = IBoltClientFactory.newInstance(executor).createBoltClient(container.boltUri)
        boltClient.connect()
        try {
            JqaDetailExporter(boltClient).exportTo(dir)

            // read everything back (no predicate)
            val all = ParquetReader().readDetailRefs(dir)
            val byRel = all.groupingBy { it.relationship }.eachCount().toSortedMap()
            val topType = all.groupingBy { it.fromType }.eachCount().maxByOrNull { it.value }!!

            println(buildString {
                appendLine("=== detail_refs export ===")
                appendLine("file bytes : %,d".format(Files.size(dir.resolve(ParquetSchema.DETAIL_REFS_FILE))))
                appendLine("total edges: ${all.size}")
                appendLine("by relationship:")
                byRel.forEach { (rel, n) -> appendLine("  %-22s %,d".format(rel, n)) }
                appendLine("busiest from_type: ${topType.key} (${topType.value} edges)")
            })

            // the export must produce the core member-level relationships
            assertThat(all).isNotEmpty
            assertThat(byRel.keys).contains("calls")

            // rows are written sorted by from_type (enables row-group skipping)
            assertThat(all.map { it.fromType }).isSorted

            // scoped read with predicate push-down returns exactly that type's edges
            val scoped = ParquetReader().readDetailRefs(dir, fromTypes = setOf(topType.key))
            assertThat(scoped).isNotEmpty
            assertThat(scoped).allMatch { it.fromType == topType.key }
            assertThat(scoped.size).isEqualTo(all.count { it.fromType == topType.key })

            // relationship filter composes with the scope filter
            val scopedCalls = ParquetReader().readDetailRefs(dir, fromTypes = setOf(topType.key), relationship = "calls")
            assertThat(scopedCalls).allMatch { it.fromType == topType.key && it.relationship == "calls" }
        } finally {
            boltClient.disconnect()
            executor.shutdownNow()
        }
    }
}
