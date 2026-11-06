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

/**
 * The jQAssistant export queries (spec §7 step 1). Each query is read by [JqaSnapshotExporter] and the
 * columns map 1:1 onto the [io.hierograph.graphstore.parquet.SnapshotSchema] tables; the kind strings
 * are the enum names expected by the loader (`CLASS`, `METHOD`, `EXTENDS`, …).
 *
 * The `artifact` identity is aligned with the module FQN the jQAssistant `GraphDbMapping` top-level
 * rules assign: a jar's `fileName` without its leading slash, otherwise the artifact's `fqn`. Stub and
 * virtual external types share the same FQN, so `RESOLVES_TO` is not needed here.
 */
object JqaExportCypher {

    val TYPES = """
        MATCH (a:Artifact)-[:CONTAINS]->(t:Type)
        WHERE NOT t:Virtual
        RETURN
          CASE WHEN a:Jar THEN substring(a.fileName, 1) ELSE a.fqn END AS artifact,
          t.fqn AS fqn,
          CASE WHEN t:Annotation THEN 'ANNOTATION' WHEN t:Enum THEN 'ENUM'
               WHEN t:Record THEN 'RECORD' WHEN t:Interface THEN 'INTERFACE'
               ELSE 'CLASS' END AS kind,
          t.sourceFileName AS source_file
    """.trimIndent()

    val MEMBERS = """
        MATCH (a:Artifact)-[:CONTAINS]->(t:Type)-[:DECLARES]->(m)
        WHERE (m:Method OR m:Field) AND NOT t:Virtual
        RETURN t.fqn AS owner_fqn,
               t.fqn + '#' + m.signature AS member_key,
               CASE WHEN m:Field THEN 'FIELD' ELSE 'METHOD' END AS kind,
               m.signature AS signature,
               m.firstLineNumber AS line_from,
               m.lastLineNumber AS line_to
    """.trimIndent()

    val REFS = """
        MATCH (t1:Type)-[:EXTENDS]->(t2:Type) WHERE NOT t1:Virtual
        RETURN DISTINCT t1.fqn AS from_type, t2.fqn AS to_type, 'EXTENDS' AS kind, 1 AS count
        UNION ALL
        MATCH (t1:Type)-[:IMPLEMENTS]->(t2:Type) WHERE NOT t1:Virtual
        RETURN DISTINCT t1.fqn AS from_type, t2.fqn AS to_type, 'IMPLEMENTS' AS kind, 1 AS count
        UNION ALL
        MATCH (t1:Type)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(at:Type) WHERE NOT t1:Virtual
        RETURN DISTINCT t1.fqn AS from_type, at.fqn AS to_type, 'ANNOTATED_BY' AS kind, 1 AS count
        UNION ALL
        MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type) WHERE NOT t1:Virtual AND NOT t2:Virtual
        RETURN t1.fqn AS from_type, t2.fqn AS to_type, 'DEPENDS_ON' AS kind, r.weight AS count
    """.trimIndent()
}
