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
package io.hierograph.itest.graphstore

import io.hierograph.boltclient.IBoltClientFactory
import io.hierograph.graphstore.jqa.JqaParquetExporter
import io.hierograph.graphstore.jqa.detail.JqaDetailExporter
import io.hierograph.itest.fwk.AbstractIntegrationTest
import io.hierograph.itest.fwk.HierographImageContainer
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.server.core.pagination.DataHashProvider
import io.hierograph.mcp.server.core.pagination.PaginationSpec
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.mcp.server.tools.detail.DetailDependenciesComponent
import io.hierograph.mcp.server.tools.detail.DetailDependenciesRouter
import io.hierograph.mcp.server.tools.detail.IDetailDependencies
import io.hierograph.mcp.server.tools.detail.ParquetDetailDependencies
import io.hierograph.mcp.server.tools.navigation.FindNodeTool
import io.hierograph.mcp.server.tools.navigation.GraphOverviewTool
import io.hierograph.mcp.server.tools.navigation.ListChildrenTool
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors

/**
 * End-to-end proof of the `hierograph.graph.source=parquet` switch: the [HierarchicalGraphService]
 * boots from a Parquet snapshot on disk (no Neo4j), and real MCP tools answer against the bridged model.
 *
 * To make the "no Neo4j" claim unambiguous, the Bolt URI is pointed at a dead address — the service's
 * best-effort connect fails and is swallowed, yet the graph still builds and the tools still work,
 * because in Parquet mode the model comes entirely from files via `ParquetReader` + `GraphStoreToHGModel`.
 *
 * The snapshot itself is produced in [parquetModeProperties] (a `@DynamicPropertySource`, which runs
 * *before* the Spring context — hence before the service's `@PostConstruct`): it exports the shared
 * jQAssistant test store to the directory the service is then told to read. The container is the only
 * thing that needs Bolt, and only to create the files — not to serve them.
 */
@SpringBootTest(
    classes = [
        HierarchicalGraphService::class,
        NodeRefFactory::class,
        GraphOverviewTool::class,
        FindNodeTool::class,
        ListChildrenTool::class,
        DataHashProvider::class,
        DetailDependenciesComponent::class,
        ParquetDetailDependencies::class,
        DetailDependenciesRouter::class,
    ]
)
class ParquetModeServerIT : AbstractIntegrationTest() {

    @Autowired
    private lateinit var graphService: HierarchicalGraphService

    @Autowired
    private lateinit var nodeRefFactory: NodeRefFactory

    @Autowired
    private lateinit var graphOverviewTool: GraphOverviewTool

    @Autowired
    private lateinit var findNodeTool: FindNodeTool

    @Autowired
    private lateinit var listChildrenTool: ListChildrenTool

    /** The @Primary router; in parquet mode it routes to the Parquet detail engine (no Neo4j). */
    @Autowired
    private lateinit var detailDependencies: IDetailDependencies

