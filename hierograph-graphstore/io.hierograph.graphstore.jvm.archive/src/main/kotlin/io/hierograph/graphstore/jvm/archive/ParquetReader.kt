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

import dev.hardwood.reader.FilterPredicate
import dev.hardwood.reader.ParquetFileReader
import dev.hardwood.reader.RowReader
import dev.hardwood.schema.ColumnProjection
import dev.hardwood.schema.FileSchema
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.archive.model.ParameterFacts
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import java.nio.file.Path

/**
 * Reads the facts of a Parquet snapshot (the [ParquetSchema] tables): the read counterpart of
 * [ParquetWriter]. It returns facts only — type rows, members, parameters, detail dependencies and the
 * classpath manifests — scoped by predicate push-down where it can, and knows nothing of a GraphStore.
 * Loading a snapshot into a GraphStore, with the type-level rollup of its dependencies, is the job of
 * `SnapshotLoader` in `io.hierograph.graphstore.jvm.store`, which builds on the per-source reads here
 * ([checkFormat], [readTypes], [readMembers], [forEachDependencyFact]).
 *
 * A snapshot path is a directory of per-artifact `.hierograph` artifacts — archives, exploded
 * directories or `collect` containers — or a single combined table directory (a legacy combined
 * layout), or a single `.hierograph` file; [SnapshotSources] resolves it to its artifacts, reading
 * archives in place. Missing tables are treated as empty.
 */
class ParquetReader {

    /** An artifact's manifest: its display name and its classpath in order (index 0 = the artifact itself). */
    data class ClasspathInfo(val name: String, val order: List<String>)

    /**
     * Reads the per-artifact `classpath.parquet` manifests (the Maven plugin's output) across the snapshot,
     * keyed by the owning artifact. Empty when no artifact carries a manifest (CLI/legacy snapshots).
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

    /**
     * Rejects an artifact that was not written in [ParquetSchema.FORMAT_VERSION]: one that still has the
     * dependency tables of format 1, or whose `types` table carries no or another version in its footer. An
     * artifact without a `types` table holds no types and is not checked. A loader calls it for every
     * source before it reads anything.
     */
    fun checkFormat(source: ArtifactSource) {
        ParquetSchema.FORMAT_1_FILES.firstOrNull { source.table(it) != null }?.let {
            throw SnapshotFormatException.stale(source.name, "it has `$it`, a table of format 1")
        }
        val types = source.table(ParquetSchema.TYPES_FILE) ?: return
        val version = ParquetFileReader.open(types).use { it.fileMetaData.keyValueMetadata()[ParquetSchema.FORMAT_VERSION_KEY] }
            ?: throw SnapshotFormatException.stale(source.name, "it carries no format version")
        val number = version.toIntOrNull()
        when {
            number == ParquetSchema.FORMAT_VERSION -> Unit
            number != null && number > ParquetSchema.FORMAT_VERSION -> throw SnapshotFormatException(
                "Snapshot artifact ${source.name} was written in format $version; this Hierograph reads format " +
                    "${ParquetSchema.FORMAT_VERSION}. Update Hierograph to load it.",
            )
            else -> throw SnapshotFormatException.stale(source.name, "it was written in format $version")
        }
    }

    /**
     * Streams [source]'s `dependencies` rows as the four columns a type-level rollup needs —
     * `from_type`, `from_key` (which tells a type's own annotations from its members'), `to_type` and the
     * kind — without building a [Dependency] per row. With [fromType], only that type's rows, read with
     * predicate push-down; without it, every row, read through a column projection.
     */
    fun forEachDependencyFact(
        source: ArtifactSource,
        fromType: String? = null,
        visit: (fromType: String, fromKey: String, toType: String, kind: DependencyKind) -> Unit,
    ) {
        val cols = ParquetSchema.Dependencies
        if (fromType != null) {
            readRows(source, ParquetSchema.DEPENDENCIES_FILE, FilterPredicate.eq(cols.FROM_TYPE, fromType)) { r ->
                visit(r.string(cols.FROM_TYPE), r.string(cols.FROM_KEY), r.string(cols.TO_TYPE), kindOf(r.string(cols.RELATIONSHIP)))
            }
            return
        }
        val input = source.table(ParquetSchema.DEPENDENCIES_FILE) ?: return
        ParquetFileReader.open(input).use { reader ->
            reader.buildRowReader()
                .projection(ColumnProjection.columns(cols.FROM_TYPE, cols.FROM_KEY, cols.TO_TYPE, cols.RELATIONSHIP))
                .build().use { rows ->
                    // A projected reader addresses its columns by their position in the projection.
                    while (rows.hasNext()) {
                        rows.next()
                        visit(rows.getString(0), rows.getString(1), rows.getString(2), kindOf(rows.getString(3)))
                    }
                }
        }
    }

    /** The kind a stored `relationship` names; a name outside the vocabulary means a corrupt or foreign table. */
    private fun kindOf(relationship: String): DependencyKind =
        DependencyKind.fromValue(relationship)
            ?: throw SnapshotFormatException("Unknown dependency kind '$relationship' in a `dependencies` table.")

    /**
     * The artifact a snapshot source holds, read from its `types.parquet`; `null` when the source holds
     * several artifacts (the unpacked layout) or none. The writer files each artifact's detail rows with
     * that artifact, so this is the artifact of every row's depending type.
     */
    private fun sourceArtifact(source: ArtifactSource): String? =
        readRows(source, ParquetSchema.TYPES_FILE, null) { it.string(ParquetSchema.Types.ARTIFACT) }.distinct().singleOrNull()

