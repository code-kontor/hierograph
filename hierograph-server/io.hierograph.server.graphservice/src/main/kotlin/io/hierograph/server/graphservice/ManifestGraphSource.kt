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
import io.hierograph.graphstore.jvm.parquet.ParquetWriter
import io.hierograph.graphstore.jvm.scanner.BytecodeScanner
import io.hierograph.graphstore.jvm.scanner.ScanArtifact
import io.hierograph.graphstore.jvm.scanner.ScanResult
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.time.measureTimedValue

/**
 * Scans the modules named in a build manifest (`.hierograph/manifest.json`, written by a build-tool plugin).
 * The plugin only records what the build tool knows (module layout + resolved classpath); the server runs the
 * ASM [BytecodeScanner] itself.
 *
 * Scans are held **per module** ([Scanned]) so a file-watch can rescan only what changed and the whole set
 * be rebuilt from the cached union. The caller composes the steps:
 *  - [scan] reads the manifest and parses every module's compiled output in memory;
 *  - [rescan] re-parses only the given modules, returning an updated [Scanned];
 *  - [reload] applies a changed manifest as a diff, rescanning only added or changed modules;
 *  - [ingest] feeds the union straight into a [GraphStore] (the fast path — no Parquet round trip);
 *  - [writeSnapshot] writes the union as a Parquet snapshot the detail tools read — off the hot path.
 *
 * Host paths recorded in the manifest are remapped onto the local workspace (see [PathRemap]) so the same
 * manifest works whether the server runs on the build host or in a container with the workspace mounted.
 */
class ManifestGraphSource(private val scanner: BytecodeScanner = BytecodeScanner()) {

    /**
     * One scanned artifact: its GAV (a module's tests carry a `:test` suffix), display name, the (local)
     * compiled-output directories scanned, the scanned classes, whether it is main or test output, and the
     * directories' [signature] at scan time.
     */
    data class ModuleScan(
        val gav: String,
        val name: String,
        val outputDirs: List<Path>,
        val result: ScanResult,
        val origin: Origin = Origin.MODULE_MAIN,
        val signature: Long = 0L, // outputSignature over outputDirs, taken just before the scan; tells a stale scan apart
    )

    /**
     * An output the manifest names, keyed like a [ModuleScan] (`<gav>`, or `<gav>:test`): one or more
     * directories that together form one artifact (a source set compiled by Java and Kotlin into separate
     * directories). The directories need not exist: an output not compiled yet is declared, watched, and
     * scanned once one of them appears.
     */
    data class DeclaredOutput(val gav: String, val name: String, val dirs: List<Path>, val origin: Origin) {
        /** Whether any of the directories exists, i.e. there is something to scan. */
        val present: Boolean get() = dirs.any { it.isDirectory() }
    }

    /**
     * A parsed manifest: the per-module scans, each module's resolved classpath (keyed by GAV), every
     * [declared] output directory, scanned or not, and the build's [buildFiles] (local path → SHA-256 of the
     * content the manifest was merged from). [relocated] is true when the manifest was recorded under a
     * different build root (a container with the workspace mounted elsewhere). [buildTool] names the producing
     * tool (informational) and [regenerate] the command that rewrites the manifest, as the manifest gives it.
     */
    data class Scanned(
        val modules: Map<String, ModuleScan>,
        val classpaths: Map<String, ArtifactClasspath>,
        val declared: Map<String, DeclaredOutput> = emptyMap(),
        val buildFiles: Map<Path, String> = emptyMap(),
        val relocated: Boolean = false,
        val buildTool: String? = null,
        val regenerate: List<String>? = null,
    ) {
        /** gav → (local) compiled-output directories (main or test) of every declared output, for the file watcher. */
        val moduleDirs: Map<String, List<Path>> get() = declared.mapValues { it.value.dirs } + modules.mapValues { it.value.outputDirs }

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
        val mainOutputDirs: List<String>? = null,
        val testOutputDirs: List<String>? = null,
        val sourceRoots: List<String> = emptyList(),
        val testSourceRoots: List<String> = emptyList(),
        val classpath: List<ClasspathEntryDto> = emptyList(),
        val testClasspath: List<ClasspathEntryDto>? = null,
    )

    private data class BuildFileDto(val path: String = "", val sha256: String = "")

    private data class BuildToolDto(val name: String = "", val version: String = "")

