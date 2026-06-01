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

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider
import io.hierograph.boltclient.IBoltClient
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DependencyDefinition

abstract class AbstractQueryBasedDependencyProvider : IDependencyDefinitionProvider, AbstractBoltClientAware() {

    private val simpleDependencyQueries = mutableListOf<String>()

    final override var dependencies: List<DependencyDefinition> = emptyList()
        private set

    override fun initialize() {
        dependencies = simpleDependencyQueries.flatMap { resolveDependencyQuery(boltClient, it) }
    }

    override fun dispose() {
        dependencies = emptyList()
    }

    protected fun addSimpleDependencyDefinitions(query: String) {
        simpleDependencyQueries.add(query)
    }

    companion object {
        fun resolveDependencyQuery(boltClient: IBoltClient, query: String): List<DependencyDefinition> =
            boltClient.asyncExecCypherQueryAndTransformResult(query) { result ->
                result.list { r ->
                    val attributesBitmap = (5 until r.size())
                        .filter { r[it].asBoolean(false) }
                        .fold(0) { bits, i -> bits or (1 shl (i - 5)) }
                    DependencyDefinition(
                        idStart = r[0].asLong(),
                        idTarget = r[1].asLong(),
                        idRel = r[2].asLong(),
                        type = r[3].asString(),
                        weight = r[4].asInt(),
                        attributesBitmap = attributesBitmap,
                    )
                }
            }.get()
    }
}
