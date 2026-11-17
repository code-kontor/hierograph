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

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * For a cyclic node set, `pairwise_dependencies` returns the order it renders in, the back edges that close the
 * cycles, the 2-cycles, and how good the order is (#15).
 */
@Suppress("UNCHECKED_CAST")
class PairwiseCycleAnalysisTest {

    /** Packages, each holding one class, with weighted dependencies between the classes. */
    private class Fixture {
        private var nextId = 1L
        private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        private val depSource = { DefaultDependencySource(identifier = nextId++) }
        private val graph = HGGraphFactory.createHGGraph()
        private val root = HGGraphFactory.createNode(graph, nodeSource)
        private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private val classOf = HashMap<Long, HGNode>()

        fun pkg(): Long {
            val pkg = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.PACKAGE }
            HierarchyFactory.addChild(hierarchy, root, pkg)
            val cls = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
            HierarchyFactory.addChild(hierarchy, pkg, cls)
            return (pkg.identifier as Long).also { classOf[it] = cls }
        }

        fun dep(from: Long, to: Long, weight: Int) {
            HGGraphFactory.createCoreDependency(classOf.getValue(from), classOf.getValue(to), "USES", depSource)
                .also { it.weight = weight }
        }

        fun tool(): PairwiseDependenciesTool {
            val graphService = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
            return PairwiseDependenciesTool(graphService, NodeRefFactory(graphService), DataHashProvider(graphService))
        }
    }

    private fun summaryOf(response: Map<String, Any?>) = response["summary"] as Map<String, Any?>

    private fun edge(from: Long, to: Long, weight: Int, reverse: Int) =
        mapOf("from" to from, "to" to to, "weight" to weight, "reverse_weight" to reverse)

    @Test
    fun `seven of twelve packages tangled by 2-cycles - the order, its back edges, and the cuts that untangle it`() {
        val f = Fixture()
        val above = List(3) { f.pkg() }
        val tangle = List(7) { f.pkg() }
        val below = List(2) { f.pkg() }
        above.zipWithNext().forEach { (a, b) -> f.dep(a, b, 10) }
        f.dep(above.last(), tangle.first(), 10)
        // A chain of 2-cycles: heavy forward, light backward (1 to 6).
        for (i in 0 until 6) {
            f.dep(tangle[i], tangle[i + 1], 100)
            f.dep(tangle[i + 1], tangle[i], i + 1)
        }
        f.dep(tangle.last(), below.first(), 10)
        f.dep(below.first(), below.last(), 10)
        val ids = (below + tangle.reversed() + above).shuffled(java.util.Random(3))

        val response = f.tool().pairwiseDependencies(ids, null, null, 500, null)
        val summary = summaryOf(response)

        assertThat(summary["has_cycles"]).isEqualTo(true)
        assertThat(summary).doesNotContainKey("topological_order")
        assertThat(summary["node_order"]).isEqualTo(above + tangle + below)
        assertThat(summary["strongly_connected_components"]).isEqualTo(listOf(tangle))
        assertThat(summary["back_edge_count"]).isEqualTo(6)
        assertThat(summary["back_edge_weight"]).isEqualTo(21L)
        assertThat(summary["back_edges"] as List<*>).first().isEqualTo(edge(tangle[6], tangle[5], 6, 100))
        assertThat(summary["back_edges_truncated"]).isEqualTo(false)
        assertThat(summary["mutual_dependency_count"]).isEqualTo(6)
        assertThat(summary["mutual_dependencies"] as List<*>).first().isEqualTo(edge(tangle[0], tangle[1], 100, 1))
        assertThat(summary["all_cycles_pairwise"]).isEqualTo(true)
        assertThat(summary["order_quality"]).isEqualTo("exact")

        val backInList = (response["edges"] as List<Map<String, Any?>>).filter { it["back"] == true }
        assertThat(backInList.map { it["from"] to it["to"] }).containsExactlyInAnyOrderElementsOf(
            (0 until 6).map { tangle[it + 1] to tangle[it] },
        )
    }

    @Test
    fun `an acyclic set has node_order and topological_order, and no back edges`() {
        val f = Fixture()
        val (a, b, c) = List(3) { f.pkg() }
        f.dep(a, b, 3); f.dep(b, c, 2)

        val response = f.tool().pairwiseDependencies(listOf(c, a, b), null, null, null, null)
        val summary = summaryOf(response)

        assertThat(summary["node_order"]).isEqualTo(listOf(a, b, c))
        assertThat(summary["topological_order"]).isEqualTo(listOf(a, b, c))
        assertThat(summary).doesNotContainKeys("back_edges", "order_quality", "all_cycles_pairwise")
        assertThat((response["edges"] as List<Map<String, Any?>>).none { it.containsKey("back") }).isTrue()
    }

    @Test
    fun `a ring of three is not pairwise - its lightest edge is the back edge`() {
        val f = Fixture()
        val (x, y, z) = List(3) { f.pkg() }
        f.dep(x, y, 5); f.dep(y, z, 4); f.dep(z, x, 1)

        val summary = summaryOf(f.tool().pairwiseDependencies(listOf(x, y, z), null, null, null, null))

        assertThat(summary["node_order"]).isEqualTo(listOf(x, y, z))
        assertThat(summary["back_edges"]).isEqualTo(listOf(edge(z, x, 1, 0)))
        assertThat(summary["mutual_dependencies"] as List<*>).isEmpty()
        assertThat(summary["all_cycles_pairwise"]).isEqualTo(false)
        assertThat(summary["order_quality"]).isEqualTo("exact")
    }

    @Test
    fun `a large ring gets a heuristic order`() {
        val f = Fixture()
        val ring = List(20) { f.pkg() }
        for (i in ring.indices) f.dep(ring[i], ring[(i + 1) % ring.size], 1)

        val summary = summaryOf(f.tool().pairwiseDependencies(ring, null, null, null, null))

        assertThat(summary["back_edge_count"]).isEqualTo(1)
        assertThat(summary["order_quality"]).isEqualTo("heuristic")
    }

    @Test
    fun `a large tangle of 2-cycles is proven exact, and its lists are capped`() {
        val f = Fixture()
        val chain = List(60) { f.pkg() }
        for (i in 0 until chain.size - 1) {
            f.dep(chain[i], chain[i + 1], 10)
            f.dep(chain[i + 1], chain[i], 1)
        }

        val summary = summaryOf(f.tool().pairwiseDependencies(chain, null, null, null, null))

        assertThat(summary["back_edge_count"]).isEqualTo(59)
        assertThat(summary["back_edge_weight"]).isEqualTo(59L)
        assertThat(summary["back_edges"] as List<*>).hasSize(50)
        assertThat(summary["back_edges_truncated"]).isEqualTo(true)
        assertThat(summary["mutual_dependencies"] as List<*>).hasSize(50)
        assertThat(summary["mutual_dependencies_truncated"]).isEqualTo(true)
        assertThat(summary["all_cycles_pairwise"]).isEqualTo(true)
        assertThat(summary["order_quality"]).isEqualTo("exact")
    }
}
