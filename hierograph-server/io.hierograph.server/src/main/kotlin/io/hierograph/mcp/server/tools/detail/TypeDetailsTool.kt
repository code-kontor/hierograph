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
 * MCP tool: `type_details`
 *
 * Returns full structural details for a single type: modifiers, superclass, interfaces, annotations,
 * member counts, inner types, and source location, served from the Parquet snapshot by [ParquetTypeDetails].
 */
@Component
class TypeDetailsTool(
    graphService: HierarchicalGraphService,
    private val parquet: ParquetTypeDetails,
) : AbstractDetailTool(graphService) {

    @Tool(
        name = "type_details",
        description = "[Entity detail] " +
                "Return full structural details for a single type — superclass, interfaces, " +
                "annotations, inner types, member counts, modifiers, and source location. " +
                "Input must be a type-kind node ID (java.class, java.interface, java.enum, " +
                "java.record, java.annotation). " +
                "Complements the enriched NodeRef from browse tools: browse tools give counts " +
                "and flags; this tool gives the actual referenced types as navigable NodeRefs. " +
                "For the members of a type (methods, fields), use list_children. " +
                "For full method or field detail, use method_details or field_details."
    )
    fun typeDetails(
        @ToolParam(description = "The node ID of the type to inspect.")
        typeId: Long
    ): Map<String, Any?> = parquet.typeDetails(typeId)
}
