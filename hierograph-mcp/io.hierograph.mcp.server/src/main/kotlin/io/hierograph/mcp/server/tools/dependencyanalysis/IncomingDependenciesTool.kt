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

import io.hierograph.mcp.server.core.pagination.PaginationSpec
import io.hierograph.mcp.server.tools.detail.IDetailDependencies
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
 * with `outgoing = false`, and detail-level queries to [IDetailDependencies]
 * with swapped from/to.
 */
@Component
class IncomingDependenciesTool(
    private val outgoingTool: OutgoingDependenciesTool,
    private val detailDependenciesTool: IDetailDependencies
) {

    @Tool(
        name = "incoming_dependencies",
        description = "[Dependency analysis] " +
                "Mirror of outgoing_dependencies — returns edges from the to_id subtree to the " +
                "from_id subtree. Shows what the target side uses of the source side. " +
                "detail_level='type' (default) returns type-to-type edges (fast). " +
                "detail_level='detail' returns method/field-level edges with source locations (slower). " +
                "The 'relationship' filter is only valid at detail level. " +
                "For the forward direction (what source uses of target), use outgoing_dependencies. " +
                "At type level, to_id is optional: omit it to return ALL incoming dependencies of " +
                "from_id (everything that depends on from_id, from anywhere) — answering 'what depends on X'. " +
                "The summary always includes 'by_target': the from_id types ranked by summed incoming " +
                "weight (the most heavily used types), computed over the full result set. " +
                "to_id is required at detail level."
    )
    fun incomingDependencies(
        @ToolParam(description = "The subtree that is depended upon — source side.")
        fromId: Long,
        @ToolParam(
            description = "The subtree that does the depending — target side. Optional at type " +
                    "level — omit to return ALL incoming dependencies of from_id (everything that " +
                    "depends on it, from anywhere). Required at detail level.",
            required = false
        )
        toId: Long?,
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
        limit: Int?,
        @ToolParam(
            description = "Opaque pagination cursor from a previous response's next_cursor. " +
                    "Pass it to retrieve the next page; omit to start from the first page. " +
                    "When continuing, keep the other parameters identical to the original call. " +
                    "If the result set is larger than you need, prefer narrowing the query " +
                    "(a smaller subtree, or detail-level relationship filter) over paginating through all of it.",
            required = false
        )
        cursor: String?
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
            // incoming: edges from toId→fromId (what toId uses of fromId).
            // toId may be null → all incoming dependencies of fromId from anywhere.
            outgoingTool.typeLevelDependencies(fromId, toId, limit, cursor, outgoing = false, spec = TYPE_SPEC)
        } else if (toId == null) {
            // detail level requires an explicit depender — the open form is type-level only
            mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "to_id is required at detail_level='detail'. The open form " +
                            "(omitted to_id, returning everything that depends on from_id) is supported only at detail_level='type'.",
                    "recovery" to "Provide a to_id, or set detail_level='type' to query everything that depends on from_id."
                )
            )
        } else {
            // Detail level: swap from/to — detail_dependencies(toId, fromId)
            // shows edges from toId subtree into fromId subtree
            val effectiveRel = if (relationship.isNullOrBlank()) null else relationship
            detailDependenciesTool.detailDependencies(toId, fromId, effectiveRel, limit, cursor, DETAIL_SPEC)
        }
    }

    companion object {
        /** Type-level pagination for incoming_dependencies (~350 bytes/edge): default 100, cap 400. */
        val TYPE_SPEC = PaginationSpec(tool = "incoming_dependencies", defaultLimit = 100, maxLimit = 400)

        /** Detail-level pagination for incoming_dependencies (~550 bytes/edge): default 80, cap 250. */
        val DETAIL_SPEC = PaginationSpec(tool = "incoming_dependencies", defaultLimit = 80, maxLimit = 250)
    }
}
