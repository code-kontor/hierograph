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

import io.hierograph.mcp.server.core.HierarchicalGraphService
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
import io.hierograph.mcp.server.tools.dependencyanalysis.PairwiseDependenciesTool
import io.hierograph.mcp.server.tools.reachability.AffectedByTool
import io.hierograph.mcp.server.tools.reachability.FindDependencyPathTool
import io.hierograph.graphql.HierarchicalGraphProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.method.MethodToolCallbackProvider

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = ["io.hierograph.mcp.server", "io.hierograph.graphql"])
class McpApplication {

    @Bean
    fun hierarchicalGraphProvider(graphService: HierarchicalGraphService): HierarchicalGraphProvider =
        HierarchicalGraphProvider { graphService.model.hierarchy }

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
