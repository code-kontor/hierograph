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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Checks a call's arguments against the JSON types its tool's input schema declares, before the arguments are
 * converted to the tool method's parameters.
 *
 * The conversion fails on a value of the wrong shape (an object where a string is declared, a string holding
 * JSON where an object is declared), and the tool then never runs: the client gets a tool-execution failure
 * instead of the standard `error` object. [check] finds such a value first, so the guard can answer with
 * `INVALID_PARAMETER` naming the parameter.
 *
 * It rejects exactly what the conversion rejects and nothing more: a numeric string or a fraction is accepted
 * for an integer, a number or boolean for a string, a number or `"true"`/`"false"` for a boolean, because the
 * conversion accepts them. `null` is always accepted; whether a parameter may be absent is the tool's concern.
 * Only the declared `type` of each property, and of an array's `items`, is checked.
 */
class ArgumentTypes(inputSchema: String) {

    /** A value that does not fit its declared type: the [path] of the argument, the [expected] and the [actual] type. */
    data class Mismatch(val path: String, val expected: String, val actual: String) {
        val message: String get() = "'$path' must be ${article(expected)}, not ${article(actual)}."
        val recovery: String get() =
            "Pass '$path' as ${article(expected)}, as the tool's schema declares" +
                (if (expected == "object") ", not as a string holding JSON." else ".")
    }

    /** The declared type of each property, with the item type of an array (one level). */
    private val properties: Map<String, JsonNode> =
        runCatching { mapper.readTree(inputSchema).get("properties") as? ObjectNode }.getOrNull()
            ?.properties()?.associate { (name, schema) -> name to schema }
            .orEmpty()

    /** The first argument of [input] that does not fit its declared type, or `null`. An input that is not a JSON object is not checked. */
    fun check(input: String): Mismatch? {
        if (properties.isEmpty()) return null
        val tree = runCatching { mapper.readTree(input) }.getOrNull() as? ObjectNode ?: return null
        for ((name, schema) in properties) {
            val value = tree.get(name) ?: continue
            mismatch(name, schema, value)?.let { return it }
        }
        return null
    }

    private fun mismatch(path: String, schema: JsonNode, value: JsonNode): Mismatch? {
        val type = schema.get("type")?.takeIf { it.isTextual }?.asText() ?: return null
        if (value.isNull) return null
        if (!fits(type, value)) return Mismatch(path, type, typeOf(value))
        if (type == "array") {
            val items = schema.get("items") ?: return null
            value.forEachIndexed { i, item -> mismatch("$path[$i]", items, item)?.let { return it } }
        }
        return null
    }

    private fun fits(type: String, value: JsonNode): Boolean = when (type) {
        "object" -> value.isObject
        "array" -> value.isArray
        "string" -> value.isValueNode
        "integer", "number" -> value.isNumber || (value.isTextual && isNumeric(value.asText()))
        "boolean" -> value.isBoolean || value.isNumber ||
            (value.isTextual && value.asText().let { it == "true" || it == "false" })
        else -> true
    }

    /** A string the conversion turns into a number: a decimal number, or empty (converted to `null`). */
    private fun isNumeric(text: String): Boolean = text.isEmpty() || text.trim().toBigDecimalOrNull() != null

    private fun typeOf(value: JsonNode): String = when {
        value.isObject -> "object"
        value.isArray -> "array"
        value.isTextual -> "string"
        value.isBoolean -> "boolean"
        value.isIntegralNumber -> "integer"
        value.isNumber -> "number"
        else -> "value"
    }

    private companion object {
        val mapper = ObjectMapper()

        fun article(type: String) = (if (type.first() in "aeiou") "an " else "a ") + type
    }
}
