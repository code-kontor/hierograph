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

    /** Loads every table found under [parquetDir] into [store]. Missing tables are treated as empty. */
    fun load(parquetDir: Path, store: GraphStore) {
        val types = readTypes(parquetDir.resolve(ParquetSchema.TYPES_FILE))
        val membersByOwner = readMembers(parquetDir.resolve(ParquetSchema.MEMBERS_FILE))
        val refsByOwner = readRefs(parquetDir.resolve(ParquetSchema.REFS_FILE))

        val registered = HashSet<String>()
        for (t in types) {
            if (registered.add(t.artifact)) {
                // MVP: the artifact column is a bare name; origin is not needed by the row projections.
                store.registerArtifact(ArtifactInfo(t.artifact, t.artifact, t.artifact, Origin.MODULE_MAIN))
            }
            store.applyClass(
                t.artifact,
                ClassFacts(
                    fqn = t.fqn,
                    kind = JavaNodeKind.valueOf(t.kind),
                    modifiers = 0,
                    members = membersByOwner[t.fqn].orEmpty(),
                    refs = refsByOwner[t.fqn].orEmpty(),
                    classHash = t.classHash.orEmpty(),
                    sourceFile = t.sourceFile,
                ),
            )
        }
        store.resolvePlaceholders()
    }

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
        return readRows(parquetDir.resolve(ParquetSchema.DETAIL_REFS_FILE), detailRefsFilter(fromTypes, toTypes, relationship)) { r ->
            DetailRef(
                fromKey = r.string(cols.FROM_KEY),
                fromType = r.string(cols.FROM_TYPE),
                toKey = r.string(cols.TO_KEY),
                toType = r.string(cols.TO_TYPE),
                relationship = r.string(cols.RELATIONSHIP),
                line = r.intOrNull(cols.LINE),
            )
        }
    }

    private fun detailRefsFilter(fromTypes: Collection<String>?, toTypes: Collection<String>?, relationship: String?): FilterPredicate? {
        val cols = ParquetSchema.DetailRefs
        val preds = buildList {
            if (!fromTypes.isNullOrEmpty()) add(FilterPredicate.`in`(cols.FROM_TYPE, *fromTypes.toTypedArray()))
            if (!toTypes.isNullOrEmpty()) add(FilterPredicate.`in`(cols.TO_TYPE, *toTypes.toTypedArray()))
            if (relationship != null) add(FilterPredicate.eq(cols.RELATIONSHIP, relationship))
        }
        return when (preds.size) {
            0 -> null
            1 -> preds[0]
            else -> FilterPredicate.and(*preds.toTypedArray())
        }
    }

    /** One row of `types.parquet`: a scanned type's identity plus its per-class snapshot facts. */
    data class TypeRow(
        val artifact: String,
        val fqn: String,
        val kind: String,
        val sourceFile: String?,
        val classHash: String?,
    )

    /**
     * Scoped read of `types.parquet`: the rows whose `fqn` is in [fqns] (one per defining artifact).
     * Used to serve per-type properties on demand without keeping them in the in-memory graph.
     */
    fun readTypeRows(parquetDir: Path, fqns: Collection<String>): List<TypeRow> =
        if (fqns.isEmpty()) emptyList()
        else readTypes(parquetDir.resolve(ParquetSchema.TYPES_FILE), FilterPredicate.`in`(ParquetSchema.Types.FQN, *fqns.toTypedArray()))

    /**
     * Scoped read of `members.parquet`, grouped by owner FQN, for the owners in [ownerFqns] (no
     * parameters attached). Members are keyed by owner FQN only, so same-FQN types from different
     * artifacts share one entry.
     */
    fun readMembersByOwner(parquetDir: Path, ownerFqns: Collection<String>): Map<String, List<MemberFacts>> =
        if (ownerFqns.isEmpty()) emptyMap()
        else readMembers(parquetDir.resolve(ParquetSchema.MEMBERS_FILE), FilterPredicate.`in`(ParquetSchema.Members.OWNER_FQN, *ownerFqns.toTypedArray()))

    private fun readTypes(path: Path, filter: FilterPredicate? = null): List<TypeRow> =
        readRows(path, filter) { r ->
            TypeRow(
                artifact = r.string(ParquetSchema.Types.ARTIFACT),
                fqn = r.string(ParquetSchema.Types.FQN),
                kind = r.string(ParquetSchema.Types.KIND),
                sourceFile = r.stringOrNull(ParquetSchema.Types.SOURCE_FILE),
                classHash = r.stringOrNull(ParquetSchema.Types.CLASS_HASH),
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
        )

    /**
     * Reads a single member by its key (`<owner fqn>#<signature>`) from `members.parquet`, with its
     * declared parameters attached from `parameters.parquet`. Returns `null` when no such member exists.
     * The detail tools use this to serve `method_details` / `field_details` straight from the snapshot.
     */
    fun readMember(parquetDir: Path, memberKey: String): MemberFacts? {
        val member = readRows(parquetDir.resolve(ParquetSchema.MEMBERS_FILE), FilterPredicate.eq(ParquetSchema.Members.MEMBER_KEY, memberKey)) { memberFacts(it) }
            .firstOrNull() ?: return null
        return member.copy(parameters = readParameters(parquetDir, memberKey))
    }

    /** Every member key in the snapshot (for building a handle→key index). */
    fun readMemberKeys(parquetDir: Path): List<String> =
        readRows(parquetDir.resolve(ParquetSchema.MEMBERS_FILE)) { it.string(ParquetSchema.Members.MEMBER_KEY) }

    /** Every member in the snapshot (no parameters attached); for enumeration/diagnostics. */
    fun readAllMembers(parquetDir: Path): List<MemberFacts> =
        readRows(parquetDir.resolve(ParquetSchema.MEMBERS_FILE)) { memberFacts(it) }

    /** Reads a method's declared parameters (ordered by index) from `parameters.parquet`. */
    fun readParameters(parquetDir: Path, methodKey: String): List<ParameterFacts> =
        readRows(parquetDir.resolve(ParquetSchema.PARAMETERS_FILE), FilterPredicate.eq(ParquetSchema.Parameters.METHOD_KEY, methodKey)) { r ->
            ParameterFacts(
                index = r.int(ParquetSchema.Parameters.INDEX),
                name = r.string(ParquetSchema.Parameters.NAME),
                typeFqn = r.string(ParquetSchema.Parameters.TYPE_FQN),
            )
        }.sortedBy { it.index }

    private fun readRefs(path: Path): Map<String, List<Ref>> =
        readRows(path) { r ->
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
