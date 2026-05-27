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

import io.hierograph.hierarchicalgraph.graphdb.mapping.service.AbstractQueryBasedDependencyProvider

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
