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
package io.hierograph.mcp.server.tools

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.MemberModifiers
import io.hierograph.graphstore.jvm.model.TypeModifiers
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.mapping.GraphStoreToHGModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies the enriched NodeRef: counts for containers and types, and the modifiers the GraphStore keeps
 * for types and members.
 */
class NodeRefFactoryTest {

    private val store = GraphStore().apply {
        registerArtifact(ArtifactInfo("billing-core", "billing-core", "billing-core"))
        applyClass(
            "billing-core",
            ClassFacts(
                "com.example.billing.Ledger", JavaKinds.CLASS, TypeModifiers.pack("public", abstract = true),
                members = listOf(
                    MemberFacts("com.example.billing.Ledger#void post()", JavaKinds.METHOD, "void post()",
                        modifiers = MemberModifiers.pack("public", abstract = true)),
                    MemberFacts("com.example.billing.Ledger#int SIZE", JavaKinds.FIELD, "int SIZE",
                        modifiers = MemberModifiers.pack("public", static = true, final = true)),
                ),
                refs = emptyList(), classHash = "h",
            ),
        )
    }
    private val graphService = HierarchicalGraphService().also { it.seed(GraphStoreToHGModel.build(store)) }
    private val factory = NodeRefFactory(graphService)

    private fun ref(key: String) = factory.enrichedNodeRef(graphService.model.lookupNode(NodeHandle.of(key))!!)

    @Test
    fun `a type carries its modifiers, is_abstract and member counts`() {
        val ref = ref(store.typeKey("billing-core", "com.example.billing.Ledger"))
        assertThat(ref["modifiers"]).isEqualTo(listOf("public", "abstract"))
        assertThat(ref["is_abstract"]).isEqualTo(true)
        assertThat(ref["member_count"]).isEqualTo(2)
    }

    @Test
    fun `methods and fields carry their modifiers`() {
        assertThat(ref(store.memberKey("billing-core", "com.example.billing.Ledger#void post()"))["modifiers"])
            .isEqualTo(listOf("public", "abstract"))
        assertThat(ref(store.memberKey("billing-core", "com.example.billing.Ledger#int SIZE"))["modifiers"])
            .isEqualTo(listOf("public", "static", "final"))
    }

    @Test
    fun `packages carry counts, not modifiers`() {
        val pkg = store.nodes().first { it.fqn == "com.example.billing" }
        val ref = ref(pkg.key)
        assertThat(ref).doesNotContainKey("modifiers")
        assertThat(ref["direct_type_count"]).isEqualTo(1)
    }
}
