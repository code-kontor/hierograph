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
package io.hierograph.mcp.jqa.hierarchicalgraph.fwk

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNodeId
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ToplevelNodeId
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.AbstractBoltClientAware
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource
import org.neo4j.driver.Record
import org.neo4j.driver.Value

/**
 * Generic [IHierarchyDefinitionProvider] that executes a declarative set of hierarchy rules.
 *
 * This adapter is domain-agnostic: it runs each rule's Cypher, takes the rule's kind verbatim,
 * captures the name/fqn columns, and delegates node-source construction to [nodeSourceFactory].
 * All knowledge of *what* is being mapped lives in the [GraphDbMapping] rules, not here.
 */
class MappingDrivenHierarchyProvider(
    private val topLevelRules: List<TopLevelRule>,
    private val parentChildRules: List<ParentChildRule>,
    private val nodeSourceFactory: (id: Long, name: String, fqn: String) -> GraphDbNodeSource,
) : IHierarchyDefinitionProvider, AbstractBoltClientAware() {

    private val _nameFqnByNodeId = HashMap<Long, Pair<String?, String?>>()

    override var toplevelNodeIds: List<ToplevelNodeId> = emptyList()
        private set
    override var parentChildNodeIds: List<ParentChildNodeId> = emptyList()
        private set

    override fun initialize() {
        // distinctBy { it.id }: the rules can legitimately surface the same module id more than
        // once (e.g. a Maven project that produces both a Main and a Test artifact matches the
        // project rule twice), and a node must not appear as a top-level entry more than once.
        toplevelNodeIds = topLevelRules.flatMap { rule ->
            queryList(rule.cypher) { r ->
                val id = r[0].asLong()
                rememberNameFqn(id, r[1], r[2])
                ToplevelNodeId(id = id, kind = rule.kind)
            }
        }.distinctBy { it.id }
        parentChildNodeIds = parentChildRules.flatMap { rule ->
            queryList(rule.cypher) { r ->
                val childId = r[1].asLong()
                rememberNameFqn(childId, r[2], r[3])
                ParentChildNodeId(
                    parentId = r[0].asLong(),
                    childId = childId,
                    childKind = rule.childKind,
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
        return nodeSourceFactory(id, name.orEmpty(), fqn.orEmpty())
    }

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
}
