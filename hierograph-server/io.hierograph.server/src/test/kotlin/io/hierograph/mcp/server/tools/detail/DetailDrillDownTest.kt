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
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies the drill-down behind `by_source_nodes` / `by_target_nodes`: it descends through
 * single-child containers, stops at types, and never weighs members.
 */
class DetailDrillDownTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun add(parent: HGNode, kind: Any): HGNode =
        HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }.also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    // billing-core ─ com ─ example ─┬─ billing ─┬─ Invoice ─ total(), ledger
    //                              │           ├─ Invoice$Line
    //                              │           └─ Ledger
    //                              └─ money ─── Money ─ plus()
    private val module = add(root, JavaKinds.MODULE)
    private val com = add(module, JavaKinds.PACKAGE)
    private val example = add(com, JavaKinds.PACKAGE)
    private val billing = add(example, JavaKinds.PACKAGE)
    private val money = add(example, JavaKinds.PACKAGE)
    private val invoice = add(billing, JavaKinds.CLASS).also { add(it, JavaKinds.METHOD); add(it, JavaKinds.FIELD) }
    private val line = add(billing, JavaKinds.CLASS)
    private val ledger = add(billing, JavaKinds.INTERFACE)
    private val moneyType = add(money, JavaKinds.RECORD).also { add(it, JavaKinds.METHOD) }

    private val weights = mapOf(billing to 6, money to 4, invoice to 3, line to 2, ledger to 0, moneyType to 4)
    private fun weight(n: HGNode) = weights[n] ?: error("weighed a node that is not a package or type: ${n.kind}")

    private fun drill(node: HGNode) =
        ParquetDetailDependencies.drillDown(hierarchy, node, ::weight).map { it["node"] to it["aggregated_weight"] }

    @Test
    fun `descends through single-child packages to the first branching level`() {
        assertThat(drill(module)).containsExactly(billing.identifier to 6, money.identifier to 4)
    }

    @Test
    fun `lists the types of a package, highest weight first, without zero weights`() {
        assertThat(drill(billing)).containsExactly(invoice.identifier to 3, line.identifier to 2)
    }

    @Test
    fun `a package holding a single type lists that type`() {
        assertThat(drill(money)).containsExactly(moneyType.identifier to 4)
    }

    @Test
    fun `a type scope has no narrower type-level scope`() {
        assertThat(drill(invoice)).isEmpty()
    }
}
