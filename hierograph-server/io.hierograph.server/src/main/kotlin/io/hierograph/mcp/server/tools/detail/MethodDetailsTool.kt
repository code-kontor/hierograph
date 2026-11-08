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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphservice.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `method_details` — full structural details for a single method, served from the Parquet
 * snapshot by [ParquetMethodDetails].
 */
@Component
class MethodDetailsTool(
    graphService: HierarchicalGraphService,
    private val parquet: ParquetMethodDetails,
) : AbstractDetailTool(graphService) {

    @Tool(
        name = "method_details",
        description = "[Detail-level] Return the full structural details of a single method, in one call. " +
                "Use this when you've identified a method of interest (via list_methods, detail_dependencies, " +
                "or another tool that surfaces method IDs) and need the complete picture: modifiers, return type, " +
                "parameters with names and types, declared exceptions, annotations, the method it overrides " +
                "(if any), and source location. " +
                "Response shape: single-entity inline form (no slim 'nodes' wrapper map). " +
                "Single-entity responses with one declaring type don't benefit from slim encoding; inline " +
                "NodeRefs are kept. The declaring_type, return_type, parameter types, throws types, annotation " +
                "types, and overrides target are all full NodeRefs — feed these into other tools (find_node, " +
                "aggregated_incoming, list_methods, etc.) to investigate. " +
                "Primitive types (void, int, boolean, etc.) appear with id: null and kind: 'java.primitive' — " +
                "these are not first-class entities in the graph and cannot be used as input to other tools. " +
                "For generic types like List<String>, the erased type (java.util.List) is reported; type " +
                "parameters are not surfaced in v0.2. " +
                "Use the location field together with your file-reading tools when you need to inspect the " +
                "actual method implementation (the line number points to the method declaration; the body " +
                "follows from there). " +
                "When to use this vs. neighboring tools: " +
                "For the methods declared on a type (composition, not single-method detail), use list_methods. " +
                "For 'which methods call this one?' or 'which methods throw this exception?', use " +
                "detail_dependencies — that's the dependency-driven view rather than the entity-detail view. " +
                "For fields rather than methods, use field_details (uses slim encoding because of read/write digest)."
    )
    fun methodDetails(
        @ToolParam(
            description = "The node ID of the method to inspect. Must be a method-kind node " +
                    "(java.method or java.constructor). Typically obtained from list_methods or detail_dependencies."
        )
        methodId: Long
    ): Map<String, Any?> = parquet.methodDetails(methodId)
}
