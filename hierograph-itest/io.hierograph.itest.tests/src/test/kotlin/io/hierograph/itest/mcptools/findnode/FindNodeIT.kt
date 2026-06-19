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
package io.hierograph.itest.mcptools.findnode

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Exercises the `find_node` MCP tool bean against the live graph. Boots the full MCP application so
 * the tool beans are available — see [io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest].
 */
class FindNodeIT : AbstractMcpApplicationIntegrationTest() {

    @Test
    fun `searching for a type by simple name returns the matching node`() {
        val results = resultsOf(findNodeTool.findNode(name = "StringUtils", kindFilter = null))

        assertThat(results).isNotEmpty
        assertThat(results.map { it["qualified_name"] })
            .contains("org.springframework.util.StringUtils")
        val stringUtils = results.first { it["qualified_name"] == "org.springframework.util.StringUtils" }
        assertThat(stringUtils["name"]).isEqualTo("StringUtils")
        assertThat(stringUtils["kind"]).isEqualTo("java.class")
    }

    @Test
    fun `the default search excludes methods and fields`() {
        val results = resultsOf(findNodeTool.findNode(name = "StringUtils", kindFilter = null))

        assertThat(results.map { it["kind"] })
            .doesNotContain("java.method", "java.field")
    }

    @Test
    fun `a kind filter narrows results to the requested kinds`() {
        val results = resultsOf(findNodeTool.findNode(name = "StringUtils", kindFilter = listOf("java.interface")))

        // StringUtils is a class, so an interface-only filter must not return it.
        assertThat(results.map { it["qualified_name"] })
            .doesNotContain("org.springframework.util.StringUtils")
        assertThat(results.map { it["kind"] }).allMatch { it == "java.interface" }
    }

    @Test
    fun `methods are only searched when explicitly requested via kind filter`() {
        val withoutFilter = resultsOf(findNodeTool.findNode(name = "isEmpty", kindFilter = null))
        assertThat(withoutFilter.map { it["kind"] }).doesNotContain("java.method")

        val asMembers = resultsOf(findNodeTool.findNode(name = "isEmpty", kindFilter = listOf("java.method")))
        assertThat(asMembers).isNotEmpty
        assertThat(asMembers.map { it["kind"] }).allMatch { it == "java.method" }
    }

    @Test
    fun `the 'types' group alias matches every type kind and nothing else`() {
        val results = resultsOf(findNodeTool.findNode(name = "ApplicationContext", kindFilter = listOf("types")))

        val typeKinds = setOf("java.class", "java.interface", "java.enum", "java.record", "java.annotation")
        assertThat(results).isNotEmpty
        assertThat(results.map { it["kind"] }).isSubsetOf(typeKinds)
        // 'ApplicationContext' resolves to an interface plus several implementing classes,
        // so the alias must span more than a single concrete kind.
        assertThat(results.map { it["kind"] }).contains("java.interface", "java.class")
        assertThat(results.map { it["qualified_name"] })
            .contains("org.springframework.context.ApplicationContext")
    }

    @Test
    fun `an unknown kind filter yields an INVALID_KIND error`() {
        val response = findNodeTool.findNode(name = "StringUtils", kindFilter = listOf("java.bogus"))

        assertThat(response).doesNotContainKey("results")
        @Suppress("UNCHECKED_CAST")
        val error = response["error"] as Map<String, Any?>
        assertThat(error["code"]).isEqualTo("INVALID_KIND")
        assertThat(error["invalid_values"]).isEqualTo(listOf("java.bogus"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun resultsOf(response: Map<String, Any?>): List<Map<String, Any?>> =
        response["results"] as List<Map<String, Any?>>
}
