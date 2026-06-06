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
package io.hierograph.mcp.server.tools.dependencyanalysis

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
import io.hierograph.mcp.server.core.pagination.PaginationSpec
import io.hierograph.mcp.server.tools.detail.IDetailDependencies
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies that [OutgoingDependenciesTool] / [IncomingDependenciesTool] are wired to pagination:
 * type-level paging through edges, the next-cursor lifecycle, the WRONG_TOOL guard between the two
 * directions, and that detail-level delegation forwards the cursor and the direction-specific spec.
 *
 * Fixture: pkgA contains classes A0..A4, each depending on its own target class T0..T4 under pkgB —
 * five type-level edges out of pkgA.
 */
class DependencyToolsPaginationTest {

    private lateinit var outgoing: OutgoingDependenciesTool
    private lateinit var incoming: IncomingDependenciesTool
    private lateinit var detail: FakeDetail

    private var pkgAId = 0L
    private var pkgBId = 0L
    private val expectedPairs = mutableSetOf<Pair<Long, Long>>()

    @BeforeEach
    fun setup() {
        var nextId = 1L
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val depSource = { DefaultDependencySource(identifier = nextId++) }

        val graph = CoreGraphFactory.createCoreGraph()
        val root = CoreGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)

        val pkgA = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.PACKAGE }
        HierarchyFactory.addChild(hierarchy, root, pkgA)
        val pkgB = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.PACKAGE }
        HierarchyFactory.addChild(hierarchy, root, pkgB)
        pkgAId = pkgA.identifier as Long
        pkgBId = pkgB.identifier as Long

        repeat(5) {
            val src = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
            HierarchyFactory.addChild(hierarchy, pkgA, src)
            val tgt = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
            HierarchyFactory.addChild(hierarchy, pkgB, tgt)
            CoreGraphFactory.createCoreDependency(src, tgt, "USES", depSource)
            expectedPairs.add((src.identifier as Long) to (tgt.identifier as Long))
        }

        val model = HGModel(graph, hierarchy)
        val graphService = HierarchicalGraphService().also { it.model = model }
        val dataHashProvider = DataHashProvider(graphService).also { it.init() }
        detail = FakeDetail()
        outgoing = OutgoingDependenciesTool(graphService, FakeNodeRefFactory(), detail, dataHashProvider)
        incoming = IncomingDependenciesTool(outgoing, detail)
    }

    @Suppress("UNCHECKED_CAST")
    private fun pairs(resp: Map<String, Any?>): List<Pair<Long, Long>> =
        (resp["edges"] as List<Map<String, Any?>>).map { (it["from"] as Long) to (it["to"] as Long) }

    @Suppress("UNCHECKED_CAST")
    private fun summary(resp: Map<String, Any?>): Map<String, Any?> = resp["summary"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(resp: Map<String, Any?>): Any? = (resp["error"] as Map<String, Any?>)["code"]

    @Test
    fun `type-level outgoing reports the total and offers a next cursor`() {
        val resp = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, 2, null)
        assertThat(pairs(resp)).hasSize(2)
        assertThat(summary(resp)["total"]).isEqualTo(5)
        assertThat(summary(resp)["returned"]).isEqualTo(2)
        assertThat(resp["next_cursor"]).isNotNull()
    }

    @Test
    fun `following the cursor enumerates every edge exactly once`() {
        val seen = mutableListOf<Pair<Long, Long>>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, 2, cursor)
            seen.addAll(pairs(resp))
            cursor = resp["next_cursor"] as String?
            pages++
        } while (cursor != null)

        assertThat(seen).containsExactlyInAnyOrderElementsOf(expectedPairs)
        assertThat(seen).doesNotHaveDuplicates()
        assertThat(pages).isEqualTo(3) // 2 + 2 + 1
    }

    @Test
    fun `the last page omits next_cursor`() {
        var resp = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, 2, null)
        resp = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, 2, resp["next_cursor"] as String?)
        resp = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, 2, resp["next_cursor"] as String?)
        assertThat(pairs(resp)).hasSize(1)
        assertThat(resp).doesNotContainKey("next_cursor")
    }

    @Test
    fun `an outgoing cursor used on incoming is rejected as WRONG_TOOL_CURSOR`() {
        val first = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, 2, null)
        val cursor = first["next_cursor"] as String?
        val resp = incoming.incomingDependencies(pkgAId, pkgBId, null, null, 2, cursor)
        assertThat(errorCode(resp)).isEqualTo("WRONG_TOOL_CURSOR")
    }

    @Test
    fun `a malformed cursor surfaces INVALID_CURSOR_FORMAT`() {
        val resp = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, 2, "!!nope!!")
        assertThat(errorCode(resp)).isEqualTo("INVALID_CURSOR_FORMAT")
    }

    @Test
    fun `outgoing detail delegation forwards the cursor and an outgoing-tool spec`() {
        outgoing.outgoingDependencies(pkgAId, pkgBId, "detail", null, 7, "the-cursor")
        assertThat(detail.lastCursor).isEqualTo("the-cursor")
        assertThat(detail.lastSpec?.tool).isEqualTo("outgoing_dependencies")
        assertThat(detail.lastFrom).isEqualTo(pkgAId)
        assertThat(detail.lastTo).isEqualTo(pkgBId)
    }

    @Test
    fun `incoming detail delegation swaps endpoints and forwards an incoming-tool spec`() {
        incoming.incomingDependencies(pkgAId, pkgBId, "detail", null, null, "c2")
        assertThat(detail.lastCursor).isEqualTo("c2")
        assertThat(detail.lastSpec?.tool).isEqualTo("incoming_dependencies")
        // incoming swaps: detailDependencies(toId, fromId)
        assertThat(detail.lastFrom).isEqualTo(pkgBId)
        assertThat(detail.lastTo).isEqualTo(pkgAId)
    }

    // ── test doubles ────────────────────────────────────────────────────

    private class FakeDetail : IDetailDependencies {
        var lastFrom: Long? = null
        var lastTo: Long? = null
        var lastCursor: String? = null
        var lastSpec: PaginationSpec? = null
        override fun detailDependencies(
            fromId: Long, toId: Long, relationship: String?, limit: Int?, cursor: String?, spec: PaginationSpec
        ): Map<String, Any?> {
            lastFrom = fromId
            lastTo = toId
            lastCursor = cursor
            lastSpec = spec
            return mapOf("detail" to true)
        }
    }

    private class FakeNodeRefFactory : INodeRefFactory {
        override fun minimalNodeRef(node: CoreNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun enrichedNodeRef(node: CoreNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun primitiveRef(name: String) = linkedMapOf<String, Any?>("name" to name)
        override fun putSlimNode(
            nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?
        ) {
            nodes[id.toString()] = mapOf("id" to id)
        }

        override fun putSlimNode(nodes: MutableMap<String, Any>, node: CoreNode) {
            nodes[node.identifier.toString()] = mapOf("id" to (node.identifier as Any))
        }

        override fun countDescendantsByKind(node: CoreNode, kinds: Set<*>) = 0
        override fun countDescendants(node: CoreNode) = 0L
    }
}
