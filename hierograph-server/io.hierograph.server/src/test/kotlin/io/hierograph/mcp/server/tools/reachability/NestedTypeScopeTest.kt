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

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.server.mapping.GraphStoreNodeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies that a type scope in the reachability tools includes its nested types, which the tree places
 * beside it in the package: `Invoice$Line` belongs to `Invoice`.
 */
@Suppress("UNCHECKED_CAST")
class NestedTypeScopeTest {

    private var nextId = 1L
    private val graph = HGGraphFactory.createHGGraph()
    private val root = HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, "", "", "<root>") }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun add(parent: HGNode, kind: Any, name: String): HGNode =
        HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, "com.example.billing.$name", name) }.also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    private fun HGNode.dependsOn(target: HGNode) {
        HGGraphFactory.createCoreDependency(this, target, "USES") { DefaultDependencySource(nextId++) }.weight = 1
    }

    private val billing = add(root, JavaKinds.PACKAGE, "billing")
    private val invoice = add(billing, JavaKinds.CLASS, "Invoice")
    private val line = add(billing, JavaKinds.CLASS, "Invoice\$Line")
    private val report = add(billing, JavaKinds.CLASS, "Report")

    init {
        line.dependsOn(invoice)
        report.dependsOn(line)
    }

    private val graphService = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }

    @Test
    fun `affected_by does not report a type's own nested types`() {
        val tool = AffectedByTool(graphService, NodeRefFactory(graphService), DataHashProvider(graphService))
        val results = tool.affectedBy(invoice.identifier as Long, "incoming", null, null, null, null)["results"] as List<Map<String, Any?>>
        // Report uses Invoice$Line, which is part of Invoice: it is affected directly.
        assertThat(results.map { (it["node"] as Map<String, Any?>)["id"] to it["distance"] })
            .containsExactly(report.identifier to 1)
    }

    @Test
    fun `find_dependency_path reaches a type through its nested types`() {
        val tool = FindDependencyPathTool(graphService, NodeRefFactory(graphService))
        val paths = tool.findDependencyPath(report.identifier as Long, invoice.identifier as Long, null, null)["paths"] as List<Map<String, Any?>>
        assertThat(paths.map { it["to"] to it["length"] }).containsExactly(line.identifier to 1)
    }
}
