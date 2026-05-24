package io.hierograph.mcp.server

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantSearchProvider
import io.hierograph.mcp.server.logging.LoggingToolCallbackProvider
import io.hierograph.mcp.server.tools.detail.FieldDetailsTool
import io.hierograph.mcp.server.tools.detail.MethodDetailsTool
import io.hierograph.mcp.server.tools.detail.TypeDetailsTool
import io.hierograph.mcp.server.tools.navigation.FindNodeTool
import io.hierograph.mcp.server.tools.navigation.GraphOverviewTool
import io.hierograph.mcp.server.tools.navigation.ListChildrenTool
import io.hierograph.mcp.server.tools.navigation.ListDescendantsTool
import io.hierograph.mcp.server.tools.dependencyanalysis.AggregatedDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.IncomingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.PairwiseDependenciesTool
import io.hierograph.mcp.server.tools.reachability.AffectedByTool
import io.hierograph.mcp.server.tools.reachability.FindDependencyPathTool
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
