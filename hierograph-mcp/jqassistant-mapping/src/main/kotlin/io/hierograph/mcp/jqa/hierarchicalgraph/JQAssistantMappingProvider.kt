package io.hierograph.mcp.jqa.hierarchicalgraph

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DefaultMappingProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DefaultMappingProviderMetadata

class JQAssistantMappingProvider : DefaultMappingProvider(
    DefaultMappingProviderMetadata(
        identifier = "io.hierograph.jqassistant.hierarchicalgraph",
        name = "Hierograph jQAssistant (hierarchical packages)"
    ),
    JQAssistantHierarchyProvider(),
    JQAssistantDependencyProvider(),
    JQAssistantNodeMetadataProvider()
)
