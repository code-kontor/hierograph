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

import io.hierograph.boltclient.IBoltClient
import io.hierograph.hierarchicalgraph.graphdb.mapping.service.IBoltClientAware
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNodeId
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ToplevelNodeId
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind

/**
 * jQAssistant-backed [IHierarchyDefinitionProvider]. Implements the SPI directly
 * (instead of subclassing `AbstractQueryBasedHierarchyProvider`) so we can parse
 * extra `name` / `fqn` columns from the Cypher queries — without widening the
 * upstream SPI.
 *
 * After [initialize] has been called (which happens inside
 * `DefaultMappingService.convert`), [nameFqnByNodeId] holds the
 * `nodeId -> (name, fqn)` map populated from those same queries. Consumers that
 * want per-node display metadata read this map after graph construction.
 */
class JQAssistantHierarchyProvider : IHierarchyDefinitionProvider, IBoltClientAware {

    private var _toplevelNodeIds: List<ToplevelNodeId> = emptyList()
    private var _parentChildNodes: List<ParentChildNodeId> = emptyList()
    private val _nameFqnByNodeId = HashMap<Long, Pair<String?, String?>>()

    /** Read-only view of the `nodeId -> (name, fqn)` map captured during [initialize]. */
    val nameFqnByNodeId: Map<Long, Pair<String?, String?>> get() = _nameFqnByNodeId

    override fun getToplevelNodeIds(): List<ToplevelNodeId> = _toplevelNodeIds

    override fun getParentChildNodeIds(): List<ParentChildNodeId> = _parentChildNodes

    override fun initialize(boltClient: IBoltClient) {
        val toplevel = mutableListOf<ToplevelNodeId>()
        for (query in toplevelNodeIdQueries) {
            val rows = boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    val id = r.get(0).asLong()
                    val kind = parseKind(r.get(1).asString())
                    val name = r.get(2).asString("").takeIf { it.isNotBlank() }
                    val fqn = r.get(3).asString("").takeIf { it.isNotBlank() }
                    _nameFqnByNodeId[id] = name to fqn
                    ToplevelNodeId(id = id, kind = kind)
                }
            }.get()
            toplevel.addAll(rows)
        }
        _toplevelNodeIds = toplevel

        val parentChild = mutableListOf<ParentChildNodeId>()
        for (query in parentChildNodeIdsQueries) {
            val rows = boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    val parentId = r.get(0).asLong()
                    val childId = r.get(1).asLong()
                    val childKind = parseKind(r.get(2).asString())
                    val childName = r.get(3).asString("").takeIf { it.isNotBlank() }
                    val childFqn = r.get(4).asString("").takeIf { it.isNotBlank() }
                    _nameFqnByNodeId[childId] = childName to childFqn
                    ParentChildNodeId(parentId = parentId, childId = childId, childKind = childKind)
                }
            }.get()
            parentChild.addAll(rows)
        }
        _parentChildNodes = parentChild
    }

    private fun parseKind(kindString: String): Any =
        JavaNodeKind.fromValue(kindString) ?: kindString

    private val toplevelNodeIdQueries: Array<String> = arrayOf(
        // scanned jars directly
        "MATCH (a:Artifact:Jar) RETURN id(a) as id, '${JavaKinds.MODULE}', a.name, a.fqn",
        // scanned maven projects
        "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a) as id, '${JavaKinds.MODULE}', a.name, a.fqn"
    )

    private val parentChildNodeIdsQueries: Array<String> = arrayOf(
        // Artifact -> top-level Packages
        "MATCH (a:Artifact:Jar)-[:CONTAINS]->(b:Package) WHERE NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}', b.name, b.fqn",
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
        "MATCH (a:Type)-[:DECLARES]->(b:Method) RETURN id(a), id(b), '${JavaKinds.METHOD}', b.signature, a.fqn + '#' + b.signature"
    )
}
