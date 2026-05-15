package org.slizaa.mcp.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

@SpringBootApplication
public class McpApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider graphToolCallbackProvider(GraphMcpTools graphMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(graphMcpTools)
                .build();
    }
}
