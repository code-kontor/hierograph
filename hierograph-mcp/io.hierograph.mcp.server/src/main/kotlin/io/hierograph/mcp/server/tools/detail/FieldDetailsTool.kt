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
 * MCP tool: `field_details` — full structural details for a single field, served from the Parquet
 * snapshot by [ParquetFieldDetails].
 */
@Component
class FieldDetailsTool(
    graphService: HierarchicalGraphService,
    private val parquet: ParquetFieldDetails,
) : AbstractDetailTool(graphService) {

    @Tool(
        name = "field_details",
        description = "[Detail-level] Return the full structural details of a single field, in one call. " +
                "Use this when you've identified a field of interest (via list_fields, detail_dependencies, " +
                "or another tool that surfaces field IDs) and need the complete picture: type, annotations, and " +
                "information about which methods read or write it. " +
                "Response shape (slim encoding): top-level 'nodes' map (each referenced node listed once " +
                "with name, qualified_name, kind, keyed by stringified ID) plus the field's structural details — " +
                "'field', 'declaring_type', 'type' (or null for primitives), 'type_name' (always-present string — " +
                "qualified name for reference types, keyword for primitives), 'annotations' (each entry is " +
                "{type: ID}), 'read_access', 'write_access' — all referencing nodes by ID. " +
                "Read/write access digests carry: method_count (true total across the codebase), methods_sample " +
                "(up to 10 method IDs — resolve via nodes[id]), sample_truncated (boolean), and by_declaring_type " +
                "(list of {type: ID, count: N}, sorted descending by count, capped at 10 entries). For fields with " +
                "many readers (loggers, common dependencies), the digest tells you the structural story without " +
                "needing to enumerate every accessor. " +
                "If you need the full list of readers or writers (beyond the inline sample), use " +
                "detail_dependencies(from=root_id, to=field_id, relationship='reads_field') (or 'writes_field') " +
                "for exhaustive enumeration. " +
                "Primitive field types: 'type' is null and no nodes entry exists for it; read 'type_name' to see " +
                "the primitive keyword (e.g. 'int', 'boolean'). The LLM should not try to use a null type ID as " +
                "input to other tools. " +
                "When to use this vs. neighboring tools: " +
                "For all the fields declared on a type (composition, not single-field detail), use list_fields. " +
                "For 'which methods read this specific field?' with exhaustive enumeration or filters, use " +
                "detail_dependencies with relationship: 'reads_field'. " +
                "For methods rather than fields, use method_details (parallel tool, but uses inline NodeRefs since " +
                "method_details is a single-entity response)."
    )
    fun fieldDetails(
        @ToolParam(
            description = "The node ID of the field to inspect. Must be a field-kind node (java.field). " +
                    "Typically obtained from list_fields or detail_dependencies."
        )
        fieldId: Long
    ): Map<String, Any?> = parquet.fieldDetails(fieldId)
}
