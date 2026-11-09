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

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.TypeResolver
import io.hierograph.graphstore.jvm.model.ArtifactClasspath
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClasspathEntry
import io.hierograph.graphstore.jvm.model.Origin
import io.hierograph.graphstore.jvm.scanner.BytecodeScanner
import io.hierograph.graphstore.jvm.scanner.ScanArtifact
import io.hierograph.graphstore.jvm.scanner.ScanResult
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.time.measureTimedValue

/**
 * Scans the reactor modules named in a build manifest (`hierograph:create-descriptor` → `.hierograph/manifest.json`).
 * The plugin only records what the build tool knows (module layout + resolved classpath); the server runs the
 * ASM [BytecodeScanner] itself.
 *
 * Scans are held **per module** ([Scanned]) so a file-watch can rescan only what changed and the whole set
 * be rebuilt from the cached union. The caller composes the steps:
 *  - [scan] reads the manifest and parses every module's compiled output in memory;
 *  - [rescan] re-parses only the given modules, returning an updated [Scanned];
 *  - [ingest] feeds the union straight into a [GraphStore] (the fast path — no Parquet round trip);
 *  - [writeSnapshot] writes the union as a Parquet snapshot the detail tools read — off the hot path.
 *
 * Host paths recorded in the manifest are remapped onto the local workspace (see [PathRemap]) so the same
 * manifest works whether the server runs on the build host or in a container with the workspace mounted.
 */
class ManifestGraphSource(private val scanner: BytecodeScanner = BytecodeScanner()) {

    /**
     * One scanned artifact: its GAV (a module's tests carry a `:test` suffix), display name, the (local)
     * compiled-output directory scanned, the scanned classes, and whether it is main or test output.
     */
    data class ModuleScan(
        val gav: String,
        val name: String,
        val outputDir: Path,
        val result: ScanResult,
        val origin: Origin = Origin.MODULE_MAIN,
    )

    /**
     * An output directory the manifest names, keyed like a [ModuleScan] (`<gav>`, or `<gav>:test`). The
     * directory need not exist: a module not compiled yet is declared, watched, and scanned once its output
     * appears.
     */
    data class DeclaredOutput(val gav: String, val name: String, val dir: Path, val origin: Origin)

    /**
     * A parsed manifest: the per-module scans, each module's resolved classpath (keyed by GAV), and every
     * [declared] output directory, scanned or not.
     */
    data class Scanned(
        val modules: Map<String, ModuleScan>,
        val classpaths: Map<String, ArtifactClasspath>,
        val declared: Map<String, DeclaredOutput> = emptyMap(),
    ) {
        /** gav → (local) compiled-output directory (main or test) of every declared output, for the file watcher. */
        val moduleDirs: Map<String, Path> get() = declared.mapValues { it.value.dir } + modules.mapValues { it.value.outputDir }

        /** The union of every module's classes and detail refs — for [ingest] and [writeSnapshot]. */
        fun combined(): ScanResult = ScanResult(
            modules.values.flatMap { it.result.classes },
            modules.values.flatMap { it.result.detailRefs },
        )
    }

    private data class ClasspathEntryDto(val gav: String, val internal: Boolean = false, val jar: String? = null)

    private data class ModuleDto(
        val gav: String,
        val name: String = "",
        val mainOutputDir: String? = null,
        val testOutputDir: String? = null,
        val sourceRoots: List<String> = emptyList(),
        val testSourceRoots: List<String> = emptyList(),
        val classpath: List<ClasspathEntryDto> = emptyList(),
        val testClasspath: List<ClasspathEntryDto>? = null,
    )

    private data class ManifestDto(
        val schemaVersion: Int = 0,
        val generator: String = "",
        val generatedAt: String = "",
        val reactorRoot: String = "",
        val modules: List<ModuleDto> = emptyList(),
    )

    /**
     * Reads [manifestPath] and scans each module's compiled output into per-module [ModuleScan]s: main
     * output always, and test output additionally when [includeTests]. A test-only module (no main output,
     * e.g. an integration-test harness) is scanned from its test output when [includeTests] is set, instead
     * of being skipped.
     *
     * Every output directory the manifest names is [declared][Scanned.declared]; only those that exist are
     * scanned. The result may hold no scans at all (nothing compiled yet), but the manifest must declare at
     * least one output directory.
     */
    fun scan(manifestPath: Path, includeTests: Boolean = false): Scanned {
        require(manifestPath.exists()) { "Manifest not found: $manifestPath (run `mvn … hierograph:create-descriptor hierograph:merge-descriptors`)" }
        val manifest = MAPPER.readValue<ManifestDto>(manifestPath.toFile())
        val remap = PathRemap(manifestPath, manifest.reactorRoot)

        val classpaths = LinkedHashMap<String, ArtifactClasspath>()
        val declared = LinkedHashMap<String, DeclaredOutput>()
        for (module in manifest.modules) {
            val name = module.name.ifEmpty { module.gav }
            val mainCp = module.classpath.map { ClasspathEntry(it.gav, it.internal) }
            classpaths[module.gav] = ArtifactClasspath(module.gav, name, mainCp)
            val mainDir = module.mainOutputDir?.let { remap.map(Path.of(it)) }
            if (mainDir != null) declared[module.gav] = DeclaredOutput(module.gav, name, mainDir, Origin.MODULE_MAIN)

            if (includeTests) {
                val testDir = module.testOutputDir?.let { remap.map(Path.of(it)) }
                if (testDir != null) {
                    val testKey = "${module.gav}:test"
                    val testName = "$name (test)"
                    // Test types resolve against their own main output first (when the module has one), then the
                    // module's full test classpath (all scopes). A manifest without testClasspath falls back to the
                    // main classpath, so test-scope deps (JUnit, …) resolve as external types. A test-only module
                    // (no main output — e.g. an integration-test harness) simply has no self-main entry.
                    val testCp = module.testClasspath?.map { ClasspathEntry(it.gav, it.internal) } ?: mainCp
                    val selfMain = if (mainDir != null) listOf(ClasspathEntry(module.gav, internal = true)) else emptyList()
                    classpaths[testKey] = ArtifactClasspath(testKey, testName, selfMain + testCp)
                    declared[testKey] = DeclaredOutput(testKey, testName, testDir, Origin.MODULE_TEST)
                }
            }
        }
        require(declared.isNotEmpty()) {
            "Manifest $manifestPath declares no module output directory (did `hierograph:create-descriptor` run?)"
        }

        val modules = LinkedHashMap<String, ModuleScan>()
        for (output in declared.values) {
            if (output.dir.isDirectory()) {
                modules[output.gav] = scanOutput(output)
            } else {
                log.info("Output of {} not compiled yet ({}); watching for it", output.gav, output.dir)
            }
        }
        log.info(
            "Scanned {} of {} declared artifact(s) from manifest {} (includeTests={})",
            modules.size, declared.size, manifestPath, includeTests,
        )
        return Scanned(modules, classpaths, declared)
    }

