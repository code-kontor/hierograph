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

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.mapping.GraphStoreNodeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `children(compact:)`, `compactedFrom`, `displayText`, `displayNode`, `predecessors(compact:)`: chain folding. */
class CompactChildrenTest {

    /**
     * ```
     * root ─ module m
     *          └ io › io.hg › { io.hg.server › io.hg.server.mcp (Tool), io.hg.web (Page) }
     * ```
     * Tool and Page depend on each other: a tangle between the two chain ends below `io.hg`.
     */
    private class Example {
        val graph = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        val root: HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val hierarchy: Hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun add(parent: HGNode, kind: Any, name: String, fqn: String): HGNode =
            HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, fqn, fqn) }.also {
                it.kind = kind
                HierarchyFactory.addChild(hierarchy, parent, it)
            }
        val m = add(root, JavaNodeKind.MODULE, "app.jar", "com.acme:app:1.0")
        val io = add(m, JavaNodeKind.PACKAGE, "io", "io")
        val hg = add(io, JavaNodeKind.PACKAGE, "hg", "io.hg")
        val server = add(hg, JavaNodeKind.PACKAGE, "server", "io.hg.server")
        val mcp = add(server, JavaNodeKind.PACKAGE, "mcp", "io.hg.server.mcp")
        val tool = add(mcp, JavaNodeKind.CLASS, "Tool", "io.hg.server.mcp.Tool")
        val web = add(hg, JavaNodeKind.PACKAGE, "web", "io.hg.web")
        val page = add(web, JavaNodeKind.CLASS, "Page", "io.hg.web.Page")
        init {
            repeat(2) { HGGraphFactory.createCoreDependency(tool, page, "DEPENDS_ON") { DefaultDependencySource(nextId++) } }
            HGGraphFactory.createCoreDependency(page, tool, "DEPENDS_ON") { DefaultDependencySource(nextId++) }
        }
    }

    private val example = Example()
    private val nodes = NodeController(HierarchicalGraphProvider { example.hierarchy })

    @Test
    fun `compact children fold each chain to its end, at every level`() {
        assertThat(nodes.children(example.m, compact = true).nodeList).containsExactly(example.hg)
        assertThat(nodes.children(example.hg, compact = true).nodeList).containsExactly(example.mcp, example.web)
        // Without `compact`, the tree as it is.
        assertThat(nodes.children(example.m, compact = false).nodeList).containsExactly(example.io)
    }

    @Test
    fun `a folded node names what it stands for, and is labelled relative to where it is shown`() {
        assertThat(nodes.compactedFrom(example.hg)).containsExactly(example.io)
        assertThat(nodes.compactedFrom(example.mcp)).containsExactly(example.server)
        assertThat(nodes.compactedFrom(example.web)).isEmpty()
        assertThat(nodes.compactedFrom(example.io)).isEmpty()

        // In full under the module; relative to the package it is shown under; its own name when not folded.
        assertThat(nodes.displayText(example.hg)).isEqualTo("io.hg")
        assertThat(nodes.displayText(example.mcp)).isEqualTo("server.mcp")
        assertThat(nodes.displayText(example.web)).isEqualTo("web")
        assertThat(nodes.displayText(example.tool)).isEqualTo("Tool")
    }

    @Test
    fun `a skipped package is shown at its chain's end, and the display ancestors skip it`() {
        assertThat(nodes.displayNode(example.io)).isEqualTo(example.hg)
        assertThat(nodes.displayNode(example.server)).isEqualTo(example.mcp)
        assertThat(nodes.displayNode(example.tool)).isEqualTo(example.tool)

        assertThat(nodes.predecessors(example.tool, compact = true))
            .containsExactly(example.mcp, example.hg, example.m, example.root)
        assertThat(nodes.predecessors(example.tool, compact = false))
            .containsExactly(example.mcp, example.server, example.hg, example.io, example.m, example.root)
    }

    @Test
    fun `the levelization of compact children names the chain ends in its tangles and feedback`() {
        val levelization = NodeSetController { example.hierarchy }
            .levelization(nodes.children(example.hg, compact = true))

        assertThat(levelization.tangles.single().nodes).containsExactlyInAnyOrder(example.mcp, example.web)
        val feedback = levelization.feedbackDependencies.single()
        assertThat(feedback.sourceNode to feedback.targetNode).isEqualTo(example.web to example.mcp)
        assertThat(feedback.weight).isEqualTo(1)
    }

    @Test
    fun `a move into a skipped package cuts the chain in that hierarchy`() {
        val fork = example.hierarchy.fork().apply { move(example.page, example.io) }
        val inFork = NodeController(HierarchicalGraphProvider { fork })

        assertThat(inFork.children(example.m, compact = true).nodeList).containsExactly(example.io)
        assertThat(inFork.children(example.io, compact = true).nodeList).containsExactly(example.hg, example.page)
        assertThat(inFork.compactedFrom(example.hg)).isEmpty()
        // The base hierarchy still folds it.
        assertThat(nodes.children(example.m, compact = true).nodeList).containsExactly(example.hg)
    }
}
