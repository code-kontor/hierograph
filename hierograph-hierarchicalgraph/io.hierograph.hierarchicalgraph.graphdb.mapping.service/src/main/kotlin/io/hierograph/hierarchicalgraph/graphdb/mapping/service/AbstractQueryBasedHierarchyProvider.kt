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
package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNodeId
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ToplevelNodeId
import io.hierograph.boltclient.IBoltClient

abstract class AbstractQueryBasedHierarchyProvider : IHierarchyDefinitionProvider, IBoltClientAware {

    private var _toplevelNodeIds: List<ToplevelNodeId> = emptyList()
    private var _parentChildNodes: List<ParentChildNodeId> = emptyList()

    override fun initialize(boltClient: IBoltClient) {
        val toplevel = mutableListOf<ToplevelNodeId>()
        for (query in toplevelNodeIdQueries()) {
            val results = boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    ToplevelNodeId(
                        id = r.get(0).asLong(),
                        kind = parseKind(r.get(1).asString())
                    )
                }
            }.get()
            toplevel.addAll(results)
        }
        _toplevelNodeIds = toplevel

        val parentChild = mutableListOf<ParentChildNodeId>()
        for (query in parentChildNodeIdsQueries()) {
            val results = boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    ParentChildNodeId(
                        parentId = r.get(0).asLong(),
                        childId = r.get(1).asLong(),
                        childKind = parseKind(r.get(2).asString())
                    )
                }
            }.get()
            parentChild.addAll(results)
        }
        _parentChildNodes = parentChild
    }

    override fun getToplevelNodeIds(): List<ToplevelNodeId> = _toplevelNodeIds

    override fun getParentChildNodeIds(): List<ParentChildNodeId> = _parentChildNodes

    protected abstract fun toplevelNodeIdQueries(): Array<String>

    protected abstract fun parentChildNodeIdsQueries(): Array<String>

    protected open fun parseKind(kindString: String): Any = kindString
}
