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
package io.hierograph.itest.mcptools.listdescendants

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.mcp.server.tools.navigation.ListDescendantsTool
import org.springframework.beans.factory.annotation.Autowired

/**
 * Shared fixture for the `list_descendants` MCP tool integration tests. Unlike `list_children`, this
 * tool walks the entire subtree (sorted by qualified name) and paginates large result sets.
 *
 * The concrete behaviour is split across themed subclasses, each exercising one facet of the tool:
 * - [ListDescendantsTraversalIT]  — full, unfiltered subtree listings
 * - [ListDescendantsFilteringIT]  — kind filters and name-pattern matching
 * - [ListDescendantsPaginationIT] — page limits and cursor traversal
 *
 * Target node IDs are resolved dynamically via the `find_node` tool
 * ([io.hierograph.mcp.server.tools.navigation.FindNodeTool]) rather than hard-coded, so the tests
 * stay valid across graph rebuilds. Booting the full MCP application (so the tool beans are
 * available) is inherited from [AbstractMcpApplicationIntegrationTest].
 */
abstract class AbstractListDescendantsIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired
    protected lateinit var listDescendantsTool: ListDescendantsTool

    /** Unfiltered, unpaginated descendants of [nodeId]. */
    protected fun listDescendants(nodeId: Long): Map<String, Any?> =
        listDescendantsTool.listDescendants(nodeId, null, null, null, null, null)

    protected fun orderedInterfaceId(): Long =
        resolveNodeId(name = "Ordered", qualifiedName = ORDERED_FQN, kind = "java.interface")

    protected fun tomcatPackageId(): Long =
        resolveNodeId(name = TOMCAT_PKG_FQN, qualifiedName = TOMCAT_PKG_FQN, kind = "java.package")

    protected fun classloadingPackageId(): Long =
        resolveNodeId(name = CLASSLOADING_PKG_FQN, qualifiedName = CLASSLOADING_PKG_FQN, kind = "java.package")

    /** Returns the IDs of every package node whose qualified name is exactly [fqn] (there may be more than one). */
    protected fun findPackageNodes(fqn: String): List<Long> {
        val response = findNodeTool.findNode(name = fqn, kindFilter = listOf("java.package"))
        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        return results.filter { it["qualified_name"] == fqn }.map { (it["id"] as Number).toLong() }
    }

    /**
     * Resolves the [fqn] package node with the largest subtree. The same package FQN can occur in
     * several modules; the `descendant_type_count` on each (enriched) find_node match identifies the
     * canonical, fully-populated one.
     */
    protected fun largestPackageNode(fqn: String): Long {
        val response = findNodeTool.findNode(name = fqn, kindFilter = listOf("java.package"))
        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        val canonical = results
            .filter { it["qualified_name"] == fqn }
            .maxByOrNull { (it["descendant_type_count"] as? Number)?.toLong() ?: 0L }
            ?: error("No package node found for '$fqn'")
        return (canonical["id"] as Number).toLong()
    }

    protected companion object {
        // org.springframework.core.Ordered is a small, stable interface: two int constants
        // (HIGHEST_PRECEDENCE, LOWEST_PRECEDENCE) and a single getOrder() method.
        const val ORDERED_FQN = "org.springframework.core.Ordered"

        // A small leaf package whose fully qualified name is unique across the graph.
        const val TOMCAT_PKG_FQN = "org.springframework.instrument.classloading.tomcat"

        // A package with a deeper, multi-level subtree (nested subpackages + types + members).
        const val CLASSLOADING_PKG_FQN = "org.springframework.instrument.classloading"

        // The kinds the 'types' group alias expands to.
        val TYPE_KINDS = listOf("java.class", "java.interface", "java.enum", "java.record", "java.annotation")
    }
}
