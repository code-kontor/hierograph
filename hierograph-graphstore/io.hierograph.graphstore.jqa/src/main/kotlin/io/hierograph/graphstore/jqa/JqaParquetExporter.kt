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
package io.hierograph.graphstore.jqa

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.boltclient.IBoltClient
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.MemberFacts
import io.hierograph.graphstore.model.MemberModifiers
import io.hierograph.graphstore.model.ParameterFacts
import io.hierograph.graphstore.model.Ref
import io.hierograph.graphstore.model.RefKind
import io.hierograph.graphstore.model.TypeModifiers
import io.hierograph.graphstore.parquet.ParquetWriter
import io.hierograph.graphstore.parquet.ParquetWriter.ArtifactClass
import io.hierograph.mcp.javaspec.JavaNodeKind
import org.neo4j.driver.Record
import java.nio.file.Path

/**
 * The MVP stand-in scanner: runs the [JqaExportCypher] queries against a live jQAssistant Neo4j store
 * and reconstructs one [ClassFacts] per type — the same unit the real ASM scanner will produce. No ASM
 * code is involved yet.
 *
 * The resulting classes can be written to a Parquet snapshot ([exportTo]) and then loaded with
 * `ParquetReader`, or fed straight into a GraphStore ([exportClasses]) when the Parquet hop is
 * not needed.
 */
class JqaParquetExporter(private val boltClient: IBoltClient) {

    /** Reads the jQAssistant store and assembles the scanned classes, grouped members and refs included. */
    fun exportClasses(): List<ArtifactClass> {
        // Parameters first (keyed by member), so each member carries its declared parameters.
        val parametersByMember = query(JqaExportCypher.PARAMETERS) { r ->
            r.string("method_key") to ParameterFacts(
                index = r.intOrNull("index") ?: 0,
                name = r.string("name"),
                typeFqn = r.string("type_fqn"),
            )
        }.groupBy({ it.first }, { it.second })

        val membersByOwner = query(JqaExportCypher.MEMBERS) { r ->
            val memberKey = r.string("member_key")
            r.string("owner_fqn") to MemberFacts(
                key = memberKey,
                kind = JavaNodeKind.valueOf(r.string("kind")),
                signature = r.string("signature"),
                name = r.stringOrNull("name").orEmpty(),
                modifiers = MemberModifiers.pack(
                    visibility = r.stringOrNull("visibility"),
                    static = r.bool("is_static"),
                    final = r.bool("is_final"),
                    abstract = r.bool("is_abstract"),
                    synchronized = r.bool("is_synchronized"),
                    native = r.bool("is_native"),
                    default = r.bool("is_default"),
                    transient = r.bool("is_transient"),
                    volatile = r.bool("is_volatile"),
                ),
                lineFrom = r.intOrNull("line_from"),
                lineTo = r.intOrNull("line_to"),
                parameters = parametersByMember[memberKey].orEmpty().sortedBy { it.index },
            )
        }.groupBy({ it.first }, { it.second })

        val refsByOwner = query(JqaExportCypher.REFS) { r ->
            r.string("from_type") to Ref(
                fromKey = r.string("from_type"),
                toKey = r.string("to_type"),
                kind = RefKind.valueOf(r.string("kind")),
                count = r.intOrNull("count"),
            )
        }.groupBy({ it.first }, { it.second })

        return query(JqaExportCypher.TYPES) { r ->
            val fqn = r.string("fqn")
            ArtifactClass(
                artifactKey = r.string("artifact"),
                facts = ClassFacts(
                    fqn = fqn,
                    kind = JavaNodeKind.valueOf(r.string("kind")),
                    modifiers = TypeModifiers.pack(
                        visibility = r.stringOrNull("visibility"),
                        abstract = r.bool("is_abstract"),
                        final = r.bool("is_final"),
                        static = r.bool("is_static"),
                    ),
                    members = membersByOwner[fqn].orEmpty(),
                    refs = refsByOwner[fqn].orEmpty(),
                    classHash = "",
                    sourceFile = r.stringOrNull("source_file"),
                ),
            )
        }
    }

    /** Exports the store and writes a Parquet snapshot (types/members/refs) under [parquetDir]. */
    fun exportTo(parquetDir: Path, codec: CompressionCodec = CompressionCodec.ZSTD) {
        ParquetWriter.writeClassFacts(parquetDir, exportClasses(), codec)
    }

    private fun <T> query(cypher: String, map: (RecordView) -> T): List<T> =
        boltClient.syncExecCypherQuery(cypher).records().map { map(RecordView(it)) }

    /** Reads columns by name off a Neo4j [Record], treating Neo4j nulls as Kotlin `null`. */
    private class RecordView(private val record: Record) {
        fun string(column: String): String = record.get(column).asString()
        fun stringOrNull(column: String): String? = record.get(column).let { if (it.isNull) null else it.asString() }
        fun intOrNull(column: String): Int? = record.get(column).let { if (it.isNull) null else it.asInt() }
        fun bool(column: String): Boolean = record.get(column).let { if (it.isNull) false else it.asBoolean() }
    }
}
