package io.hierograph.mcp.server

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantSearchProvider
import io.hierograph.mcp.server.logging.LoggingToolCallbackProvider
import io.hierograph.mcp.server.mcp.detail.FieldDetailsTool
import io.hierograph.mcp.server.mcp.detail.MethodDetailsTool
import io.hierograph.mcp.server.mcp.detail.TypeDetailsTool
import io.hierograph.mcp.server.mcp.navigation.FindNodeTool
import io.hierograph.mcp.server.mcp.navigation.GraphOverviewTool
import io.hierograph.mcp.server.mcp.navigation.ListChildrenTool
import io.hierograph.mcp.server.mcp.navigation.ListDescendantsTool
import io.hierograph.mcp.server.mcp.dependencyanalysis.AggregatedDependenciesTool
import io.hierograph.mcp.server.mcp.dependencyanalysis.IncomingDependenciesTool
import io.hierograph.mcp.server.mcp.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.mcp.server.mcp.dependencyanalysis.PairwiseDependenciesTool
import io.hierograph.mcp.server.mcp.reachability.AffectedByTool
import io.hierograph.mcp.server.mcp.reachability.FindDependencyPathTool
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
