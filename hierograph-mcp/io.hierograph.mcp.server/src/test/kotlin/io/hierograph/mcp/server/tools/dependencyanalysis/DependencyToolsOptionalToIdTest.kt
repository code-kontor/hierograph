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
 * Covers the optional `to_id` behavior on [OutgoingDependenciesTool] and
 * [IncomingDependenciesTool]:
 *
 * - type level, `to_id` provided  -> edges constrained to the to_id subtree
 * - type level, `to_id` omitted    -> ALL outgoing / incoming core dependencies
 * - detail level, `to_id` omitted   -> INVALID_PARAMETER (open form is type-level only)
 * - detail level, `to_id` provided  -> delegates to IDetailDependencies
 *
 * The fixture builds a tiny in-memory graph:
 *
 *   root
 *    +- pkgA            (package)
 *    |   +- A1          (class)   ->  B1, X
 *    |   |   +- m       (method)            (for INVALID_NODE_KIND)
 *    |   +- A2          (class)   ->  B1
 *    +- pkgB            (package)
 *    |   +- B1          (class)
 *    +- X               (class)            ("external" target outside pkgA/pkgB)
 */
class DependencyToolsOptionalToIdTest {

    private lateinit var outgoing: OutgoingDependenciesTool
    private lateinit var incoming: IncomingDependenciesTool
    private lateinit var detail: FakeDetail

