/*
 * Copyright 2024 Gerd Wuetherich
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
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNode
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.RootNode
import io.hierograph.boltclient.IBoltClient

abstract class AbstractQueryBasedHierarchyProvider : IHierarchyDefinitionProvider, IBoltClientAware {

    private var _toplevelNodeIds: List<RootNode> = emptyList()
    private var _parentChildNodes: List<ParentChildNode> = emptyList()

    override fun initialize(boltClient: IBoltClient) {
        val toplevel = mutableListOf<RootNode>()
        for (query in toplevelNodeIdQueries()) {
            val results = boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    RootNode(
                        id = r.get(0).asLong(),
                        kind = parseKind(r.get(1).asString())
                    )
                }
            }.get()
            toplevel.addAll(results)
        }
        _toplevelNodeIds = toplevel

        val parentChild = mutableListOf<ParentChildNode>()
        for (query in parentChildNodeIdsQueries()) {
            val results = boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    ParentChildNode(
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

    override fun getToplevelNodeIds(): List<RootNode> = _toplevelNodeIds

    override fun getParentChildNodeIds(): List<ParentChildNode> = _parentChildNodes

    protected abstract fun toplevelNodeIdQueries(): Array<String>

    protected abstract fun parentChildNodeIdsQueries(): Array<String>

    protected open fun parseKind(kindString: String): Any = kindString
}
