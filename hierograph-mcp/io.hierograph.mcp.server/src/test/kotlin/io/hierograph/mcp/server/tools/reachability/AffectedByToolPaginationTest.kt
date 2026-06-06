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
package io.hierograph.mcp.server.tools.reachability

import io.hierograph.hierarchicalgraph.core.model.CoreGraphFactory
import io.hierograph.hierarchicalgraph.core.model.CoreNode
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.server.core.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that [AffectedByTool] is wired to pagination: paging through a blast radius, the
 * next-cursor lifecycle, and the structured cursor errors surfacing through the tool.
 *
 * Fixture: a target class with five distinct classes depending on it — an incoming blast radius
 * of five types at distance 1.
 */
class AffectedByToolPaginationTest {

    private lateinit var tool: AffectedByTool
    private var targetId = 0L
    private val dependerIds = mutableSetOf<Long>()

    @BeforeEach
    fun setup() {
        var nextId = 1L
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val depSource = { DefaultDependencySource(identifier = nextId++) }

        val graph = CoreGraphFactory.createCoreGraph()
        val root = CoreGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)

        val pkg = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.PACKAGE }
        HierarchyFactory.addChild(hierarchy, root, pkg)
        val target = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
        HierarchyFactory.addChild(hierarchy, pkg, target)
        targetId = target.identifier as Long

        repeat(5) {
            val depender = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
            HierarchyFactory.addChild(hierarchy, pkg, depender)
            CoreGraphFactory.createCoreDependency(depender, target, "USES", depSource)
            dependerIds.add(depender.identifier as Long)
        }

        val model = HGModel(graph, hierarchy)
        val graphService = HierarchicalGraphService().also { it.model = model }
        val dataHashProvider = DataHashProvider(graphService).also { it.init() }
        tool = AffectedByTool(graphService, FakeNodeRefFactory(), dataHashProvider)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resultIds(resp: Map<String, Any?>): List<Long> =
        (resp["results"] as List<Map<String, Any?>>).map { (it["node"] as Map<String, Any?>)["id"] as Long }

    @Suppress("UNCHECKED_CAST")
    private fun summary(resp: Map<String, Any?>): Map<String, Any?> = resp["summary"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(resp: Map<String, Any?>): Any? = (resp["error"] as Map<String, Any?>)["code"]

    @Test
    fun `first page reports the true total and offers a next cursor`() {
        val resp = tool.affectedBy(targetId, "incoming", null, null, 2, null)
        assertThat(resultIds(resp)).hasSize(2)
        assertThat(summary(resp)["total"]).isEqualTo(5)
        assertThat(summary(resp)["returned"]).isEqualTo(2)
        assertThat(resp["next_cursor"]).isNotNull()
    }

    @Test
    fun `following the cursor enumerates every affected type exactly once`() {
        val seen = mutableListOf<Long>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = tool.affectedBy(targetId, "incoming", null, null, 2, cursor)
            seen.addAll(resultIds(resp))
            cursor = resp["next_cursor"] as String?
            pages++
        } while (cursor != null)

        assertThat(seen).containsExactlyInAnyOrderElementsOf(dependerIds)
        assertThat(seen).doesNotHaveDuplicates()
        assertThat(pages).isEqualTo(3) // 2 + 2 + 1
    }

    @Test
    fun `the last page omits next_cursor`() {
        var resp = tool.affectedBy(targetId, "incoming", null, null, 2, null)
        resp = tool.affectedBy(targetId, "incoming", null, null, 2, resp["next_cursor"] as String?)
        resp = tool.affectedBy(targetId, "incoming", null, null, 2, resp["next_cursor"] as String?)
        assertThat(resultIds(resp)).hasSize(1)
        assertThat(resp).doesNotContainKey("next_cursor")
    }

    @Test
    fun `a malformed cursor surfaces INVALID_CURSOR_FORMAT`() {
        val resp = tool.affectedBy(targetId, "incoming", null, null, 2, "!!nope!!")
        assertThat(errorCode(resp)).isEqualTo("INVALID_CURSOR_FORMAT")
    }

    @Test
    fun `changing direction mid-pagination surfaces STALE_CURSOR_QUERY`() {
        val first = tool.affectedBy(targetId, "incoming", null, null, 2, null)
        val cursor = first["next_cursor"] as String?
        val resp = tool.affectedBy(targetId, "outgoing", null, null, 2, cursor)
        assertThat(errorCode(resp)).isEqualTo("STALE_CURSOR_QUERY")
    }

    private class FakeNodeRefFactory : INodeRefFactory {
        override fun minimalNodeRef(node: CoreNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun enrichedNodeRef(node: CoreNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun primitiveRef(name: String) = linkedMapOf<String, Any?>("name" to name)
        override fun putSlimNode(
            nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?
        ) {
        }

        override fun putSlimNode(nodes: MutableMap<String, Any>, node: CoreNode) {}
        override fun countDescendantsByKind(node: CoreNode, kinds: Set<*>) = 0
        override fun countDescendants(node: CoreNode) = 0L
    }
}
