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
package io.hierograph.mcp.server.core

import com.fasterxml.jackson.databind.ObjectMapper
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.views.InvalidViewException
import io.hierograph.server.graphservice.views.UnknownViewException
import io.hierograph.server.graphservice.views.ViewService
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Wraps a delegate [ToolCallbackProvider], runs every tool call against one pinned graph snapshot
 * ([HierarchicalGraphService.withPinnedSnapshot]), and short-circuits every tool call with the standard
 * [GraphUnavailable] description while no hierarchical graph is loaded (the graph source could not be
 * built — see [HierarchicalGraphService.isGraphAvailable]).
 *
 * Tools whose names are in [toolsAllowedWithoutGraph] are exempt: they must run even without a graph so
 * that a graph can be loaded in the first place (`reload_graph`). Everything else answers with the one modular description.
 *
 * With a [viewService], every tool not in [toolsWithoutView] gains the optional `view` parameter
 * ([ViewParameter]): a call that names a view runs with the view's snapshot pinned instead of the graph's
 * ([ViewService.withView]), and an unknown or invalid view is answered with a `VIEW_NOT_FOUND` or
 * `VIEW_INVALID` error without running the tool.
 */
class GraphAvailabilityToolCallbackProvider(
    private val delegate: ToolCallbackProvider,
    private val graphService: HierarchicalGraphService,
    private val toolsAllowedWithoutGraph: Set<String>,
    private val viewService: ViewService? = null,
    private val toolsWithoutView: Set<String> = emptySet(),
) : ToolCallbackProvider {

    private val objectMapper = ObjectMapper()

    override fun getToolCallbacks(): Array<ToolCallback> =
        delegate.toolCallbacks.map { GuardingToolCallback(it) }.toTypedArray()

    private inner class GuardingToolCallback(
        private val delegate: ToolCallback
    ) : ToolCallback {

        private val name = delegate.toolDefinition.name()

        private val takesView = viewService != null && name !in toolsWithoutView

        private val definition: ToolDefinition =
            if (takesView) ViewParameter.addTo(delegate.toolDefinition) else delegate.toolDefinition

        /** The tool's own argument types; `view` is checked and removed before these are. */
        private val argumentTypes = ArgumentTypes(delegate.toolDefinition.inputSchema())

        override fun getToolDefinition(): ToolDefinition = definition

        override fun call(toolInput: String): String =
            guard(toolInput) { delegate.call(it) }

        override fun call(toolInput: String, toolContext: ToolContext?): String =
            guard(toolInput) { delegate.call(it, toolContext) }

        private inline fun guard(toolInput: String, crossinline invoke: (String) -> String): String {
            if (!graphService.isGraphAvailable && name !in toolsAllowedWithoutGraph) {
                return objectMapper.writeValueAsString(GraphUnavailable.response())
            }
            val split = if (takesView) ViewParameter.split(toolInput) else ViewParameter.Split(null, toolInput)
            split.invalid?.let { value ->
                return error(
                    "INVALID_PARAMETER", "'view' must be a string naming a view, not $value.",
                    "Pass the view's name as a string, e.g. \"view\": \"layers\", or leave 'view' out to use the hierarchy as scanned.",
                )
            }
            // A value of the wrong JSON type would fail converting to the tool's parameter, before the tool runs.
            argumentTypes.check(split.input)?.let { return error("INVALID_PARAMETER", it.message, it.recovery) }
            val viewName = split.view
            // One snapshot per call: a reload during the call cannot mix two graphs into one answer.
            if (viewName == null) return graphService.withPinnedSnapshot { invoke(split.input) }
            val views = viewService!!
            val base = graphService.liveSnapshot()
            val view = try {
                graphService.withPinnedSnapshot(base) { views.view(viewName) }
            } catch (e: UnknownViewException) {
                return error("VIEW_NOT_FOUND", e.message!!, "Call list_views for the views there are, or define_view to define one.")
            } catch (e: InvalidViewException) {
                return error("VIEW_INVALID", e.message!!, "Fix the document and define it again with define_view; get_view shows its problems.")
            }
            return views.withView(view) { invoke(split.input) }
        }

        private fun error(code: String, message: String, recovery: String): String =
            objectMapper.writeValueAsString(
                mapOf("error" to linkedMapOf("code" to code, "message" to message, "recovery" to recovery)),
            )
    }
}
