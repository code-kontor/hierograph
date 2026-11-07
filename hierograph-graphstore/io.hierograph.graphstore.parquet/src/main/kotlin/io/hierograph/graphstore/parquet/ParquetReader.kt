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
package io.hierograph.graphstore.parquet

import dev.hardwood.InputFile
import dev.hardwood.reader.FilterPredicate
import dev.hardwood.reader.ParquetFileReader
import dev.hardwood.reader.RowReader
import dev.hardwood.schema.FileSchema
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.TypeResolver
import io.hierograph.graphstore.model.ArtifactInfo
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.model.MemberFacts
import io.hierograph.graphstore.model.Origin
import io.hierograph.graphstore.model.ParameterFacts
import io.hierograph.graphstore.model.Ref
import io.hierograph.graphstore.model.RefKind
import io.hierograph.mcp.javaspec.JavaNodeKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads a GraphStore Parquet snapshot (the [ParquetSchema] tables) back into a [GraphStore].
 *
 * The loader reconstructs one [ClassFacts] per type from the flat `types` / `members` / `refs` tables
 * and feeds it through [GraphStore.applyClass] — the *same* ingest path a live scan uses — then calls
 * [GraphStore.resolvePlaceholders] once. Referenced-but-undefined types (jQAssistant's "required" types
 * that were never exported) surface as placeholders through their incoming refs, so snapshots stay
 * composable and never duplicate external nodes.
 */
class ParquetReader {

    /**
     * Loads a snapshot into [store]. [parquetDir] is either a directory of per-artifact `.hierograph`
     * archives (the scanner's output — each loaded in turn) or a single combined table directory
     * (the legacy jQA export). Missing tables are treated as empty; [GraphStore.resolvePlaceholders] runs
     * once after all artifacts are in, so cross-artifact references resolve against the full set.
     */
    /** An artifact's manifest: its display name and its classpath in order (index 0 = the artifact itself). */
    data class ClasspathInfo(val name: String, val order: List<String>)

    /**
     * Reads the per-artifact `classpath.parquet` manifests (the Maven plugin's output) across the snapshot,
     * keyed by the owning artifact. Empty when no archive carries a manifest (CLI/legacy snapshots).
     */
    fun readClasspath(parquetDir: Path): Map<String, ClasspathInfo> {
        val cols = ParquetSchema.Classpath
        data class Row(val artifactKey: String, val name: String, val index: Int, val depKey: String)
        val rows = readAcross(parquetDir, ParquetSchema.CLASSPATH_FILE) { r ->
            Row(r.string(cols.ARTIFACT_KEY), r.string(cols.NAME), r.int(cols.INDEX), r.string(cols.DEP_KEY))
        }
        return rows.groupBy { it.artifactKey }.mapValues { (_, rs) ->
            ClasspathInfo(rs.first().name, rs.sortedBy { it.index }.map { it.depKey })
        }
    }

    fun load(parquetDir: Path, store: GraphStore) {
        // Classpath-shadowing resolution: if the snapshot carries manifests, resolve references by each
        // owner's real classpath order instead of the OWNER_FIRST stand-in. Must be installed before ingest.
        val classpaths = readClasspath(parquetDir)
        if (classpaths.isNotEmpty()) {
            store.setTypeResolver(TypeResolver.classpathOrder(classpaths.mapValues { it.value.order }))
        }
        for (dir in snapshotDirs(parquetDir)) {
            val types = readTypes(dir.resolve(ParquetSchema.TYPES_FILE))
            val membersByOwner = readMembers(dir.resolve(ParquetSchema.MEMBERS_FILE))
            val refsByOwner = readRefs(dir.resolve(ParquetSchema.REFS_FILE))

            val registered = HashSet<String>()
            for (t in types) {
                if (registered.add(t.artifact)) {
                    // Display name from the classpath manifest when present (the plugin keys artifacts by GAV
                    // but names them by artifactId); else the artifact key itself (CLI snapshots).
                    val name = classpaths[t.artifact]?.name ?: t.artifact
                    store.registerArtifact(ArtifactInfo(t.artifact, name, t.artifact, Origin.MODULE_MAIN))
                }
                store.applyClass(
                    t.artifact,
                    ClassFacts(
                        fqn = t.fqn,
                        kind = JavaNodeKind.valueOf(t.kind),
                        modifiers = t.modifiers ?: 0,
                        members = membersByOwner[t.fqn].orEmpty(),
                        refs = refsByOwner[t.fqn].orEmpty(),
                        classHash = t.classHash.orEmpty(),
                        sourceFile = t.sourceFile,
                    ),
                )
            }
        }
        store.resolvePlaceholders()
    }

