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
package io.hierograph.mcp.jqa.hierarchicalgraph

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.AbstractBoltClientAware
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNodeId
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ToplevelNodeId
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import org.neo4j.driver.Record
import org.neo4j.driver.Value

/**
 * jQAssistant-backed [IHierarchyDefinitionProvider]. Implements the SPI directly
 * (instead of subclassing `AbstractQueryBasedHierarchyProvider`) so we can parse
 * extra `name` / `fqn` columns from the Cypher queries — without widening the
 * upstream SPI.
 *
 * After [boltClient] has been set (which happens inside
 * `DefaultMappingService.convert`), [nameFqnByNodeId] holds the
 * `nodeId -> (name, fqn)` map populated from those same queries. Consumers that
 * want per-node display metadata read this map after graph construction.
 */
class JQAssistantHierarchyProvider : IHierarchyDefinitionProvider, AbstractBoltClientAware() {

    private val _nameFqnByNodeId = HashMap<Long, Pair<String?, String?>>()

    override var toplevelNodeIds: List<ToplevelNodeId> = emptyList()
        private set
    override var parentChildNodeIds: List<ParentChildNodeId> = emptyList()
        private set

    /** Read-only view of the `nodeId -> (name, fqn)` map captured during [boltClient] assignment. */
    val nameFqnByNodeId: Map<Long, Pair<String?, String?>> get() = _nameFqnByNodeId

    override fun initialize() {
        // distinctBy { it.id }: the queries can legitimately surface the same module id more than
        // once (e.g. a Maven project that produces both a Main and a Test artifact matches the
        // project query twice), and a node must not appear as a top-level entry more than once.
        toplevelNodeIds = toplevelNodeIdQueries.flatMap { query ->
            queryList(query) { r ->
                val id = r[0].asLong()
                rememberNameFqn(id, r[2], r[3])
                ToplevelNodeId(id = id, kind = parseKind(r[1].asString()))
            }
        }.distinctBy { it.id }
        parentChildNodeIds = parentChildNodeIdsQueries.flatMap { query ->
            queryList(query) { r ->
                val childId = r[1].asLong()
                rememberNameFqn(childId, r[3], r[4])
                ParentChildNodeId(
                    parentId = r[0].asLong(),
                    childId = childId,
                    childKind = parseKind(r[2].asString()),
                )
            }
        }
    }

    override fun dispose() {
        toplevelNodeIds = emptyList()
        parentChildNodeIds = emptyList()
        _nameFqnByNodeId.clear()
    }

    override fun createNodeSource(id: Long): GraphDbNodeSource {
        val (name, fqn) = _nameFqnByNodeId[id] ?: (null to null)
        return ExtendedGraphDbNodeSource(identifier = id, name = name.orEmpty(), fqn = fqn.orEmpty())
    }

    private fun parseKind(kindString: String): Any =
        JavaNodeKind.fromValue(kindString) ?: kindString

    /** Runs [cypher] and maps each result record, blocking on the async result. */
    private fun <T> queryList(cypher: String, mapRecord: (Record) -> T): List<T> =
        boltClient.asyncExecCypherQueryAndTransformResult(cypher) { result ->
            result.list(mapRecord)
        }.get()

    /** Captures the (blank-normalized) name/fqn columns for [id] for later [createNodeSource] lookups. */
    private fun rememberNameFqn(id: Long, name: Value, fqn: Value) {
        _nameFqnByNodeId[id] = name.asString("").takeIf { it.isNotBlank() } to
            fqn.asString("").takeIf { it.isNotBlank() }
    }

    private val toplevelNodeIdQueries: List<String> = listOf(
        // scanned jars directly
        "MATCH (a:Artifact:Jar) RETURN id(a) as id, '${JavaKinds.MODULE}', a.name, a.fqn",
        // scanned maven projects
        "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a) as id, '${JavaKinds.MODULE}', a.name, a.fqn",
        // Virtual 'External' module
        "MATCH (a:Virtual:Artifact) RETURN id(a) as id, '${JavaKinds.MODULE}'",
    )

    private val parentChildNodeIdsQueries: List<String> = listOf(
        // Artifact -> top-level Packages
        "MATCH (a:Artifact:Jar)-[:CONTAINS]->(b:Package) WHERE NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}', b.name, b.fqn",
        "MATCH (a:Artifact:Virtual)-[:CONTAINS]->(b:Package) WHERE NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}', b.name, b.fqn",
        "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a), id(b), '${JavaKinds.MODULE}', b.name, b.fqn",
        "MATCH (a:Artifact:Maven:File:Main)-[:CONTAINS]->(b:Package) where a.type = 'jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}', b.name, b.fqn",
        "MATCH (a:Artifact:Maven:File:Test)-[:CONTAINS]->(b:Package) where a.type = 'test-jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}', b.name, b.fqn",
        // Package -> sub-Packages
        "MATCH (a:Package)-[:CONTAINS]->(b:Package) RETURN id(a), id(b), '${JavaKinds.PACKAGE}', b.name, b.fqn",
        // Package -> Types (Class, Interface, Enum, Annotation, Record)
        "MATCH (a:Package)-[:CONTAINS]->(b:Class) RETURN id(a), id(b), '${JavaKinds.CLASS}', b.name, b.fqn",
        "MATCH (a:Package)-[:CONTAINS]->(b:Interface) RETURN id(a), id(b), '${JavaKinds.INTERFACE}', b.name, b.fqn",
        "MATCH (a:Package)-[:CONTAINS]->(b:Enum) RETURN id(a), id(b), '${JavaKinds.ENUM}', b.name, b.fqn",
        "MATCH (a:Package)-[:CONTAINS]->(b:Annotation) RETURN id(a), id(b), '${JavaKinds.ANNOTATION}', b.name, b.fqn",
        "MATCH (a:Package)-[:CONTAINS]->(b:Record) RETURN id(a), id(b), '${JavaKinds.RECORD}', b.name, b.fqn",
        // Types -> Methods and Fields (signature used as the display name; fqn is type.fqn + '#' + signature)
        "MATCH (a:Type)-[:DECLARES]->(b:Field) RETURN id(a), id(b), '${JavaKinds.FIELD}', b.signature, a.fqn + '#' + b.signature",
        "MATCH (a:Type)-[:DECLARES]->(b:Method) RETURN id(a), id(b), '${JavaKinds.METHOD}', b.signature, a.fqn + '#' + b.signature",
        //
        "MATCH (a:Virtual:Package)-[:CONTAINS]->(b:Virtual:Type) RETURN id(a), id(b), '${JavaKinds.CLASS}', b.name, b.fqn",
    )
}
