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
package io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNodeId
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ToplevelNodeId
import org.neo4j.driver.Record

abstract class AbstractQueryBasedHierarchyProvider : IHierarchyDefinitionProvider, AbstractBoltClientAware() {

    final override var toplevelNodeIds: List<ToplevelNodeId> = emptyList()
        private set
    final override var parentChildNodeIds: List<ParentChildNodeId> = emptyList()
        private set

    override fun initialize() {
        // distinctBy { it.id }: multiple queries (or a single query) may surface the same top-level
        // id more than once; a node must not appear as a top-level entry twice.
        toplevelNodeIds = toplevelNodeIdQueries().flatMap { cypher ->
            queryList(cypher) { r ->
                ToplevelNodeId(
                    id = r[0].asLong(),
                    kind = parseKind(r[1].asString()),
                )
            }
        }.distinctBy { it.id }
        parentChildNodeIds = parentChildNodeIdsQueries().flatMap { cypher ->
            queryList(cypher) { r ->
                ParentChildNodeId(
                    parentId = r[0].asLong(),
                    childId = r[1].asLong(),
                    childKind = parseKind(r[2].asString()),
                )
            }
        }
    }

    override fun dispose() {
        toplevelNodeIds = emptyList()
        parentChildNodeIds = emptyList()
    }

    protected abstract fun toplevelNodeIdQueries(): List<String>

    protected abstract fun parentChildNodeIdsQueries(): List<String>

    protected open fun parseKind(kindString: String): Any = kindString

    private fun <T> queryList(cypher: String, mapRecord: (Record) -> T): List<T> =
        boltClient.asyncExecCypherQueryAndTransformResult(cypher) { result ->
            result.list(mapRecord)
        }.get()
}
