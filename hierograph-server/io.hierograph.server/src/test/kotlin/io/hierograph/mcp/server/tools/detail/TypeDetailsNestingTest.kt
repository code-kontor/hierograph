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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.mapping.GraphStoreNodeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies the nesting `type_details` derives from binary names ([NestedTypes]).
 */
class TypeDetailsNestingTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private val root = HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, "", "", "<root>") }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun add(parent: HGNode, kind: Any, name: String, fqn: String): HGNode =
        HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, fqn, fqn) }.also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    private val billing = add(root, JavaKinds.PACKAGE, "billing", "com.example.billing")
    private val invoice = add(billing, JavaKinds.CLASS, "Invoice", "com.example.billing.Invoice")
    private val line = add(billing, JavaKinds.CLASS, "Invoice\$Line", "com.example.billing.Invoice\$Line")
    private val status = add(billing, JavaKinds.ENUM, "Invoice\$Status", "com.example.billing.Invoice\$Status")
    private val lineTax = add(billing, JavaKinds.CLASS, "Invoice\$Line\$Tax", "com.example.billing.Invoice\$Line\$Tax")
    private val anonymous = add(billing, JavaKinds.CLASS, "Invoice\$1", "com.example.billing.Invoice\$1")
    private val local = add(billing, JavaKinds.CLASS, "Invoice\$1Draft", "com.example.billing.Invoice\$1Draft")
    private val generated = add(billing, JavaKinds.CLASS, "Ledger\$\$Proxy", "com.example.billing.Ledger\$\$Proxy")

    @Test
    fun `member types are the direct, named nested types`() {
        assertThat(NestedTypes.memberTypes(hierarchy, invoice)).containsExactly(line, status)
        assertThat(NestedTypes.memberTypes(hierarchy, line)).containsExactly(lineTax)
        assertThat(NestedTypes.memberTypes(hierarchy, status)).isEmpty()
    }

    @Test
    fun `the enclosing type is the sibling named up to the last dollar`() {
        assertThat(NestedTypes.enclosingType(hierarchy, line)).isSameAs(invoice)
        assertThat(NestedTypes.enclosingType(hierarchy, lineTax)).isSameAs(line)
        assertThat(NestedTypes.enclosingType(hierarchy, anonymous)).isSameAs(invoice)
        assertThat(NestedTypes.enclosingType(hierarchy, local)).isSameAs(invoice)
        assertThat(NestedTypes.enclosingType(hierarchy, invoice)).isNull()
        assertThat(NestedTypes.enclosingType(hierarchy, generated)).isNull()
    }
}
