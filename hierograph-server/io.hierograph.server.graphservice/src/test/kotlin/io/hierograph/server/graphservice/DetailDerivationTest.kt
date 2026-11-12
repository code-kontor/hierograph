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
package io.hierograph.server.graphservice

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.ExternalTypeFilter
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Deriving from the scan in memory gives what deriving from the snapshot written from it gives. */
class DetailDerivationTest {

    @Test
    fun `derivation from the scan equals derivation from its snapshot`(@TempDir tmp: Path) {
        // This module's classes and the graph store's: two artifacts with types that extend across them.
        val graphservice = Path.of("target/classes").toAbsolutePath()
        val graphstore = Path.of("../../hierograph-graphstore/io.hierograph.graphstore.jvm/target/classes").toAbsolutePath().normalize()
        assertThat(graphservice).`as`("module is compiled before the test runs").isDirectory()
        assertThat(graphstore).`as`("graph store module is compiled").isDirectory()

        val manifest = tmp.resolve("manifest.json")
        Files.writeString(
            manifest,
            """
            {
              "schemaVersion": 1,
              "generator": "test",
              "generatedAt": "2026-01-01T00:00:00Z",
              "reactorRoot": "${tmp.toUri().path}",
              "modules": [
                {
                  "gav": "io.hierograph:test-graphstore:0",
                  "name": "graphstore",
                  "mainOutputDir": "${graphstore.toUri().path}",
                  "testOutputDir": null,
                  "sourceRoots": [],
                  "classpath": []
                },
                {
                  "gav": "io.hierograph:test-graphservice:0",
                  "name": "graphservice",
                  "mainOutputDir": "${graphservice.toUri().path}",
                  "testOutputDir": null,
                  "sourceRoots": [],
                  "classpath": [
                    { "gav": "io.hierograph:test-graphstore:0", "internal": true, "jar": null }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val source = ManifestGraphSource()
        val scanned = source.scan(manifest)
        val store = GraphStore(ExternalTypeFilter.EXCLUDE_UBIQUITOUS).also { source.ingest(scanned, it) }
        val snapshot = tmp.resolve("snapshot")
        source.writeSnapshot(scanned, snapshot)
        val reader = ParquetReader()

        val fromSnapshot = DetailDerivation.derive(store, reader.readAllMembers(snapshot), reader.readDetailRefs(snapshot))
        val fromScan = DetailDerivation.fromScan(store, scanned.combined())

        assertThat(fromSnapshot.map { it.relationship }.toSet()).`as`("the fixture derives both kinds").contains("overrides", "calls")
        assertThat(fromScan).containsExactlyInAnyOrderElementsOf(fromSnapshot)
        // The detail tools list a member's edges in this order; the artifacts may be read in another order.
        assertThat(fromScan.groupBy { it.fromKey }).isEqualTo(fromSnapshot.groupBy { it.fromKey })
    }
}
