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
import io.hierograph.graphstore.model.Ref
import io.hierograph.graphstore.model.RefKind
import io.hierograph.graphstore.parquet.SnapshotWriter
import io.hierograph.graphstore.parquet.SnapshotWriter.ArtifactClass
import io.hierograph.mcp.javaspec.JavaNodeKind
import org.neo4j.driver.Record
import java.nio.file.Path

/**
 * The MVP stand-in scanner: runs the [JqaExportCypher] queries against a live jQAssistant Neo4j store
 * and reconstructs one [ClassFacts] per type — the same unit the real ASM scanner will produce. No ASM
 * code is involved yet.
 *
 * The resulting classes can be written to a Parquet snapshot ([exportTo]) and then loaded with
 * `ParquetSnapshotLoader`, or fed straight into a GraphStore ([exportClasses]) when the Parquet hop is
 * not needed.
 */
class JqaSnapshotExporter(private val boltClient: IBoltClient) {

    /** Reads the jQAssistant store and assembles the scanned classes, grouped members and refs included. */
    fun exportClasses(): List<ArtifactClass> {
        val membersByOwner = query(JqaExportCypher.MEMBERS) { r ->
            r.string("owner_fqn") to MemberFacts(
                key = r.string("member_key"),
                kind = JavaNodeKind.valueOf(r.string("kind")),
                signature = r.string("signature"),
                name = r.stringOrNull("name").orEmpty(),
                lineFrom = r.intOrNull("line_from"),
                lineTo = r.intOrNull("line_to"),
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
                    modifiers = 0,
                    members = membersByOwner[fqn].orEmpty(),
                    refs = refsByOwner[fqn].orEmpty(),
                    classHash = "",
                    sourceFile = r.stringOrNull("source_file"),
                ),
            )
        }
    }

    /** Exports the store and writes a Parquet snapshot (types/members/refs) under [snapshotDir]. */
    fun exportTo(snapshotDir: Path, codec: CompressionCodec = CompressionCodec.ZSTD) {
        SnapshotWriter.writeClassFacts(snapshotDir, exportClasses(), codec)
    }

    private fun <T> query(cypher: String, map: (RecordView) -> T): List<T> =
        boltClient.syncExecCypherQuery(cypher).records().map { map(RecordView(it)) }

    /** Reads columns by name off a Neo4j [Record], treating Neo4j nulls as Kotlin `null`. */
    private class RecordView(private val record: Record) {
        fun string(column: String): String = record.get(column).asString()
        fun stringOrNull(column: String): String? = record.get(column).let { if (it.isNull) null else it.asString() }
        fun intOrNull(column: String): Int? = record.get(column).let { if (it.isNull) null else it.asInt() }
    }
}
