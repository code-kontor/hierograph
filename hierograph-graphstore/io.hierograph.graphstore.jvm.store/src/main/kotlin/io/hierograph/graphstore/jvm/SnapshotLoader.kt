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
package io.hierograph.graphstore.jvm

import io.hierograph.graphstore.jvm.archive.ArtifactSource
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.graphstore.jvm.archive.ParquetSchema
import io.hierograph.graphstore.jvm.archive.SnapshotFormatException
import io.hierograph.graphstore.jvm.archive.SnapshotSources
import dev.hardwood.reader.FilterPredicate
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DependencyRollup
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.model.Ref
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loads a GraphStore Parquet snapshot into a [GraphStore]: the snapshot counterpart of the server's
 * in-memory ingest of a fresh scan.
 *
 * The snapshot holds facts only; [reader] reads them. The loader reconstructs one [ClassFacts] per type
 * from the `types` / `members` tables, rolls the type's aggregated references up from its
 * `dependencies` rows ([DependencyRollup]), and feeds both through [GraphStore.applyClass] — the *same*
 * ingest path a live scan uses. Referenced-but-undefined types (targets that were never scanned) surface
 * as placeholders through their incoming refs, so snapshots stay composable and never duplicate external
 * nodes.
 */
class SnapshotLoader(private val reader: ParquetReader = ParquetReader()) {

    /**
     * Loads a snapshot into [store]. [parquetDir] is any snapshot path [ParquetReader] reads. A reference
     * into an artifact loaded later starts as a placeholder that the later artifact's type replaces, so
     * cross-artifact references resolve against the full set whatever the load order.
     *
     * The artifacts are read in parallel, then applied to [store] one after another in snapshot order, so the
     * result is the same as a sequential load. An artifact written in another format version is rejected
     * with a [SnapshotFormatException] before anything is applied.
     */
    fun load(parquetDir: Path, store: GraphStore) {
        val sources = SnapshotSources.of(parquetDir)
        sources.forEach { reader.checkFormat(it) }
        // Classpath-shadowing resolution: if the snapshot carries manifests, resolve references by each
        // owner's real classpath order instead of the OWNER_FIRST stand-in. Must be installed before ingest.
        val classpaths = reader.readClasspath(parquetDir)
        if (classpaths.isNotEmpty()) {
            store.setTypeResolver(TypeResolver.classpathOrder(classpaths.mapValues { it.value.order }))
        }
        readInParallel(sources) { source ->
            ArtifactTables(
                types = reader.readTypes(source),
                membersByOwner = reader.readMembers(source),
                refs = rollUp(source),
            )
        }.forEach { (types, membersByOwner, refs) ->
            val registered = HashSet<String>()
            for (t in types) {
                if (registered.add(t.artifact)) {
                    // Display name from the classpath manifest when present (the plugin keys artifacts by GAV
                    // but names them by artifactId); else the artifact key itself (CLI snapshots).
                    val name = classpaths[t.artifact]?.name ?: t.artifact
                    store.registerArtifact(ArtifactInfo(t.artifact, name, t.artifact))
                }
                store.applyClass(
                    t.artifact,
                    ClassFacts(
                        fqn = t.fqn,
                        kind = JavaNodeKind.valueOf(t.kind),
                        modifiers = t.modifiers ?: 0,
                        members = membersByOwner[t.fqn].orEmpty(),
                        classHash = t.classHash.orEmpty(),
                        sourceFile = t.sourceFile,
                    ),
                    refs.refsOf(t.fqn),
                )
            }
        }
    }

    /** A scanned type's `types.parquet` row together with its members and its aggregated references. */
    data class TypeFacts(val row: ParquetReader.TypeRow, val members: List<MemberFacts>, val refs: List<Ref>)

    /**
     * The facts of the type [fqn] as defined by [artifact]: its row, members and aggregated references
     * (rolled up from its `dependencies` rows), all read from the one snapshot artifact whose `types.parquet`
     * holds that row, so a type defined by several artifacts is never mixed with another definition of the
     * same name. `null` when no artifact defines it. In the unpacked layout, where one directory holds the
     * tables of every artifact, members and dependencies carry no artifact and are read for the FQN.
     */
    fun readTypeFacts(parquetDir: Path, artifact: String, fqn: String): TypeFacts? {
        val typeFilter = FilterPredicate.eq(ParquetSchema.Types.FQN, fqn)
        for (source in SnapshotSources.of(parquetDir)) {
            val row = reader.readTypes(source, typeFilter).firstOrNull { it.artifact == artifact } ?: continue
            val members = reader.readMembers(source, FilterPredicate.eq(ParquetSchema.Members.OWNER_FQN, fqn))[fqn].orEmpty()
            return TypeFacts(row, members, rollUp(source, fromType = fqn).refsOf(fqn))
        }
        return null
    }

    /** One artifact's tables as [load] needs them, read off the ingest thread. */
    private data class ArtifactTables(
        val types: List<ParquetReader.TypeRow>,
        val membersByOwner: Map<String, List<MemberFacts>>,
        val refs: DependencyRollup.Accumulator,
    )

    /** Rolls [source]'s `dependencies` rows (only [fromType]'s, when given) up into aggregated references. */
    private fun rollUp(source: ArtifactSource, fromType: String? = null): DependencyRollup.Accumulator =
        DependencyRollup.Accumulator().also { acc -> reader.forEachDependencyFact(source, fromType, acc::add) }

    /**
     * Reads every source with [read] on a pool of up to one thread per processor and returns the results in
     * source order; rethrows the first failure once all reads have finished.
     */
    private fun <T> readInParallel(sources: List<ArtifactSource>, read: (ArtifactSource) -> T): List<T> {
        if (sources.size <= 1) return sources.map(read)
        val threads = minOf(sources.size, Runtime.getRuntime().availableProcessors())
        val counter = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads) { task ->
            Thread(task, "hierograph-parquet-read-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        try {
            val futures = sources.map { source -> pool.submit(Callable { read(source) }) }
            val results = futures.map { f -> runCatching { f.get() } }
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw (it as? ExecutionException)?.cause ?: it }
            return results.map { it.getOrThrow() }
        } finally {
            pool.shutdownNow()
        }
    }
}
