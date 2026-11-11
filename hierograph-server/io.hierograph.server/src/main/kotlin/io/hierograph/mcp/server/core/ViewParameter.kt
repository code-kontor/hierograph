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
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * The optional `view` parameter of the structural tools (views specification, §8.1).
 *
 * The tools themselves do not declare it: [GraphAvailabilityToolCallbackProvider] adds it to their input
 * schema ([addTo]), takes it out of the call's input ([split]) and runs the call in the named view. A tool
 * therefore answers in a view without knowing about views: every read of the graph inside the call sees
 * the view's hierarchy.
 */
object ViewParameter {

    const val NAME = "view"

    const val DESCRIPTION =
        "Name of a view to answer in (see list_views). A view regroups the hierarchy: its groups are nodes of " +
            "kind 'view.group' above the nodes they group, and a group's id is valid wherever a node id is. " +
            "Dependencies are the same in every view; only where nodes sit changes. Omit to use the hierarchy " +
            "as scanned."

    private val mapper = ObjectMapper()

    /** [definition] with `view` added to its input schema's properties, as an optional string. */
    fun addTo(definition: ToolDefinition): ToolDefinition {
        val schema = (mapper.readTree(definition.inputSchema()) as? ObjectNode) ?: mapper.createObjectNode()
        if (!schema.has("type")) schema.put("type", "object")
        val properties = (schema.get("properties") as? ObjectNode) ?: schema.putObject("properties")
        properties.putObject(NAME).put("type", "string").put("description", DESCRIPTION)
        return ToolDefinition.builder()
            .name(definition.name())
            .description(definition.description())
            .inputSchema(mapper.writeValueAsString(schema))
            .build()
    }

    /**
     * A call's input split into the view it names, if any, and the input without the `view` argument.
     * [invalid] is the `view` value as given when it is neither a string nor `null`; the call is then refused.
     */
    data class Split(val view: String?, val input: String, val invalid: String? = null)

    /**
     * Splits [toolInput]: the view it names (`null` when absent, `null` or blank) and the input without it.
     * A `view` that is not a string (a number, an array, an object) is not taken as "no view", which would
     * silently answer from the scanned hierarchy: it is returned as [Split.invalid]. An input that is not a
     * JSON object is passed on unchanged, for the tool to report.
     */
    fun split(toolInput: String): Split {
        val tree = runCatching { mapper.readTree(toolInput) }.getOrNull() as? ObjectNode ?: return Split(null, toolInput)
        if (!tree.has(NAME)) return Split(null, toolInput)
        val value = tree.remove(NAME)
        val input = mapper.writeValueAsString(tree)
        if (value != null && !value.isNull && !value.isTextual) return Split(null, input, invalid = value.toString())
        val view = value?.takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
        return Split(view, input)
    }
}
