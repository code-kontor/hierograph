package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

interface IDependencyDefinitionProvider {
    fun getDependencies(): List<IDependencyDefinition>
}
