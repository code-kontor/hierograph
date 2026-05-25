package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

data class DefaultMappingProviderMetadata(
    override val identifier: String,
    override val name: String,
    override val description: String? = null,
    override val categories: Map<String, String> = emptyMap()
) : IMappingProviderMetadata
