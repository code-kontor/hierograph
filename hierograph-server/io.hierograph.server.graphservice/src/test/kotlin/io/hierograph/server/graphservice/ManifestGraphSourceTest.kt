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
import io.hierograph.graphstore.jvm.PlaceholderPolicy
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.Origin
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
                    { "gav": "io.hierograph:io.hierograph.graphstore.jvm:0", "internal": true, "jar": null }
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
        val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
        ParquetReader().load(snapshotDir, store)
        val typeFqns = store.nodes().filter { JavaKinds.isTypeKind(it.kind) }.map { it.fqn }.toSet()
        assertThat(typeFqns).contains("io.hierograph.server.graphservice.ManifestGraphSource")
    }

    /** With includeTests, a module's test output is scanned as a separate `<gav>:test` MODULE_TEST artifact. */
    @Test
    fun `includeTests scans test output as a separate MODULE_TEST artifact`(@TempDir tmp: Path) {
        val classesDir = Path.of("target/classes").toAbsolutePath()
        val testClassesDir = Path.of("target/test-classes").toAbsolutePath()
        assertThat(Files.isDirectory(classesDir)).`as`("main classes compiled").isTrue()
        assertThat(Files.isDirectory(testClassesDir)).`as`("test classes compiled before the test runs").isTrue()

        val gav = "io.hierograph:test-graphservice:0"
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
                  "gav": "$gav",
                  "name": "graphservice",
                  "mainOutputDir": "${classesDir.toUri().path}",
                  "testOutputDir": "${testClassesDir.toUri().path}",
                  "sourceRoots": [],
                  "classpath": []
                }
              ]
            }
            """.trimIndent(),
        )

        // Default (tests off): only the main artifact is scanned.
        assertThat(ManifestGraphSource().scan(manifest).modules.keys).containsExactly(gav)

        // Tests on: a second `:test` artifact appears, tagged MODULE_TEST, with the main one MODULE_MAIN.
        val scanned = ManifestGraphSource().scan(manifest, includeTests = true)
        assertThat(scanned.modules.keys).contains(gav, "$gav:test")
        assertThat(scanned.modules.getValue(gav).origin).isEqualTo(Origin.MODULE_MAIN)
        val test = scanned.modules.getValue("$gav:test")
        assertThat(test.origin).isEqualTo(Origin.MODULE_TEST)
        assertThat(test.result.classes).`as`("test output scanned").isNotEmpty()

        // The ingested graph carries a test type (this very class) that the tests-off scan does not.
        val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
        ManifestGraphSource().ingest(scanned, store)
        val typeFqns = store.nodes().filter { JavaKinds.isTypeKind(it.kind) }.map { it.fqn }.toSet()
        assertThat(typeFqns).contains("io.hierograph.server.graphservice.ManifestGraphSourceTest")
    }

    /** A test-only module (no main output) is skipped by default but, with includeTests, scanned as `<gav>:test` only. */
    @Test
    fun `test-only module is scanned from test output under includeTests`(@TempDir tmp: Path) {
        val classesDir = Path.of("target/classes").toAbsolutePath()
        val testClassesDir = Path.of("target/test-classes").toAbsolutePath()
        assertThat(Files.isDirectory(classesDir)).`as`("main classes compiled").isTrue()
        assertThat(Files.isDirectory(testClassesDir)).`as`("test classes compiled before the test runs").isTrue()

        val mainGav = "io.hierograph:test-graphservice:0"
        val itestGav = "io.hierograph:test-itest:0"
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
                  "gav": "$mainGav",
                  "name": "graphservice",
                  "mainOutputDir": "${classesDir.toUri().path}",
                  "testOutputDir": null,
                  "sourceRoots": [],
                  "classpath": []
                },
                {
                  "gav": "$itestGav",
                  "name": "itest",
                  "mainOutputDir": null,
                  "testOutputDir": "${testClassesDir.toUri().path}",
                  "sourceRoots": [],
                  "classpath": []
                }
              ]
            }
            """.trimIndent(),
        )

        // Default (tests off): the test-only module has no main output and is skipped entirely.
        assertThat(ManifestGraphSource().scan(manifest).modules.keys).containsExactly(mainGav)

        // Tests on: the test-only module appears only as `<gav>:test` (MODULE_TEST), never as a main `<gav>`.
        val scanned = ManifestGraphSource().scan(manifest, includeTests = true)
        assertThat(scanned.modules.keys).contains("$itestGav:test")
        assertThat(scanned.modules.keys).doesNotContain(itestGav)
        val itest = scanned.modules.getValue("$itestGav:test")
        assertThat(itest.origin).isEqualTo(Origin.MODULE_TEST)
        assertThat(itest.result.classes).`as`("test-only output scanned").isNotEmpty()
    }

    /** An output directory that does not exist yet is declared and watched, and scanned by `rescan` once it appears. */
    @Test
    fun `uncompiled module is declared and scanned once its output appears`(@TempDir tmp: Path) {
        val classesDir = Path.of("target/classes").toAbsolutePath()
        assertThat(Files.isDirectory(classesDir)).`as`("main classes compiled").isTrue()

        val gav = "io.hierograph:test-graphservice:0"
        val outputDir = tmp.resolve("graphservice/target/classes")
        // The manifest sits at <reactorRoot>/.hierograph/, so the recorded reactor root is the local one.
        val manifest = Files.createDirectories(tmp.resolve(".hierograph")).resolve("manifest.json")
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
                  "gav": "$gav",
                  "name": "graphservice",
                  "mainOutputDir": "${outputDir.toUri().path}",
                  "testOutputDir": null,
                  "sourceRoots": [],
                  "classpath": []
                }
              ]
            }
            """.trimIndent(),
        )

        // Nothing compiled: no scan, but the output is declared and a watch target.
        val source = ManifestGraphSource()
        val before = source.scan(manifest)
        assertThat(before.modules).isEmpty()
        assertThat(before.declared.keys).containsExactly(gav)
        assertThat(before.moduleDirs).containsEntry(gav, outputDir)

        // The output appears (first compile) → rescan adds the module.
        Files.walk(classesDir).use { paths ->
            paths.forEach { p -> Files.copy(p, outputDir.resolve(classesDir.relativize(p).toString()).also { Files.createDirectories(it.parent) }) }
        }
        val after = source.rescan(before, setOf(gav))
        assertThat(after.modules.keys).containsExactly(gav)
        assertThat(after.modules.getValue(gav).result.classes).isNotEmpty()

        // The output disappears again (`mvn clean`) → the scan is dropped, the declaration stays.
        Files.walk(outputDir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        val cleaned = source.rescan(after, setOf(gav))
        assertThat(cleaned.modules).isEmpty()
        assertThat(cleaned.declared.keys).containsExactly(gav)
    }

    /** A manifest that declares no output directory at all is rejected. */
    @Test
    fun `manifest without any output directory is rejected`(@TempDir tmp: Path) {
        val manifest = tmp.resolve("manifest.json")
        Files.writeString(
            manifest,
            """{ "reactorRoot": "${tmp.toUri().path}", "modules": [ { "gav": "com.example:billing-core:1.4.0" } ] }""",
        )
        assertThatThrownBy { ManifestGraphSource().scan(manifest) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("declares no module output directory")
    }
}
