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
import dev.hardwood.reader.ParquetFileReader
import dev.hardwood.reader.RowReader
import dev.hardwood.schema.FileSchema
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.model.ArtifactInfo
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.MemberFacts
import io.hierograph.graphstore.model.Origin
import io.hierograph.graphstore.model.Ref
import io.hierograph.graphstore.model.RefKind
import io.hierograph.mcp.javaspec.JavaNodeKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads a GraphStore Parquet snapshot (the [SnapshotSchema] tables) back into a [GraphStore].
 *
 * The loader reconstructs one [ClassFacts] per type from the flat `types` / `members` / `refs` tables
 * and feeds it through [GraphStore.applyClass] — the *same* ingest path a live scan uses — then calls
 * [GraphStore.resolvePlaceholders] once. Referenced-but-undefined types (jQAssistant's "required" types
 * that were never exported) surface as placeholders through their incoming refs, so snapshots stay
 * composable and never duplicate external nodes.
 */
class ParquetSnapshotLoader {

    /** Loads every table found under [snapshotDir] into [store]. Missing tables are treated as empty. */
    fun load(snapshotDir: Path, store: GraphStore) {
        val types = readTypes(snapshotDir.resolve(SnapshotSchema.TYPES_FILE))
        val membersByOwner = readMembers(snapshotDir.resolve(SnapshotSchema.MEMBERS_FILE))
        val refsByOwner = readRefs(snapshotDir.resolve(SnapshotSchema.REFS_FILE))

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

    private data class TypeRow(
        val artifact: String,
        val fqn: String,
        val kind: String,
        val sourceFile: String?,
        val classHash: String?,
    )

    private fun readTypes(path: Path): List<TypeRow> =
        readRows(path) { r ->
            TypeRow(
                artifact = r.string(SnapshotSchema.Types.ARTIFACT),
                fqn = r.string(SnapshotSchema.Types.FQN),
                kind = r.string(SnapshotSchema.Types.KIND),
                sourceFile = r.stringOrNull(SnapshotSchema.Types.SOURCE_FILE),
                classHash = r.stringOrNull(SnapshotSchema.Types.CLASS_HASH),
            )
        }

    private fun readMembers(path: Path): Map<String, List<MemberFacts>> =
        readRows(path) { r ->
            r.string(SnapshotSchema.Members.OWNER_FQN) to MemberFacts(
                key = r.string(SnapshotSchema.Members.MEMBER_KEY),
                kind = JavaNodeKind.valueOf(r.string(SnapshotSchema.Members.KIND)),
                signature = r.string(SnapshotSchema.Members.SIGNATURE),
                lineFrom = r.intOrNull(SnapshotSchema.Members.LINE_FROM),
                lineTo = r.intOrNull(SnapshotSchema.Members.LINE_TO),
            )
        }.groupBy({ it.first }, { it.second })

    private fun readRefs(path: Path): Map<String, List<Ref>> =
        readRows(path) { r ->
            r.string(SnapshotSchema.Refs.FROM_TYPE) to Ref(
                fromKey = r.string(SnapshotSchema.Refs.FROM_KEY),
                toKey = r.string(SnapshotSchema.Refs.TO_KEY),
                kind = RefKind.valueOf(r.string(SnapshotSchema.Refs.KIND)),
                count = r.intOrNull(SnapshotSchema.Refs.COUNT),
            )
        }.groupBy({ it.first }, { it.second })

    private fun <T> readRows(path: Path, map: (RowCursor) -> T): List<T> {
        if (!Files.exists(path)) return emptyList()
        val out = ArrayList<T>()
        ParquetFileReader.open(InputFile.of(path)).use { reader ->
            reader.rowReader().use { rowReader ->
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

        fun stringOrNull(name: String): String? =
            index(name).let { if (reader.isNull(it)) null else reader.getString(it) }

        fun intOrNull(name: String): Int? =
            index(name).let { if (reader.isNull(it)) null else reader.getInt(it) }
    }
}
