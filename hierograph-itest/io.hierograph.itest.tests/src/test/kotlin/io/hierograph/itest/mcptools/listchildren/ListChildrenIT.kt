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
package io.hierograph.itest.mcptools.listchildren

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.mcp.server.tools.navigation.ListChildrenTool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired

/**
 * Exercises the `list_children` MCP tool bean against the live graph. The target node ID is resolved
 * dynamically via the `find_node` tool ([io.hierograph.mcp.server.tools.navigation.FindNodeTool])
 * rather than hard-coded, so the test stays valid across graph rebuilds. Boots the full MCP
 * application so the tool beans are available —
 * see [io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest].
 */
class ListChildrenIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired
    private lateinit var listChildrenTool: ListChildrenTool

    // ── full (unfiltered) listings ─────────────────────────────────────────

    @Test
    fun `listing the children of an interface returns its declared members`() {
        val response = listChildren(orderedInterfaceId())

        @Suppress("UNCHECKED_CAST")
        val parent = response["parent"] as Map<String, Any?>
        assertThat(parent["qualified_name"]).isEqualTo(ORDERED_FQN)
        assertThat(parent["kind"]).isEqualTo("java.interface")

        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results.map { it["name"] })
            .containsExactlyInAnyOrder("int HIGHEST_PRECEDENCE", "int LOWEST_PRECEDENCE", "int getOrder()")

        @Suppress("UNCHECKED_CAST")
        val byKind = (response["summary"] as Map<String, Any?>)["by_kind"] as Map<String, Any?>
        assertThat(byKind).containsEntry("java.field", 2).containsEntry("java.method", 1)
    }

    @Test
    fun `listing the children of a package returns its types`() {
        val response = listChildren(tomcatPackageId())

        @Suppress("UNCHECKED_CAST")
        val parent = response["parent"] as Map<String, Any?>
        assertThat(parent["qualified_name"]).isEqualTo(TOMCAT_PKG_FQN)
        assertThat(parent["kind"]).isEqualTo("java.package")

        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results.map { it["qualified_name"] }).containsExactlyInAnyOrder(
            "$TOMCAT_PKG_FQN.TomcatLoadTimeWeaver",
            "$TOMCAT_PKG_FQN.package-info"
        )

        @Suppress("UNCHECKED_CAST")
        val byKind = (response["summary"] as Map<String, Any?>)["by_kind"] as Map<String, Any?>
        assertThat(byKind).containsEntry("java.class", 1).containsEntry("java.interface", 1)
    }

    @Test
    fun `listing the children of a module returns its top-level package`() {
        val response = listChildren(springContextModuleId())

        @Suppress("UNCHECKED_CAST")
        val parent = response["parent"] as Map<String, Any?>
        assertThat(parent["qualified_name"]).isEqualTo("spring-context-7.0.8.jar")
        assertThat(parent["kind"]).isEqualTo("java.module")

        // A Spring module's sources all sit under the single top-level `org` package.
        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results).hasSize(1)
        assertThat(results.single()["name"]).isEqualTo("org")
        assertThat(results.single()["kind"]).isEqualTo("java.package")
    }

    // ── kind_filter variations ─────────────────────────────────────────────

    @ParameterizedTest(name = "interface kind_filter [{0}] -> [{1}]")
    @CsvSource(
        value = [
            "java.field   | int HIGHEST_PRECEDENCE, int LOWEST_PRECEDENCE",
            "java.method  | int getOrder()",
            "members      | int HIGHEST_PRECEDENCE, int LOWEST_PRECEDENCE, int getOrder()",
            "types        |",
            "java.package |",
        ],
        delimiter = '|'
    )
    fun `kind_filter variations on an interface`(kind: String, expectedNames: String?) {
        assertThat(childNames(orderedInterfaceId(), listOf(kind.trim())))
            .containsExactlyInAnyOrderElementsOf(expected(expectedNames))
    }

    @ParameterizedTest(name = "package kind_filter [{0}] -> [{1}]")
    @CsvSource(
        value = [
            "types          | TomcatLoadTimeWeaver, package-info",
            "java.class     | TomcatLoadTimeWeaver",
            "java.interface | package-info",
            "members        |",
            "packages       |",
        ],
        delimiter = '|'
    )
    fun `kind_filter variations on a package`(kind: String, expectedNames: String?) {
        assertThat(childNames(tomcatPackageId(), listOf(kind.trim())))
            .containsExactlyInAnyOrderElementsOf(expected(expectedNames))
    }

    @ParameterizedTest(name = "module kind_filter [{0}] -> [{1}]")
    @CsvSource(
        value = [
            "packages     | org",
            "java.package | org",
            "java.class   |",
            "types        |",
        ],
        delimiter = '|'
    )
    fun `kind_filter variations on a module`(kind: String, expectedNames: String?) {
        assertThat(childNames(springContextModuleId(), listOf(kind.trim())))
            .containsExactlyInAnyOrderElementsOf(expected(expectedNames))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun listChildren(nodeId: Long): Map<String, Any?> =
        listChildrenTool.listChildren(nodeId, null, null, null, null)

    /** Returns the `name` of each child of [nodeId] under the given [kindFilter]. */
    private fun childNames(nodeId: Long, kindFilter: List<String>?): List<Any?> {
        val response = listChildrenTool.listChildren(nodeId, kindFilter, null, null, null)
        @Suppress("UNCHECKED_CAST")
        return (response["results"] as List<Map<String, Any?>>).map { it["name"] }
    }

    /** Splits the comma-joined CSV `expected` column into trimmed, non-blank child names. */
    private fun expected(names: String?): List<String> =
        names?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun orderedInterfaceId(): Long =
        resolveNodeId(name = "Ordered", qualifiedName = ORDERED_FQN, kind = "java.interface")

    private fun tomcatPackageId(): Long =
        resolveNodeId(name = TOMCAT_PKG_FQN, qualifiedName = TOMCAT_PKG_FQN, kind = "java.package")

    /**
     * Resolves the ID of the module containing the (findable) tomcat package by climbing the parent
     * chain. `find_node` does not index modules (they are the top level of the hierarchy), so we
     * start from a package it *can* find and walk up via each node's `parent` ref (returned by
     * `list_children`) until a `java.module` is reached.
     */
    private fun springContextModuleId(): Long {
        var ref = nodeRefOf(tomcatPackageId())
        while (ref["kind"] != "java.module") {
            ref = nodeRefOf((ref["parent_id"] as Number).toLong())
        }
        return (ref["id"] as Number).toLong()
    }

    /** Returns the enriched ref of [nodeId] (the `parent` field of its `list_children` response). */
    @Suppress("UNCHECKED_CAST")
    private fun nodeRefOf(nodeId: Long): Map<String, Any?> =
        listChildrenTool.listChildren(nodeId, null, null, null, null)["parent"] as Map<String, Any?>

    private companion object {
        // org.springframework.core.Ordered is a small, stable interface: two int constants
        // (HIGHEST_PRECEDENCE, LOWEST_PRECEDENCE) and a single getOrder() method.
        const val ORDERED_FQN = "org.springframework.core.Ordered"

        // A small leaf package whose fully qualified name is unique across the graph.
        const val TOMCAT_PKG_FQN = "org.springframework.instrument.classloading.tomcat"
    }
}