    @Test
    fun `serves a hierarchical graph from Parquet with no reachable Neo4j`() {
        // The graph was built in @PostConstruct from the Parquet files, with Bolt pointed at a dead port.
        assertThat(graphService.isGraphAvailable)
            .withFailMessage("graph should have been built from the Parquet snapshot at startup")
            .isTrue()
        val root = graphService.model.hierarchy.rootNode
        assertThat(graphService.model.hierarchy.childrenOf(root))
            .withFailMessage("the built graph should have top-level modules under the root")
            .isNotEmpty()

        // The Neo4j driver connects lazily, so `isConnected` is not a reliable signal. Prove it the hard
        // way: a real query against the configured Bolt endpoint fails because nothing is listening there.
        // Since the graph nonetheless exists, it could only have been built from the Parquet files.
        assertThatThrownBy { graphService.boltClient.syncExecCypherQuery("RETURN 1") }
            .withFailMessage("Bolt was pointed at a dead port; a query must fail, proving the graph came from Parquet")
            .isInstanceOf(Throwable::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `graph_overview tool answers against the Parquet-backed model`() {
        val overview = graphOverviewTool.graphOverview()

        val stats = overview["stats"] as Map<String, Any?>
        val totalNodes = stats["total_nodes"] as Int
        val totalEdges = stats["total_type_level_edges"] as Int
        val nodesByKind = stats["nodes_by_kind"] as Map<String, Int>
        val hierarchy = overview["hierarchy"] as List<Map<String, Any?>>

        assertThat(totalNodes).isPositive()
        // The bridge carries the type-to-type dependencies across as core edges, not just the hierarchy.
        assertThat(totalEdges).isPositive()
        // The bridge produces the full structural vocabulary: modules, packages and types.
        assertThat(nodesByKind.keys)
            .contains(JavaNodeKind.PACKAGE.value)
            .anyMatch { it in JavaKinds.TYPE_KINDS.map { kind -> kind.value } }
        assertThat(hierarchy)
            .withFailMessage("expected at least one top-level module under the root")
            .isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `find_node tool resolves a bridged node by name`() {
        // Pick a real structural node's name straight from the built index, then prove find_node finds it.
        val sample = graphService.searchIndex.entries.first { entry ->
            entry.nameLower.isNotBlank() &&
                    (entry.node.kind in JavaKinds.TYPE_KINDS ||
                            entry.node.kind == JavaNodeKind.PACKAGE ||
                            entry.node.kind == JavaNodeKind.MODULE)
        }

        val response = findNodeTool.findNode(sample.nameLower, null)
        val results = response["results"] as List<Map<String, Any?>>

        assertThat(results)
            .withFailMessage("find_node should resolve '%s' in the Parquet-backed model", sample.nameLower)
            .isNotEmpty()
        assertThat(results).anyMatch { ref ->
            (ref["name"] as? String ?: "").lowercase().contains(sample.nameLower)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `list_children tool resolves a content-addressed handle over Parquet`() {
        // Pick the richest top-level node so it is guaranteed to have children, and address it by the
        // handle the bridge minted — proving an id-taking tool resolves a node in Parquet mode.
        val (parentId, _) = richestTopLevel()

        val response = listChildrenTool.listChildren(parentId, null, null, null, null)

        assertThat(response).withFailMessage("list_children should resolve handle %s, not error", parentId)
            .doesNotContainKey("error")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results).isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `outgoing_dependencies (type level) runs over the Parquet-backed model`() {
        val (parentId, _) = richestTopLevel()

        // Build the tool directly: at type level it never touches the detail path, so the detail component
        // is a fail-fast stand-in. toId omitted = "all outgoing edges of the source", limit=1 = summary only.
        val tool = OutgoingDependenciesTool(
            graphService, nodeRefFactory, FailIfCalledDetail, DataHashProvider(graphService)
        )

        val response = tool.outgoingDependencies(parentId, null, "type", null, 1, null)

        assertThat(response).withFailMessage("outgoing_dependencies should resolve handle %s, not error", parentId)
            .doesNotContainKey("error")
        assertThat(response).containsKey("summary")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `detail_dependencies runs over the Parquet detail engine with no reachable Neo4j`() {
        // The @Primary router must be the Parquet-backed one in parquet mode.
        assertThat(detailDependencies).isInstanceOf(DetailDependenciesRouter::class.java)

        // Whole-graph scope (root -> root) so the member-level edge set is certainly non-empty on a real
        // scan. Served entirely from detail_refs.parquet; the dead Bolt port is never touched.
        val rootId = graphService.model.hierarchy.rootNode.identifier as Long
        val response = detailDependencies.detailDependencies(rootId, rootId, null, 5, null, DETAIL_SPEC)

        assertThat(response).withFailMessage("detail_dependencies should not error in parquet mode")
            .doesNotContainKey("error")
        val summary = response["summary"] as Map<String, Any?>
        assertThat(summary["total_edges"] as Int)
            .withFailMessage("the Parquet detail engine should find member-level edges in the scanned graph")
            .isPositive()
        val edges = response["edges"] as List<Map<String, Any?>>
        assertThat(edges).isNotEmpty()
        // every edge carries a relationship from the detail vocabulary and resolvable endpoint handles
        assertThat(edges).allSatisfy { edge ->
            assertThat(edge["relationship"] as String).isNotBlank()
            assertThat(edge["from"]).isInstanceOf(Long::class.javaObjectType)
            assertThat(edge["to"]).isInstanceOf(Long::class.javaObjectType)
        }

        // Prove the detail path used no Neo4j: the configured Bolt endpoint is dead.
        assertThatThrownBy { graphService.boltClient.syncExecCypherQuery("RETURN 1") }
            .isInstanceOf(Throwable::class.java)
    }

    /** The top-level node with the most children and its handle — a node that certainly has children. */
    private fun richestTopLevel(): Pair<Long, Int> {
        val hierarchy = graphService.model.hierarchy
        val node = hierarchy.childrenOf(hierarchy.rootNode)
            .maxByOrNull { hierarchy.childrenOf(it).size }!!
        return (node.identifier as Long) to hierarchy.childrenOf(node).size
    }

    /** Detail stand-in: type-level dependency queries must never reach it. */
    private object FailIfCalledDetail : IDetailDependencies {
        override fun detailDependencies(
            fromId: Long, toId: Long, relationship: String?, limit: Int?, cursor: String?, spec: PaginationSpec
        ): Map<String, Any?> = error("detail path must not be reached at detail_level=type")
    }

    companion object {

        /** Pagination policy for the detail call (mirrors the dependency tools' detail spec). */
        private val DETAIL_SPEC = PaginationSpec(tool = "outgoing_dependencies", defaultLimit = 80, maxLimit = 250, bytesPerItem = 550)

        @JvmStatic
        @DynamicPropertySource
        fun parquetModeProperties(registry: DynamicPropertyRegistry) {
            // Runs before the Spring context (hence before the service's @PostConstruct): make sure the
            // shared container is up, export its jQA store to a Parquet snapshot, and point the service at it.
            HierographImageContainer.instance.start()

            val parquetDir = Paths.get("target", "parquet-mode-it").toAbsolutePath()
            Files.createDirectories(parquetDir)
            exportSnapshot(parquetDir)

            registry.add("hierograph.graph.source") { "parquet" }
            registry.add("hierograph.graph.parquetDir") { parquetDir.toString() }
            registry.add("hierograph.graph.dumpFile") { "target/parquet-mode-it-graph.txt" }
            // Deliberately unreachable: proves the Parquet path needs no live database. The service's
            // best-effort connect fails and is logged, then the graph builds from files regardless.
            registry.add("hierograph.bolt.uri") { "bolt://localhost:1" }
        }

        /** Export the container's jQA store (type-level tables + member-level detail_refs) to [parquetDir]. */
        private fun exportSnapshot(parquetDir: java.nio.file.Path) {
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            val boltClient = IBoltClientFactory.newInstance(executor).createBoltClient(HierographImageContainer.boltUri)
            boltClient.connect()
            try {
                JqaParquetExporter(boltClient).exportTo(parquetDir)
                JqaDetailExporter(boltClient).exportTo(parquetDir)
            } finally {
                boltClient.disconnect()
                executor.shutdownNow()
            }
        }
    }
}
