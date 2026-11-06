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
import io.hierograph.graphstore.jqa.DiffOptions
import io.hierograph.graphstore.jqa.GraphDbMappingReference
import io.hierograph.graphstore.jqa.JqaSnapshotExporter
import io.hierograph.graphstore.jqa.SnapshotDiff
import io.hierograph.graphstore.parquet.ParquetSnapshotLoader
import io.hierograph.itest.fwk.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * End-to-end acceptance of the GraphStore MVP (spec §7 step 4): the GraphStore, built from the shared
 * jQAssistant store via a Parquet snapshot, must project the **same** hierarchy and type dependencies as
 * the existing `GraphDbMapping` — compared by FQN, since the two backends share no node identity.
 *
 * The dependency `other` flag and the `weight` are ignored (see [DiffOptions]): both carry documented,
 * expected MVP divergences. The full diff is printed before the assertions so the first run against a
 * real store shows exactly which rows, if any, still need alignment.
 */
class GraphStoreVsGraphDbMappingIT : AbstractIntegrationTest() {

    @Test
    fun `GraphStore projection matches GraphDbMapping modulo documented differences`(@TempDir snapshotDir: Path) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val boltClient = IBoltClientFactory.newInstance(executor).createBoltClient(container.boltUri)
        boltClient.connect()
        try {
            // GraphStore side: jQA export -> Parquet snapshot -> load (the real write + read path).
            JqaSnapshotExporter(boltClient).exportTo(snapshotDir)
            val store = GraphStore()
            ParquetSnapshotLoader().load(snapshotDir, store)

            // Reference side: the existing GraphDbMapping projection of the same store.
            val reference = GraphDbMappingReference(boltClient).build()

            val result = SnapshotDiff.diff(
                store.hierarchyRows().toList(),
                store.typeDependencies().toList(),
                reference.hierarchy,
                reference.dependencies,
                DiffOptions(ignoreDependencyOther = true, ignoreDependencyWeight = true),
            )

            println("=== GraphStore vs GraphDbMapping diff ===")
            println(result.render())

            assertThat(result.hierarchy.isEmpty)
                .withFailMessage { "Hierarchy differs:\n${result.render()}" }
                .isTrue()
            assertThat(result.dependencies.isEmpty)
                .withFailMessage { "Dependencies differ:\n${result.render()}" }
                .isTrue()
        } finally {
            boltClient.disconnect()
            executor.shutdownNow()
        }
    }
}
