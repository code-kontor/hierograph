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
package io.hierograph.mcp.server.tools.dependencyanalysis

import io.hierograph.mcp.server.tools.detail.DetailDependenciesComponent
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `incoming_dependencies`
 *
 * Mirror of `outgoing_dependencies` with reversed direction.
 * `incoming_dependencies(from_id: A, to_id: B)` returns edges from B to A
 * — i.e., what B uses of A.
 *
 * Delegates type-level queries to [OutgoingDependenciesTool.typeLevelDependencies]
 * with `outgoing = false`, and detail-level queries to [DetailDependenciesComponent]
 * with swapped from/to.
 */
@Component
class IncomingDependenciesTool(
    private val outgoingTool: OutgoingDependenciesTool,
    private val detailDependenciesTool: DetailDependenciesComponent
) {

    @Tool(
        name = "incoming_dependencies",
        description = "[Dependency analysis] " +
                "Mirror of outgoing_dependencies — returns edges from the to_id subtree to the " +
                "from_id subtree. Shows what the target side uses of the source side. " +
                "detail_level='type' (default) returns type-to-type edges (fast). " +
                "detail_level='detail' returns method/field-level edges with source locations (slower). " +
                "The 'relationship' filter is only valid at detail level. " +
                "For the forward direction (what source uses of target), use outgoing_dependencies."
    )
    fun incomingDependencies(
        @ToolParam(description = "The subtree that is depended upon — source side.")
        fromId: Long,
        @ToolParam(description = "The subtree that does the depending — target side.")
        toId: Long,
        @ToolParam(
            description = "Zoom level: 'type' (default, in-memory) or 'detail' (method/field-level, Neo4j).",
            required = false
        )
        detailLevel: String?,
        @ToolParam(
            description = "Filter to a specific detail-level relationship kind. Only valid when detail_level='detail'.",
            required = false
        )
        relationship: String?,
        @ToolParam(
            description = "Maximum edges per page. Default: 100 (type) / 80 (detail). Caps: 400 / 250.",
            required = false
        )
        limit: Int?
    ): Map<String, Any?> {

        val level = detailLevel ?: "type"

        // ── validate detail_level ──────────────────────────────────────
        if (level !in listOf("type", "detail")) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "Invalid detail_level: '$level'. Must be 'type' or 'detail'.",
                    "recovery" to "Use 'type' for type-to-type edges or 'detail' for method/field-level edges."
                )
            )
        }

        // ── validate relationship at type level ────────────────────────
        if (level == "type" && !relationship.isNullOrBlank()) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "The 'relationship' parameter is only valid at detail_level='detail'. " +
                            "At type level, edges carry attribute flags but cannot be filtered by detail-level relationship.",
                    "recovery" to "Either remove the 'relationship' parameter, or set detail_level='detail' to filter by relationship kind."
                )
            )
        }

        return if (level == "type") {
            // incoming: edges from toId→fromId (what toId uses of fromId)
            outgoingTool.typeLevelDependencies(fromId, toId, limit, outgoing = false)
        } else {
            // Detail level: swap from/to — detail_dependencies(toId, fromId)
            // shows edges from toId subtree into fromId subtree
            val effectiveLimit = (limit ?: 80).coerceIn(1, 250)
            val effectiveRel = if (relationship.isNullOrBlank()) null else relationship
            detailDependenciesTool.detailDependencies(toId, fromId, effectiveRel, effectiveLimit)
        }
    }
}
