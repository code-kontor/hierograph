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

import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.server.core.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies the pagination + cost-class split of [PairwiseDependenciesTool]:
 *  - the global summary (cycles, SCCs, topological order, density) is computed over the whole node set
 *    and returned on the FIRST page only;
 *  - the edge list paginates, with `edge_sort` and `min_weight` shaping it without touching the summary
 *    analytics;
 *  - the standard structured cursor errors surface through the tool.
 *
 * Acyclic fixture (3 single-class packages, weighted package-level edges):
 *     pA → pB (weight 3),  pB → pC (weight 2),  pA → pC (weight 1)   // 3 edges, no cycle
 */
class PairwiseDependenciesPaginationTest {

    private lateinit var tool: PairwiseDependenciesTool
    private var pA = 0L
    private var pB = 0L
    private var pC = 0L

    @BeforeEach
    fun setup() {
        var nextId = 1L
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val depSource = { DefaultDependencySource(identifier = nextId++) }

        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)

        fun pkgWithClass(): Pair<HGNode, HGNode> {
            val pkg = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.PACKAGE }
            HierarchyFactory.addChild(hierarchy, root, pkg)
            val cls = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
            HierarchyFactory.addChild(hierarchy, pkg, cls)
            return pkg to cls
        }

        val (packageA, a) = pkgWithClass()
        val (packageB, b) = pkgWithClass()
        val (packageC, c) = pkgWithClass()
        pA = packageA.identifier as Long
        pB = packageB.identifier as Long
        pC = packageC.identifier as Long

        // weighted class-level core deps → package-level aggregated weights
        HGGraphFactory.createCoreDependency(a, b, "USES", depSource).also { it.weight = 3 }
        HGGraphFactory.createCoreDependency(b, c, "USES", depSource).also { it.weight = 2 }
        HGGraphFactory.createCoreDependency(a, c, "USES", depSource).also { it.weight = 1 }

        tool = build(HGModel(graph, hierarchy))
    }

    private fun build(model: HGModel): PairwiseDependenciesTool {
        val graphService = HierarchicalGraphService().also { it.model = model }
        val dataHashProvider = DataHashProvider(graphService).also { it.init() }
        return PairwiseDependenciesTool(graphService, FakeNodeRefFactory(), dataHashProvider)
    }

    // ── accessors ─────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun edges(resp: Map<String, Any?>): List<Map<String, Any?>> =
        resp["edges"] as List<Map<String, Any?>>

    private fun weights(resp: Map<String, Any?>): List<Int> = edges(resp).map { it["weight"] as Int }

    @Suppress("UNCHECKED_CAST")
    private fun summary(resp: Map<String, Any?>): Map<String, Any?> = resp["summary"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(resp: Map<String, Any?>): Any? = (resp["error"] as Map<String, Any?>)["code"]

    // ── first-page cost-class split ─────────────────────────────────────────

    @Test
    fun `first page carries the global summary, nodes map, and topological order`() {
        val resp = tool.pairwiseDependencies(listOf(pA, pB, pC), null, null, null, null, null)

        assertThat(resp).containsKeys("nodes", "summary", "edges")
        val s = summary(resp)
        assertThat(s["node_count"]).isEqualTo(3)
        assertThat(s["edge_count"]).isEqualTo(3)
        assertThat(s["returned_edge_count"]).isEqualTo(3)
        assertThat(s["has_cycles"]).isEqualTo(false)
        assertThat(s["density"]).isEqualTo(0.5) // 3 / (3*2)
        assertThat(s).containsKey("topological_order")
        @Suppress("UNCHECKED_CAST")
        assertThat(s["topological_order"] as List<Any?>).containsExactlyInAnyOrder(pA, pB, pC)
    }

    @Test
    fun `continuation page omits the summary and nodes map`() {
        val first = tool.pairwiseDependencies(listOf(pA, pB, pC), null, null, null, 2, null)
        assertThat(edges(first)).hasSize(2)
        assertThat(first["next_cursor"]).isNotNull()

        val second = tool.pairwiseDependencies(
            listOf(pA, pB, pC), null, null, null, 2, first["next_cursor"] as String?
        )
        assertThat(edges(second)).hasSize(1)
        assertThat(second).doesNotContainKey("summary")
        assertThat(second).doesNotContainKey("nodes")
        assertThat(second).doesNotContainKey("next_cursor") // last page
    }

    @Test
    fun `following the cursor enumerates every edge exactly once`() {
        val seen = mutableListOf<Pair<Long, Long>>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = tool.pairwiseDependencies(listOf(pA, pB, pC), null, null, null, 2, cursor)
            edges(resp).forEach { seen.add((it["from"] as Long) to (it["to"] as Long)) }
            cursor = resp["next_cursor"] as String?
            pages++
        } while (cursor != null)

        assertThat(seen).doesNotHaveDuplicates()
        assertThat(seen).containsExactlyInAnyOrder(pA to pB, pB to pC, pA to pC)
        assertThat(pages).isEqualTo(2) // 2 + 1
    }

    // ── edge_sort / min_weight shape the edge list, not the analytics ───────

    @Test
    fun `weight_desc orders edges heaviest first`() {
        val resp = tool.pairwiseDependencies(listOf(pA, pB, pC), null, "weight_desc", null, null, null)
        assertThat(weights(resp)).containsExactly(3, 2, 1)
    }

    @Test
    fun `min_weight filters the edge list but leaves the summary analytics intact`() {
        val resp = tool.pairwiseDependencies(listOf(pA, pB, pC), null, "weight_desc", 2, null, null)

        // edge list is filtered to weight >= 2
        assertThat(weights(resp)).containsExactly(3, 2)

        val s = summary(resp)
        assertThat(s["edge_count"]).isEqualTo(3)          // unfiltered count, drives density
        assertThat(s["returned_edge_count"]).isEqualTo(2) // post-filter total that paginates
        assertThat(s["density"]).isEqualTo(0.5)           // unchanged by min_weight
        assertThat(resp).doesNotContainKey("next_cursor") // both fit on one page
    }

    // ── cycle / SCC analysis over the whole set ─────────────────────────────

    @Test
    fun `a cycle is reported with has_cycles, an SCC, and no topological order`() {
        var nextId = 1L
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val depSource = { DefaultDependencySource(identifier = nextId++) }
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)

        fun pkgWithClass(): Pair<HGNode, HGNode> {
            val pkg = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.PACKAGE }
            HierarchyFactory.addChild(hierarchy, root, pkg)
            val cls = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
            HierarchyFactory.addChild(hierarchy, pkg, cls)
            return pkg to cls
        }
        val (packageX, x) = pkgWithClass()
        val (packageY, y) = pkgWithClass()
        HGGraphFactory.createCoreDependency(x, y, "USES", depSource)
        HGGraphFactory.createCoreDependency(y, x, "USES", depSource) // back-edge → cycle
        val cyclicTool = build(HGModel(graph, hierarchy))

        val resp = cyclicTool.pairwiseDependencies(
            listOf(packageX.identifier as Long, packageY.identifier as Long), null, null, null, null, null
        )

        val s = summary(resp)
        assertThat(s["has_cycles"]).isEqualTo(true)
        assertThat(s).doesNotContainKey("topological_order")
        @Suppress("UNCHECKED_CAST")
        val sccs = s["strongly_connected_components"] as List<List<Any?>>
        assertThat(sccs).hasSize(1)
        assertThat(sccs[0]).containsExactlyInAnyOrder(packageX.identifier, packageY.identifier)
    }

    // ── cursor errors + input validation ────────────────────────────────────

    @Test
    fun `changing min_weight mid-pagination surfaces STALE_CURSOR_QUERY`() {
        val first = tool.pairwiseDependencies(listOf(pA, pB, pC), null, null, null, 2, null)
        val cursor = first["next_cursor"] as String?
        // same node set, different min_weight → different query identity
        val resp = tool.pairwiseDependencies(listOf(pA, pB, pC), null, null, 2, 2, cursor)
        assertThat(errorCode(resp)).isEqualTo("STALE_CURSOR_QUERY")
    }

    @Test
    fun `a malformed cursor surfaces INVALID_CURSOR_FORMAT`() {
        val resp = tool.pairwiseDependencies(listOf(pA, pB, pC), null, null, null, 2, "!!nope!!")
        assertThat(errorCode(resp)).isEqualTo("INVALID_CURSOR_FORMAT")
    }

    @Test
    fun `fewer than two nodes is rejected before any node lookup`() {
        val resp = tool.pairwiseDependencies(listOf(pA), null, null, null, null, null)
        assertThat(errorCode(resp)).isEqualTo("INPUT_TOO_SMALL")
    }

    @Test
    fun `exceeding the soft node cap is rejected before any node lookup`() {
        // size check precedes resolution, so non-existent ids are fine here
        val resp = tool.pairwiseDependencies((1L..1001L).toList(), null, null, null, null, null)
        assertThat(errorCode(resp)).isEqualTo("INPUT_TOO_LARGE")
        @Suppress("UNCHECKED_CAST")
        assertThat((resp["error"] as Map<String, Any?>)["max_nodes"]).isEqualTo(1000)
    }

    // ── test double ─────────────────────────────────────────────────────────

    private class FakeNodeRefFactory : INodeRefFactory {
        override fun minimalNodeRef(node: HGNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun enrichedNodeRef(node: HGNode) = linkedMapOf<String, Any?>("id" to node.identifier)
        override fun primitiveRef(name: String) = linkedMapOf<String, Any?>("name" to name)
        override fun putSlimNode(
            nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?
        ) {
            nodes[id.toString()] = mapOf("id" to id)
        }

        override fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode) {
            nodes[node.identifier.toString()] = mapOf("id" to (node.identifier as Any))
        }

        override fun countDescendantsByKind(node: HGNode, kinds: Set<*>) = 0
        override fun countDescendants(node: HGNode) = 0L
    }
}
