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
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.TypeResolver
import io.hierograph.graphstore.model.ArtifactClasspath
import io.hierograph.graphstore.model.ArtifactInfo
import io.hierograph.graphstore.model.ClasspathEntry
import io.hierograph.graphstore.model.Origin
import io.hierograph.graphstore.scanner.BytecodeScanner
import io.hierograph.graphstore.scanner.ScanArtifact
import io.hierograph.graphstore.scanner.ScanResult
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.time.measureTimedValue

/**
 * Scans the reactor modules named in a build manifest (`hierograph:manifest` → `.hierograph/manifest.json`)
 * — the server-owned replacement for the former plugin-side scan. The plugin only records what the build
 * tool knows (module layout + resolved classpath); the server runs the ASM [BytecodeScanner] itself.
 *
 * Three steps the caller composes (see [HierarchicalGraphService]):
 *  - [scan] reads the manifest and parses every module's compiled output in memory;
 *  - [ingest] feeds that straight into a [GraphStore] (the fast path — no Parquet round trip), so the
 *    hierarchical graph can go live immediately;
 *  - [writeSnapshot] writes the same scan as a Parquet snapshot the detail tools read — done off the hot
 *    path so the live graph never waits on it.
 *
 * Only app (reactor) modules are scanned; libraries stay unresolved placeholders as today.
 */
class ManifestGraphSource(private val scanner: BytecodeScanner = BytecodeScanner()) {

    /** A parsed manifest: the scan result plus each module's resolved classpath (keyed by GAV). */
    data class Scanned(val result: ScanResult, val classpaths: Map<String, ArtifactClasspath>)

    private data class ClasspathEntryDto(val gav: String, val internal: Boolean = false, val jar: String? = null)

    private data class ModuleDto(
        val gav: String,
        val name: String = "",
        val mainOutputDir: String? = null,
        val testOutputDir: String? = null,
        val sourceRoots: List<String> = emptyList(),
        val classpath: List<ClasspathEntryDto> = emptyList(),
    )

    private data class ManifestDto(
        val schemaVersion: Int = 0,
        val generator: String = "",
        val generatedAt: String = "",
        val reactorRoot: String = "",
        val modules: List<ModuleDto> = emptyList(),
    )

    /** Reads [manifestPath] and scans every module's compiled main output into an in-memory [Scanned]. */
    fun scan(manifestPath: Path): Scanned {
        require(manifestPath.exists()) { "Manifest not found: $manifestPath (run `mvn … hierograph:manifest`)" }
        val manifest = MAPPER.readValue<ManifestDto>(manifestPath.toFile())

        val artifacts = ArrayList<ScanArtifact>()
        val classpaths = LinkedHashMap<String, ArtifactClasspath>()
        for (module in manifest.modules) {
            classpaths[module.gav] = ArtifactClasspath(
                module.gav,
                module.name.ifEmpty { module.gav },
                module.classpath.map { ClasspathEntry(it.gav, it.internal) },
            )
            val mainDir = module.mainOutputDir?.let(Path::of)
            if (mainDir == null || !mainDir.isDirectory()) {
                log.warn("Manifest module {} has no scannable main output ({}); skipping", module.gav, module.mainOutputDir)
                continue
            }
            artifacts += ScanArtifact(module.gav, mainDir)
        }
        require(artifacts.isNotEmpty()) {
            "Manifest $manifestPath lists no module with compiled output to scan (did the reactor build run?)"
        }

        log.info("Scanning {} module(s) from manifest {}", artifacts.size, manifestPath)
        val (result, elapsed) = measureTimedValue { scanner.scan(artifacts) }
        log.info("Scanned {} types, {} detail refs in {}", result.classes.size, result.detailRefs.size, elapsed)
        return Scanned(result, classpaths)
    }

    /**
     * Ingests a [Scanned] straight into [store] — the same ingest path a Parquet load uses
     * ([GraphStore.registerArtifact] / [applyClass][GraphStore.applyClass] / [resolvePlaceholders]
     * [GraphStore.resolvePlaceholders]), but fed from memory so the graph never waits on Parquet I/O.
     * Installs the real per-owner classpath-order [TypeResolver] from the manifest (index 0 = the owner
     * itself, then its dependencies) before ingest, so reference resolution matches the Parquet load.
     */
    fun ingest(scanned: Scanned, store: GraphStore) {
        if (scanned.classpaths.isNotEmpty()) {
            val order = scanned.classpaths.mapValues { (gav, cp) -> listOf(gav) + cp.entries.map { it.depKey } }
            store.setTypeResolver(TypeResolver.classpathOrder(order))
        }
        val registered = HashSet<String>()
        for (artifactClass in scanned.result.classes) {
            val key = artifactClass.artifactKey
            if (registered.add(key)) {
                val name = scanned.classpaths[key]?.name ?: key
                store.registerArtifact(ArtifactInfo(key, name, key, Origin.MODULE_MAIN))
            }
            store.applyClass(key, artifactClass.facts)
        }
        store.resolvePlaceholders()
    }

    /** Writes [scanned] as a Parquet snapshot into [dir] (replacing any previous contents). */
    fun writeSnapshot(scanned: Scanned, dir: Path) {
        deleteRecursively(dir)
        Files.createDirectories(dir)
        scanned.result.writeTo(dir, classpaths = scanned.classpaths)
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

    private companion object {
        private val log = LoggerFactory.getLogger(ManifestGraphSource::class.java)
        private val MAPPER = JsonMapper.builder()
            .addModule(kotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build()
    }
}