    private fun <T> readAcross(parquetDir: Path, file: String, filter: FilterPredicate? = null, map: (RowCursor) -> T): List<T> =
        SnapshotSources.of(parquetDir).flatMap { readRows(it, file, filter, map) }

    /**
     * Scoped read of the `dependencies` table with predicate push-down: returns the rows
     * whose `from_type` is in [fromTypes] (unconstrained when null/empty), whose `to_type` is in
     * [toTypes] (likewise), and whose kind is [kind] (any when null). Hardwood
     * skips row groups outside the `from_type` range via column statistics; `to_type` filters rows but,
     * not being the sort key, does not drive row-group skipping.
     */
    fun readDependencies(
        parquetDir: Path,
        fromTypes: Collection<String>? = null,
        toTypes: Collection<String>? = null,
        kind: DependencyKind? = null,
    ): List<Dependency> {
        val cols = ParquetSchema.Dependencies
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
            kind != null -> FilterPredicate.eq(cols.RELATIONSHIP, kind.value)
            else -> null
        }
        return SnapshotSources.of(parquetDir).flatMap { source ->
            val rows = readRows(source, ParquetSchema.DEPENDENCIES_FILE, pushdown) { r ->
                Dependency(
                    fromKey = r.string(cols.FROM_KEY),
                    fromType = r.string(cols.FROM_TYPE),
                    toKey = r.string(cols.TO_KEY),
                    toType = r.string(cols.TO_TYPE),
                    kind = kindOf(r.string(cols.RELATIONSHIP)),
                    line = r.intOrNull(cols.LINE),
                )
            }
            if (rows.isEmpty()) rows else sourceArtifact(source)?.let { a -> rows.map { it.copy(fromArtifact = a) } } ?: rows
        }.filter {
            (from == null || it.fromType in from) &&
                (to == null || it.toType in to) &&
                (kind == null || it.kind == kind)
        }
    }


    /** One row of `types.parquet`: a scanned type's identity plus its per-class snapshot facts. */
    data class TypeRow(
        val artifact: String,
        val fqn: String,
        val kind: String,
        val sourceFile: String?,
        val classHash: String?,
        /** Packed [io.hierograph.graphstore.jvm.archive.model.TypeModifiers] flags; `null` for a snapshot written before the column existed. */
        val modifiers: Int?,
    )

    /**
     * Scoped read of `types.parquet`: the rows whose `fqn` is in [fqns] (one per defining artifact).
     * Used to serve per-type properties on demand without keeping them in the in-memory graph.
     */
    fun readTypeRows(parquetDir: Path, fqns: Collection<String>): List<TypeRow> {
        if (fqns.isEmpty()) return emptyList()
        val filter = FilterPredicate.`in`(ParquetSchema.Types.FQN, *fqns.toTypedArray())
        return SnapshotSources.of(parquetDir).flatMap { readTypes(it, filter) }
    }

    /**
     * Scoped read of `members.parquet`, grouped by owner FQN, for the owners in [ownerFqns] (no
     * parameters attached). Members are keyed by owner FQN only, so same-FQN types from different
     * artifacts share one entry.
     */
    fun readMembersByOwner(parquetDir: Path, ownerFqns: Collection<String>): Map<String, List<MemberFacts>> {
        if (ownerFqns.isEmpty()) return emptyMap()
        val filter = FilterPredicate.`in`(ParquetSchema.Members.OWNER_FQN, *ownerFqns.toTypedArray())
        return SnapshotSources.of(parquetDir)
            .flatMap { readMembers(it, filter).entries }
            .groupBy({ it.key }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
    }

    /** [source]'s `types.parquet` rows, restricted by [filter] when given. */
    fun readTypes(source: ArtifactSource, filter: FilterPredicate? = null): List<TypeRow> =
        readRows(source, ParquetSchema.TYPES_FILE, filter) { r ->
            TypeRow(
                artifact = r.string(ParquetSchema.Types.ARTIFACT),
                fqn = r.string(ParquetSchema.Types.FQN),
                kind = r.string(ParquetSchema.Types.KIND),
                sourceFile = r.stringOrNull(ParquetSchema.Types.SOURCE_FILE),
                classHash = r.stringOrNull(ParquetSchema.Types.CLASS_HASH),
                modifiers = if (r.hasColumn(ParquetSchema.Types.MODIFIERS)) r.intOrNull(ParquetSchema.Types.MODIFIERS) ?: 0 else null,
            )
        }

    /** [source]'s `members.parquet` rows (no parameters attached), grouped by owner FQN, restricted by [filter] when given. */
    fun readMembers(source: ArtifactSource, filter: FilterPredicate? = null): Map<String, List<MemberFacts>> =
        readRows(source, ParquetSchema.MEMBERS_FILE, filter) { r -> r.string(ParquetSchema.Members.OWNER_FQN) to memberFacts(r) }
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
        val member = SnapshotSources.of(parquetDir).firstNotNullOfOrNull { source ->
            readRows(source, ParquetSchema.MEMBERS_FILE, filter) { memberFacts(it) }.firstOrNull()
        } ?: return null
        return member.copy(parameters = readParameters(parquetDir, memberKey))
    }

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
                annotations = if (r.hasColumn(ParquetSchema.Parameters.ANNOTATIONS))
                    r.stringOrNull(ParquetSchema.Parameters.ANNOTATIONS)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
                else emptyList(),
            )
        }.sortedBy { it.index }

    private fun <T> readRows(source: ArtifactSource, file: String, filter: FilterPredicate? = null, map: (RowCursor) -> T): List<T> {
        val input = source.table(file) ?: return emptyList()
        val out = ArrayList<T>()
        ParquetFileReader.open(input).use { reader ->
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