    private data class ManifestDto(
        val schemaVersion: Int = 0,
        val generator: String = "",
        val generatedAt: String = "",
        val reactorRoot: String = "",
        val buildTool: BuildToolDto? = null,
        val regenerate: List<String>? = null,
        val modules: List<ModuleDto> = emptyList(),
        val buildFiles: List<BuildFileDto> = emptyList(),
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
    fun scan(manifestPath: Path, includeTests: Boolean = false, buildRoot: Path? = null): Scanned {
        val plan = readPlan(manifestPath, includeTests, buildRoot)
        val modules = LinkedHashMap<String, ModuleScan>()
        for (output in plan.declared.values) {
            if (output.present) {
                modules[output.gav] = scanOutput(output)
            } else {
                log.info("Output of {} not compiled yet ({}); watching for it", output.gav, output.dirs)
            }
        }
        log.info(
            "Scanned {} of {} declared artifact(s) from manifest {} (includeTests={})",
            modules.size, plan.declared.size, manifestPath, includeTests,
        )
        return plan.toScanned(modules)
    }

    /**
     * Applies a changed manifest to [previous] as a **diff**, keyed by declared output: a removed output is
     * dropped; an added one is scanned if one of its directories exists; a kept one is rescanned only when its
     * output directories changed or their contents changed since the scan ([ModuleScan.signature]), and
     * otherwise reused. Classpaths and names are taken from the new manifest. A classpath change alone needs no
     * rescan: a scan depends only on the key and the directories, and classpaths are applied at [ingest].
     *
     * The signature check matters because the watcher re-baselines when it is re-targeted after a manifest
     * change: classes the same build compiled before that point are never reported as a change.
     */
    fun reload(previous: Scanned, manifestPath: Path, includeTests: Boolean = false, buildRoot: Path? = null): Scanned {
        val plan = readPlan(manifestPath, includeTests, buildRoot)
        val modules = LinkedHashMap<String, ModuleScan>()
        var reused = 0
        for (output in plan.declared.values) {
            val prev = previous.modules[output.gav]
            when {
                !output.present -> {}
                prev != null && prev.outputDirs == output.dirs && prev.signature == signatureOf(output.dirs) -> {
                    modules[output.gav] = prev.copy(name = output.name, origin = output.origin)
                    reused++
                }
                else -> modules[output.gav] = scanOutput(output)
            }
        }
        val dropped = previous.declared.keys.count { it !in plan.declared }
        log.info(
            "Applied manifest {}: {} of {} declared artifact(s) present, {} reused, {} scanned, {} dropped",
            manifestPath, modules.size, plan.declared.size, reused, modules.size - reused, dropped,
        )
        return plan.toScanned(modules)
    }

    /**
     * The build files of [scanned] whose current content no longer matches the hash recorded in the manifest,
     * sorted; a missing file counts as changed. Empty when the manifest reflects the build definition (or the
     * manifest lists no build files).
     */
    fun staleBuildFiles(scanned: Scanned): List<Path> =
        scanned.buildFiles.filter { (path, sha256) -> contentSha256(path) != sha256 }.keys.sorted()

    private fun contentSha256(file: Path): String? = runCatching {
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /**
     * What a manifest declares: each output's classpath and (re-rooted) output directories, the build files, the
     * build tool and the regeneration command.
     */
    private class Plan(
        val classpaths: Map<String, ArtifactClasspath>,
        val declared: Map<String, DeclaredOutput>,
        val buildFiles: Map<Path, String>,
        val relocated: Boolean,
        val buildTool: String?,
        val regenerate: List<String>?,
    ) {
        fun toScanned(modules: Map<String, ModuleScan>) =
            Scanned(modules, classpaths, declared, buildFiles, relocated, buildTool, regenerate)
    }

    private fun readPlan(manifestPath: Path, includeTests: Boolean, buildRoot: Path?): Plan {
        require(manifestPath.exists()) { "Manifest not found: $manifestPath (run a build with the Hierograph plugin applied)" }
        val manifest = MAPPER.readValue<ManifestDto>(manifestPath.toFile())
        val remap = PathRemap(buildRoot ?: defaultBuildRoot(manifestPath), manifest.reactorRoot)

        val classpaths = LinkedHashMap<String, ArtifactClasspath>()
        val declared = LinkedHashMap<String, DeclaredOutput>()
        for (module in manifest.modules) {
            val name = module.name.ifEmpty { module.gav }
            val mainCp = module.classpath.map { ClasspathEntry(it.gav, it.internal) }
            classpaths[module.gav] = ArtifactClasspath(module.gav, name, mainCp)
            // The directory lists, falling back to the single directory a manifest from an older producer records.
            val mainDirs = (module.mainOutputDirs ?: listOfNotNull(module.mainOutputDir)).map { remap.map(Path.of(it)) }
            if (mainDirs.isNotEmpty()) declared[module.gav] = DeclaredOutput(module.gav, name, mainDirs, Origin.MODULE_MAIN)

            if (includeTests) {
                val testDirs = (module.testOutputDirs ?: listOfNotNull(module.testOutputDir)).map { remap.map(Path.of(it)) }
                if (testDirs.isNotEmpty()) {
                    val testKey = "${module.gav}:test"
                    val testName = "$name (test)"
                    // Test types resolve against their own main output first (when the module has one), then the
                    // module's full test classpath (all scopes). A manifest without testClasspath falls back to the
                    // main classpath, so test-scope deps (JUnit, …) resolve as external types. A test-only module
                    // (no main output — e.g. an integration-test harness) simply has no self-main entry.
                    val testCp = module.testClasspath?.map { ClasspathEntry(it.gav, it.internal) } ?: mainCp
                    val selfMain = if (mainDirs.isNotEmpty()) listOf(ClasspathEntry(module.gav, internal = true)) else emptyList()
                    classpaths[testKey] = ArtifactClasspath(testKey, testName, selfMain + testCp)
                    declared[testKey] = DeclaredOutput(testKey, testName, testDirs, Origin.MODULE_TEST)
                }
            }
        }
        require(declared.isNotEmpty()) {
            "Manifest $manifestPath declares no module output directory"
        }
        val buildFiles = manifest.buildFiles.filter { it.path.isNotEmpty() }
            .associate { remap.map(Path.of(it.path)) to it.sha256 }
        val buildTool = manifest.buildTool?.name?.takeIf { it.isNotEmpty() }
        return Plan(classpaths, declared, buildFiles, remap.relocated, buildTool, manifest.regenerate?.takeIf { it.isNotEmpty() })
    }

    /**
     * Rescans only [changedGavs], returning a new [Scanned] with those modules replaced. A module whose
     * output directories have all disappeared is dropped (its classes then vanish from the rebuilt graph) but
     * stays declared; a declared module whose output has appeared is scanned and added.
     */
    fun rescan(previous: Scanned, changedGavs: Set<String>): Scanned {
        val modules = LinkedHashMap(previous.modules)
        for (gav in changedGavs) {
            val prev = previous.modules[gav]
            if (prev == null) {
                val output = previous.declared[gav] ?: continue
                if (output.present) modules[gav] = scanOutput(output)
                continue
            }
            if (prev.outputDirs.none { it.isDirectory() }) {
                modules.remove(gav)
                continue
            }
            val signature = signatureOf(prev.outputDirs)
            modules[gav] = prev.copy(result = scanModule(gav, prev.outputDirs), signature = signature)
        }
        return previous.copy(modules = modules)
    }

    private fun scanOutput(output: DeclaredOutput): ModuleScan {
        val signature = signatureOf(output.dirs) // before the scan: a write during the scan then shows as stale
        return ModuleScan(output.gav, output.name, output.dirs, scanModule(output.gav, output.dirs), output.origin, signature)
    }

    private fun signatureOf(dirs: List<Path>): Long = dirs.sumOf { outputSignature(it) }

    /** Scans the existing [dirs] of one artifact, all under the key [gav]. */
    private fun scanModule(gav: String, dirs: List<Path>): ScanResult {
        val artifacts = dirs.filter { it.isDirectory() }.map { ScanArtifact(gav, it) }
        val (result, elapsed) = measureTimedValue { scanner.scan(artifacts) }
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

    /**
     * Writes the union of [scanned] as a Parquet snapshot into [dir] (replacing any previous contents), one
     * exploded artifact per artifact key: the snapshot stays local, so its tables are not zipped, and
     * deleting [dir] removes everything it holds.
     */
    fun writeSnapshot(scanned: Scanned, dir: Path) {
        deleteRecursively(dir)
        Files.createDirectories(dir)
        scanned.combined().writeTo(dir, classpaths = scanned.classpaths, packaging = ParquetWriter.Packaging.EXPLODED)
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
     * Remaps a host path recorded in the manifest onto the local workspace. The local build root is the
     * configured one, or by default where the manifest was read (`<build root>/.hierograph/manifest.json`,
     * see [defaultBuildRoot]); a recorded path under the recorded reactor root is re-rooted there. On the build
     * host, and for a manifest written where it is read, the two roots are equal → identity; for a host manifest
     * read in a container with the workspace mounted they differ and the re-root makes the recorded paths
     * resolve. A path outside the build tree is left as recorded.
     */
    private class PathRemap(localBuildRoot: Path?, recordedReactorRoot: String) {
        private val localRoot: Path? = localBuildRoot?.toAbsolutePath()?.normalize()
        private val recordedRoot: Path? =
            recordedReactorRoot.takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it) }.getOrNull() }

        /** True when the manifest was recorded under a different build root than the one it is read from. */
        val relocated: Boolean get() = localRoot != null && recordedRoot != null && localRoot != recordedRoot

        fun map(recorded: Path): Path {
            val local = localRoot ?: return recorded
            val root = recordedRoot ?: return recorded
            if (local == root) return recorded
            return if (recorded.startsWith(root)) local.resolve(root.relativize(recorded)) else recorded
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ManifestGraphSource::class.java)

        /**
         * The local build root of a manifest at [manifestPath] when none is configured: its grandparent, because a
         * manifest written by the build lives at `<build root>/.hierograph/manifest.json`.
         */
        fun defaultBuildRoot(manifestPath: Path): Path? = manifestPath.toAbsolutePath().normalize().parent?.parent
        private val MAPPER = JsonMapper.builder()
            .addModule(kotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
    }
}
