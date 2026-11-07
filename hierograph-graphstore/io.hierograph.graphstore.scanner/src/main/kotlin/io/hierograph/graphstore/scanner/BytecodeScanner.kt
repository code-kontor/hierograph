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
package io.hierograph.graphstore.scanner

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.parquet.ParquetWriter
import io.hierograph.graphstore.parquet.ParquetWriter.ArtifactClass
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** One scan input: a JAR file or a class directory. [key] becomes the module's artifact key. */
data class ScanArtifact(val key: String, val path: Path) {
    companion object {
        /** An artifact keyed by its file or directory name (`spring-web-7.0.8.jar`), as jQAssistant names JARs. */
        fun of(path: Path): ScanArtifact = ScanArtifact(path.fileName.toString(), path)
    }
}

/** The scanned classes (type level) and their member-level references. */
data class ScanResult(val classes: List<ArtifactClass>, val detailRefs: List<DetailRef>) {

    /** Writes the result as a Parquet snapshot: `types` / `members` / `parameters` / `refs` + `detail_refs`. */
    fun writeTo(dir: Path, codec: CompressionCodec = CompressionCodec.ZSTD) {
        Files.createDirectories(dir)
        ParquetWriter.writeClassFacts(dir, classes, codec)
        ParquetWriter.writeDetailRefs(dir, detailRefs, codec)
    }
}

/**
 * Hierograph's own bytecode scanner (FULL mode): reads every class file of the given [ScanArtifact]s with
 * ASM into `ClassFacts` and member-level `DetailRef`s — the replacement for the jQAssistant export, with
 * the same snapshot shape and key conventions. `module-info` is skipped; of a multi-release JAR, each
 * class is scanned once, in its newest version (`META-INF/versions/<n>/…` overrides the base — what runs on
 * a current JDK); `package-info` and nested/anonymous classes are types. Overrides and virtual calls need
 * the type hierarchy and are added over the whole scanned set by [HierarchyResolver].
 *
 * Artifacts are scanned in parallel, one task per artifact on up to [parallelism] threads; the results are
 * collected in input order, so the output is identical to a sequential scan. The hierarchy pass runs after
 * all artifacts are scanned.
 */
class BytecodeScanner(private val parallelism: Int = Runtime.getRuntime().availableProcessors()) {

    init {
        require(parallelism >= 1) { "parallelism must be >= 1, was $parallelism" }
    }

    fun scan(artifacts: List<ScanArtifact>): ScanResult {
        val scanned = scanArtifacts(artifacts).flatMap { (artifact, classes) -> classes.map { artifact.key to it } }
        val classes = scanned.map { (key, c) -> ArtifactClass(key, c.facts) }
        val direct = scanned.flatMap { it.second.detailRefs }
        val detailRefs = direct + HierarchyResolver.resolve(scanned.map { it.second }, direct.filter { it.relationship == "calls" })
        return ScanResult(classes, detailRefs)
    }

    /** Scans each artifact as its own task; returns the per-artifact results in input order. */
    private fun scanArtifacts(artifacts: List<ScanArtifact>): List<Pair<ScanArtifact, List<ScannedClass>>> {
        fun scanOne(artifact: ScanArtifact): List<ScannedClass> =
            ArrayList<ScannedClass>().also { out -> classFiles(artifact.path) { bytes -> out += ClassScanner.scan(bytes) } }

        val threads = minOf(parallelism, artifacts.size)
        if (threads <= 1) return artifacts.map { it to scanOne(it) }

        val counter = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads) { task ->
            Thread(task, "hierograph-scan-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        try {
            val futures = artifacts.map { artifact -> pool.submit(Callable { scanOne(artifact) }) }
            return artifacts.zip(futures).map { (artifact, future) ->
                try {
                    artifact to future.get()
                } catch (e: ExecutionException) {
                    throw IllegalStateException("Scanning ${artifact.path} failed: ${e.cause?.message}", e.cause)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun classFiles(path: Path, consume: (ByteArray) -> Unit) {
        when {
            path.isDirectory() -> Files.walk(path).use { files ->
                files.filter { Files.isRegularFile(it) && isScannable(path.relativize(it).toString().replace('\\', '/')) }
                    .sorted()
                    .forEach { consume(Files.readAllBytes(it)) }
            }
            path.extension == "jar" || path.extension == "zip" -> ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                // Each class once, in its newest version: a multi-release overlay (META-INF/versions/<n>/…)
                // replaces the base class, as it does at run time on a current JDK.
                val byName = HashMap<String, Pair<Int, ZipEntry>>()
                for (e in entries) {
                    val overlay = MULTI_RELEASE.matchEntire(e.name)
                    val name = overlay?.groupValues?.get(2) ?: e.name
                    val version = overlay?.groupValues?.get(1)?.toInt() ?: 0
                    if (!isScannable(name)) continue
                    if (version >= (byName[name]?.first ?: -1)) byName[name] = version to e
                }
                byName.toSortedMap().values.forEach { (_, entry) -> zip.getInputStream(entry).use { consume(it.readAllBytes()) } }
            }
            else -> throw IllegalArgumentException("Not a JAR or class directory: $path")
        }
    }

    private companion object {
        val MULTI_RELEASE = Regex("META-INF/versions/(\\d+)/(.+)")
    }

    private fun isScannable(relativePath: String): Boolean =
        relativePath.endsWith(".class") &&
            !relativePath.startsWith("META-INF/") &&
            relativePath.substringAfterLast('/') != "module-info.class"
}

/**
 * CLI: `scan --out <snapshot-dir> [--threads <n>] <jar-or-class-dir>...` — scans the artifacts (in parallel,
 * `--threads` defaulting to the number of cores) and writes a Parquet snapshot that the server loads with
 * `hierograph.graph.source=parquet`.
 */
fun main(args: Array<String>) {
    val usage = "usage: scan --out <snapshot-dir> [--threads <n>] <jar-or-class-dir>..."
    fun option(name: String): Pair<Int, String>? {
        val i = args.indexOf(name)
        if (i < 0) return null
        require(i + 1 < args.size) { usage }
        return i to args[i + 1]
    }
    val out = option("--out") ?: throw IllegalArgumentException(usage)
    val threads = option("--threads")
    val optionIndexes = listOfNotNull(out, threads).flatMap { (i, _) -> listOf(i, i + 1) }.toSet()
    val inputs = args.filterIndexed { i, _ -> i !in optionIndexes }.map { ScanArtifact.of(Path.of(it)) }
    require(inputs.isNotEmpty()) { usage }
    val parallelism = threads?.second?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()

    val started = System.nanoTime()
    val result = BytecodeScanner(parallelism).scan(inputs)
    result.writeTo(Path.of(out.second))
    println(
        "Scanned ${inputs.size} artifacts on $parallelism threads: ${result.classes.size} types, " +
            "${result.classes.sumOf { it.facts.members.size }} members, ${result.detailRefs.size} detail refs " +
            "→ ${out.second} in ${(System.nanoTime() - started) / 1_000_000} ms"
    )
}
