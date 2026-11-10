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
package io.hierograph.mcp.server.tools.views

import com.fasterxml.jackson.databind.ObjectMapper
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.views.UnknownViewException
import io.hierograph.server.graphservice.views.ViewService
import io.hierograph.server.views.ViewDocumentException
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tools `define_view`, `list_views`, `get_view` and `delete_view` (views specification, §8.2): the
 * documents that regroup the hierarchy, and what applying them found.
 */
@Component
class ViewTools(
    private val viewService: ViewService,
    private val graphService: HierarchicalGraphService,
) {

    private val mapper = ObjectMapper()

    @Tool(
        name = "define_view",
        description = "[Views] " +
            "Define a view: a named regrouping of the hierarchy that the other tools answer in when called with " +
            "view=<name>. The document is {\"name\": \"...\", \"description\": \"...\", \"groups\": [{\"name\": \"...\", " +
            "\"members\": [selectors], \"groups\": [nested groups]}]}. A selector is a qualified-name pattern " +
            "(module GAV, package or type fqn; '*' matches anything but ':', '**' anything, '{a,b}' alternatives) " +
            "or {\"match\": pattern, \"kind\": kind or alias, \"within\": pattern of an enclosing node}. A node is in " +
            "at most one group; the direct members of a group must share one parent. A restructuring view also " +
            "moves code, to project a split or merge before it is made: \"restructure\": true, \"modules\": " +
            "[{\"name\": \"...\"}] (virtual modules to create) and \"moves\": [{\"select\": selector, \"to\": " +
            "virtual module name, module GAV pattern or \"<module>/<package fqn>\"}] (packages and types move with " +
            "their subtrees, a module merges into the target); groups then apply to the restructured hierarchy. " +
            "Its result adds virtual_modules and restructuring: the module dependencies added, removed and changed, " +
            "and new_cycles / introduces_cycles. The view is stored on the " +
            "server, replacing a runtime view of the same name and shadowing a project view of that name, then " +
            "applied: returns the applied groups with their ids and the report of what could not be applied. " +
            "Iterate by defining the document again."
    )
    fun defineView(
        @ToolParam(description = "The view document as a JSON object.")
        document: Map<String, Any?>,
    ): Map<String, Any?> {
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document)
        return try {
            ViewResponses.applied(viewService.define(json))
        } catch (e: ViewDocumentException) {
            ViewResponses.error(
                "INVALID_VIEW_DOCUMENT", "The view document is invalid; nothing was stored.",
                "Fix the listed problems and call define_view again.",
                "problems" to e.problems,
            )
        }
    }

    @Tool(
        name = "list_views",
        description = "[Views] " +
            "List the views that can be passed as view=<name>: project views kept with the code and views " +
            "defined at runtime with define_view (a runtime view shadows a project view of the same name). " +
            "Each entry has name, description, source, file and, for a valid view, how many groups it applies " +
            "and how many errors and warnings applying it reported; an invalid document is listed with its problems. " +
            "'default' describes the hierarchy as scanned, which a call without view answers in; it is not a view name."
    )
    fun listViews(): Map<String, Any?> {
        val views = viewService.entries().map { entry ->
            ViewResponses.entry(entry).apply {
                if (entry.isValid && graphService.isGraphAvailable) {
                    runCatching { viewService.view(entry.name) }.getOrNull()?.let { putAll(ViewResponses.summary(it)) }
                }
            }
        }
        return linkedMapOf("default" to defaultEntry(), "views" to views, "summary" to linkedMapOf("total" to views.size))
    }

    /**
     * The hierarchy as scanned: what a call without `view` answers in. Listed beside the views rather than
     * among them, so no view name is reserved for it; its top-level count is left out while no graph is loaded.
     */
    private fun defaultEntry(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "description" to "The hierarchy as scanned. Omit view to use it.",
    ).apply {
        if (graphService.isGraphAvailable) {
            val hierarchy = graphService.model.hierarchy
            put("top_level_count", hierarchy.childrenOf(hierarchy.rootNode).size)
        }
    }

    @Tool(
        name = "get_view",
        description = "[Views] " +
            "Return a view: its document, its source and file, the groups it applies (path, id, parent id, " +
            "number of children) and the full report of applying it to the current graph. For a document that " +
            "does not parse, returns its problems instead."
    )
    fun getView(
        @ToolParam(description = "The view's name.")
        name: String,
    ): Map<String, Any?> {
        val entry = try {
            viewService.entry(name)
        } catch (e: UnknownViewException) {
            return notFound(e)
        }
        val document = runCatching { mapper.readValue(entry.text, Any::class.java) }.getOrElse { entry.text }
        if (!entry.isValid || !graphService.isGraphAvailable) {
            return ViewResponses.entry(entry).apply { put("document", document) }
        }
        return ViewResponses.applied(viewService.view(name)).apply { put("document", document) }
    }

    @Tool(
        name = "delete_view",
        description = "[Views] " +
            "Delete a view defined with define_view. A project view it shadowed applies again and is returned; " +
            "project views themselves are kept with the code and cannot be deleted through the server."
    )
    fun deleteView(
        @ToolParam(description = "The view's name.")
        name: String,
    ): Map<String, Any?> {
        val restored = try {
            viewService.delete(name)
        } catch (e: UnknownViewException) {
            return notFound(e)
        } catch (e: IllegalArgumentException) {
            return ViewResponses.error(
                "PROJECT_VIEW", e.message ?: "'$name' is a project view.",
                "Edit or remove the file in the project's .hierograph/views directory, or shadow it with define_view.",
            )
        }
        return linkedMapOf("deleted" to name, "restored" to restored?.let { ViewResponses.entry(it) })
    }

    private fun notFound(e: UnknownViewException) =
        ViewResponses.error("VIEW_NOT_FOUND", e.message!!, "Call list_views for the views there are.")
}
