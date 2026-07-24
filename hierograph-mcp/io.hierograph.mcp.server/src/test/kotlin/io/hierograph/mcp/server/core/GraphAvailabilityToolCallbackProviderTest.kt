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
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Covers the "start without a database" guard: while no hierarchical graph is loaded, every tool call
 * is short-circuited with the modular [GraphUnavailable] description, except tools explicitly exempted
 * (those needed to load a graph in the first place). Once a graph is available, calls pass through.
 */
class GraphAvailabilityToolCallbackProviderTest {

    private val objectMapper = ObjectMapper()

    /** A minimal ToolCallback that records whether it was invoked and returns a fixed payload. */
    private class FakeToolCallback(private val toolName: String) : ToolCallback {
        var invoked = false
        override fun getToolDefinition(): ToolDefinition =
            ToolDefinition.builder().name(toolName).description(toolName).inputSchema("{}").build()

        override fun call(toolInput: String): String {
            invoked = true
            return """{"ok":true}"""
        }

        override fun call(toolInput: String, toolContext: ToolContext?): String = call(toolInput)
    }

    private fun serviceWithGraph(): HierarchicalGraphService {
        val graph = HGGraphFactory.createHGGraph()
        var nextId = 1L
        val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        return HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
    }

    private fun provider(service: HierarchicalGraphService, vararg callbacks: ToolCallback) =
        GraphAvailabilityToolCallbackProvider(
            delegate = ToolCallbackProvider.from(*callbacks),
            graphService = service,
            toolsAllowedWithoutGraph = setOf("reload_graph")
        )

    @Test
    fun `without a graph, a normal tool is short-circuited with the unavailable description`() {
        val tool = FakeToolCallback("graph_overview")
        val callback = provider(HierarchicalGraphService(), tool).toolCallbacks.single()

        val result = objectMapper.readValue(callback.call("{}"), Map::class.java)

        assertThat(tool.invoked).isFalse()
        assertThat(result["status"]).isEqualTo(GraphUnavailable.STATUS)
        assertThat(result["message"] as String).isNotBlank()
    }

    @Test
    fun `without a graph, an exempt tool still runs`() {
        val tool = FakeToolCallback("reload_graph")
        val callback = provider(HierarchicalGraphService(), tool).toolCallbacks.single()

        val result = objectMapper.readValue(callback.call("{}"), Map::class.java)

        assertThat(tool.invoked).isTrue()
        assertThat(result["ok"]).isEqualTo(true)
    }

    @Test
    fun `with a graph, a normal tool passes through`() {
        val tool = FakeToolCallback("graph_overview")
        val callback = provider(serviceWithGraph(), tool).toolCallbacks.single()

        val result = objectMapper.readValue(callback.call("{}"), Map::class.java)

        assertThat(tool.invoked).isTrue()
        assertThat(result["ok"]).isEqualTo(true)
    }
}
