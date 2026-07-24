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
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Wraps a delegate [ToolCallbackProvider] and short-circuits every tool call with the standard
 * [GraphUnavailable] description while no hierarchical graph is loaded (the server was started without
 * a reachable database — see [HierarchicalGraphService.isGraphAvailable]).
 *
 * Tools whose names are in [toolsAllowedWithoutGraph] are exempt: they must run even without a graph so
 * that a graph can eventually be loaded/connected in the first place (e.g. `reload_graph`, and future
 * "connect to a database" tools). Everything else answers with the one modular description.
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

        private inline fun guard(invoke: () -> String): String {
            val name = delegate.toolDefinition.name()
            return if (graphService.isGraphAvailable || name in toolsAllowedWithoutGraph) {
                invoke()
            } else {
                objectMapper.writeValueAsString(GraphUnavailable.response())
            }
        }
    }
}
