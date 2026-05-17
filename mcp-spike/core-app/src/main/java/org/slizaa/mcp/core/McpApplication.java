package org.slizaa.mcp.core;

import org.slizaa.mcp.core.logging.LoggingToolCallbackProvider;
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
    public ToolCallbackProvider graphToolCallbackProvider(
            DiscoveryMcpTools discoveryMcpTools,
            PairwiseDependencyMcpTools pairwiseDependencyMcpTools,
            ScopeDependencyMcpTools scopeDependencyMcpTools,
            ReachabilityMcpTools reachabilityMcpTools,
            DetailMcpTools detailMcpTools) {
        ToolCallbackProvider delegate = MethodToolCallbackProvider.builder()
                .toolObjects(discoveryMcpTools, pairwiseDependencyMcpTools,
                        scopeDependencyMcpTools, reachabilityMcpTools, detailMcpTools)
                .build();
        return new LoggingToolCallbackProvider(delegate);
    }
}
