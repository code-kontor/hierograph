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

import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.PlaceholderPolicy
import io.hierograph.graphstore.model.JavaKinds
import io.hierograph.graphstore.parquet.ParquetReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManifestGraphSourceTest {

    /** Scanning this module's own compiled classes via a manifest must yield a snapshot that loads back. */
    @Test
    fun `materializes a loadable snapshot from a manifest`(@TempDir tmp: Path) {
        val classesDir = Path.of("target/classes").toAbsolutePath()
        assertThat(Files.isDirectory(classesDir)).`as`("module is compiled before the test runs").isTrue()

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
                  "gav": "io.hierograph:test-graphservice:0",
                  "name": "graphservice",
                  "mainOutputDir": "${classesDir.toUri().path}",
                  "testOutputDir": null,
                  "sourceRoots": [],
                  "classpath": [
                    { "gav": "io.hierograph:io.hierograph.graphstore:0", "internal": true, "jar": null }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val snapshotDir = tmp.resolve("snapshot")
        ManifestGraphSource().materialize(manifest, snapshotDir)

        // A snapshot was written…
        assertThat(Files.list(snapshotDir).use { it.count() }).isGreaterThan(0L)

        // …and it loads back into a GraphStore with this class present as a scanned type.
        val store = GraphStore(PlaceholderPolicy.JQA_ALIGNED)
        ParquetReader().load(snapshotDir, store)
        val typeFqns = store.nodes().filter { JavaKinds.isTypeKind(it.kind) }.map { it.fqn }.toSet()
        assertThat(typeFqns).contains("io.hierograph.server.graphservice.ManifestGraphSource")
    }
}