    /**
     * The table directories to read a snapshot from: the extracted dir of each `*.hierograph` archive in
     * [parquetDir] (one per artifact), or just [parquetDir] itself when it holds the tables directly (the
     * legacy combined layout) or is not a directory. Archives are extracted once and cached
     * (see [HierographArchive.extracted]).
     */
    private fun snapshotDirs(parquetDir: Path): List<Path> {
        if (!Files.isDirectory(parquetDir)) return listOf(parquetDir)
        val archives = Files.list(parquetDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(HierographArchive.EXTENSION) }.sorted().toList()
        }
        return if (archives.isEmpty()) listOf(parquetDir) else archives.map { HierographArchive.extracted(it) }
    }

    /** Reads [file] across every snapshot dir under [parquetDir] and concatenates the mapped rows. */
    private fun <T> readAcross(parquetDir: Path, file: String, filter: FilterPredicate? = null, map: (RowCursor) -> T): List<T> =
        snapshotDirs(parquetDir).flatMap { readRows(it.resolve(file), filter, map) }

    /**
     * Scoped read of the member-level `detail_refs` table with predicate push-down: returns the rows
     * whose `from_type` is in [fromTypes] (unconstrained when null/empty), whose `to_type` is in
     * [toTypes] (likewise), and whose `relationship` matches [relationship] (any when null). Hardwood
     * skips row groups outside the `from_type` range via column statistics; `to_type` filters rows but,
     * not being the sort key, does not drive row-group skipping.
     */
    fun readDetailRefs(
        parquetDir: Path,
        fromTypes: Collection<String>? = null,
        toTypes: Collection<String>? = null,
        relationship: String? = null,
    ): List<DetailRef> {
        val cols = ParquetSchema.DetailRefs
        val from = fromTypes?.takeIf { it.isNotEmpty() }?.toHashSet()
        val to = toTypes?.takeIf { it.isNotEmpty() }?.toHashSet()
        // Push down exactly ONE predicate (prefer the sorted `from_type` for row-group skipping) and apply
        // the rest in memory. hardwood 1.1.0.Beta3 mis-prunes pages when several detail-ref predicates are
        // AND-ed, silently dropping valid rows — notably class-level edges whose `from_key` is a bare type
        // fqn (they sort first in a `from_type` group). Each predicate alone is correct, so we combine in
        // memory rather than via FilterPredicate.and.
        val pushdown = when {
            from != null -> FilterPredicate.`in`(cols.FROM_TYPE, *from.toTypedArray())
            to != null -> FilterPredicate.`in`(cols.TO_TYPE, *to.toTypedArray())
            relationship != null -> FilterPredicate.eq(cols.RELATIONSHIP, relationship)
            else -> null
        }
        return readAcross(parquetDir, ParquetSchema.DETAIL_REFS_FILE, pushdown) { r ->
            DetailRef(
                fromKey = r.string(cols.FROM_KEY),
                fromType = r.string(cols.FROM_TYPE),
                toKey = r.string(cols.TO_KEY),
                toType = r.string(cols.TO_TYPE),
                relationship = r.string(cols.RELATIONSHIP),
                line = r.intOrNull(cols.LINE),
            )
        }.filter {
            (from == null || it.fromType in from) &&
                (to == null || it.toType in to) &&
                (relationship == null || it.relationship == relationship)
        }
    }


    /** One row of `types.parquet`: a scanned type's identity plus its per-class snapshot facts. */
    data class TypeRow(
        val artifact: String,
        val fqn: String,
        val kind: String,
        val sourceFile: String?,
        val classHash: String?,
        /** Packed [io.hierograph.graphstore.model.TypeModifiers] flags; `null` for a snapshot written before the column existed. */
        val modifiers: Int?,
    )

    /**
     * Scoped read of `types.parquet`: the rows whose `fqn` is in [fqns] (one per defining artifact).
     * Used to serve per-type properties on demand without keeping them in the in-memory graph.
     */
    fun readTypeRows(parquetDir: Path, fqns: Collection<String>): List<TypeRow> {
        if (fqns.isEmpty()) return emptyList()
        val filter = FilterPredicate.`in`(ParquetSchema.Types.FQN, *fqns.toTypedArray())
        return snapshotDirs(parquetDir).flatMap { readTypes(it.resolve(ParquetSchema.TYPES_FILE), filter) }
    }

    /**
     * Scoped read of `members.parquet`, grouped by owner FQN, for the owners in [ownerFqns] (no
     * parameters attached). Members are keyed by owner FQN only, so same-FQN types from different
     * artifacts share one entry.
     */
    fun readMembersByOwner(parquetDir: Path, ownerFqns: Collection<String>): Map<String, List<MemberFacts>> {
        if (ownerFqns.isEmpty()) return emptyMap()
        val filter = FilterPredicate.`in`(ParquetSchema.Members.OWNER_FQN, *ownerFqns.toTypedArray())
        return snapshotDirs(parquetDir)
            .flatMap { readMembers(it.resolve(ParquetSchema.MEMBERS_FILE), filter).entries }
            .groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
    }

    /**
     * Scoped read of the type-level `refs.parquet`: the outgoing refs (extends / implements / annotated_by /
     * depends_on) of the types in [fromTypes], grouped by source type FQN. Targets are bare FQNs, kept even
     * when the placeholder policy keeps them out of the in-memory graph (e.g. `java.lang.Object`).
     */
    fun readRefsFrom(parquetDir: Path, fromTypes: Collection<String>): Map<String, List<Ref>> {
        if (fromTypes.isEmpty()) return emptyMap()
        val filter = FilterPredicate.`in`(ParquetSchema.Refs.FROM_TYPE, *fromTypes.toTypedArray())
        return snapshotDirs(parquetDir)
            .flatMap { readRefs(it.resolve(ParquetSchema.REFS_FILE), filter).entries }
            .groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
    }

    private fun readTypes(path: Path, filter: FilterPredicate? = null): List<TypeRow> =
        readRows(path, filter) { r ->
            TypeRow(
                artifact = r.string(ParquetSchema.Types.ARTIFACT),
                fqn = r.string(ParquetSchema.Types.FQN),
                kind = r.string(ParquetSchema.Types.KIND),
                sourceFile = r.stringOrNull(ParquetSchema.Types.SOURCE_FILE),
                classHash = r.stringOrNull(ParquetSchema.Types.CLASS_HASH),
                modifiers = if (r.hasColumn(ParquetSchema.Types.MODIFIERS)) r.intOrNull(ParquetSchema.Types.MODIFIERS) ?: 0 else null,
            )
        }

    private fun readMembers(path: Path, filter: FilterPredicate? = null): Map<String, List<MemberFacts>> =
        readRows(path, filter) { r -> r.string(ParquetSchema.Members.OWNER_FQN) to memberFacts(r) }
            .groupBy({ it.first }, { it.second })

    private fun memberFacts(r: RowCursor): MemberFacts =
        MemberFacts(
            key = r.string(ParquetSchema.Members.MEMBER_KEY),
            kind = JavaNodeKind.valueOf(r.string(ParquetSchema.Members.KIND)),
            signature = r.string(ParquetSchema.Members.SIGNATURE),
            name = r.stringOrNull(ParquetSchema.Members.NAME).orEmpty(),
            modifiers = r.intOrNull(ParquetSchema.Members.MODIFIERS) ?: 0,
            lineFrom = r.intOrNull(ParquetSchema.Members.LINE_FROM),
            lineTo = r.intOrNull(ParquetSchema.Members.LINE_TO),
            bridgeTarget = r.stringOrNull(ParquetSchema.Members.BRIDGE_TARGET),
        )

    /**
     * Reads a single member by its key (`<owner fqn>#<signature>`) from `members.parquet`, with its
     * declared parameters attached from `parameters.parquet`. Returns `null` when no such member exists.
     * The detail tools use this to serve `method_details` / `field_details` straight from the snapshot.
     */
    fun readMember(parquetDir: Path, memberKey: String): MemberFacts? {
        val filter = FilterPredicate.eq(ParquetSchema.Members.MEMBER_KEY, memberKey)
        val member = snapshotDirs(parquetDir).firstNotNullOfOrNull { dir ->
            readRows(dir.resolve(ParquetSchema.MEMBERS_FILE), filter) { memberFacts(it) }.firstOrNull()
        } ?: return null
        return member.copy(parameters = readParameters(parquetDir, memberKey))
    }

    /** Every member key in the snapshot (for building a handle→key index). */
    fun readMemberKeys(parquetDir: Path): List<String> =
        readAcross(parquetDir, ParquetSchema.MEMBERS_FILE) { it.string(ParquetSchema.Members.MEMBER_KEY) }

    /** Every member in the snapshot (no parameters attached); for enumeration/diagnostics. */
    fun readAllMembers(parquetDir: Path): List<MemberFacts> =
        readAcross(parquetDir, ParquetSchema.MEMBERS_FILE) { memberFacts(it) }

    /** Reads a method's declared parameters (ordered by index) from `parameters.parquet`. */
    fun readParameters(parquetDir: Path, methodKey: String): List<ParameterFacts> =
        readAcross(parquetDir, ParquetSchema.PARAMETERS_FILE, FilterPredicate.eq(ParquetSchema.Parameters.METHOD_KEY, methodKey)) { r ->
            ParameterFacts(
                index = r.int(ParquetSchema.Parameters.INDEX),
                name = r.string(ParquetSchema.Parameters.NAME),
                typeFqn = r.string(ParquetSchema.Parameters.TYPE_FQN),
            )
        }.sortedBy { it.index }

    private fun readRefs(path: Path, filter: FilterPredicate? = null): Map<String, List<Ref>> =
        readRows(path, filter) { r ->
            r.string(ParquetSchema.Refs.FROM_TYPE).let { fromType ->
                fromType to Ref(
                    fromKey = fromType, // type-level refs are type→type: source key == owning type
                    toKey = r.string(ParquetSchema.Refs.TO_TYPE),
                    kind = RefKind.valueOf(r.string(ParquetSchema.Refs.KIND)),
                    count = r.intOrNull(ParquetSchema.Refs.COUNT),
                )
            }
        }.groupBy({ it.first }, { it.second })

    private fun <T> readRows(path: Path, filter: FilterPredicate? = null, map: (RowCursor) -> T): List<T> {
        if (!Files.exists(path)) return emptyList()
        val out = ArrayList<T>()
        ParquetFileReader.open(InputFile.of(path)).use { reader ->
            val builder = reader.buildRowReader()
            filter?.let { builder.filter(it) }
            builder.build().use { rowReader ->
                val cursor = RowCursor(rowReader, reader.fileSchema)
                while (rowReader.hasNext()) {
                    rowReader.next()
                    out += map(cursor)
                }
            }
        }
        return out
    }

    /** Reads columns by name off a [RowReader], resolving each name to its ordinal once via the schema. */
    private class RowCursor(private val reader: RowReader, schema: FileSchema) {
        private val indexByName: Map<String, Int> =
            schema.columns.associate { it.name() to it.columnIndex() }

        /** Whether the snapshot's schema has [name] — false for columns added after it was written. */
        fun hasColumn(name: String): Boolean = name in indexByName

        private fun index(name: String): Int =
            indexByName[name] ?: error("Column '$name' not present in snapshot schema")

        fun string(name: String): String = reader.getString(index(name))

        fun int(name: String): Int = reader.getInt(index(name))

        fun stringOrNull(name: String): String? =
            index(name).let { if (reader.isNull(it)) null else reader.getString(it) }

        fun intOrNull(name: String): Int? =
            index(name).let { if (reader.isNull(it)) null else reader.getInt(it) }
    }
}
