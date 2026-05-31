package io.hierograph.mcp.jqa.hierarchicalgraph

import io.hierograph.hierarchicalgraph.graphdb.mapping.service.DefaultMappingService
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource

class JQAssistantMappingService : DefaultMappingService() {

    override fun createNodeSource(id: Long, hierarchyDefinitionProvider: IHierarchyDefinitionProvider): GraphDbNodeSource {
        hierarchyDefinitionProvider as JQAssistantHierarchyProvider
        return ExtendedGraphDbNodeSource(identifier = id, hierarchyDefinitionProvider.nameFqnByNodeId.get(id)?.first ?: "", hierarchyDefinitionProvider.nameFqnByNodeId.get(id)?.second ?: "")
    }
}