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

/** Verifies the parameter checks, orderings and edge cases of the reachability tools. */
@Suppress("UNCHECKED_CAST")
class ReachabilityValidationTest {

    private var nextId = 1L
    private val graph = HGGraphFactory.createHGGraph()
    private val root = HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, "", "", "<root>") }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun add(parent: HGNode, kind: Any, name: String): HGNode =
        HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, name, name) }.also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    private fun HGNode.dependsOn(target: HGNode) {
        HGGraphFactory.createCoreDependency(this, target, "USES") { DefaultDependencySource(nextId++) }.weight = 1
    }

    // Two modules with one package each; Money is used from both modules, A and B form a cycle.
    private val core = add(root, JavaNodeKind.MODULE, "core")
    private val app = add(root, JavaNodeKind.MODULE, "app")
    private val money = add(add(core, JavaNodeKind.PACKAGE, "money"), JavaNodeKind.CLASS, "Money")
    private val corePkg = add(core, JavaNodeKind.PACKAGE, "ledger")
    private val appPkg = add(app, JavaNodeKind.PACKAGE, "web")
    private val a = add(corePkg, JavaNodeKind.CLASS, "A")
    private val b = add(appPkg, JavaNodeKind.CLASS, "B")
    private val method = add(a, JavaNodeKind.METHOD, "void run()")

    init {
        a.dependsOn(money); b.dependsOn(money); a.dependsOn(b); b.dependsOn(a)
    }

    private val service = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
    private val affected = AffectedByTool(service, NodeRefFactory(service), DataHashProvider(service))
    private val paths = FindDependencyPathTool(service, NodeRefFactory(service))

    private fun code(resp: Map<String, Any?>) = (resp["error"] as Map<String, Any?>?)?.get("code")
    private fun id(n: HGNode) = n.identifier as Long

    @Test
    fun `affected_by rejects kinds that can never be affected`() {
        val resp = affected.affectedBy(id(money), "incoming", null, listOf("packages"), null, null)
        assertThat(code(resp)).isEqualTo("INVALID_KIND")
        assertThat((resp["error"] as Map<String, Any?>)["valid_aliases"]).isEqualTo(listOf("types"))
        assertThat(code(affected.affectedBy(id(money), "incoming", null, listOf("external.type"), null, null))).isNull()
    }

    @Test
    fun `depth and length must be at least 1`() {
        assertThat(code(affected.affectedBy(id(money), "incoming", 0, null, null, null))).isEqualTo("INVALID_PARAMETER")
        assertThat(code(paths.findDependencyPath(id(a), id(money), null, 0))).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `nodes are checked before the other parameters, fromId completely before toId`() {
        assertThat(code(affected.affectedBy(999L, "sideways", null, null, null, null))).isEqualTo("NODE_NOT_FOUND")
        assertThat(code(paths.findDependencyPath(id(method), 999L, null, null))).isEqualTo("INVALID_NODE_KIND")
    }

    @Test
    fun `modules with equal counts are ordered by name`() {
        val modules = (affected.affectedBy(id(money), "incoming", null, null, null, null)["summary"] as Map<String, Any?>)["by_parent_module"] as List<Map<String, Any?>>
        assertThat(modules.map { it["name"] }).containsExactly("app", "core")
    }

    @Test
    fun `a scope searched against itself yields its cycles, and an empty scope names both scopes`() {
        val cycle = paths.findDependencyPath(id(a), id(a), null, null)["paths"] as List<Map<String, Any?>>
        assertThat(cycle.map { it["length"] }).containsExactly(2)
        val empty = paths.findDependencyPath(id(add(root, JavaNodeKind.MODULE, "empty")), id(a), null, null)
        assertThat((empty["nodes"] as Map<String, Any?>)).hasSize(2)
    }
}
