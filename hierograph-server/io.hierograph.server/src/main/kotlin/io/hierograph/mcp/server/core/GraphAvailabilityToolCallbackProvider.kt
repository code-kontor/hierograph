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
 */
class GraphAvailabilityToolCallbackProvider(
    private val delegate: ToolCallbackProvider,
    private val graphService: HierarchicalGraphService,
    private val toolsAllowedWithoutGraph: Set<String>
) : ToolCallbackProvider {

    private val objectMapper = ObjectMapper()

    override fun getToolCallbacks(): Array<ToolCallback> =
        delegate.toolCallbacks.map { GuardingToolCallback(it) }.toTypedArray()

    private inner class GuardingToolCallback(
        private val delegate: ToolCallback
    ) : ToolCallback {

        override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

        override fun call(toolInput: String): String =
            guard { delegate.call(toolInput) }

        override fun call(toolInput: String, toolContext: ToolContext?): String =
            guard { delegate.call(toolInput, toolContext) }

        private inline fun guard(crossinline invoke: () -> String): String {
            val name = delegate.toolDefinition.name()
            return if (graphService.isGraphAvailable || name in toolsAllowedWithoutGraph) {
                // One snapshot per call: a reload during the call cannot mix two graphs into one answer.
                graphService.withPinnedSnapshot { invoke() }
            } else {
                objectMapper.writeValueAsString(GraphUnavailable.response())
            }
        }
    }
}
