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

import graphql.GraphQLContext
import graphql.Scalars
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.schema.DataFetchingEnvironmentImpl
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.graphql.controller.DependencySetController
import io.hierograph.server.graphql.controller.GraphUnavailableException
import io.hierograph.server.graphql.controller.GraphUnavailableExceptionResolver
import io.hierograph.server.graphql.controller.HierarchicalGraphController
import io.hierograph.server.graphql.controller.InvalidArgumentException
import io.hierograph.server.graphql.controller.NodeController
import io.hierograph.server.graphql.controller.NodeSetController
import io.hierograph.server.graphql.model.DependencySetModel
import io.hierograph.server.graphql.model.NodeSelection
import io.hierograph.server.graphql.model.NodeSetModel
import io.hierograph.server.graphql.model.NodeType
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.NodePropertiesProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RequestConsistencyAndErrorsTest {

    /** A small model: root ─ module ─ a, b, c, with a ⇄ b a cycle and c → a. */
    private class Example {
        val graph = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val root = node()
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun child(parent: HGNode) = node().also { HierarchyFactory.addChild(hierarchy, parent, it) }
        val module = child(root)
        val a = child(module)
        val b = child(module)
        val c = child(module)
        private fun dep(from: HGNode, to: HGNode, type: String = "DEPENDS_ON") =
            HGGraphFactory.createCoreDependency(from, to, type) { DefaultDependencySource("${from.identifier}_${to.identifier}_$type") }
        val ab = dep(a, b)
        val ba = dep(b, a)
        val ca = dep(c, a)
        val caOther = dep(c, a, "USES_SOME_THING")
        val model = HGModel(graph, hierarchy)
        val provider = HierarchicalGraphProvider { hierarchy }
    }

    private val example = Example()
    private fun id(node: HGNode) = node.identifier.toString()

    // ── 1.1 one graph per request ─────────────────────────────────────────

    @Test
    fun `a request reads the graph it started with, even after a reload`() {
        val first = Example()
        val second = Example()
        val service = HierarchicalGraphService().also { it.seed(first.model) }
        val pinning = GraphSnapshotPinning(service)
        val request = GraphQLContext.of(mapOf(GraphSnapshotPinning.SNAPSHOT_KEY to service.liveSnapshot()!!))

        service.seed(second.model) // a reload completes while the request runs

        assertThat(pinning.pinned(request) { service.model }).isSameAs(first.model)
        assertThat(service.model).isSameAs(second.model)
        assertThat(pinning.pinned(GraphQLContext.getDefault()) { service.model }).isSameAs(second.model)
    }

    @Test
    fun `the properties batch reads the request's graph`() {
        val first = Example()
        val service = HierarchicalGraphService().also { it.seed(first.model) }
        val pinning = GraphSnapshotPinning(service)
        val request = GraphQLContext.of(mapOf(GraphSnapshotPinning.SNAPSHOT_KEY to service.liveSnapshot()!!))
        service.seed(Example().model)

        val seen = mutableListOf<HGModel>()
        val properties = NodePropertiesProvider { nodes -> seen += service.model; nodes.associateWith { emptyMap() } }
        NodeController(first.provider, properties, pinning).properties(listOf(first.a), request)

        assertThat(seen).containsExactly(first.model)
    }

    // ── 1.2 no graph loaded ───────────────────────────────────────────────

    @Test
    fun `without a loaded graph the provider reports GRAPH_UNAVAILABLE`() {
        val provider = GraphQlConfiguration().hierarchicalGraphProvider(HierarchicalGraphService())
        assertThatThrownBy { provider.hierarchy() }.isInstanceOf(GraphUnavailableException::class.java)

        val env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field("hierarchicalGraph")).build())
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo().type(Scalars.GraphQLInt).path(ResultPath.rootPath()).build())
            .build()
        val error = GraphUnavailableExceptionResolver().resolveToSingleError(GraphUnavailableException(), env)!!
        assertThat(error.errorType).isSameAs(GraphUnavailableExceptionResolver.GRAPH_UNAVAILABLE)
        assertThat(error.toSpecification()["extensions"].toString()).contains("GRAPH_UNAVAILABLE")
        assertThat(error.message).contains("No hierarchical graph is available")
        assertThat(GraphUnavailableExceptionResolver().resolveToSingleError(IllegalStateException(), env)).isNull()
    }

    // ── 1.3 malformed ids ─────────────────────────────────────────────────

    @Test
    fun `an id that is not a handle is a BAD_REQUEST naming the id`() {
        val graphs = HierarchicalGraphController()
        assertThatThrownBy { graphs.node(example.hierarchy, "com.example.Invoice") }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("'com.example.Invoice'")
        assertThatThrownBy { graphs.nodes(example.hierarchy, listOf(id(example.a), "x")) }
            .isInstanceOf(InvalidArgumentException::class.java)
        assertThatThrownBy { NodeController(example.provider).dependenciesTo(example.a, listOf("x")) }
            .isInstanceOf(InvalidArgumentException::class.java)
        assertThatThrownBy { graphs.dependency(example.hierarchy, "1_2") }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("<source id>_<target id>_<type>")
        // a well-formed id that names nothing is not an error
        assertThat(graphs.node(example.hierarchy, "424242")).isNull()
    }

    // ── 2.3 unknown-only selections ───────────────────────────────────────

    @Test
    fun `a selection of only unknown nodes matches nothing, an empty selection restricts nothing`() {
        val set = DependencySetModel(listOf(example.ab, example.ba, example.ca))
        val controller = DependencySetController(example.provider)

        assertThat(controller.filteredDependencies(set, listOf(NodeSelection(listOf("424242"), NodeType.SOURCE))).dependencyList).isEmpty()
        assertThat(controller.filteredDependencies(set, listOf(NodeSelection(emptyList(), NodeType.SOURCE))).dependencyList)
            .containsExactly(example.ab, example.ba, example.ca)
    }

    // ── 3.1 cycle members and positions ───────────────────────────────────

    @Test
    fun `cycle members are listed at their positions`() {
        val matrix = NodeSetController(example.provider)
            .orderedAdjacencyMatrix(NodeSetModel(listOf(example.a, example.b, example.c)))

        val cycle = matrix.stronglyConnectedComponents.single()
        assertThat(cycle.nodes).containsExactlyInAnyOrder(example.a, example.b)
        assertThat(cycle.nodePositions).isSorted
        cycle.nodes.forEachIndexed { k, node -> assertThat(matrix.orderedNodes[cycle.nodePositions[k]]).isSameAs(node) }
    }

    // ── 4.1 dependency lookup by id ───────────────────────────────────────

    @Test
    fun `dependencies are found by id, including types that contain underscores`() {
        val graphs = HierarchicalGraphController()
        val ids = listOf(example.ca, example.caOther, example.ab).map { HierarchicalGraphController.coreDependencyId(it) }

        assertThat(graphs.dependency(example.hierarchy, ids[1])).isSameAs(example.caOther)
        assertThat(graphs.dependencies(example.hierarchy, ids + ids[0] + "${id(example.a)}_${id(example.c)}_DEPENDS_ON")!!.dependencyList)
            .containsExactly(example.ca, example.caOther, example.ab)
    }
}
