package org.slizaa.jqassistant.hierarchicalgraph

import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.AbstractQueryBasedDependencyProvider

class JQAssistantDependencyProvider : AbstractQueryBasedDependencyProvider() {

    override fun initialize() {
        addSimpleDependencyDefinitions(
            """
            MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type)
            RETURN id(t1), id(t2), id(r), type(r), r.weight,
                   EXISTS { (t1)-[:EXTENDS]->(t2) },
                   EXISTS { (t1)-[:IMPLEMENTS]->(t2) },
                   EXISTS { (t1)-[:ANNOTATED_BY]->(t2) },
                   NOT EXISTS { (t1)-[:EXTENDS]->(t2) }
                       AND NOT EXISTS { (t1)-[:IMPLEMENTS]->(t2) }
                       AND NOT EXISTS { (t1)-[:ANNOTATED_BY]->(t2) }
            """.trimIndent()
        )
    }
}
