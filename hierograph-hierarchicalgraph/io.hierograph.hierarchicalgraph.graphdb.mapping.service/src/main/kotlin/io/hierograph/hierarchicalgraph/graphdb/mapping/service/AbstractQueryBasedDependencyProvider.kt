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

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DefaultDependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider
import io.hierograph.boltclient.IBoltClient

abstract class AbstractQueryBasedDependencyProvider : IDependencyDefinitionProvider, IBoltClientAware {

    private val _simpleDependencyQueries = mutableListOf<String>()
    private var _dependencies: List<IDependencyDefinition> = emptyList()

    override fun initialize(boltClient: IBoltClient) {
        // Let subclass register queries
        initialize()

        // Execute all registered queries
        val results = mutableListOf<IDependencyDefinition>()
        for (query in _simpleDependencyQueries) {
            results.addAll(resolveDependencyQuery(boltClient, query))
        }
        _dependencies = results
    }

    override fun getDependencies(): List<IDependencyDefinition> = _dependencies

    protected abstract fun initialize()

    protected fun addSimpleDependencyDefinitions(query: String) {
        _simpleDependencyQueries.add(query)
    }

    companion object {
        fun resolveDependencyQuery(boltClient: IBoltClient, query: String): List<IDependencyDefinition> {
            return boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    var attributesBitmap = 0
                    if (r.size() > 5) {
                        for (i in 5 until r.size()) {
                            if (r.get(i).asBoolean(false)) {
                                attributesBitmap = attributesBitmap or (1 shl (i - 5))
                            }
                        }
                    }
                    DefaultDependencyDefinition(
                        idStart = r.get(0).asLong(),
                        idTarget = r.get(1).asLong(),
                        idRel = r.get(2).asLong(),
                        type = r.get(3).asString(),
                        weight = r.get(4).asInt(),
                        attributesBitmap = attributesBitmap
                    ) as IDependencyDefinition
                }
            }.get()
        }
    }
}
