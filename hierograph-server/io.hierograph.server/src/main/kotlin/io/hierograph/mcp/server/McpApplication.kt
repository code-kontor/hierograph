/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.mcp.server

import io.hierograph.mcp.server.core.GraphAvailabilityToolCallbackProvider
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.mcp.server.core.logging.LoggingToolCallbackProvider
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
import io.hierograph.mcp.server.tools.dependencyanalysis.LevelizedStructureTool
import io.hierograph.mcp.server.tools.dependencyanalysis.PairwiseDependenciesTool
import io.hierograph.mcp.server.tools.reachability.AffectedByTool
import io.hierograph.mcp.server.tools.reachability.FindDependencyPathTool
import io.hierograph.mcp.server.tools.admin.ReloadGraphTool
import io.hierograph.mcp.server.tools.admin.ServerInfoTool
import io.hierograph.mcp.server.instance.InstanceGuard
import io.hierograph.mcp.server.instance.ServerVersion
import io.hierograph.mcp.server.tools.views.ViewTools
import io.hierograph.server.graphservice.views.ViewService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = ["io.hierograph.mcp.server", "io.hierograph.server.graphservice", "io.hierograph.server.graphql"])
class McpApplication {

    @Bean
    fun graphToolCallbackProvider(
        @Value("\${hierograph.tool-call-logger.enabled:false}") logToolCalls: Boolean,
        graphService: HierarchicalGraphService,
        findNodeTool: FindNodeTool,
        graphOverviewTool: GraphOverviewTool,
        listChildrenTool: ListChildrenTool,
        listDescendantsTool: ListDescendantsTool,
        aggregatedDependenciesTool: AggregatedDependenciesTool,
        pairwiseDependenciesTool: PairwiseDependenciesTool,
        levelizedStructureTool: LevelizedStructureTool,
        outgoingDependenciesTool: OutgoingDependenciesTool,
        incomingDependenciesTool: IncomingDependenciesTool,
        affectedByTool: AffectedByTool,
        findDependencyPathTool: FindDependencyPathTool,
        typeDetailsTool: TypeDetailsTool,
        methodDetailsTool: MethodDetailsTool,
        fieldDetailsTool: FieldDetailsTool,
        reloadGraphTool: ReloadGraphTool,
        serverInfoTool: ServerInfoTool,
        viewTools: ViewTools,
        viewService: ViewService,
    ): ToolCallbackProvider {
        val delegate = MethodToolCallbackProvider.builder()
            .toolObjects(
                findNodeTool, graphOverviewTool, listChildrenTool, listDescendantsTool,
                aggregatedDependenciesTool, pairwiseDependenciesTool, levelizedStructureTool,
                outgoingDependenciesTool, incomingDependenciesTool,
                affectedByTool, findDependencyPathTool,
                typeDetailsTool, methodDetailsTool, fieldDetailsTool,
                reloadGraphTool, serverInfoTool,
                viewTools
            )
            .build()
        // Short-circuit every tool with the standard "no graph available" description while the server
        // runs empty. `reload_graph` is exempt so a graph can still be loaded once a database is set up,
        // `server_info` so a client can still find out which server it talks to, and the view tools that only
        // read or delete documents work without a graph too.
        // Every other tool takes the optional `view` parameter and answers in the view it names.
        val guarded = GraphAvailabilityToolCallbackProvider(
            delegate,
            graphService,
            toolsAllowedWithoutGraph = setOf("reload_graph", "server_info", "list_views", "get_view", "delete_view"),
            viewService = viewService,
            toolsWithoutView = setOf("reload_graph", "server_info", "define_view", "list_views", "get_view", "delete_view"),
        )
        return if (logToolCalls) LoggingToolCallbackProvider(guarded) else guarded
    }
}

fun main(args: Array<String>) {
    runApplication<McpApplication>(*args) {
        // One server per workspace, checked before the graph is built and the port is bound.
        addListeners(InstanceGuard())
        // The MCP handshake's serverInfo carries the real version; a setting still overrides it.
        setDefaultProperties(mapOf("spring.ai.mcp.server.version" to ServerVersion.current()))
    }
}
