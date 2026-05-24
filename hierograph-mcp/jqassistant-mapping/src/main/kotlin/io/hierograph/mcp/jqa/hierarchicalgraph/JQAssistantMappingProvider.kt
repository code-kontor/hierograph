package io.hierograph.mcp.jqa.hierarchicalgraph

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider.DefaultMappingProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.annotations.SlizaaMappingProvider

@SlizaaMappingProvider
class JQAssistantMappingProvider : DefaultMappingProvider(
    IMappingProvider.IMappingProviderMetadata.createMetadata(
        "org.slizaa.jqassistant.hierarchicalgraph",
        "Slizaa jQAssistant (hierarchical packages)",
        null, null
    ),
    JQAssistantHierarchyProvider(),
    JQAssistantDependencyProvider(),
    JQAssistantLabelProvider(),
    JQAssistantNodeComparator(),
    JQAssistantNodeMetadataProvider()
)