    private var pkgAId = 0L
    private var pkgBId = 0L
    private var a1Id = 0L
    private var a2Id = 0L
    private var b1Id = 0L
    private var xId = 0L
    private var mId = 0L

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
        val a1 = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
        HierarchyFactory.addChild(hierarchy, pkgA, a1)
        val a2 = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
        HierarchyFactory.addChild(hierarchy, pkgA, a2)
        val b1 = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
        HierarchyFactory.addChild(hierarchy, pkgB, b1)
        val x = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
        HierarchyFactory.addChild(hierarchy, root, x)
        val m = CoreGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.METHOD }
        HierarchyFactory.addChild(hierarchy, a1, m)

        CoreGraphFactory.createCoreDependency(a1, b1, "USES", depSource)
        CoreGraphFactory.createCoreDependency(a1, x, "USES", depSource)
        CoreGraphFactory.createCoreDependency(a2, b1, "USES", depSource)

        pkgAId = pkgA.identifier as Long
        pkgBId = pkgB.identifier as Long
        a1Id = a1.identifier as Long
        a2Id = a2.identifier as Long
        b1Id = b1.identifier as Long
        xId = x.identifier as Long
        mId = m.identifier as Long

        val model = HGModel(graph, hierarchy)
        val graphService = HierarchicalGraphService().also { it.model = model }
        val dataHashProvider = DataHashProvider(graphService).also { it.init() }
        detail = FakeDetail()
        outgoing = OutgoingDependenciesTool(graphService, FakeNodeRefFactory(), detail, dataHashProvider)
        incoming = IncomingDependenciesTool(outgoing, detail)
    }

    // -- outgoing, type level ------------------------------------------------

    @Test
    fun `outgoing with to_id constrains to the target subtree`() {
        val result = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, null, null)
        assertThat(pairs(result)).containsExactlyInAnyOrder(a1Id to b1Id, a2Id to b1Id)
    }

    @Test
    fun `outgoing without to_id returns all outgoing dependencies`() {
        val result = outgoing.outgoingDependencies(pkgAId, null, null, null, null, null)
        assertThat(pairs(result)).containsExactlyInAnyOrder(
            a1Id to b1Id, a1Id to xId, a2Id to b1Id
        )
        assertThat(summary(result)["total"]).isEqualTo(3)
    }

    // -- incoming, type level ------------------------------------------------

    @Test
    fun `incoming with to_id constrains to the depender subtree`() {
        val result = incoming.incomingDependencies(b1Id, pkgAId, null, null, null, null)
        assertThat(pairs(result)).containsExactlyInAnyOrder(a1Id to b1Id, a2Id to b1Id)
    }

    @Test
    fun `incoming without to_id returns everything that depends on from_id`() {
        val result = incoming.incomingDependencies(b1Id, null, null, null, null, null)
        assertThat(pairs(result)).containsExactlyInAnyOrder(a1Id to b1Id, a2Id to b1Id)
    }

    @Test
    fun `incoming without to_id on an external-style target finds its single depender`() {
        val result = incoming.incomingDependencies(xId, null, null, null, null, null)
        assertThat(pairs(result)).containsExactly(a1Id to xId)
    }

    // -- by_target weighted rollup (open form only) --------------------------

    @Test
    fun `open outgoing form adds a by_target weighted ranking`() {
        val result = outgoing.outgoingDependencies(pkgAId, null, null, null, null, null)
        val byTarget = byTarget(result)
        // pkgA depends on B1 (from A1 and A2) and X (from A1). B1 is depended on
        // by two source types, so it should rank ahead of X.
        assertThat(byTarget.map { it["id"] }).containsExactly(b1Id, xId)
        val b1Weight = byTarget.first { it["id"] == b1Id }["weight"] as Int
        val xWeight = byTarget.first { it["id"] == xId }["weight"] as Int
        assertThat(b1Weight).isGreaterThan(xWeight)
    }

    @Test
    fun `open incoming form ranks the from_id types by incoming weight`() {
        val result = incoming.incomingDependencies(b1Id, null, null, null, null, null)
        val byTarget = byTarget(result)
        // incoming to B1: every edge targets B1, so by_target has a single entry.
        assertThat(byTarget).hasSize(1)
        assertThat(byTarget.first()["id"]).isEqualTo(b1Id)
    }

    @Test
    fun `constrained form also emits by_target, scoped to the target subtree`() {
        val result = outgoing.outgoingDependencies(pkgAId, pkgBId, null, null, null, null)
        val byTarget = byTarget(result)
        // Only B1 is in the target subtree; A1->B1 and A2->B1 each weigh 1 -> B1 = 2.
        assertThat(byTarget).hasSize(1)
        assertThat(byTarget.first()["id"]).isEqualTo(b1Id)
        assertThat(byTarget.first()["weight"]).isEqualTo(2)
    }

    // -- detail level requires to_id -----------------------------------------

    @Test
    fun `outgoing detail without to_id is rejected`() {
        val result = outgoing.outgoingDependencies(pkgAId, null, "detail", null, null, null)
        assertThat(errorCode(result)).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `incoming detail without to_id is rejected`() {
        val result = incoming.incomingDependencies(b1Id, null, "detail", null, null, null)
        assertThat(errorCode(result)).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `outgoing detail with to_id delegates to detail provider`() {
        val result = outgoing.outgoingDependencies(pkgAId, pkgBId, "detail", null, null, null)
        assertThat(result["detail"]).isEqualTo(true)
        assertThat(detail.lastFrom).isEqualTo(pkgAId)
        assertThat(detail.lastTo).isEqualTo(pkgBId)
    }

    @Test
    fun `incoming detail with to_id delegates with swapped endpoints`() {
        incoming.incomingDependencies(b1Id, pkgAId, "detail", null, null, null)
        // incoming swaps: detailDependencies(toId, fromId)
        assertThat(detail.lastFrom).isEqualTo(pkgAId)
        assertThat(detail.lastTo).isEqualTo(b1Id)
    }

    // -- validation ----------------------------------------------------------

    @Test
    fun `member id is rejected with INVALID_NODE_KIND`() {
        val result = outgoing.outgoingDependencies(mId, null, null, null, null, null)
        assertThat(errorCode(result)).isEqualTo("INVALID_NODE_KIND")
    }

    @Test
    fun `unknown from_id is rejected with NODE_NOT_FOUND`() {
        val result = outgoing.outgoingDependencies(999999L, null, null, null, null, null)
        assertThat(errorCode(result)).isEqualTo("NODE_NOT_FOUND")
    }

    @Test
    fun `unknown to_id is rejected with NODE_NOT_FOUND`() {
        val result = outgoing.outgoingDependencies(pkgAId, 999999L, null, null, null, null)
        assertThat(errorCode(result)).isEqualTo("NODE_NOT_FOUND")
    }

    // -- helpers -------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun edges(result: Map<String, Any?>): List<Map<String, Any?>> =
        result["edges"] as List<Map<String, Any?>>

    private fun pairs(result: Map<String, Any?>): Set<Pair<Any?, Any?>> =
        edges(result).map { it["from"] to it["to"] }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun summary(result: Map<String, Any?>): Map<String, Any?> =
        result["summary"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun byTarget(result: Map<String, Any?>): List<Map<String, Any?>> =
        summary(result)["by_target"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(result: Map<String, Any?>): Any? =
        (result["error"] as? Map<String, Any?>)?.get("code")

    // -- test doubles --------------------------------------------------------

    private class FakeDetail : IDetailDependencies {
        var lastFrom: Long? = null
        var lastTo: Long? = null
        var lastCursor: String? = null
        var lastSpec: PaginationSpec? = null
        override fun detailDependencies(
            fromId: Long,
            toId: Long,
            relationship: String?,
            limit: Int?,
            cursor: String?,
            spec: PaginationSpec
        ): Map<String, Any?> {
            lastFrom = fromId
            lastTo = toId
            lastCursor = cursor
            lastSpec = spec
            return mapOf("detail" to true, "from" to fromId, "to" to toId)
        }
    }

    private class FakeNodeRefFactory : INodeRefFactory {
        override fun minimalNodeRef(node: CoreNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun enrichedNodeRef(node: CoreNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun primitiveRef(name: String) = linkedMapOf<String, Any?>("name" to name)
        override fun putSlimNode(
            nodes: MutableMap<String, Any>,
            id: Long, name: String?, fqn: String?, kind: String?
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
