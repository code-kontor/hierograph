package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

interface IMappingProviderMetadata {
    val identifier: String
    val name: String
    val description: String?
    val categories: Map<String, String>
}
