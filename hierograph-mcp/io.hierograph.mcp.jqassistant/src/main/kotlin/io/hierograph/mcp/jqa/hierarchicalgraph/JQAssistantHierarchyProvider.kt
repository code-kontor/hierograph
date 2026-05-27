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

import io.hierograph.hierarchicalgraph.graphdb.mapping.service.AbstractQueryBasedHierarchyProvider
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind

class JQAssistantHierarchyProvider : AbstractQueryBasedHierarchyProvider() {

    override fun parseKind(kindString: String): Any =
        JavaNodeKind.fromValue(kindString) ?: kindString

    override fun toplevelNodeIdQueries(): Array<String> = arrayOf(
        // scanned jars directly
        "MATCH (a:Artifact:Jar) RETURN id(a) as id, '${JavaKinds.MODULE}'",
        // scanned maven projects
        "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a) as id, '${JavaKinds.MODULE}'"
    )

    override fun parentChildNodeIdsQueries(): Array<String> = arrayOf(
        // Artifact -> top-level Packages
        "MATCH (a:Artifact:Jar)-[:CONTAINS]->(b:Package) WHERE NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}'",
        "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a), id(b), '${JavaKinds.MODULE}'",
        "MATCH (a:Artifact:Maven:File:Main)-[:CONTAINS]->(b:Package) where a.type = 'jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}'",
        "MATCH (a:Artifact:Maven:File:Test)-[:CONTAINS]->(b:Package) where a.type = 'test-jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), '${JavaKinds.PACKAGE}'",
        // Package -> sub-Packages
        "MATCH (a:Package)-[:CONTAINS]->(b:Package) RETURN id(a), id(b), '${JavaKinds.PACKAGE}'",
        // Package -> Types (Class, Interface, Enum, Annotation, Record)
        "MATCH (a:Package)-[:CONTAINS]->(b:Class) RETURN id(a), id(b), '${JavaKinds.CLASS}'",
        "MATCH (a:Package)-[:CONTAINS]->(b:Interface) RETURN id(a), id(b), '${JavaKinds.INTERFACE}'",
        "MATCH (a:Package)-[:CONTAINS]->(b:Enum) RETURN id(a), id(b), '${JavaKinds.ENUM}'",
        "MATCH (a:Package)-[:CONTAINS]->(b:Annotation) RETURN id(a), id(b), '${JavaKinds.ANNOTATION}'",
        "MATCH (a:Package)-[:CONTAINS]->(b:Record) RETURN id(a), id(b), '${JavaKinds.RECORD}'",
        // Types -> Methods and Fields
        "MATCH (a:Type)-[:DECLARES]->(b:Field) RETURN id(a), id(b), '${JavaKinds.FIELD}'",
        "MATCH (a:Type)-[:DECLARES]->(b:Method) RETURN id(a), id(b), '${JavaKinds.METHOD}'"
    )
}
