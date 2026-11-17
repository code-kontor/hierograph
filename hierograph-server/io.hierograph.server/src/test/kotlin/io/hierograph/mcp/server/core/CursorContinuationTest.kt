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
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.pagination.ContinuationStore
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition
import java.time.Duration

/**
 * A paged query can be continued with its cursor alone: the wrapper remembers the arguments of a call that
 * returned a `next_cursor` and supplies them again ([GraphAvailabilityToolCallbackProvider]).
 */
class CursorContinuationTest {

    private val mapper = ObjectMapper()

    /** A paginated tool: `nodeIds` required, `cursor` and `limit` optional; page 1 hands out cursor "c1". */
    private class PagedTool : ToolCallback {
        val inputs = mutableListOf<Map<*, *>>()
        override fun getToolDefinition(): ToolDefinition = ToolDefinition.builder()
            .name("pairwise_dependencies").description("paged")
            .inputSchema(
                """{"type":"object","properties":{"nodeIds":{"type":"array","items":{"type":"integer"}},""" +
                    """"edgeSort":{"type":"string"},"limit":{"type":"integer"},"cursor":{"type":"string"}},"required":["nodeIds"]}""",
            ).build()

        override fun call(toolInput: String): String {
            val input = ObjectMapper().readValue(toolInput, Map::class.java)
            inputs += input
            return if (input["cursor"] == null) """{"edges":[1],"next_cursor":"c1"}""" else """{"edges":[2]}"""
        }

        override fun call(toolInput: String, toolContext: ToolContext?): String = call(toolInput)
    }

    private fun service(): HierarchicalGraphService {
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 1L) }
        return HierarchicalGraphService().also { it.seed(HGModel(graph, HierarchyFactory.createHierarchy(graph, root))) }
    }

    private fun wrap(tool: ToolCallback, store: ContinuationStore = ContinuationStore()) =
        GraphAvailabilityToolCallbackProvider(
            ToolCallbackProvider.from(tool), service(), toolsAllowedWithoutGraph = emptySet(), continuations = store,
        ).toolCallbacks.single()

    private fun call(callback: ToolCallback, input: String): Map<*, *> = mapper.readValue(callback.call(input), Map::class.java)

    @Test
    fun `a paginated tool's schema declares no required parameters, so a cursor alone passes validation`() {
        val schema = mapper.readTree(wrap(PagedTool()).toolDefinition.inputSchema())
        assertThat(schema.has("required")).isFalse()
        assertThat(schema["properties"].has("nodeIds")).isTrue()
    }

    @Test
    fun `a call with the cursor alone runs with the first call's arguments, and its own limit`() {
        val tool = PagedTool()
        val callback = wrap(tool)

        call(callback, """{"nodeIds":[1,2,3],"edgeSort":"weight_desc","limit":5}""")
        val second = call(callback, """{"cursor":"c1","limit":7}""")

        assertThat(second["edges"]).isEqualTo(listOf(2))
        assertThat(tool.inputs.last()).isEqualTo(
            mapOf("nodeIds" to listOf(1, 2, 3), "edgeSort" to "weight_desc", "cursor" to "c1", "limit" to 7),
        )
    }

    @Test
    fun `a call with the cursor alone keeps the first call's page size`() {
        val tool = PagedTool()
        val callback = wrap(tool)

        call(callback, """{"nodeIds":[1,2],"limit":200}""")
        call(callback, """{"cursor":"c1"}""")

        assertThat(tool.inputs.last()).isEqualTo(mapOf("nodeIds" to listOf(1, 2), "limit" to 200, "cursor" to "c1"))
    }

    @Test
    fun `a call that repeats the arguments with the cursor passes through unchanged`() {
        val tool = PagedTool()
        val callback = wrap(tool)

        call(callback, """{"nodeIds":[1,2]}""")
        call(callback, """{"nodeIds":[1,2],"cursor":"c1"}""")

        assertThat(tool.inputs.last()).isEqualTo(mapOf("nodeIds" to listOf(1, 2), "cursor" to "c1"))
    }

    @Test
    fun `a cursor whose arguments are unknown is refused without running the tool`() {
        val tool = PagedTool()
        val result = call(wrap(tool), """{"cursor":"never-issued"}""")

        assertThat((result["error"] as Map<*, *>)["code"]).isEqualTo("CURSOR_ARGUMENTS_UNKNOWN")
        assertThat(tool.inputs).isEmpty()
    }

    @Test
    fun `a first call without a required argument is refused by the wrapper`() {
        val tool = PagedTool()
        val result = call(wrap(tool), """{"edgeSort":"dsm"}""")

        val error = result["error"] as Map<*, *>
        assertThat(error["code"]).isEqualTo("INVALID_PARAMETER")
        assertThat(error["message"] as String).contains("nodeIds")
        assertThat(tool.inputs).isEmpty()
    }

    @Test
    fun `the store forgets arguments after their maximum age, and the least recently used beyond its capacity`() {
        var now = 0L
        val store = ContinuationStore(capacity = 2, maxAge = Duration.ofMinutes(30), clock = { now })
        val args = mapper.createObjectNode().put("x", 1)

        store.remember("t", "a", args)
        now += Duration.ofMinutes(31).toMillis()
        assertThat(store.recall("t", "a")).isNull()

        store.remember("t", "b", args)
        store.remember("t", "c", args)
        store.remember("t", "d", args)
        assertThat(store.recall("t", "b")).isNull()
        assertThat(store.recall("t", "c")).isEqualTo(args)
        assertThat(store.recall("other", "c")).isNull()
    }
}
