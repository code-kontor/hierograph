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

import io.hierograph.server.graphservice.HierarchicalGraphService
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
        description = "[Entity detail] Return the full structural details of a single method or constructor, " +
                "in one call. Use this when you've identified a method of interest (via list_children, " +
                "outgoing_dependencies / incoming_dependencies with detailLevel 'detail', or another tool that " +
                "surfaces method IDs) and need the complete picture: modifiers, return type, parameters with " +
                "names and types, declared exceptions (throws), exceptions thrown in the body (raises), " +
                "annotations, the method it overrides (if any), and source location. " +
                "Response shape: single-entity inline form (no slim 'nodes' map). The declaring_type, " +
                "return_type, parameter types, throws/raises types, annotation types, and overrides target are " +
                "inline NodeRefs — feed their ids into other tools (type_details, list_children, " +
                "incoming_dependencies, etc.) to investigate. " +
                "Primitive types (void, int, boolean, etc.) appear with id: null and kind: 'java.primitive'; " +
                "types outside the graph (e.g. java.lang.String) appear as kind 'external.type' with id: null. " +
                "Neither can be used as input to other tools. " +
                "For generic types like List<String>, the erased type (java.util.List) is reported; type " +
                "arguments are not surfaced. For an array type, the element type is reported. " +
                "Use the location field together with your file-reading tools when you need to inspect the " +
                "actual method implementation (the line number is the first line of the method's code; it is " +
                "null for abstract and native methods). " +
                "When to use this vs. neighboring tools: " +
                "For the methods declared on a type, use list_children. " +
                "For 'which methods call this one?' or 'which methods throw this exception?', use " +
                "incoming_dependencies with detailLevel 'detail' — the dependency-driven view rather than the " +
                "entity-detail view. " +
                "For fields rather than methods, use field_details."
    )
    fun methodDetails(
        @ToolParam(
            description = "The node ID of the method to inspect. Must be a method-kind node " +
                    "(a java.method from list_children, including constructors). Typically obtained from list_children " +
                    "or from outgoing_dependencies / incoming_dependencies with detailLevel 'detail'."
        )
        methodId: Long
    ): Map<String, Any?> = parquet.methodDetails(methodId)
}
