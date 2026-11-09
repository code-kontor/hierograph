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
package io.hierograph.server.graphql

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.graphql.controller.DependencySetController
import io.hierograph.server.graphql.controller.HierarchicalGraphController
import io.hierograph.server.graphql.controller.NodeController
import io.hierograph.server.graphql.controller.NodeSetController
import io.hierograph.server.graphql.model.DependencySetModel
import io.hierograph.server.graphql.model.NodeSetModel
import io.hierograph.server.graphql.model.NodeType
import io.hierograph.server.graphql.model.NodesToConsider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UnknownIdsAndReferencesTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val root = node()
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
    private fun child(parent: HGNode): HGNode = node().also { HierarchyFactory.addChild(hierarchy, parent, it) }
    private fun dep(from: HGNode, to: HGNode) =
        HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource("${from.identifier}_${to.identifier}") }

    // root ─ billingCore ─┬─ billing ─┬─ invoice      invoice → moneyType, ledger → moneyType,
    //                     │           └─ ledger       ledger → invoice (internal to billing)
    //                     └─ money ───── moneyType
    private val billingCore = child(root)
    private val billing = child(billingCore)
    private val invoice = child(billing)
    private val ledger = child(billing)
    private val money = child(billingCore)
    private val moneyType = child(money)
    private val invoiceToMoney = dep(invoice, moneyType)
    private val ledgerToMoney = dep(ledger, moneyType)
    private val ledgerToInvoice = dep(ledger, invoice)

    private val provider = HierarchicalGraphProvider { hierarchy }
    private fun id(node: HGNode) = node.identifier.toString()

    // ── 1.4 unknown ids are reported ──────────────────────────────────────

    @Test
    fun `every field reports the ids that name nothing, and answers without them`() {
        val graphs = HierarchicalGraphController()
        val nodes = NodeController(provider)

        val (single, r1) = UnknownIds.collecting { graphs.node(hierarchy, "424242") }
        assertThat(single).isNull()
        assertThat(r1.nodeIds).containsExactly("424242")

        val (set, r2) = UnknownIds.collecting { graphs.nodes(hierarchy, listOf(id(invoice), "424242", "434343")) }
        assertThat(set!!.nodeList).containsExactly(invoice)
        assertThat(r2.nodeIds).containsExactly("424242", "434343")

        val (deps, r3) = UnknownIds.collecting { nodes.dependenciesTo(billing, listOf(id(money), "424242")) }
        assertThat(deps).containsExactly(invoiceToMoney, ledgerToMoney)
        assertThat(r3.nodeIds).containsExactly("424242")

        val unknownDependency = "${id(money)}_${id(billing)}_DEPENDS_ON"
        val (byId, r4) = UnknownIds.collecting {
            graphs.dependencies(hierarchy, listOf(HierarchicalGraphController.coreDependencyId(ledgerToInvoice), unknownDependency))
        }
        assertThat(byId!!.dependencyList).containsExactly(ledgerToInvoice)
        assertThat(r4.dependencyIds).containsExactly(unknownDependency)

        val (children, r5) = UnknownIds.collecting {
            DependencySetController(provider).filteredChildren(DependencySetModel(listOf(invoiceToMoney)), "424242", NodeType.SOURCE)
        }
        assertThat(children).isEmpty()
        assertThat(r5.nodeIds).containsExactly("424242")
    }

    @Test
    fun `known ids and empty results report nothing`() {
        val (cell, report) = UnknownIds.collecting {
            HierarchicalGraphController().dependencySetForAggregatedDependency(hierarchy, id(money), id(billing))
        }
        assertThat(cell).isNull() // no dependency connects the subtrees: an empty answer, not a stale id
        assertThat(report.isEmpty).isTrue()
    }

    // ── 2.4 referenced nodes leave out the receiver's own subtree ─────────

    @Test
    fun `referenced and referencing nodes leave out the receiver's own subtree`() {
        val nodes = NodeController(provider)
        assertThat(nodes.referencedNodes(billing, false).nodeList).containsExactly(moneyType)
        assertThat(nodes.referencedNodes(ledger, false).nodeList).containsExactly(moneyType, invoice)
        assertThat(nodes.referencingNodes(billing, false).nodeList).isEmpty()
        assertThat(nodes.referencingNodes(invoice, false).nodeList).containsExactly(ledger)
        assertThat(nodes.filterReferencedNodes(billing, listOf(id(billingCore)), NodesToConsider.SELF_AND_SUCCESSORS, false).nodeList)
            .containsExactly(moneyType)

        // in a set, a member's reference to another member counts
        val members = NodeSetModel(listOf(invoice, ledger))
        assertThat(NodeSetController(provider).referencedNodes(members, false).nodeList).containsExactly(moneyType, invoice)
    }

    // ── 5.x the schema ────────────────────────────────────────────────────

    @Test
    fun `the schema files parse together and use one predecessor argument name`() {
        val registry = TypeDefinitionRegistry()
        for (file in listOf("hierograph.graphqls", "hierograph-hierarchicalgraph.graphqls")) {
            val text = javaClass.classLoader.getResource("graphql/$file")!!.readText()
            assertThat(text).doesNotContain("includedPredecessors").doesNotContain("InResult").doesNotContain("globalIdentifier")
            registry.merge(SchemaParser().parse(text))
        }
        assertThat(registry.getType("HierarchicalGraph")).isPresent
    }
}
