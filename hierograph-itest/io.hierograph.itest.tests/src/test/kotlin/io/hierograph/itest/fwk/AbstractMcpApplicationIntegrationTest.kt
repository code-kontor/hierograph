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
package io.hierograph.itest.fwk

import io.hierograph.mcp.server.McpApplication
import io.hierograph.mcp.server.tools.navigation.FindNodeTool
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base for integration tests that need the *full* MCP application context — e.g. tests that
 * exercise MCP tool beans ([io.hierograph.mcp.server.tools]), which are component-scanned by
 * [McpApplication] and are not present in the graph-only slice
 * ([AbstractHierarchicalGraphIntegrationTest]).
 *
 * The hierarchical graph (via [io.hierograph.mcp.server.core.HierarchicalGraphService]) is also
 * built here, so the dump-file location is redirected into `target/` as well.
 */
@SpringBootTest(classes = [McpApplication::class])
abstract class AbstractMcpApplicationIntegrationTest : AbstractSpringIntegrationTest() {

    @Autowired
    protected lateinit var findNodeTool: FindNodeTool

    /**
     * Resolves a single node ID via the `find_node` tool, asserting that exactly the expected node
     * (matched on qualified name and kind) is present among the matches. Lets tests target a node
     * by name instead of hard-coding graph IDs that change across rebuilds.
     */
    protected fun resolveNodeId(name: String, qualifiedName: String, kind: String): Long {
        val response = findNodeTool.findNode(name = name, kindFilter = listOf(kind))

        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        val match = results.singleOrNull { it["qualified_name"] == qualifiedName }
            ?: error("Expected exactly one '$qualifiedName' ($kind) among find_node matches, got: " +
                    results.map { it["qualified_name"] })
        return (match["id"] as Number).toLong()
    }

    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun graphProperties(registry: DynamicPropertyRegistry) {
            registry.add("hierograph.graph.dumpFile") { "target/hierarchical-graph.txt" }
        }
    }
}
