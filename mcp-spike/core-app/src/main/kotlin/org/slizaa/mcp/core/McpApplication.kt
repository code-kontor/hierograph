package org.slizaa.mcp.core

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider
import org.slizaa.jqassistant.hierarchicalgraph.JQAssistantSearchProvider
import org.slizaa.mcp.core.logging.LoggingToolCallbackProvider
import org.slizaa.mcp.core.mcp.detail.DetailDependenciesMcpTool
import org.slizaa.mcp.core.mcp.detail.FieldDetailsMcpTool
import org.slizaa.mcp.core.mcp.detail.ListFieldsMcpTool
import org.slizaa.mcp.core.mcp.detail.ListMethodsMcpTool
import org.slizaa.mcp.core.mcp.detail.MethodDetailsMcpTool
import org.slizaa.mcp.core.mcp.discovery.DiscoveryMcpTools
import org.slizaa.mcp.core.mcp.navigation.FindNodeTool
import org.slizaa.mcp.core.mcp.navigation.GraphOverviewTool
import org.slizaa.mcp.core.mcp.pairwisedependency.PairwiseDependencyMcpTools
import org.slizaa.mcp.core.mcp.reachability.ReachabilityMcpTools
import org.slizaa.mcp.core.mcp.scopedependency.ScopeDependencyMcpTools
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider

@SpringBootApplication
@EnableScheduling
class McpApplication {

    @Bean
    fun searchProvider(graphService: HierarchicalGraphService): ISearchProvider =
        JQAssistantSearchProvider(
            graphService.boltClient,
            graphService.rootNode.getExtension(INodeMetadataProvider::class.java)
        )

    @Bean
    fun graphToolCallbackProvider(
        findNodeTool: FindNodeTool,
        graphOverviewTool: GraphOverviewTool,
        discoveryMcpTools: DiscoveryMcpTools,
        pairwiseDependencyMcpTools: PairwiseDependencyMcpTools,
        scopeDependencyMcpTools: ScopeDependencyMcpTools,
        reachabilityMcpTools: ReachabilityMcpTools,
        listMethodsTool: ListMethodsMcpTool,
        listFieldsTool: ListFieldsMcpTool,
        detailDependenciesTool: DetailDependenciesMcpTool,
        methodDetailsTool: MethodDetailsMcpTool,
        fieldDetailsTool: FieldDetailsMcpTool
    ): ToolCallbackProvider {
        val delegate = MethodToolCallbackProvider.builder()
            .toolObjects(
                findNodeTool, graphOverviewTool, discoveryMcpTools, pairwiseDependencyMcpTools,
                scopeDependencyMcpTools, reachabilityMcpTools,
                listMethodsTool, listFieldsTool, detailDependenciesTool,
                methodDetailsTool, fieldDetailsTool
            )
            .build()
        return LoggingToolCallbackProvider(delegate)
    }
}

fun main(args: Array<String>) {
    runApplication<McpApplication>(*args)
}