    /**
     * Rescans only [changedGavs], returning a new [Scanned] with those modules replaced. A module whose
     * output directory has disappeared is dropped (its classes then vanish from the rebuilt graph) but stays
     * declared; a declared module whose output has appeared is scanned and added.
     */
    fun rescan(previous: Scanned, changedGavs: Set<String>): Scanned {
        val modules = LinkedHashMap(previous.modules)
        for (gav in changedGavs) {
            val prev = previous.modules[gav]
            if (prev == null) {
                val output = previous.declared[gav] ?: continue
                if (output.dir.isDirectory()) modules[gav] = scanOutput(output)
                continue
            }
            if (!prev.outputDir.isDirectory()) {
                modules.remove(gav)
                continue
            }
            modules[gav] = prev.copy(result = scanModule(gav, prev.outputDir))
        }
        return Scanned(modules, previous.classpaths, previous.declared)
    }

    private fun scanOutput(output: DeclaredOutput): ModuleScan =
        ModuleScan(output.gav, output.name, output.dir, scanModule(output.gav, output.dir), output.origin)

    private fun scanModule(gav: String, dir: Path): ScanResult {
        val (result, elapsed) = measureTimedValue { scanner.scan(listOf(ScanArtifact(gav, dir))) }
        log.info("Scanned {}: {} types, {} detail refs in {}", gav, result.classes.size, result.detailRefs.size, elapsed)
        return result
    }

    /**
     * Ingests a [Scanned] straight into [store] — the same ingest path a Parquet load uses
     * ([GraphStore.registerArtifact] / [applyClass][GraphStore.applyClass] / [resolvePlaceholders]
     * [GraphStore.resolvePlaceholders]), fed from memory. Installs the real per-owner classpath-order
     * [TypeResolver] from the manifest (index 0 = the owner itself, then its deps) before ingest.
     */
    fun ingest(scanned: Scanned, store: GraphStore) {
        if (scanned.classpaths.isNotEmpty()) {
            val order = scanned.classpaths.mapValues { (gav, cp) -> listOf(gav) + cp.entries.map { it.depKey } }
            store.setTypeResolver(TypeResolver.classpathOrder(order))
        }
        val registered = HashSet<String>()
        for (module in scanned.modules.values) {
            for (artifactClass in module.result.classes) {
                val key = artifactClass.artifactKey
                if (registered.add(key)) {
                    val name = scanned.classpaths[key]?.name ?: key
                    store.registerArtifact(ArtifactInfo(key, name, key, module.origin))
                }
                store.applyClass(key, artifactClass.facts)
            }
        }
        store.resolvePlaceholders()
    }

    /** Writes the union of [scanned] as a Parquet snapshot into [dir] (replacing any previous contents). */
    fun writeSnapshot(scanned: Scanned, dir: Path) {
        deleteRecursively(dir)
        Files.createDirectories(dir)
        scanned.combined().writeTo(dir, classpaths = scanned.classpaths)
    }

    /** Convenience for one-shot use (and tests): [scan] then [writeSnapshot]; returns [snapshotDir]. */
    fun materialize(manifestPath: Path, snapshotDir: Path): Path {
        writeSnapshot(scan(manifestPath), snapshotDir)
        return snapshotDir
    }

    private fun deleteRecursively(dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    /**
     * Remaps a host path recorded in the manifest onto the local workspace. The manifest always lives at
     * `<reactorRoot>/.hierograph/manifest.json`, so the local reactor root is where the manifest was
     * actually read (`manifest.parent.parent`); a recorded path under the recorded reactor root is
     * re-rooted there. On the build host the two roots are equal → identity; in a container with the
     * workspace mounted they differ and the re-root makes the recorded paths resolve. A path outside the
     * reactor tree is left as recorded.
     */
    private class PathRemap(manifestPath: Path, recordedReactorRoot: String) {
        private val localRoot: Path? = manifestPath.toAbsolutePath().normalize().parent?.parent
        private val recordedRoot: Path? =
            recordedReactorRoot.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }

        fun map(recorded: Path): Path {
            val local = localRoot ?: return recorded
            val root = recordedRoot ?: return recorded
            if (local == root) return recorded
            return if (recorded.startsWith(root)) local.resolve(root.relativize(recorded)) else recorded
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ManifestGraphSource::class.java)
        private val MAPPER = JsonMapper.builder()
            .addModule(kotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
    }
}
