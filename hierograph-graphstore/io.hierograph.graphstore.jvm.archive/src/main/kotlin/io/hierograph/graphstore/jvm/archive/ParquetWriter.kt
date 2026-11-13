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
package io.hierograph.graphstore.jvm.archive

import dev.hardwood.OutputFile
import dev.hardwood.metadata.CompressionCodec
import dev.hardwood.schema.FileSchema
import dev.hardwood.writer.ParquetFileWriter
import dev.hardwood.writer.StructBuilder
import dev.hardwood.writer.WriterConfig
import io.hierograph.graphstore.jvm.archive.model.ArtifactClasspath
import io.hierograph.graphstore.jvm.archive.model.ClasspathEntry
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.Dependency
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.iterator

/**
 * Writes the snapshot tables ([ParquetSchema]) from [ClassFacts] and [Dependency] facts, the units the
 * scanner produces. This is the write counterpart of [ParquetReader]: what is written here reads back
 * through it row for row. Loading a snapshot into a GraphStore is the job of `SnapshotLoader`, in the
 * store module.
 *
 * [writeSnapshot] and [writeArtifacts] compress with ZSTD by default (the spec's choice; dictionary
 * encoding absorbs the repeated FQNs); the single-table writers [writeDependencies] and [writeClasspath]
 * default to uncompressed. Pass [CompressionCodec.UNCOMPRESSED] to avoid needing a codec on the classpath
 * (e.g. in tests). Every table carries [ParquetSchema.FORMAT_VERSION] in its footer.
 *
 * Only facts are written. The type-level aggregate is not: it is the rollup of the dependency rows, which
 * the loader computes at load (`DependencyRollup`, in the store module).
 *
 * The tables are independent files, so they are written **in parallel**, one task per file; each file's
 * content (and row order) is the same as a sequential write.
 */
object ParquetWriter {

    /** One scanned class together with the key of the artifact that owns it. */
    data class ArtifactClass(val artifactKey: String, val facts: ClassFacts)

    /** Writes a complete snapshot — `types`, `members`, `parameters` and `dependencies` — all four in parallel. */
    fun writeSnapshot(dir: Path, classes: List<ArtifactClass>, dependencies: List<Dependency>, codec: CompressionCodec = CompressionCodec.ZSTD) =
        runInParallel(classFactsTasks(dir, classes, codec) + ({ writeDependencies(dir, dependencies, codec) }))

    /** How [writeArtifacts] packages each artifact's tables (`spec/hierograph-graphstore-format.md` §2). */
    enum class Packaging {
        /** A `<key>.hierograph` file: the tables as a STORED ZIP, the form for shipping. */
        ARCHIVE,

        /** A `<key>.hierograph/` directory holding the tables, the form for local work. */
        EXPLODED,
    }

    /**
     * Writes a project snapshot as **one `.hierograph` artifact per artifact key** under [outDir], packaged
     * as [packaging] decides: the classes are grouped by [ArtifactClass.artifactKey], the [dependencies] by
     * [Dependency.fromArtifact], the artifact whose class file the fact comes from, and each group's tables become
     * `<artifact>.hierograph` — an archive or an exploded directory, with identical tables. An artifact of
     * the same name already in [outDir] is replaced, whichever its form. This is the scanner's output shape;
     * the loader reads such a directory back across all artifacts.
     *
     * The scanner sets [Dependency.fromArtifact] on every fact, so each copy of a type that several artifacts
     * define (two library versions, a test class shadowing a main class) keeps its own facts. A fact without
     * it is filed with the artifact of the first class whose FQN is its `fromType`; a fact whose `fromType`
     * is none of [classes] is not written.
     *
     * Nothing is written outside [outDir]: an archive's tables are staged in `.staging-<artifact>` there and
     * the staging directory is deleted once zipped; an exploded artifact needs no staging.
     *
     * [cancelled] is checked before each artifact. Once it returns `true`, the write stops with a
     * [CancellationException]; the artifacts written so far stay in [outDir], and the caller discards them.
     */
    fun writeArtifacts(
        outDir: Path,
        classes: List<ArtifactClass>,
        dependencies: List<Dependency>,
        codec: CompressionCodec = CompressionCodec.ZSTD,
        classpaths: Map<String, ArtifactClasspath> = emptyMap(),
        packaging: Packaging = Packaging.ARCHIVE,
        cancelled: () -> Boolean = { false },
    ) {
        Files.createDirectories(outDir)
        // A fact names its artifact; one without it goes to the first class that defines its type (see the KDoc).
        val artifactByFqn = HashMap<String, String>().apply { classes.forEach { putIfAbsent(it.facts.fqn, it.artifactKey) } }
        val detailByArtifact = dependencies.groupBy { it.fromArtifact ?: artifactByFqn[it.fromType] }
        for ((artifactKey, artifactClasses) in classes.groupBy { it.artifactKey }) {
            if (cancelled()) throw CancellationException("Snapshot write to $outDir cancelled before artifact $artifactKey")
            val fileName = HierographArchive.fileName(artifactKey)
            val target = outDir.resolve(fileName)
            deleteRecursively(target)
            val tablesDir = when (packaging) {
                Packaging.EXPLODED -> target
                Packaging.ARCHIVE -> outDir.resolve(".staging-" + fileName.removeSuffix(HierographArchive.EXTENSION))
            }
            deleteRecursively(tablesDir)
            Files.createDirectories(tablesDir)
            try {
                writeSnapshot(tablesDir, artifactClasses, detailByArtifact[artifactKey].orEmpty(), codec)
                classpaths[artifactKey]?.let { writeClasspath(tablesDir, it, codec) }
                if (packaging == Packaging.ARCHIVE) HierographArchive.zip(tablesDir, target)
            } finally {
                if (packaging == Packaging.ARCHIVE) deleteRecursively(tablesDir)
            }
        }
    }

