package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

open class DefaultMappingProvider(
    override val metadata: IMappingProviderMetadata,
    override val hierarchyDefinitionProvider: IHierarchyDefinitionProvider,
    override val dependencyDefinitionProvider: IDependencyDefinitionProvider,
    override val nodeMetadataProvider: INodeMetadataProvider
) : IMappingProvider
