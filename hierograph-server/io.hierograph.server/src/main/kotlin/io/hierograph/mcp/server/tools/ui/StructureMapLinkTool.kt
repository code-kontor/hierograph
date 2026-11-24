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
package io.hierograph.mcp.server.tools.ui

import io.hierograph.mcp.server.instance.ServerInstance
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.PackageFolding
import io.hierograph.server.graphservice.views.ViewService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * MCP tool: `structure_map_link`
 *
 * The web UI's levelized structure map with a set of dependencies drawn on it: the agent computes the
 * dependencies that matter (those an extraction has to cut, those against an intended layering) and hands the
 * user one link. The URL alone carries the set (`dependency_ids`), nothing is stored in the server; the map
 * resolves the ids and opens itself just far enough unless `expandedIds` are given.
 */
@Component
class StructureMapLinkTool(
    private val instance: ServerInstance,
    private val graphService: HierarchicalGraphService,
    private val viewService: ViewService,
) {

    @Tool(
        name = "structure_map_link",
        description = "[Web UI] A link to the web UI's levelized structure map with these dependencies drawn on it, " +
                "to hand to the user: the map draws each dependency as an arrow between the boxes its ends lie in " +
                "(several between the same boxes as one), and opens the map just far " +
                "enough to tell the ends apart. Use it to show the dependencies that matter for the question at " +
                "hand — the ones an extraction has to cut, the ones against an intended layering, the uses of a " +
                "type about to move — where they sit in the structure. An end can be any node: module, package, " +
                "type or member (drawn at its type). With 'view', the map shows that view. Returns url, view, and " +
                "a summary with the pairs that name no node (unknown_ids) and those without a dependency in the " +
                "graph (without_dependency); the map doesn't draw those, so tell the user. At most " +
                "$MAX_DEPENDENCIES dependencies."
    )
    fun structureMapLink(
        @ToolParam(
            description = "The dependencies, each an object {\"sourceId\": <node id>, \"targetId\": <node id>}: " +
                    "from anything in the source's subtree to anything in the target's."
        )
        dependencies: List<Map<String, Any?>>,
        @ToolParam(
            description = "The containers to show expanded, instead of the map's own choice (just far enough " +
                    "to tell the ends apart). Leave it out unless you want a different picture.",
            required = false
        )
        expandedIds: List<Long>?,
    ): Map<String, Any?> {
        val uiUrl = instance.uiUrl
            ?: return errorResponse(
                "UI_UNAVAILABLE",
                "The server doesn't know its web UI's URL yet.",
                "Call server_info for ui_url, or retry once the server is ready.",
            )
        if (dependencies.isEmpty()) {
            return errorResponse(
                "INVALID_PARAMETER",
                "'dependencies' is empty.",
                "Pass at least one {\"sourceId\": …, \"targetId\": …}.",
            )
        }
        val pairs = dependencies.mapIndexed { index, entry ->
            val source = idOf(entry["sourceId"])
            val target = idOf(entry["targetId"])
            if (source == null || target == null) {
                return errorResponse(
                    "INVALID_PARAMETER",
                    "Dependency $index is not {\"sourceId\": <node id>, \"targetId\": <node id>}: $entry.",
                    "Pass each dependency as an object with the numeric node ids of its source and target.",
                )
            }
            source to target
        }.distinct()
        if (pairs.size > MAX_DEPENDENCIES) {
            return errorResponse(
                "TOO_MANY_DEPENDENCIES",
                "${pairs.size} dependencies, at most $MAX_DEPENDENCIES fit a link.",
                "Pass the most relevant $MAX_DEPENDENCIES, or lift them to coarser ends (their packages or " +
                        "modules), which the map draws as one arrow each.",
            )
        }

        val model = graphService.model
        val hierarchy = model.hierarchy
        val unknown = LinkedHashSet<Long>()
        val withoutDependency = mutableListOf<Map<String, Long>>()
        for ((sourceId, targetId) in pairs) {
            val source = model.lookupNode(sourceId)
            val target = model.lookupNode(targetId)
            if (source == null) unknown += sourceId
            if (target == null) unknown += targetId
            if (source != null && target != null && hierarchy.getAggregatedDependency(source, target) == null) {
                withoutDependency += linkedMapOf("sourceId" to sourceId, "targetId" to targetId)
            }
        }

        val view = viewService.currentView()?.entry?.name
        val url = buildString {
            append(uiUrl).append("structure?")
            val params = mutableListOf<String>()
            if (view != null) params += "view=" + URLEncoder.encode(view, StandardCharsets.UTF_8)
            params += "dependency_ids=" + encodePairs(pairs)
            if (!expandedIds.isNullOrEmpty()) {
                // The map is folded: a skipped package is shown, and opened, as its chain's end.
                val folding = PackageFolding.of(hierarchy)
                val shown = expandedIds.map { id -> model.lookupNode(id)?.let { folding.displayNodeOf(it).identifier } ?: id }
                params += "expanded_ids=" + shown.distinct().joinToString(",")
            }
            append(params.joinToString("&"))
        }
        if (url.length > MAX_URL_LENGTH) {
            return errorResponse(
                "LINK_TOO_LONG",
                "The link would be ${url.length} characters, more than a browser request carries ($MAX_URL_LENGTH).",
                "Pass fewer dependencies or fewer expandedIds, or lift the dependencies to coarser ends.",
            )
        }
        return linkedMapOf(
            "url" to url,
            "view" to view,
            "summary" to linkedMapOf(
                "dependencies" to pairs.size,
                "unknown_ids" to unknown.toList(),
                "without_dependency" to withoutDependency,
            ),
        )
    }

    private fun idOf(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun errorResponse(code: String, message: String, recovery: String): Map<String, Any?> =
        mapOf("error" to linkedMapOf<String, Any?>("code" to code, "message" to message, "recovery" to recovery))

    companion object {
        /** The map's cap on the dependencies of a link. */
        const val MAX_DEPENDENCIES = 200

        /** Below the server's default limit for the request line and headers (8 KB). */
        const val MAX_URL_LENGTH = 7000

        /**
         * `<source>-<target>`, comma-separated, a source with several targets grouped as
         * `<source>-<target>.<target>`, in the order the sources first appear.
         */
        fun encodePairs(pairs: List<Pair<Long, Long>>): String =
            pairs.groupBy({ it.first }, { it.second }).entries
                .joinToString(",") { (source, targets) -> "$source-${targets.joinToString(".")}" }
    }
}
