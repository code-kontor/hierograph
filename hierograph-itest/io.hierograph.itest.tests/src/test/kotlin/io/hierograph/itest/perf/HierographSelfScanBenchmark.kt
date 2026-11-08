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
package io.hierograph.itest.perf

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.PlaceholderPolicy
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.graphstore.scanner.BytecodeScanner
import io.hierograph.graphstore.scanner.ScanArtifact
import io.hierograph.server.mapping.GraphStoreToHGModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * Performance benchmark for scanning the **Hierograph project itself**. It first creates the build
 * manifest with the real plugin (`hierograph:create-descriptor` per module → `hierograph:merge-descriptors`), then measures:
 *
 *   a) **parsing** — the ASM [BytecodeScanner] over every module's compiled output into `ClassFacts`
 *      and member-level detail refs (what the server does in `manifest` mode); and
 *   b) **hierarchical-graph creation** — loading the scan into a [GraphStore] and bridging it to the
 *      `HGModel` the tools query (`ParquetReader.load` + [GraphStoreToHGModel]).
 *
 * Opt-in (not part of a normal `-Pitest` run): run with `-Dhierograph.benchmark=true`. It shells out to
 * `mvn`, so Maven must be on `PATH` and the project resolvable. A warm-up scan precedes the measured run.
 */
@EnabledIfSystemProperty(
    named = "hierograph.benchmark",
    matches = "true",
    disabledReason = "Opt-in performance benchmark; run with -Dhierograph.benchmark=true",
)
class HierographSelfScanBenchmark {

    private val log = LoggerFactory.getLogger(HierographSelfScanBenchmark::class.java)

    @Test
    fun `scan the hierograph reactor and build its hierarchical graph`() {
        val root = reactorRoot()
        log.info("Hierograph reactor root: {}", root)

        // 1) Create the manifest with the real plugin goals (compiles, then register + aggregate).
        createManifest(root)
        val manifestPath = root.resolve(".hierograph/manifest.json")
        assertThat(manifestPath.exists()).`as`("manifest created at $manifestPath").isTrue()

        val modules = MAPPER.readValue<ManifestDto>(manifestPath.toFile()).modules
        val artifacts = modules.mapNotNull { m ->
            m.mainOutputDir?.let(Path::of)?.takeIf { it.isDirectory() }?.let { ScanArtifact(m.gav, it) }
        }
        assertThat(artifacts).`as`("manifest modules with compiled output").isNotEmpty()

        // Warm up the JIT and filesystem caches so the measured run reflects steady state.
        BytecodeScanner().scan(artifacts)

        // 2a) PARSING — ASM scan into ClassFacts + member-level detail refs.
        val (scan, scanTime) = measureTimedValue { BytecodeScanner().scan(artifacts) }

        // Phase-1 step between parse and graph build: materialise the Parquet snapshot the loader reads.
        val snapshotDir = createTempDirectory("hierograph-bench-snapshot")
        val writeTime = measureTime { scan.writeTo(snapshotDir) }

        // 2b) HIERARCHICAL-GRAPH CREATION — load into a GraphStore, then build the HGModel.
        val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
        val loadTime = measureTime { ParquetReader().load(snapshotDir, store) }
        val (model, modelTime) = measureTimedValue { GraphStoreToHGModel.build(store) }
        val graphTime = loadTime + modelTime

        val hierarchy = model.hierarchy
        val rootChildren = hierarchy.childrenOf(hierarchy.rootNode)
        var nodes = 0
        rootChildren.forEach { hierarchy.traverse(it) { _ -> nodes++ } }
        val members = scan.classes.sumOf { it.facts.members.size }

        log.info(
            """

            ┌─ Hierograph self-scan benchmark ──────────────────────────────
            │ modules scanned     : {}
            │ types (classes)     : {}
            │ members             : {}
            │ detail refs         : {}
            │ hierarchy nodes     : {} (root children: {})
            ├─ (a) PARSING (ASM scan) ──────────────────────────────────────
            │ scan                : {}
            ├─ (b) HIERARCHICAL GRAPH ──────────────────────────────────────
            │ parquet load        : {}
            │ model build         : {}
            │ graph create (b)    : {}
            ├─ (materialisation overhead, Phase-1 only) ───────────────────
            │ snapshot write      : {}
            └───────────────────────────────────────────────────────────────
            """.trimIndent(),
            artifacts.size, scan.classes.size, members, scan.detailRefs.size, nodes, rootChildren.size,
            scanTime, loadTime, modelTime, graphTime, writeTime,
        )

        assertThat(scan.classes).`as`("scanned types").isNotEmpty()
        assertThat(rootChildren).`as`("hierarchy root children").isNotEmpty()
    }

    /** Walk up from the test's working directory to the reactor root (the dir holding `hierograph-maven-plugin`). */
    private fun reactorRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (dir.resolve("hierograph-maven-plugin").isDirectory() && dir.resolve("pom.xml").exists()) return dir
            dir = dir.parent
        }
        error("Could not locate the Hierograph reactor root from ${Path.of("").toAbsolutePath()}")
    }

    /** Run `mvn … hierograph:create-descriptor hierograph:merge-descriptors` at [root] to (re)create `.hierograph/manifest.json`. */
    private fun createManifest(root: Path) {
        val version = projectVersion(root)
        val plugin = "io.hierograph:io.hierograph.maven.plugin:$version"
        val cmd = listOf("mvn", "-q", "-B", "process-classes", "$plugin:create-descriptor", "$plugin:merge-descriptors")
        log.info("Creating manifest: {}", cmd.joinToString(" "))
        val process = ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "Manifest build failed (exit $exit):\n$output" }
    }

    /** The reactor's version, read from the root POM's first `<version>` (which is the plugin's version too). */
    private fun projectVersion(root: Path): String =
        VERSION.find(root.resolve("pom.xml").readText())?.groupValues?.get(1)
            ?: error("Could not read <version> from ${root.resolve("pom.xml")}")

    private data class ManifestDto(val modules: List<ModuleDto> = emptyList())
    private data class ModuleDto(val gav: String, val mainOutputDir: String? = null)

    private companion object {
        private val VERSION = Regex("<version>([^<]+)</version>")
        private val MAPPER = JsonMapper.builder()
            .addModule(kotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
    }
}
