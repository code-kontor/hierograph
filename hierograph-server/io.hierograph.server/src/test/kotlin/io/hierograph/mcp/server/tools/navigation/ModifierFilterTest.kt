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
package io.hierograph.mcp.server.tools.navigation

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.MemberModifiers
import io.hierograph.graphstore.jvm.model.Origin
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.server.mapping.GraphStoreToHGModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies `modifierFilter` on [ListChildrenTool] and [ListDescendantsTool] end to end: member modifiers
 * flow from the scanned [MemberFacts] through the GraphStore and the model into the filter.
 */
class ModifierFilterTest {

    private lateinit var children: ListChildrenTool
    private lateinit var descendants: ListDescendantsTool
    private lateinit var store: GraphStore

    private fun member(sig: String, kind: JavaNodeKind, bits: Int) =
        MemberFacts(key = "com.example.billing.Invoice#$sig", kind = kind, signature = sig, modifiers = bits)

    private fun id(sig: String): Long = NodeHandle.of(store.memberKey("billing-core", "com.example.billing.Invoice#$sig"))
    private val invoiceId get() = NodeHandle.of(store.typeKey("billing-core", "com.example.billing.Invoice"))
    private val packageId get() = store.nodes().first { it.fqn == "com.example.billing" }.key.let(NodeHandle::of)

    @BeforeEach
    fun setUp() {
        store = GraphStore()
        store.registerArtifact(ArtifactInfo("billing-core", "billing-core", "billing-core", Origin.MODULE_MAIN))
        store.applyClass(
            "billing-core",
            ClassFacts(
                "com.example.billing.Invoice", JavaKinds.CLASS, 0,
                members = listOf(
                    member("total()", JavaKinds.METHOD, MemberModifiers.pack("public")),
                    member("of()", JavaKinds.METHOD, MemberModifiers.pack("public", static = true)),
                    member("check()", JavaKinds.METHOD, MemberModifiers.pack("private")),
                    member("<init>()", JavaKinds.CONSTRUCTOR, MemberModifiers.pack("public")),
                    member("ZERO", JavaKinds.FIELD, MemberModifiers.pack("public", static = true, final = true)),
                    member("ledger", JavaKinds.FIELD, MemberModifiers.pack(null)),
                ),
                refs = emptyList(), classHash = "h",
            ),
        )
        val graphService = HierarchicalGraphService().also { it.seed(GraphStoreToHGModel.build(store)) }
        children = ListChildrenTool(graphService, IdNodeRefFactory())
        descendants = ListDescendantsTool(graphService, IdNodeRefFactory(), DataHashProvider(graphService))
    }

    @Suppress("UNCHECKED_CAST")
    private fun ids(resp: Map<String, Any?>): List<Any?> =
        (resp["results"] as List<Map<String, Any?>>).map { it["id"] }

    @Suppress("UNCHECKED_CAST")
    private fun error(resp: Map<String, Any?>): Map<String, Any?> = resp["error"] as Map<String, Any?>

    @Test
    fun `list_children keeps the members whose modifiers include every requested value`() {
        assertThat(ids(children.listChildren(invoiceId, null, null, listOf("public"), null)))
            .containsExactlyInAnyOrder(id("total()"), id("of()"), id("<init>()"), id("ZERO"))
        assertThat(ids(children.listChildren(invoiceId, null, null, listOf("static", "final"), null)))
            .containsExactly(id("ZERO"))
        assertThat(ids(children.listChildren(invoiceId, null, null, listOf("package-private"), null)))
            .containsExactly(id("ledger"))
    }

    @Test
    fun `values match case-insensitively and combine with the kind filter`() {
        assertThat(ids(children.listChildren(invoiceId, listOf("java.method"), null, listOf("PUBLIC", "Static"), null)))
            .containsExactly(id("of()"))
    }

    @Test
    fun `an empty filter is no filter`() {
        assertThat(ids(children.listChildren(invoiceId, null, null, emptyList(), null))).hasSize(6)
    }

    @Test
    fun `an active filter excludes packages and types`() {
        val resp = descendants.listDescendants(packageId, null, null, listOf("private"), null, null)
        assertThat(ids(resp)).containsExactly(id("check()"))
    }

    @Test
    fun `an unknown modifier is rejected with INVALID_PARAMETER`() {
        val err = error(children.listChildren(invoiceId, null, null, listOf("public", "pubic"), null))
        assertThat(err["code"]).isEqualTo("INVALID_PARAMETER")
        assertThat(err["message"] as String).contains("'pubic'")
        assertThat(error(descendants.listDescendants(invoiceId, null, null, listOf("sealed"), null, null))["code"])
            .isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `a reordered or recased filter keeps the list_descendants cursor valid`() {
        val first = descendants.listDescendants(packageId, null, null, listOf("public", "static"), 1, null)
        val cursor = first["next_cursor"] as String
        val next = descendants.listDescendants(packageId, null, null, listOf("Static", "public"), 1, cursor)
        assertThat(next["error"]).isNull()
        assertThat(ids(next)).hasSize(1)
    }

    private class IdNodeRefFactory : INodeRefFactory {
        override fun minimalNodeRef(node: HGNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun enrichedNodeRef(node: HGNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun primitiveRef(name: String) = linkedMapOf<String, Any?>("name" to name)
        override fun putSlimNode(nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?) {}
        override fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode) {}
        override fun countDescendantsByKind(node: HGNode, kinds: Set<*>) = 0
        override fun countDescendants(node: HGNode) = 0L
    }
}
