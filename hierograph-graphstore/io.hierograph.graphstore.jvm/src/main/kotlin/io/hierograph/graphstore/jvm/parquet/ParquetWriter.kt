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
package io.hierograph.graphstore.jvm.parquet

import dev.hardwood.OutputFile
import dev.hardwood.metadata.CompressionCodec
import dev.hardwood.schema.FileSchema
import dev.hardwood.writer.ParquetFileWriter
import dev.hardwood.writer.StructBuilder
import dev.hardwood.writer.WriterConfig
import io.hierograph.graphstore.jvm.model.ArtifactClasspath
import io.hierograph.graphstore.jvm.model.ClasspathEntry
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DetailRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes the GraphStore Parquet files (the [ParquetSchema] tables) from [ClassFacts], the same unit
 * the scanner and the reader exchange. This is the write counterpart of [ParquetReader]: a set
 * of classes written here loads back into an equivalent [io.hierograph.graphstore.jvm.GraphStore].
 *
 * Tables are ZSTD-compressed by default (the spec's choice; [ParquetSchema]'s dictionary encoding
 * absorbs the repeated FQNs). Pass [CompressionCodec.UNCOMPRESSED] to avoid needing a codec on the
 * classpath (e.g. in tests).
 *
 * The tables are independent files, so they are written **in parallel**, one task per file; each file's
 * content (and row order) is the same as a sequential write.
 */
object ParquetWriter {

    /** One scanned class together with the key of the artifact that owns it. */
    data class ArtifactClass(val artifactKey: String, val facts: ClassFacts)

    /** Writes the four type-level tables (`types`, `members`, `parameters`, `refs`) in parallel. */
    fun writeClassFacts(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec = CompressionCodec.ZSTD) =
        runInParallel(classFactsTasks(dir, classes, codec))

    /** Writes a complete snapshot — the four type-level tables plus `detail_refs` — all five in parallel. */
    fun writeSnapshot(dir: Path, classes: List<ArtifactClass>, detailRefs: List<DetailRef>, codec: CompressionCodec = CompressionCodec.ZSTD) =
        runInParallel(classFactsTasks(dir, classes, codec) + ({ writeDetailRefs(dir, detailRefs, codec) }))

    /** How [writeArtifacts] packages each artifact's tables (`spec/hierograph-graphstore-format.md` §2). */
    enum class Packaging {
        /** A `<key>.hierograph` file: the tables as a STORED ZIP, the form for shipping. */
        ARCHIVE,

        /** A `<key>.hierograph/` directory holding the tables, the form for local work. */
        EXPLODED,
    }

    /**
     * Writes a project snapshot as **one `.hierograph` artifact per artifact key** under [outDir], packaged
     * as [packaging] decides: the classes are grouped by [ArtifactClass.artifactKey], the [detailRefs] by the
     * artifact that owns their `from` side (its declaring type's artifact), and each group's tables become
     * `<artifact>.hierograph` — an archive or an exploded directory, with identical tables. An artifact of
     * the same name already in [outDir] is replaced, whichever its form. This is the scanner's output shape;
     * the loader reads such a directory back across all artifacts. (A reference's `from` type is always one
     * this snapshot scanned, so it maps to an artifact; if an FQN is defined by several artifacts the first
     * wins — a rare, documented duplicate-FQN case.)
     *
     * Nothing is written outside [outDir]: an archive's tables are staged in `.staging-<artifact>` there and
     * the staging directory is deleted once zipped; an exploded artifact needs no staging.
     */
    fun writeArtifacts(
        outDir: Path,
        classes: List<ArtifactClass>,
        detailRefs: List<DetailRef>,
        codec: CompressionCodec = CompressionCodec.ZSTD,
        classpaths: Map<String, ArtifactClasspath> = emptyMap(),
        packaging: Packaging = Packaging.ARCHIVE,
    ) {
        Files.createDirectories(outDir)
        val artifactByFqn = classes.associate { it.facts.fqn to it.artifactKey }
        val detailByArtifact = detailRefs.groupBy { artifactByFqn[it.fromType] }
        for ((artifactKey, artifactClasses) in classes.groupBy { it.artifactKey }) {
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
        { writeRefs(dir, classes, codec) },
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
        val members = classes.flatMap { c -> c.facts.members.map { c.facts.fqn to it } }
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

    private fun writeRefs(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec) {
        val refs = classes.flatMap { c -> c.facts.refs.map { c.facts.fqn to it } }
        writeTable(dir.resolve(ParquetSchema.REFS_FILE), ParquetSchema.refsSchema(), codec, refs) { sb, (fromType, r) ->
            sb.setString(ParquetSchema.Refs.FROM_TYPE, fromType)
            sb.setString(ParquetSchema.Refs.TO_TYPE, r.toKey)
            sb.setString(ParquetSchema.Refs.KIND, r.kind.name)
            sb.setNullableInt(ParquetSchema.Refs.COUNT, r.count)
        }
    }

    /**
     * Writes member-level ("detail") refs to the `detail_refs` table, sorted by `from_type` (then
     * `from_key`) so a scoped read via [ParquetReader.readDetailRefs] skips row groups. Defaults to
     * uncompressed (callers that want ZSTD pass it explicitly). The sort runs in parallel; it is stable,
     * so rows equal in all sort keys keep their input order and the file is the same as a sequential write.
     */
    fun writeDetailRefs(dir: Path, refs: List<DetailRef>, codec: CompressionCodec = CompressionCodec.UNCOMPRESSED) {
        val cols = ParquetSchema.DetailRefs
        val sorted = refs.toTypedArray()
            .also { Arrays.parallelSort(it, compareBy({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.relationship })) }
            .asList()
        writeTable(dir.resolve(ParquetSchema.DETAIL_REFS_FILE), ParquetSchema.detailRefsSchema(), codec, sorted) { sb, r ->
            sb.setString(cols.FROM_KEY, r.fromKey)
            sb.setString(cols.FROM_TYPE, r.fromType)
            sb.setString(cols.TO_KEY, r.toKey)
            sb.setString(cols.TO_TYPE, r.toType)
            sb.setString(cols.RELATIONSHIP, r.relationship)
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
