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

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies `source_count` of [AffectedByTool]: the number of input-subtree types that reach a result
 * within `maxDepth`, at every distance.
 *
 * Fixture (arrows are dependencies): the input package `money` holds `Money` and `Currency`.
 *
 * ```
 * Invoice ──▶ Money ◀── Ledger ◀── Report
 *    │                    │
 *    └──▶ Currency ◀──────┘
 * Audit ──▶ Invoice      Rates ──▶ Currency
 * ```
 */
class AffectedBySourceCountTest {

    private var nextId = 1L
    private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
    private val depSource = { DefaultDependencySource(identifier = nextId++) }
    private val graph = HGGraphFactory.createHGGraph()
    private val root = HGGraphFactory.createNode(graph, nodeSource)
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun node(parent: HGNode, kind: JavaNodeKind) =
        HGGraphFactory.createNode(graph, nodeSource).also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    private fun HGNode.dependsOn(target: HGNode) {
        HGGraphFactory.createCoreDependency(this, target, "USES", depSource)
    }

    private val money = node(root, JavaNodeKind.PACKAGE)
    private val billing = node(root, JavaNodeKind.PACKAGE)
    private val moneyType = node(money, JavaNodeKind.RECORD)
    private val currency = node(money, JavaNodeKind.CLASS)
    private val invoice = node(billing, JavaNodeKind.CLASS)
    private val ledger = node(billing, JavaNodeKind.INTERFACE)
    private val report = node(billing, JavaNodeKind.CLASS)
    private val audit = node(billing, JavaNodeKind.CLASS)
    private val rates = node(billing, JavaNodeKind.CLASS)

    init {
        invoice.dependsOn(moneyType); invoice.dependsOn(currency)
        ledger.dependsOn(moneyType); ledger.dependsOn(currency)
        report.dependsOn(ledger)
        audit.dependsOn(invoice)
        rates.dependsOn(currency)
    }

    private val tool = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
        .let { AffectedByTool(it, IdNodeRefFactory(), DataHashProvider(it)) }

    @Suppress("UNCHECKED_CAST")
    private fun sourceCounts(nodeId: Any, maxDepth: Int? = null): Map<Any?, Any?> =
        (tool.affectedBy(nodeId as Long, "incoming", maxDepth, null, null, null)["results"] as List<Map<String, Any?>>)
            .associate { (it["node"] as Map<String, Any?>)["id"] to it["source_count"] }

    @Test
    fun `counts the distinct input types that reach each result, at every distance`() {
        assertThat(sourceCounts(money.identifier)).isEqualTo(
            mapOf(
                invoice.identifier to 2, ledger.identifier to 2, rates.identifier to 1,
                report.identifier to 2, audit.identifier to 2,
            )
        )
    }

    @Test
    fun `counts only sources within maxDepth`() {
        assertThat(sourceCounts(money.identifier, maxDepth = 1)).isEqualTo(
            mapOf(invoice.identifier to 2, ledger.identifier to 2, rates.identifier to 1)
        )
    }

    @Test
    fun `a single input type counts 1 everywhere`() {
        assertThat(sourceCounts(currency.identifier).values).containsOnly(1)
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
