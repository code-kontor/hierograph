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
package io.hierograph.graphstore.jqa.detail

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.boltclient.IBoltClient
import java.nio.file.Path

/**
 * MVP stand-in for the FULL-mode scanner's member-level output: runs jQAssistant's native member edges
 * and writes them to `detail_refs.parquet`, so the Parquet-backed detail engine can be built and diffed
 * before the ASM scanner exists.
 *
 * Unlike the detail *query* (which expands inheritance and filters by scope), the export enumerates the
 * **raw** member edges of every scanned (non-virtual) type once; scope expansion and relationship
 * filtering are query-time concerns handled against the snapshot.
 */
class JqaDetailExporter(private val boltClient: IBoltClient) {

    fun exportClasses(): List<DetailRef> =
        boltClient.syncExecCypherQuery(DETAIL_REFS).records().map { r ->
            DetailRef(
                fromKey = r.get("from_key").asString(),
                fromType = r.get("from_type").asString(),
                toKey = r.get("to_key").asString(),
                toType = r.get("to_type").asString(),
                relationship = r.get("relationship").asString(),
                line = r.get("line").let { if (it.isNull) null else it.asInt() },
            )
        }

    fun exportTo(dir: Path, codec: CompressionCodec = CompressionCodec.UNCOMPRESSED) {
        DetailRefs.write(dir, exportClasses(), codec)
    }

    companion object {
        /** The full-export query: every scanned type's raw member edges. */
        val DETAIL_REFS = detailRefsCypher("")

        /**
         * Raw member-level edges mirroring the detail tool's `BRANCHES`, as a `UNION ALL`. Each branch
         * returns the same six columns. Member keys use `type.fqn + '#' + signature` (matching
         * `members.parquet`); type-reference targets set `to_key = to_type = the type FQN`. `read_by` /
         * `written_by` are not stored — they are the `READS` / `WRITES` edges read in reverse at query
         * time. `parameter_annotated_by` is deferred.
         *
         * [sourceFilter] is appended to every branch's `WHERE` (the source type is always bound to
         * `ft`), so the same patterns serve both the full export (`""`) and a scoped reference query
         * (e.g. `"AND ft.fqn IN ${'$'}fromFqns"`).
         */
        fun detailRefsCypher(sourceFilter: String): String = """
            MATCH (ft:Type)-[:DECLARES]->(s:Method)-[r:INVOKES|VIRTUAL_INVOKES]->(t:Method)<-[:DECLARES]-(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn + '#' + t.signature AS to_key, tt.fqn AS to_type,
                   'calls' AS relationship, r.lineNumber AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s:Method)-[r:READS]->(t:Field)<-[:DECLARES]-(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn + '#' + t.signature AS to_key, tt.fqn AS to_type,
                   'reads_field' AS relationship, r.lineNumber AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s:Method)-[r:WRITES]->(t:Field)<-[:DECLARES]-(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn + '#' + t.signature AS to_key, tt.fqn AS to_type,
                   'writes_field' AS relationship, r.lineNumber AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s:Method)-[:OVERRIDES]->(t:Method)<-[:DECLARES]-(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn + '#' + t.signature AS to_key, tt.fqn AS to_type,
                   'overrides' AS relationship, s.firstLineNumber AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s:Method)-[:THROWS]->(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn AS to_key, tt.fqn AS to_type,
                   'throws' AS relationship, s.firstLineNumber AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s:Method)-[:RETURNS]->(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn AS to_key, tt.fqn AS to_type,
                   'returns' AS relationship, s.firstLineNumber AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s:Field)-[:OF_TYPE]->(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn AS to_key, tt.fqn AS to_type,
                   'has_type' AS relationship, null AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s:Method)-[:HAS]->(:Parameter)-[:OF_TYPE]->(tt:Type)
            WHERE NOT ft:Virtual $sourceFilter
            RETURN DISTINCT ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   tt.fqn AS to_key, tt.fqn AS to_type,
                   'parameter_type' AS relationship, s.firstLineNumber AS line
            UNION ALL
            MATCH (ft:Type)-[:DECLARES]->(s)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(at:Type)
            WHERE NOT ft:Virtual AND (s:Method OR s:Field) $sourceFilter
            RETURN ft.fqn + '#' + s.signature AS from_key, ft.fqn AS from_type,
                   at.fqn AS to_key, at.fqn AS to_type,
                   'annotated_by' AS relationship, null AS line
        """.trimIndent()
    }
}
