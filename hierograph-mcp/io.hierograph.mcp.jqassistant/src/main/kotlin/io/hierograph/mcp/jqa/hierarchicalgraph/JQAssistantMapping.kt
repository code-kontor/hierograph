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

import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.jqa.hierarchicalgraph.fwk.DependencyRule
import io.hierograph.mcp.jqa.hierarchicalgraph.fwk.GraphDbMapping
import io.hierograph.mcp.jqa.hierarchicalgraph.fwk.ParentChildRule
import io.hierograph.mcp.jqa.hierarchicalgraph.fwk.TopLevelRule

/**
 * The declarative mapping from jQAssistant's Neo4j graph onto Hierograph's Java vocabulary.
 *
 * This is the *what* of the jQAssistant adapter: it pairs each Cypher query with the [JavaKinds]
 * kind it yields. It carries no SPI/bolt machinery — the mapping-driven adapters in `fwk` execute it.
 * All jQAssistant label knowledge (`Artifact:Jar`, `Package`, `DEPENDS_ON`, …) is confined here.
 *
 * Column conventions (see the rule kdocs in [GraphDbMapping]):
 *  - top-level rules `RETURN` `id, name, fqn`
 *  - parent-child rules `RETURN` `parentId, childId, name, fqn`
 *  - dependency rules `RETURN` `startId, targetId, relId, type, weight, <boolean attribute flags…>`
 */
val jQAssistantMapping = GraphDbMapping(
    topLevelRules = listOf(
        // scanned jars directly
        TopLevelRule(
            JavaKinds.MODULE,
            "MATCH (a:Artifact:Jar) RETURN id(a) AS id, substring(a.fileName, 1) AS name, substring(a.fileName, 1) AS fqn",
        ),
        // scanned maven projects
        TopLevelRule(
            JavaKinds.MODULE,
            "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a) AS id, a.artifactId, a.fqn",
        ),
        // Virtual 'External' module
        TopLevelRule(
            JavaKinds.MODULE,
            "MATCH (a:Virtual:Artifact) RETURN id(a) AS id, 'External Types' AS name, 'External Types' AS fqn",
        ),
    ),
    parentChildRules = listOf(
        // Artifact -> top-level Packages
        ParentChildRule(
            JavaKinds.PACKAGE,
            "MATCH (a:Artifact:Jar)-[:CONTAINS]->(b:Package) WHERE NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), b.name, b.fqn",
        ),
        ParentChildRule(
            JavaKinds.PACKAGE,
            "MATCH (a:Artifact:Virtual)-[:CONTAINS]->(b:Package) WHERE NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), b.name, b.fqn",
        ),
        // "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a), id(b), b.name, b.fqn"
        ParentChildRule(
            JavaKinds.MODULE,
            "MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main) RETURN id(a), id(b), b.name, b.fqn",
        ),
        ParentChildRule(
            JavaKinds.PACKAGE,
            "MATCH (a:Artifact:Maven:File:Main)-[:CONTAINS]->(b:Package) where a.type = 'jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), b.name, b.fqn",
        ),
        ParentChildRule(
            JavaKinds.PACKAGE,
            "MATCH (a:Artifact:Maven:File:Test)-[:CONTAINS]->(b:Package) where a.type = 'test-jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b), b.name, b.fqn",
        ),
        // Package -> sub-Packages
        ParentChildRule(
            JavaKinds.PACKAGE,
            "MATCH (a:Package)-[:CONTAINS]->(b:Package) RETURN id(a), id(b), b.name, b.fqn",
        ),
        // Package -> Types (Class, Interface, Enum, Annotation, Record)
        ParentChildRule(
            JavaKinds.CLASS,
            "MATCH (a:Package)-[:CONTAINS]->(b:Class) RETURN id(a), id(b), b.name, b.fqn",
        ),
        ParentChildRule(
            JavaKinds.INTERFACE,
            "MATCH (a:Package)-[:CONTAINS]->(b:Interface) RETURN id(a), id(b), b.name, b.fqn",
        ),
        ParentChildRule(
            JavaKinds.ENUM,
            "MATCH (a:Package)-[:CONTAINS]->(b:Enum) RETURN id(a), id(b), b.name, b.fqn",
        ),
        ParentChildRule(
            JavaKinds.ANNOTATION,
            "MATCH (a:Package)-[:CONTAINS]->(b:Annotation) RETURN id(a), id(b), b.name, b.fqn",
        ),
        ParentChildRule(
            JavaKinds.RECORD,
            "MATCH (a:Package)-[:CONTAINS]->(b:Record) RETURN id(a), id(b), b.name, b.fqn",
        ),
        // Types -> Methods and Fields (signature used as the display name; fqn is type.fqn + '#' + signature)
        ParentChildRule(
            JavaKinds.FIELD,
            "MATCH (a:Type)-[:DECLARES]->(b:Field) RETURN id(a), id(b), b.signature, a.fqn + '#' + b.signature",
        ),
        ParentChildRule(
            JavaKinds.METHOD,
            "MATCH (a:Type)-[:DECLARES]->(b:Method) RETURN id(a), id(b), b.signature, a.fqn + '#' + b.signature",
        ),
        //
        ParentChildRule(
            JavaKinds.EXTERNAL_TYPE,
            "MATCH (a:Virtual:Package)-[:CONTAINS]->(b:Virtual:Type) RETURN id(a), id(b), b.name, b.fqn",
        ),
    ),
    dependencyRules = listOf(
        DependencyRule(
            // `is_annotated_by` is derived from jQAssistant's native two-hop annotation shape,
            //   (t1)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(t2),
            // which holds uniformly for internal and external annotation types: for external
            // ones the OF_TYPE leg is lifted onto the canonical :Virtual:Type by the
            // hierograph:VirtualExternalAnnotatedBy concept (see virtual-external.xml), so the
            // same traversal reaches the virtual annotation type t2 that DEPENDS_ON also targets.
            // (EXTENDS / IMPLEMENTS are native direct Type→Type edges, hence the single-hop EXISTS.)
            """
            MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type)
            WITH t1, t2, r,
                 EXISTS { (t1)-[:ANNOTATED_BY]->(:Annotation)-[:OF_TYPE]->(t2) } AS isAnnotatedBy
            RETURN id(t1), id(t2), id(r), type(r), r.weight,
                   EXISTS { (t1)-[:EXTENDS]->(t2) },
                   EXISTS { (t1)-[:IMPLEMENTS]->(t2) },
                   isAnnotatedBy,
                   NOT EXISTS { (t1)-[:EXTENDS]->(t2) }
                       AND NOT EXISTS { (t1)-[:IMPLEMENTS]->(t2) }
                       AND NOT isAnnotatedBy
            """.trimIndent()
        ),
    ),
)
