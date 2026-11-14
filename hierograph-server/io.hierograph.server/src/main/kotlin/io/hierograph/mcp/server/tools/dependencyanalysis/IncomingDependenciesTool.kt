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

import io.hierograph.mcp.server.pagination.PaginationSpec
import io.hierograph.mcp.server.tools.detail.IDetailDependencies
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `incoming_dependencies`
 *
 * Mirror of `outgoing_dependencies` with reversed direction.
 * `incoming_dependencies(fromId: A, toId: B)` returns edges from B to A
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
                "Mirror of outgoing_dependencies — returns edges from the toId subtree to the " +
                "fromId subtree. Shows what the target side uses of the source side. " +
                "detailLevel='type' (default) returns type-to-type edges (fast). " +
                "detailLevel='detail' returns method/field-level edges with source locations (slower); " +
                "it also accepts a method or field id as fromId or toId, narrowing the edges to that member. " +
                "The 'relationship' filter is only valid at detail level. " +
                "At detail level, parameter_type and parameter_annotated_by edges carry " +
                "parameter={position,name}; parameterPosition keeps only the edges of the parameter at that " +
                "0-based position (e.g. relationship='parameter_type', parameterPosition=0: 'which methods take " +
                "this type as their first parameter'). " +
                "At type level each edge carries `kinds`: how many facts of each kind (calls, extends, casts, …) " +
                "make up its weight; a kind name is also the detail-level relationship that lists those facts. " +
                "The by_target, by_source_type, by_attribute/by_kind and by_relationship summaries are computed " +
                "over the ENTIRE result set, independent of page size. " +
                "On a hub node a full page of edges fills the ~50 KB response budget, most of it edges " +
                "you will not read. If you only need the ranking or attribute breakdown " +
                "(the common case, especially when toId is omitted), pass limit=1 and read the " +
                "summary — do NOT page through edges you won't use. " +
                "For the forward direction (what source uses of target), use outgoing_dependencies. " +
                "At type level, toId is optional: omit it to return ALL incoming dependencies of " +
                "fromId (everything that depends on fromId, from anywhere) — answering 'what depends on X'. " +
                "That question is almost always answered by the by_target / by_source_type summary, not " +
                "by the edge list — request a minimal page (limit=1). " +
                "The summary always includes 'by_target': the fromId types ranked by summed incoming " +
                "weight (the most heavily used types), computed over the full result set. " +
                "At detail level, toId is optional too: omit it to return every member-level reference " +
                "into fromId, from anywhere (from_scope is then null; by_source_nodes drills down from the root). " +
                "This reads every snapshot's dependencies, so it is slower than a query with toId; with " +
                "relationship='parameter_type' and parameterPosition=0 it answers 'which methods anywhere take " +
                "this type as their first parameter' in one call. " +
                "Detail rows: 'inherited': true marks a row whose depending type is a supertype outside toId — " +
                "code a toId type inherits, counted as its own (a base class's own references are left out " +
                "when the base class lies in fromId). 'receiver' appears when the code used a fromId type that " +
                "only inherits the member: 'to' and 'to_parent' then name the declaring member and type, which " +
                "may lie outside fromId, and 'receiver' names the fromId type the reference was compiled against. " +
                "A 'to' of kind external.member is a member no scanned type declares (a member of an external " +
                "type, or one inherited from outside the scanned code, like getMessage() on an exception)."
    )
    fun incomingDependencies(
        @ToolParam(description = "The subtree that is depended upon — source side.")
        fromId: Long,
        @ToolParam(
            description = "The subtree that does the depending — target side. Optional at type " +
                    "level — omit to return ALL incoming dependencies of fromId (everything that " +
                    "depends on it, from anywhere), at type or detail level.",
            required = false
        )
        toId: Long?,
        @ToolParam(
            description = "Zoom level: 'type' (default, in-memory) or 'detail' (member-level, with source lines).",
            required = false
        )
        detailLevel: String?,
        @ToolParam(
            description = "Filter to a specific detail-level relationship kind (e.g. calls, extends, casts, read_by; " +
                    "graph_overview lists them all). Only valid when detailLevel='detail'.",
            required = false
        )
        relationship: String?,
        @ToolParam(
            description = "Maximum edges per page. Default: 100 (type) / 80 (detail). Caps: 125 / 90, the most " +
                    "that fit the ~50 KB response budget; larger values are clamped. Pass limit=1 when you " +
                    "only need the summary (by_target / by_source_type / by_attribute / by_kind).",
            required = false
        )
        limit: Int?,
        @ToolParam(
            description = "Opaque pagination cursor from a previous response's next_cursor. " +
                    "Pass it to retrieve the next page; omit to start from the first page. " +
                    "When continuing, keep the other parameters identical to the original call. " +
                    "If the result set is larger than you need, prefer the full-set summary (limit=1) or " +
                    "narrowing the query (a smaller subtree, or detail-level relationship filter) over " +
                    "paginating through all of it.",
            required = false
        )
        cursor: String?,
        @ToolParam(
            description = "Detail level only: keep the parameter_type / parameter_annotated_by edges of the " +
                    "parameter at this 0-based position (0 = first parameter). Without relationship it selects " +
                    "both parameter relationships; with any other relationship it is an error.",
            required = false
        )
        parameterPosition: Int? = null
    ): Map<String, Any?> {

        val level = detailLevel ?: "type"

        // ── validate detailLevel ──────────────────────────────────────
        if (level !in listOf("type", "detail")) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "Invalid detailLevel: '$level'. Must be 'type' or 'detail'.",
                    "recovery" to "Use 'type' for type-to-type edges or 'detail' for method/field-level edges."
                )
            )
        }

        // ── validate parameterPosition at type level ───────────────────
        if (level == "type" && parameterPosition != null) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "The 'parameterPosition' parameter is only valid at detailLevel='detail'. " +
                            "Type-level edges aggregate all references between two types and have no parameter position.",
                    "recovery" to "Either remove 'parameterPosition', or set detailLevel='detail' to filter parameter edges by position."
                )
            )
        }

        // ── validate relationship at type level ────────────────────────
        if (level == "type" && !relationship.isNullOrBlank()) {
            return mapOf(
                "error" to mapOf(
                    "code" to "INVALID_PARAMETER",
                    "message" to "The 'relationship' parameter is only valid at detailLevel='detail'. " +
                            "At type level, edges carry attribute flags but cannot be filtered by detail-level relationship.",
                    "recovery" to "Either remove the 'relationship' parameter, or set detailLevel='detail' to filter by relationship kind."
                )
            )
        }

        return if (level == "type") {
            // incoming: edges from toId→fromId (what toId uses of fromId).
            // toId may be null → all incoming dependencies of fromId from anywhere.
            outgoingTool.typeLevelDependencies(fromId, toId, limit, cursor, outgoing = false, spec = TYPE_SPEC)
        } else {
            // Detail level: swap from/to — detail_dependencies(toId, fromId)
            // shows edges from toId subtree into fromId subtree; toId may be null → every depender, from anywhere
            outgoingTool.missingDetailScope(fromId, toId)?.let { return it }
            val effectiveRel = if (relationship.isNullOrBlank()) null else relationship
            detailDependenciesTool.detailDependencies(toId, fromId, effectiveRel, limit, cursor, DETAIL_SPEC, parameterPosition)
        }
    }

    companion object {
        /** Type-level pagination for incoming_dependencies (~400 bytes/edge with its kinds): default 100, cap 125 (the budget's fill). */
        val TYPE_SPEC = PaginationSpec(tool = "incoming_dependencies", defaultLimit = 100, maxLimit = 125, bytesPerItem = 400)

        /** Detail-level pagination for incoming_dependencies (~550 bytes/edge): default 80, cap 90 (the budget's fill). */
        val DETAIL_SPEC = PaginationSpec(tool = "incoming_dependencies", defaultLimit = 80, maxLimit = 90, bytesPerItem = 550)
    }
}
