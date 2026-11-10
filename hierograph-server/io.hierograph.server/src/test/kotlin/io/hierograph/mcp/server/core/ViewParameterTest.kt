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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.definition.ToolDefinition

class ViewParameterTest {

    private val mapper = ObjectMapper()

    @Test
    fun `addTo adds an optional string property and keeps the rest of the schema`() {
        val definition = ToolDefinition.builder().name("t").description("d").inputSchema(
            """{"type":"object","properties":{"nodeId":{"type":"integer"}},"required":["nodeId"]}""",
        ).build()

        val schema = mapper.readTree(ViewParameter.addTo(definition).inputSchema())

        assertThat(schema.path("properties").path("nodeId").path("type").asText()).isEqualTo("integer")
        assertThat(schema.path("properties").path("view").path("type").asText()).isEqualTo("string")
        assertThat(schema.path("required").map { it.asText() }).containsExactly("nodeId")
        assertThat(ViewParameter.addTo(definition).name()).isEqualTo("t")
    }

    @Test
    fun `addTo gives a tool without parameters an object schema`() {
        val definition = ToolDefinition.builder().name("t").description("d").inputSchema("{}").build()

        val schema = mapper.readTree(ViewParameter.addTo(definition).inputSchema())

        assertThat(schema.path("type").asText()).isEqualTo("object")
        assertThat(schema.path("properties").has("view")).isTrue()
    }

    @Test
    fun `split takes the view out of the input`() {
        val split = ViewParameter.split("""{"nodeId":1,"view":" layers "}""")

        assertThat(split.view).isEqualTo("layers")
        assertThat(mapper.readTree(split.input).fieldNames().asSequence().toList()).containsExactly("nodeId")
    }

    @Test
    fun `a missing, null, blank or non-text view is no view`() {
        assertThat(ViewParameter.split("""{"nodeId":1}""")).isEqualTo(ViewParameter.Split(null, """{"nodeId":1}"""))
        for (input in listOf("""{"view":null}""", """{"view":"  "}""", """{"view":3}""")) {
            val split = ViewParameter.split(input)
            assertThat(split.view).isNull()
            assertThat(mapper.readTree(split.input).has("view")).isFalse()
        }
    }

    @Test
    fun `an input that is not a JSON object passes unchanged`() {
        assertThat(ViewParameter.split("not json")).isEqualTo(ViewParameter.Split(null, "not json"))
        assertThat(ViewParameter.split("[1]")).isEqualTo(ViewParameter.Split(null, "[1]"))
    }
}
