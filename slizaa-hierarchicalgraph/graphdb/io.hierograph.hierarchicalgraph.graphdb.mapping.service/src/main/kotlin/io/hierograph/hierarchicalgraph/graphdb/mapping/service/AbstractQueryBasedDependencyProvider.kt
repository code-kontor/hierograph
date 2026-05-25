package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DefaultDependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider
import org.slizaa.core.boltclient.IBoltClient

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