    /**
     * Writes the artifact's classpath manifest (`classpath.parquet`): index 0 is the artifact itself, then
     * its dependencies in resolution order. Drives load-time classpath-shadowing resolution.
     */
    fun writeClasspath(dir: Path, classpath: ArtifactClasspath, codec: CompressionCodec = CompressionCodec.UNCOMPRESSED) {
        val self = ClasspathEntry(classpath.artifactKey, internal = true)
        val rows = (listOf(self) + classpath.entries).mapIndexed { index, entry -> index to entry }
        writeTable(dir.resolve(ParquetSchema.CLASSPATH_FILE), ParquetSchema.classpathSchema(), codec, rows) { sb, (index, entry) ->
            sb.setString(ParquetSchema.Classpath.ARTIFACT_KEY, classpath.artifactKey)
            sb.setString(ParquetSchema.Classpath.NAME, classpath.name)
            sb.setInt(ParquetSchema.Classpath.INDEX, index)
            sb.setString(ParquetSchema.Classpath.DEP_KEY, entry.depKey)
            sb.setInt(ParquetSchema.Classpath.INTERNAL, if (entry.internal) 1 else 0)
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted { a, b -> b.compareTo(a) }.forEach(Files::delete) }
    }

    private fun classFactsTasks(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec): List<() -> Unit> = listOf(
        { writeTypes(dir, classes, codec) },
        { writeMembers(dir, classes, codec) },
        { writeParameters(dir, classes, codec) },
    )

    private fun writeTypes(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec) {
        writeTable(dir.resolve(ParquetSchema.TYPES_FILE), ParquetSchema.typesSchema(), codec, classes) { sb, c ->
            sb.setString(ParquetSchema.Types.ARTIFACT, c.artifactKey)
            sb.setString(ParquetSchema.Types.FQN, c.facts.fqn)
            sb.setString(ParquetSchema.Types.KIND, c.facts.kind.name)
            sb.setNullable(ParquetSchema.Types.SOURCE_FILE, c.facts.sourceFile)
            sb.setNullable(ParquetSchema.Types.CLASS_HASH, c.facts.classHash.ifEmpty { null })
            // Always written (0 = package-private, no flags), so "column absent" unambiguously means an
            // older snapshot without type modifiers.
            sb.setInt(ParquetSchema.Types.MODIFIERS, c.facts.modifiers)
        }
    }

    private fun writeMembers(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec) {
        // Sorted by owner so that a scoped read skips the data of other types; the sort is stable, so each
        // type's members keep their declaration order.
        val members = classes.flatMap { c -> c.facts.members.map { c.facts.fqn to it } }.sortedBy { it.first }
        writeTable(dir.resolve(ParquetSchema.MEMBERS_FILE), ParquetSchema.membersSchema(), codec, members) { sb, (owner, m) ->
            sb.setString(ParquetSchema.Members.OWNER_FQN, owner)
            sb.setString(ParquetSchema.Members.MEMBER_KEY, m.key)
            sb.setString(ParquetSchema.Members.KIND, m.kind.name)
            sb.setString(ParquetSchema.Members.NAME, m.name)
            sb.setString(ParquetSchema.Members.SIGNATURE, m.signature)
            sb.setNullableInt(ParquetSchema.Members.MODIFIERS, m.modifiers.takeIf { it != 0 })
            sb.setNullableInt(ParquetSchema.Members.LINE_FROM, m.lineFrom)
            sb.setNullableInt(ParquetSchema.Members.LINE_TO, m.lineTo)
            sb.setNullable(ParquetSchema.Members.BRIDGE_TARGET, m.bridgeTarget)
        }
    }

