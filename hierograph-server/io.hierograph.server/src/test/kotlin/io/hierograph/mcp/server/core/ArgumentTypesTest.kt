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

import com.fasterxml.jackson.core.type.TypeReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.util.json.JsonParser
import java.lang.reflect.Type

class ArgumentTypesTest {

    private val schema = """{ "type": "object", "properties": {
        "nodeId":    { "type": "integer" },
        "name":      { "type": "string" },
        "flag":      { "type": "boolean" },
        "nodeIds":   { "type": "array", "items": { "type": "integer" } },
        "kinds":     { "type": "array", "items": { "type": "string" } },
        "document":  { "type": "object" },
        "anything":  { "description": "no declared type" } } }"""

    private val types = ArgumentTypes(schema)

    private fun check(name: String, json: String) = types.check("""{ "$name": $json }""")

    /** The Kotlin parameter type each property converts to, as the tools declare them. */
    private val parameterTypes: Map<String, Type> = mapOf(
        "nodeId" to java.lang.Long::class.java,
        "name" to String::class.java,
        "flag" to java.lang.Boolean::class.java,
        "nodeIds" to object : TypeReference<List<Long>>() {}.type,
        "kinds" to object : TypeReference<List<String>>() {}.type,
        "document" to object : TypeReference<Map<String, Any?>>() {}.type,
    )

    private val cases = listOf(
        "nodeId" to "12", "nodeId" to "\"12\"", "nodeId" to "1.5", "nodeId" to "\"\"", "nodeId" to "\"abc\"",
        "nodeId" to "true", "nodeId" to "[1]", "nodeId" to "{}",
        "name" to "\"a\"", "name" to "5", "name" to "true", "name" to "[\"a\"]", "name" to "{}",
        "flag" to "true", "flag" to "\"true\"", "flag" to "1", "flag" to "\"yes\"", "flag" to "[]",
        "nodeIds" to "[1, 2]", "nodeIds" to "[\"1\"]", "nodeIds" to "1", "nodeIds" to "[\"a\"]", "nodeIds" to "\"[1]\"", "nodeIds" to "{}",
        "kinds" to "[\"a\"]", "kinds" to "[1]", "kinds" to "\"a\"", "kinds" to "[{}]",
        "document" to "{}", "document" to "\"{}\"", "document" to "[]", "document" to "3",
    )

    @Test
    fun `accepts exactly what the conversion to the tool's parameter accepts`() {
        for ((name, json) in cases) {
            val converts = runCatching<Any?> { JsonParser.fromJson<Any>(json, parameterTypes.getValue(name)) }.isSuccess
            assertThat(check(name, json) == null).describedAs("$name = $json (conversion %s)", if (converts) "succeeds" else "fails")
                .isEqualTo(converts)
        }
    }

    @Test
    fun `a mismatch names the argument, the declared and the given type`() {
        val mismatch = check("document", "\"{\\\"name\\\": \\\"x\\\"}\"")!!

        assertThat(mismatch.message).isEqualTo("'document' must be an object, not a string.")
        assertThat(mismatch.recovery).contains("not as a string holding JSON")
    }

    @Test
    fun `an array item is reported with its index`() {
        assertThat(check("nodeIds", "[1, \"a\"]")!!.message).isEqualTo("'nodeIds[1]' must be an integer, not a string.")
    }

    @Test
    fun `null, absent and undeclared values pass, and an input that is not an object is left to the tool`() {
        assertThat(check("document", "null")).isNull()
        assertThat(types.check("{}")).isNull()
        assertThat(check("anything", "[1, {}]")).isNull()
        assertThat(check("unknown", "{}")).isNull()
        assertThat(types.check("not json")).isNull()
        assertThat(ArgumentTypes("{}").check("""{ "document": 3 }""")).isNull()
    }
}
