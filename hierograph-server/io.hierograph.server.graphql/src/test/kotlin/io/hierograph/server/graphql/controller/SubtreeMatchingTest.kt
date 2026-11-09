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
package io.hierograph.server.graphql.controller

import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.graphql.model.DependencySetModel
import io.hierograph.server.graphql.model.NodeSelection
import io.hierograph.server.graphql.model.NodeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Package and module ids match the dependencies of the types below them. Only types are endpoints, so
 * without subtree matching every one of these queries with a package or module id would come back empty.
 */
class SubtreeMatchingTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val root = node()
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
    private fun child(parent: HGNode): HGNode = node().also { HierarchyFactory.addChild(hierarchy, parent, it) }
    private fun dep(from: HGNode, to: HGNode, weight: Int): HGCoreDependency =
        HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource("${from.identifier}_${to.identifier}") }
            .also { it.weight = weight }

    // root ─ billingCore ─┬─ billing ─┬─ invoice
    //                     │           └─ ledger
    //                     └─ money ───── moneyType
    private val billingCore = child(root)
    private val billing = child(billingCore)
    private val invoice = child(billing)
    private val ledger = child(billing)
    private val money = child(billingCore)
    private val moneyType = child(money)

    private val invoiceToMoney = dep(invoice, moneyType, 3)
    private val ledgerToMoney = dep(ledger, moneyType, 1)
    private val ledgerToInvoice = dep(ledger, invoice, 2)

    private val provider = HierarchicalGraphProvider { hierarchy }
    private val nodes = NodeController(provider)
    private val dependencySets = DependencySetController(provider)

    private fun id(node: HGNode) = node.identifier.toString()

    @Test
    fun `dependenciesTo and dependenciesFrom match the subtrees of the given nodes`() {
        assertThat(nodes.dependenciesTo(billing, listOf(id(money)))).containsExactly(invoiceToMoney, ledgerToMoney)
        assertThat(nodes.dependenciesTo(billing, listOf(id(moneyType)))).containsExactly(invoiceToMoney, ledgerToMoney)
        assertThat(nodes.dependenciesFrom(money, listOf(id(billing)))).containsExactly(invoiceToMoney, ledgerToMoney)
        assertThat(nodes.dependenciesFrom(invoice, listOf(id(billingCore)))).containsExactly(ledgerToInvoice)
    }

    @Test
    fun `children are filtered by dependencies into or out of the subtrees of the given nodes`() {
        assertThat(nodes.childrenFilteredByReferencedNodes(billingCore, listOf(id(money))).nodeList).containsExactly(billing)
        assertThat(nodes.childrenFilteredByReferencedNodes(billing, listOf(id(money))).nodeList).containsExactly(invoice, ledger)
        assertThat(nodes.childrenFilteredByReferencingNodes(billingCore, listOf(id(ledger))).nodeList).containsExactly(billing, money)
    }

    @Test
    fun `filteredChildren returns the children that contain an endpoint`() {
        val billingToMoney = DependencySetModel(listOf(invoiceToMoney, ledgerToMoney))

        assertThat(dependencySets.filteredChildren(billingToMoney, id(billingCore), NodeType.SOURCE)).containsExactly(billing)
        assertThat(dependencySets.filteredChildren(billingToMoney, id(billingCore), NodeType.TARGET)).containsExactly(money)
        assertThat(dependencySets.filteredChildren(billingToMoney, id(billing), NodeType.SOURCE)).containsExactly(invoice, ledger)
        assertThat(dependencySets.filteredChildrenIds(billingToMoney, id(root), NodeType.TARGET)).containsExactly(id(billingCore))
        assertThat(dependencySets.filteredChildren(billingToMoney, id(money), NodeType.SOURCE)).isEmpty()
    }

    @Test
    fun `filteredDependencies matches the subtrees of the selected nodes`() {
        val all = DependencySetModel(listOf(invoiceToMoney, ledgerToMoney, ledgerToInvoice))

        fun filter(vararg selection: Pair<HGNode, NodeType>) = dependencySets
            .filteredDependencies(all, selection.map { (node, type) -> NodeSelection(listOf(id(node)), type) })
            .dependencyList

        assertThat(filter(money to NodeType.TARGET)).containsExactly(invoiceToMoney, ledgerToMoney)
        assertThat(filter(billing to NodeType.TARGET)).containsExactly(ledgerToInvoice)
        assertThat(filter(ledger to NodeType.SOURCE, billing to NodeType.TARGET)).containsExactly(ledgerToInvoice)
        assertThat(filter(billingCore to NodeType.SOURCE)).containsExactly(invoiceToMoney, ledgerToMoney, ledgerToInvoice)
    }
}
