package org.slizaa.mcp.core

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider
import org.slizaa.jqassistant.hierarchicalgraph.JQAssistantSearchProvider
import org.slizaa.mcp.core.logging.LoggingToolCallbackProvider
import org.slizaa.mcp.core.mcp.detail.FieldDetailsTool
import org.slizaa.mcp.core.mcp.detail.MethodDetailsTool
import org.slizaa.mcp.core.mcp.detail.TypeDetailsTool
import org.slizaa.mcp.core.mcp.navigation.FindNodeTool
import org.slizaa.mcp.core.mcp.navigation.GraphOverviewTool
import org.slizaa.mcp.core.mcp.navigation.ListChildrenTool
import org.slizaa.mcp.core.mcp.navigation.ListDescendantsTool
import org.slizaa.mcp.core.mcp.dependencyanalysis.AggregatedDependenciesTool
import org.slizaa.mcp.core.mcp.dependencyanalysis.IncomingDependenciesTool
import org.slizaa.mcp.core.mcp.dependencyanalysis.OutgoingDependenciesTool
import org.slizaa.mcp.core.mcp.dependencyanalysis.PairwiseDependenciesTool
import org.slizaa.mcp.core.mcp.reachability.AffectedByTool
import org.slizaa.mcp.core.mcp.reachability.FindDependencyPathTool
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
        listChildrenTool: ListChildrenTool,
        listDescendantsTool: ListDescendantsTool,
        aggregatedDependenciesTool: AggregatedDependenciesTool,
        pairwiseDependenciesTool: PairwiseDependenciesTool,
        outgoingDependenciesTool: OutgoingDependenciesTool,
        incomingDependenciesTool: IncomingDependenciesTool,
        affectedByTool: AffectedByTool,
        findDependencyPathTool: FindDependencyPathTool,
        typeDetailsTool: TypeDetailsTool,
        methodDetailsTool: MethodDetailsTool,
        fieldDetailsTool: FieldDetailsTool
    ): ToolCallbackProvider {
        val delegate = MethodToolCallbackProvider.builder()
            .toolObjects(
                findNodeTool, graphOverviewTool, listChildrenTool, listDescendantsTool,
                aggregatedDependenciesTool, pairwiseDependenciesTool,
                outgoingDependenciesTool, incomingDependenciesTool,
                affectedByTool, findDependencyPathTool,
                typeDetailsTool, methodDetailsTool, fieldDetailsTool
            )
            .build()
        return LoggingToolCallbackProvider(delegate)
    }
}

fun main(args: Array<String>) {
    runApplication<McpApplication>(*args)
}