    private fun writeParameters(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec) {
        // One row per parameter, keyed by the owning member; sorted so a scoped read skips row groups.
        val parameters = classes.flatMap { c -> c.facts.members }
            .flatMap { m -> m.parameters.map { m.key to it } }
            .sortedWith(compareBy({ it.first }, { it.second.index }))
        writeTable(dir.resolve(ParquetSchema.PARAMETERS_FILE), ParquetSchema.parametersSchema(), codec, parameters) { sb, (methodKey, p) ->
            sb.setString(ParquetSchema.Parameters.METHOD_KEY, methodKey)
            sb.setInt(ParquetSchema.Parameters.INDEX, p.index)
            sb.setString(ParquetSchema.Parameters.NAME, p.name)
            sb.setString(ParquetSchema.Parameters.TYPE_FQN, p.typeFqn)
            sb.setNullable(ParquetSchema.Parameters.ANNOTATIONS, p.annotations.takeIf { it.isNotEmpty() }?.joinToString(" "))
        }
    }

    /**
     * Writes the detail facts to the `dependencies` table, sorted by `from_type`, then `from_key`,
     * `to_type`, `to_key` and `relationship`, so a scoped read via [ParquetReader.readDependencies] skips
     * the data of other types. Defaults to
     * uncompressed (callers that want ZSTD pass it explicitly). The sort runs in parallel; it is stable,
     * so rows equal in all sort keys keep their input order and the file is the same as a sequential write.
     */
    fun writeDependencies(dir: Path, dependencies: List<Dependency>, codec: CompressionCodec = CompressionCodec.UNCOMPRESSED) {
        val cols = ParquetSchema.Dependencies
        val sorted = dependencies.toTypedArray()
            .also { Arrays.parallelSort(it, compareBy({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.kind.value })) }
            .asList()
        writeTable(dir.resolve(ParquetSchema.DEPENDENCIES_FILE), ParquetSchema.dependenciesSchema(), codec, sorted) { sb, r ->
            sb.setString(cols.FROM_KEY, r.fromKey)
            sb.setString(cols.FROM_TYPE, r.fromType)
            sb.setString(cols.TO_KEY, r.toKey)
            sb.setString(cols.TO_TYPE, r.toType)
            sb.setString(cols.RELATIONSHIP, r.kind.value)
            sb.setNullableInt(cols.LINE, r.line)
        }
    }

    /** Runs [tasks] concurrently, one thread each; rethrows the first failure after all have finished. */
    private fun runInParallel(tasks: List<() -> Unit>) {
        if (tasks.size <= 1) return tasks.forEach { it() }
        val counter = AtomicInteger()
        val pool = Executors.newFixedThreadPool(tasks.size) { task ->
            Thread(task, "hierograph-parquet-write-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        try {
            val futures = tasks.map { task -> pool.submit(Callable { task() }) }
            val failures = futures.mapNotNull { f -> runCatching { f.get() }.exceptionOrNull() }
            failures.firstOrNull()?.let { throw (it as? ExecutionException)?.cause ?: it }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun <T> writeTable(path: Path, schema: FileSchema, codec: CompressionCodec, rows: List<T>, set: (StructBuilder, T) -> Unit) {
        val config = WriterConfig.builder().codec(codec).build()
        ParquetFileWriter.create(OutputFile.of(path), schema, config).use { writer ->
            writer.keyValueMetadata(ParquetSchema.FORMAT_VERSION_KEY, ParquetSchema.FORMAT_VERSION.toString())
            val rowWriter = writer.rowWriter()
            for (row in rows) {
                rowWriter.writeRow { sb -> set(sb, row) }
            }
            writer.endRowGroup()
        }
    }

    private fun StructBuilder.setNullable(name: String, value: String?) {
        if (value == null) setNull(name) else setString(name, value)
    }

    private fun StructBuilder.setNullableInt(name: String, value: Int?) {
        if (value == null) setNull(name) else setInt(name, value)
    }
}
