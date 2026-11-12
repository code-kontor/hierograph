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
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import io.hierograph.graphstore.jvm.scanner.BytecodeScanner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

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

        // A snapshot was written, one exploded artifact (a `.hierograph` directory) per artifact…
        val artifacts = Files.list(snapshotDir).use { it.toList() }
        assertThat(artifacts).isNotEmpty()
        assertThat(artifacts).allSatisfy {
            assertThat(it.fileName.toString()).endsWith(".hierograph")
            assertThat(it).isDirectory()
        }

        // …and it loads back into a GraphStore with this class present as a scanned type.
        val store = GraphStore(ExternalTypeFilter.EXCLUDE_UBIQUITOUS)
        ParquetReader().load(snapshotDir, store)
        val typeFqns = store.nodes().filter { JavaKinds.isTypeKind(it.kind) }.map { it.fqn }.toSet()
        assertThat(typeFqns).contains("io.hierograph.server.graphservice.ManifestGraphSource")
    }

    /** With includeTests, a module's test output is scanned as a separate `<gav>:test` artifact. */
    @Test
    fun `includeTests scans test output as a separate artifact`(@TempDir tmp: Path) {
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

        // Tests on: a second `:test` artifact appears next to the main one.
        val scanned = ManifestGraphSource().scan(manifest, includeTests = true)
        assertThat(scanned.modules.keys).contains(gav, "$gav:test")
        val test = scanned.modules.getValue("$gav:test")
        assertThat(test.result.classes).`as`("test output scanned").isNotEmpty()

        // The ingested graph carries a test type (this very class) that the tests-off scan does not.
        val store = GraphStore(ExternalTypeFilter.EXCLUDE_UBIQUITOUS)
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

        // Tests on: the test-only module appears only as `<gav>:test`, never as a main `<gav>`.
        val scanned = ManifestGraphSource().scan(manifest, includeTests = true)
        assertThat(scanned.modules.keys).contains("$itestGav:test")
        assertThat(scanned.modules.keys).doesNotContain(itestGav)
        val itest = scanned.modules.getValue("$itestGav:test")
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
        assertThat(before.moduleDirs).containsEntry(gav, listOf(outputDir))

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

    /** Two modules, `a` and `b`, each with a copy of this module's classes, and their manifest. */
    private fun twoModules(tmp: Path): Triple<Path, Path, Path> {
        val classesDir = Path.of("target/classes").toAbsolutePath()
        val a = copyTree(classesDir, tmp.resolve("a/target/classes"))
        val b = copyTree(classesDir, tmp.resolve("b/target/classes"))
        val manifest = Files.createDirectories(tmp.resolve(".hierograph")).resolve("manifest.json")
        fun module(name: String, dir: Path) = """
            { "gav": "io.hierograph:$name:0", "name": "$name", "mainOutputDir": "${dir.toUri().path}",
              "testOutputDir": null, "sourceRoots": [], "classpath": [] }
        """
        Files.writeString(
            manifest,
            """
            { "schemaVersion": 1, "generator": "test", "generatedAt": "2026-01-01T00:00:00Z",
              "reactorRoot": "${tmp.toUri().path}", "modules": [ ${module("a", a)}, ${module("b", b)} ] }
            """.trimIndent(),
        )
        return Triple(manifest, a, b)
    }

    /** A source whose first scan deletes [dir] just before the scanner reads it, as a concurrent `mvn clean` does. */
    private fun deletingOnFirstScan(dir: Path): Pair<ManifestGraphSource, () -> Int> {
        val scanner = BytecodeScanner()
        var calls = 0
        val source = ManifestGraphSource(scanner) { artifacts ->
            if (calls++ == 0) deleteTree(dir)
            scanner.scanByKey(artifacts)
        }
        return source to { calls }
    }

    @Test
    fun `an output deleted during the scan is left out, and the scan of the others succeeds`(@TempDir tmp: Path) {
        val (manifest, _, b) = twoModules(tmp)
        val (source, calls) = deletingOnFirstScan(b)

        val scanned = source.scan(manifest)

        assertThat(calls()).`as`("scanned again after the output disappeared").isEqualTo(2)
        assertThat(scanned.modules.keys).containsExactly("io.hierograph:a:0")
        assertThat(scanned.modules.getValue("io.hierograph:a:0").result.classes).isNotEmpty()
        // b stays declared and watched, and is scanned once it appears again.
        assertThat(scanned.declared.keys).containsExactly("io.hierograph:a:0", "io.hierograph:b:0")
        assertThat(scanned.moduleDirs).containsEntry("io.hierograph:b:0", listOf(b))
        copyTree(Path.of("target/classes").toAbsolutePath(), b)
        assertThat(source.rescan(scanned, setOf("io.hierograph:b:0")).modules.keys)
            .containsExactly("io.hierograph:a:0", "io.hierograph:b:0")
    }

    @Test
    fun `a rescan drops a module whose output is deleted during the scan`(@TempDir tmp: Path) {
        val (manifest, _, b) = twoModules(tmp)
        val scanned = ManifestGraphSource().scan(manifest)
        assertThat(scanned.modules.keys).containsExactly("io.hierograph:a:0", "io.hierograph:b:0")

        val (source, _) = deletingOnFirstScan(b)
        val rescanned = source.rescan(scanned, setOf("io.hierograph:a:0", "io.hierograph:b:0"))

        assertThat(rescanned.modules.keys).containsExactly("io.hierograph:a:0")
        assertThat(rescanned.declared.keys).contains("io.hierograph:b:0")
    }

    @Test
    fun `a scan failure without a disappeared output is not retried`(@TempDir tmp: Path) {
        val (manifest, _, _) = twoModules(tmp)
        var calls = 0
        val source = ManifestGraphSource(BytecodeScanner()) { calls++; error("broken class file") }

        assertThatThrownBy { source.scan(manifest) }.hasMessage("broken class file")
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `declare reads the declared outputs without scanning`(@TempDir tmp: Path) {
        val (manifest, a, b) = twoModules(tmp)
        var calls = 0
        val source = ManifestGraphSource(BytecodeScanner()) { calls++; emptyMap() }

        val declared = source.declare(manifest)

        assertThat(calls).isZero()
        assertThat(declared.modules).isEmpty()
        assertThat(declared.moduleDirs).containsEntry("io.hierograph:a:0", listOf(a)).containsEntry("io.hierograph:b:0", listOf(b))
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

    /**
     * A changed manifest is applied as a diff: an unchanged module keeps its scan (even when only its classpath
     * changed), a renamed one is scanned under its new key, a removed one is dropped, and a kept module whose
     * classes changed on disk since its scan is rescanned.
     */
    @Test
    fun `reload applies a changed manifest as a diff`(@TempDir tmp: Path) {
        val classesDir = Path.of("target/classes").toAbsolutePath()
        val testClassesDir = Path.of("target/test-classes").toAbsolutePath()
        val apiDir = copyTree(classesDir, tmp.resolve("billing-api/target/classes"))
        val coreDir = copyTree(testClassesDir, tmp.resolve("billing-core/target/classes"))
        val manifest = Files.createDirectories(tmp.resolve(".hierograph")).resolve("manifest.json")

        fun writeManifest(coreGav: String, apiClasspath: String) = Files.writeString(
            manifest,
            """
            {
              "reactorRoot": "${tmp.toUri().path}",
              "modules": [
                { "gav": "com.example:billing-api:1.4.0", "name": "billing-api", "mainOutputDir": "${apiDir.toUri().path}",
                  "classpath": [ $apiClasspath ] },
                { "gav": "$coreGav", "name": "billing-core", "mainOutputDir": "${coreDir.toUri().path}", "classpath": [] }
              ]
            }
            """.trimIndent(),
        )

        // A reused scan keeps its result object; a rescan produces a new one.
        val api = "com.example:billing-api:1.4.0"
        val core = "com.example:billing-core:1.4.0"
        val ledger = "com.example:billing-ledger:1.4.0"
        val source = ManifestGraphSource()
        writeManifest(core, "")
        val first = source.scan(manifest)

        // Classpath-only change: nothing is rescanned, the new classpath is taken.
        writeManifest(core, """{ "gav": "org.slf4j:slf4j-api:2.0.16", "internal": false, "jar": null }""")
        val second = source.reload(first, manifest)
        assertThat(second.modules.getValue(api).result).isSameAs(first.modules.getValue(api).result)
        assertThat(second.modules.getValue(core).result).isSameAs(first.modules.getValue(core).result)
        assertThat(second.classpaths.getValue(api).entries.map { it.depKey })
            .containsExactly("org.slf4j:slf4j-api:2.0.16")

        // Rename billing-core → billing-ledger: only that module is scanned, under its new key; the old key is gone.
        writeManifest(ledger, "")
        val third = source.reload(second, manifest)
        assertThat(third.modules.keys).containsExactlyInAnyOrder(api, ledger)
        assertThat(third.declared.keys).doesNotContain(core)
        assertThat(third.modules.getValue(api).result).isSameAs(first.modules.getValue(api).result)
        assertThat(third.modules.getValue(ledger).result.classes).isNotEmpty()

        // Classes of a kept module changed since its scan (compiled by the build that rewrote the manifest): rescanned.
        Files.walk(apiDir).use { paths ->
            val clazz = paths.filter { it.toString().endsWith(".class") }.findFirst().orElseThrow()
            Files.setLastModifiedTime(clazz, FileTime.fromMillis(Files.getLastModifiedTime(clazz).toMillis() + 10_000))
        }
        val fourth = source.reload(third, manifest)
        assertThat(fourth.modules.getValue(api).result).isNotSameAs(third.modules.getValue(api).result)
        assertThat(fourth.modules.getValue(ledger).result).isSameAs(third.modules.getValue(ledger).result)
    }

    /** A build file is stale when its content no longer matches the hash recorded in the manifest. */
    @Test
    fun `build files are stale when their content differs from the recorded hash`(@TempDir tmp: Path) {
        val pom = Files.writeString(tmp.resolve("pom.xml"), "<project/>")
        val gone = tmp.resolve("billing-export/pom.xml")
        val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pom)).joinToString("") { "%02x".format(it) }
        val manifest = Files.createDirectories(tmp.resolve(".hierograph")).resolve("manifest.json")
        Files.writeString(
            manifest,
            """
            {
              "reactorRoot": "${tmp.toUri().path}",
              "modules": [ { "gav": "com.example:billing-core:1.4.0", "mainOutputDir": "${tmp.resolve("billing-core/target/classes").toUri().path}" } ],
              "buildFiles": [ { "path": "$pom", "sha256": "$sha" } ]
            }
            """.trimIndent(),
        )
        val source = ManifestGraphSource()
        val scanned = source.scan(manifest)
        assertThat(scanned.buildFiles.keys).containsExactly(pom)
        assertThat(scanned.relocated).isFalse()
        assertThat(source.staleBuildFiles(scanned)).isEmpty()

        Files.writeString(pom, "<project><modules><module>billing-export</module></modules></project>")
        assertThat(source.staleBuildFiles(scanned)).containsExactly(pom)

        Files.writeString(pom, "<project/>") // reverted
        assertThat(source.staleBuildFiles(scanned)).isEmpty()

        // A listed file that no longer exists counts as changed.
        val withGone = scanned.copy(buildFiles = scanned.buildFiles + (gone to sha))
        assertThat(source.staleBuildFiles(withGone)).containsExactly(gone)
    }

    /**
     * An output with several directories (a source set compiled by Java and Kotlin) is one artifact: every
     * existing directory is scanned under the one key, the output is present while any directory exists, and a
     * change in either directory makes a kept scan stale.
     */
    @Test
    fun `an output with several directories is scanned as one artifact`(@TempDir tmp: Path) {
        val javaDir = copyTree(Path.of("target/classes").toAbsolutePath(), tmp.resolve("billing-core/build/classes/java/main"))
        val kotlinDir = copyTree(Path.of("target/test-classes").toAbsolutePath(), tmp.resolve("billing-core/build/classes/kotlin/main"))
        val gav = "com.example:billing-core:1.4.0"
        val manifest = Files.createDirectories(tmp.resolve(".hierograph")).resolve("manifest.json")
        Files.writeString(
            manifest,
            """
            {
              "reactorRoot": "${tmp.toUri().path}",
              "buildTool": { "name": "gradle", "version": "8.10" },
              "regenerate": ["./gradlew", "-q", "hierographManifest"],
              "modules": [ { "gav": "$gav", "name": "billing-core",
                             "mainOutputDirs": ["${javaDir.toUri().path}", "${kotlinDir.toUri().path}"] } ]
            }
            """.trimIndent(),
        )
        val source = ManifestGraphSource()
        val scanned = source.scan(manifest)
        assertThat(scanned.buildTool).isEqualTo("gradle")
        assertThat(scanned.regenerate).containsExactly("./gradlew", "-q", "hierographManifest")
        assertThat(scanned.moduleDirs.getValue(gav)).containsExactly(javaDir, kotlinDir)
        val typeFqns = scanned.modules.getValue(gav).result.classes.map { it.facts.fqn }
        assertThat(typeFqns)
            .contains("io.hierograph.server.graphservice.ManifestGraphSource") // from the first directory
            .contains("io.hierograph.server.graphservice.ManifestGraphSourceTest") // from the second
        assertThat(scanned.modules.getValue(gav).result.classes.map { it.artifactKey }.distinct()).containsExactly(gav)

        // A change in the second directory makes the kept scan stale.
        Files.walk(kotlinDir).use { paths ->
            val clazz = paths.filter { it.toString().endsWith(".class") }.findFirst().orElseThrow()
            Files.setLastModifiedTime(clazz, FileTime.fromMillis(Files.getLastModifiedTime(clazz).toMillis() + 10_000))
        }
        val reloaded = source.reload(scanned, manifest)
        assertThat(reloaded.modules.getValue(gav).result).isNotSameAs(scanned.modules.getValue(gav).result)

        // Present while one directory exists; dropped when none does.
        deleteTree(kotlinDir)
        assertThat(source.rescan(reloaded, setOf(gav)).modules.keys).containsExactly(gav)
        deleteTree(javaDir)
        assertThat(source.rescan(reloaded, setOf(gav)).modules).isEmpty()
    }

    /**
     * A manifest written in a container's state directory records the container's build root. With that build
     * root configured, its paths are used as recorded and it is not relocated — although it does not live at
     * `<build root>/.hierograph/manifest.json`.
     */
    @Test
    fun `an explicit build root takes a manifest outside the build root as not relocated`(@TempDir tmp: Path) {
        val workspace = Files.createDirectories(tmp.resolve("workspace"))
        val outputDir = copyTree(Path.of("target/classes").toAbsolutePath(), workspace.resolve("billing-core/target/classes"))
        val manifest = Files.createDirectories(tmp.resolve("state")).resolve("manifest.json")
        Files.writeString(
            manifest,
            """{ "reactorRoot": "${workspace.toUri().path.removeSuffix("/")}",
                 "modules": [ { "gav": "com.example:billing-core:1.4.0", "mainOutputDirs": ["${outputDir.toUri().path}"] } ] }""",
        )
        val source = ManifestGraphSource()

        val withRoot = source.scan(manifest, buildRoot = workspace)
        assertThat(withRoot.relocated).isFalse()
        assertThat(withRoot.modules.keys).containsExactly("com.example:billing-core:1.4.0")

        // Without it, the root is derived from the manifest's location (tmp), which differs from the recorded one.
        assertThat(source.scan(manifest).relocated).isTrue()
    }

    private fun deleteTree(dir: Path) = Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }

    private fun copyTree(from: Path, to: Path): Path {
        Files.walk(from).use { paths ->
            paths.forEach { p -> Files.copy(p, to.resolve(from.relativize(p).toString()).also { Files.createDirectories(it.parent) }) }
        }
        return to
    }
}
