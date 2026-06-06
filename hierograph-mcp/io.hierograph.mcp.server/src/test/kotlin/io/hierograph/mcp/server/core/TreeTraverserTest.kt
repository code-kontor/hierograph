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
package io.hierograph.mcp.server.core

import io.hierograph.hierarchicalgraph.core.model.CoreGraphFactory
import io.hierograph.hierarchicalgraph.core.model.CoreNode
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.Hierarchy
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TreeTraverserTest {

    private var nextId = 1L
    private val nodeSource = { DefaultNodeSource(identifier = nextId++) }

    // root
    //   a   name="A"  fqn="p.A"
    //     a1 name="A1" fqn="p.A.A1"
    //   b   (no name, no fqn)
    //   c   name="C"  (no fqn)
    //   d   (no name)  fqn="p.D"
    private lateinit var root: CoreNode
    private lateinit var a: CoreNode
    private lateinit var a1: CoreNode
    private lateinit var b: CoreNode
    private lateinit var c: CoreNode
    private lateinit var d: CoreNode
    private lateinit var hierarchy: Hierarchy

    private lateinit var names: Map<CoreNode, Pair<String?, String?>>

    @BeforeEach
    fun setup() {
        val graph = CoreGraphFactory.createCoreGraph()
        root = CoreGraphFactory.createNode(graph, nodeSource)
        a = CoreGraphFactory.createNode(graph, nodeSource)
        a1 = CoreGraphFactory.createNode(graph, nodeSource)
        b = CoreGraphFactory.createNode(graph, nodeSource)
        c = CoreGraphFactory.createNode(graph, nodeSource)
        d = CoreGraphFactory.createNode(graph, nodeSource)

        val h = HierarchyFactory.createHierarchy(graph, root)
        HierarchyFactory.addChild(h, root, a)
        HierarchyFactory.addChild(h, a, a1)
        HierarchyFactory.addChild(h, root, b)
        HierarchyFactory.addChild(h, root, c)
        HierarchyFactory.addChild(h, root, d)
        hierarchy = h

        names = mapOf(
            a to ("A" to "p.A"),
            a1 to ("A1" to "p.A.A1"),
            c to ("C" to null),
            d to (null to "p.D")
            // b intentionally absent -> (null, null)
        )
    }

    private fun lookup(node: CoreNode): Pair<String?, String?> = names[node] ?: (null to null)

    @Test
    fun `dumpToString renders an indented preorder tree with name and fqn`() {
        val expected = listOf(
            "[${root.identifier}]",
            "  [${a.identifier}] A (p.A)",
            "    [${a1.identifier}] A1 (p.A.A1)",
            "  [${b.identifier}]",
            "  [${c.identifier}] C",
            "  [${d.identifier}] (p.D)"
        ).joinToString("\n")

        assertThat(TreeTraverser.dumpToString(root, hierarchy, nameAndFqn = ::lookup)).isEqualTo(expected)
    }

    @Test
    fun `dumpTree emits one line per node in preorder via the sink`() {
        val lines = mutableListOf<String>()
        TreeTraverser.dumpTree(root, hierarchy, sink = { lines.add(it) }, nameAndFqn = ::lookup)

        assertThat(lines).containsExactly(
            "[${root.identifier}]",
            "  [${a.identifier}] A (p.A)",
            "    [${a1.identifier}] A1 (p.A.A1)",
            "  [${b.identifier}]",
            "  [${c.identifier}] C",
            "  [${d.identifier}] (p.D)"
        )
    }

    @Test
    fun `name is omitted when null and fqn parens are omitted when null`() {
        val lines = mutableListOf<String>()
        TreeTraverser.dumpTree(root, hierarchy, sink = { lines.add(it) }, nameAndFqn = ::lookup)

        // b: neither -> bare id; c: name only; d: fqn only
        assertThat(lines).contains(
            "  [${b.identifier}]",
            "  [${c.identifier}] C",
            "  [${d.identifier}] (p.D)"
        )
    }

    @Test
    fun `dumping a subtree starts at the given node with depth zero`() {
        val lines = mutableListOf<String>()
        TreeTraverser.dumpTree(a, hierarchy, sink = { lines.add(it) }, nameAndFqn = ::lookup)

        assertThat(lines).containsExactly(
            "[${a.identifier}] A (p.A)",
            "  [${a1.identifier}] A1 (p.A.A1)"
        )
    }

    @Test
    fun `indent string is configurable`() {
        val lines = mutableListOf<String>()
        TreeTraverser.dumpTree(a, hierarchy, sink = { lines.add(it) }, indent = "\t", nameAndFqn = ::lookup)

        assertThat(lines).containsExactly(
            "[${a.identifier}] A (p.A)",
            "\t[${a1.identifier}] A1 (p.A.A1)"
        )
    }

    @Test
    fun `default extractor yields bare id lines for non-graphdb node sources`() {
        // DefaultNodeSource is not an ExtendedGraphDbNodeSource, so the default
        // extractor reports (null, null) and only the id is printed.
        val lines = mutableListOf<String>()
        TreeTraverser.dumpTree(a, hierarchy, sink = { lines.add(it) })

        assertThat(lines).containsExactly(
            "[${a.identifier}]",
            "  [${a1.identifier}]"
        )
    }
}
