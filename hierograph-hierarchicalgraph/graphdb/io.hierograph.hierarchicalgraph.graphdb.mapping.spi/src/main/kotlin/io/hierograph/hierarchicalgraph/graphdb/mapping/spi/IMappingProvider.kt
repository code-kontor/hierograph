package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

interface IMappingProvider {
    val metadata: IMappingProviderMetadata
    val hierarchyDefinitionProvider: IHierarchyDefinitionProvider
    val dependencyDefinitionProvider: IDependencyDefinitionProvider
    val nodeMetadataProvider: INodeMetadataProvider
}
