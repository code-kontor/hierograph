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

import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Verifies which paths [FindDependencyPathTool] returns and how it reports them. */
@Suppress("UNCHECKED_CAST")
class FindDependencyPathToolTest {

    private var nextId = 1L
    private val graph = HGGraphFactory.createHGGraph()
    private val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun node(parent: HGNode, kind: JavaNodeKind) =
        HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }.also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    private fun pkg() = node(root, JavaNodeKind.PACKAGE)
    private fun type(pkg: HGNode) = node(pkg, JavaNodeKind.CLASS)
    private fun HGNode.dependsOn(target: HGNode, weight: Int) {
        HGGraphFactory.createCoreDependency(this, target, "USES") { DefaultDependencySource(nextId++) }.weight = weight
    }

    private fun tool() = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
        .let { FindDependencyPathTool(it, NodeRefFactory(it)) }

    private fun paths(resp: Map<String, Any?>) = resp["paths"] as List<Map<String, Any?>>

    @Test
    fun `each path names its endpoint types and the summary echoes the scopes`() {
        val source = pkg(); val target = pkg(); val via = pkg()
        val a = type(source); val x = type(via); val t = type(target)
        a.dependsOn(x, 1); x.dependsOn(t, 1)

        val resp = tool().findDependencyPath(source.identifier as Long, target.identifier as Long, null, null)
        val path = paths(resp).single()
        assertThat(path["from"]).isEqualTo(a.identifier)
        assertThat(path["to"]).isEqualTo(t.identifier)
        val summary = resp["summary"] as Map<String, Any?>
        assertThat(summary["from_scope"]).isEqualTo(source.identifier)
        assertThat(summary["to_scope"]).isEqualTo(target.identifier)
    }

    @Test
    fun `among paths of equal length the heaviest are returned`() {
        val source = pkg(); val target = pkg(); val via = pkg()
        val light = type(source); val heavy = type(source)
        val x = type(via); val y = type(via); val t = type(target)
        light.dependsOn(x, 1); x.dependsOn(t, 1)   // found first, weight 2
        heavy.dependsOn(y, 5); y.dependsOn(t, 5)   // weight 10

        val resp = tool().findDependencyPath(source.identifier as Long, target.identifier as Long, 1, null)
        assertThat(paths(resp).single()["from"]).isEqualTo(heavy.identifier)
        assertThat(paths(resp).single()["total_weight"]).isEqualTo(10)
    }

    @Test
    fun `shorter paths come before heavier longer ones`() {
        val source = pkg(); val target = pkg(); val via = pkg()
        val a = type(source); val x = type(via); val t = type(target)
        a.dependsOn(x, 9); x.dependsOn(t, 9); a.dependsOn(t, 1)

        val lengths = paths(tool().findDependencyPath(source.identifier as Long, target.identifier as Long, 5, null))
            .map { it["length"] }
        assertThat(lengths).containsExactly(1, 2)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `a dense layered graph is searched without enumerating its paths`() {
        // 10 layers of 12 types, every type depending on every type of the next layer: 12^10 paths.
        val source = pkg(); val target = pkg(); val via = pkg()
        var previous = List(12) { type(source) }
        repeat(10) {
            val layer = List(12) { type(via) }
            previous.forEach { p -> layer.forEach { p.dependsOn(it, 1) } }
            previous = layer
        }
        val t = type(target)
        previous.forEach { it.dependsOn(t, 1) }

        val resp = tool().findDependencyPath(source.identifier as Long, target.identifier as Long, 20, null)
        assertThat(paths(resp)).hasSize(20).allSatisfy { assertThat(it["length"]).isEqualTo(11) }
    }
}
