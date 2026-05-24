package org.slizaa.mcp.core;

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ISearchProvider;
import org.slizaa.jqassistant.hierarchicalgraph.JQAssistantSearchProvider;
import org.slizaa.mcp.core.logging.LoggingToolCallbackProvider;
import org.slizaa.mcp.core.mcp.detail.DetailDependenciesMcpTool;
import org.slizaa.mcp.core.mcp.detail.FieldDetailsMcpTool;
import org.slizaa.mcp.core.mcp.detail.ListFieldsMcpTool;
import org.slizaa.mcp.core.mcp.detail.ListMethodsMcpTool;
import org.slizaa.mcp.core.mcp.detail.MethodDetailsMcpTool;
import org.slizaa.mcp.core.mcp.discovery.DiscoveryMcpTools;
import org.slizaa.mcp.core.mcp.navigation.FindNodeTool;
import org.slizaa.mcp.core.mcp.pairwisedependency.PairwiseDependencyMcpTools;
import org.slizaa.mcp.core.mcp.reachability.ReachabilityMcpTools;
import org.slizaa.mcp.core.mcp.scopedependency.ScopeDependencyMcpTools;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

@SpringBootApplication
@EnableScheduling
public class McpApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }

    @Bean
    public ISearchProvider searchProvider(HierarchicalGraphService graphService) {
        return new JQAssistantSearchProvider(
                graphService.getBoltClient(),
                graphService.getRootNode().getExtension(INodeMetadataProvider.class)
        );
    }

    @Bean
    public ToolCallbackProvider graphToolCallbackProvider(
            FindNodeTool findNodeTool,
            DiscoveryMcpTools discoveryMcpTools,
            PairwiseDependencyMcpTools pairwiseDependencyMcpTools,
            ScopeDependencyMcpTools scopeDependencyMcpTools,
            ReachabilityMcpTools reachabilityMcpTools,
            ListMethodsMcpTool listMethodsTool,
            ListFieldsMcpTool listFieldsTool,
            DetailDependenciesMcpTool detailDependenciesTool,
            MethodDetailsMcpTool methodDetailsTool,
            FieldDetailsMcpTool fieldDetailsTool) {
        ToolCallbackProvider delegate = MethodToolCallbackProvider.builder()
                .toolObjects(findNodeTool, discoveryMcpTools, pairwiseDependencyMcpTools,
                        scopeDependencyMcpTools, reachabilityMcpTools,
                        listMethodsTool, listFieldsTool, detailDependenciesTool,
                        methodDetailsTool, fieldDetailsTool)
                .build();
        return new LoggingToolCallbackProvider(delegate);
    }
}
