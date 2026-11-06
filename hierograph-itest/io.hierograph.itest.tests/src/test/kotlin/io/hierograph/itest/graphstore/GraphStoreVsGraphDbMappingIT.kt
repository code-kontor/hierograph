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
import io.hierograph.graphstore.jqa.DiffOptions
import io.hierograph.graphstore.jqa.GraphDbMappingReference
import io.hierograph.graphstore.jqa.JqaParquetExporter
import io.hierograph.graphstore.jqa.SnapshotDiff
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.itest.fwk.AbstractIntegrationTest
import io.hierograph.mcp.javaspec.JavaKinds
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
 * expected MVP divergences. Acceptance is defined by **category**, not exact rows (so it is stable across
 * scans): type dependencies must match exactly, and the only tolerated hierarchy differences are the two
 * documented selection-seam artifacts —
 *  - **only in GraphDbMapping:** `PACKAGE` rows. jQA models every jar directory (incl. resource dirs like
 *    `mockito-extensions`) and empty packages as `:Package`; the GraphStore derives packages from scanned
 *    type FQNs, so a package with no types simply never appears.
 *  - **only in GraphStore:** type rows. A few types are reachable via `(:Artifact)-[:CONTAINS]->(:Type)`
 *    (the export's seam) but are not linked under their `:Package` in jQA, so the package-based reference
 *    rules miss them.
 *
 * Any other divergence — a dependency diff, a non-`PACKAGE` reference-only row, a non-type GraphStore-only
 * row (e.g. a shared node re-parented) — fails the test. The full diff is printed before the assertions.
 */
class GraphStoreVsGraphDbMappingIT : AbstractIntegrationTest() {

    @Test
    fun `GraphStore projection matches GraphDbMapping modulo documented differences`(@TempDir parquetDir: Path) {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val boltClient = IBoltClientFactory.newInstance(executor).createBoltClient(container.boltUri)
        boltClient.connect()
        try {
            // GraphStore side: jQA export -> Parquet snapshot -> load (the real write + read path).
            JqaParquetExporter(boltClient).exportTo(parquetDir)
            val store = GraphStore(PlaceholderPolicy.JQA_ALIGNED)
            ParquetReader().load(parquetDir, store)

            // Reference side: the existing GraphDbMapping projection of the same store, members included
            // (the GraphStore materializes members as children of their type).
            val reference = GraphDbMappingReference(boltClient, includeMembers = true).build()

            val result = SnapshotDiff.diff(
                store.hierarchyRows().toList(),
                store.typeDependencies().toList(),
                reference.hierarchy,
                reference.dependencies,
                DiffOptions(ignoreDependencyOther = true, ignoreDependencyWeight = true),
            )

            println("=== GraphStore vs GraphDbMapping diff ===")
            println(result.render())

            // Type dependencies must match exactly.
            assertThat(result.dependencies.isEmpty)
                .withFailMessage { "Dependencies differ:\n${result.render()}" }
                .isTrue()

            // Hierarchy differences are confined to the documented selection-seam categories: packages, and
            // jQA's *referenced-but-not-declared* member stubs — members of library types, or inherited
            // members invoked through a subtype (e.g. ApplicationContext#getBean, declared on BeanFactory).
            // Those are known only through member-level refs, which the GraphStore deliberately does not
            // materialize. Every *declared* member (one in members.parquet) must match.
            val declaredMembers = ParquetReader().readMemberKeys(parquetDir).toHashSet()
            val referenceOnlyStubs = result.hierarchy.onlyInReference.count { it.kind in JavaKinds.MEMBER_KINDS }
            println("reference-only member stubs (referenced, not declared): $referenceOnlyStubs")
            val unexpectedOnlyInReference = result.hierarchy.onlyInReference.filterNot { row ->
                row.kind == JavaKinds.PACKAGE || row.kind in JavaKinds.MEMBER_KINDS && row.fqn !in declaredMembers
            }
            assertThat(unexpectedOnlyInReference)
                .withFailMessage { "Unexpected rows only in GraphDbMapping:\n${unexpectedOnlyInReference.take(50).joinToString("\n")}" }
                .isEmpty()
            assertThat(result.hierarchy.onlyInGraphStore)
                .withFailMessage { "Unexpected non-type rows only in GraphStore:\n${result.render()}" }
                .allMatch { JavaKinds.isTypeKind(it.kind) }
        } finally {
            boltClient.disconnect()
            executor.shutdownNow()
        }
    }
}
