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
package io.hierograph.mcp.javaspec

import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.core.model.HierarchicalGraphFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PrintTreeTest {

    @Test
    fun `leaf node prints a single line`() {
        val root = newRoot(id = 1L, name = "root", fqn = "root.fqn")
        val out = mutableListOf<String>()
        root.printTree(sink = out::add)
        assertThat(out).containsExactly("[1] root (root.fqn)")
    }

    @Test
    fun `depth increases the indentation by two spaces per level by default`() {
        // root → module → pkg → A
        val root = newRoot(id = 1L, name = "root", fqn = "root")
        val module = addChild(root, root, id = 2L, name = "m", fqn = "com.acme")
        val pkg = addChild(root, module, id = 3L, name = "p", fqn = "com.acme.p")
        addChild(root, pkg, id = 4L, name = "A", fqn = "com.acme.p.A")

        val out = mutableListOf<String>()
        root.printTree(sink = out::add)

        assertThat(out).containsExactly(
            "[1] root (root)",
            "  [2] m (com.acme)",
            "    [3] p (com.acme.p)",
            "      [4] A (com.acme.p.A)"
        )
    }

    @Test
    fun `custom indent string is honored`() {
        val root = newRoot(id = 1L)
        addChild(root, root, id = 2L, name = "x", fqn = null)

        val out = mutableListOf<String>()
        root.printTree(sink = out::add, indent = "\t")

        assertThat(out).containsExactly(
            "[1]",
            "\t[2] x"
        )
    }

    @Test
    fun `missing name omits the name segment and missing fqn omits the parenthesized segment`() {
        val root = newRoot(id = 1L)
        addChild(root, root, id = 2L, name = "n", fqn = null)
        addChild(root, root, id = 3L, name = null, fqn = "only.fqn")
        addChild(root, root, id = 4L, name = null, fqn = null)

        val out = mutableListOf<String>()
        root.printTree(sink = out::add)

        assertThat(out).containsExactly(
            "[1]",
            "  [2] n",
            "  [3] (only.fqn)",
            "  [4]"
        )
    }

    @Test
    fun `custom nameAndFqn lambda overrides the default extractor`() {
        // The receiver's nodeSource has no name/fqn, but the lambda supplies
        // them per-id (mirroring how JQAssistantHierarchyProvider exposes its
        // name/fqn map to the caller).
        val root = HierarchicalGraphFactory.createRootNode { DefaultNodeSource(identifier = 1L) }
        HierarchicalGraphFactory.createNode(root, root) { DefaultNodeSource(identifier = 2L) }

        val table = mapOf(
            1L to ("root" to "root.fqn"),
            2L to ("child" to "child.fqn")
        )
        val out = mutableListOf<String>()
        root.printTree(
            sink = out::add,
            nameAndFqn = { node -> table[node.identifier as Long] ?: (null to null) }
        )

        assertThat(out).containsExactly(
            "[1] root (root.fqn)",
            "  [2] child (child.fqn)"
        )
    }

    // ── fixture ────────────────────────────────────────────────────────

    private fun newRoot(id: Long, name: String? = null, fqn: String? = null): HGRootNode =
        HierarchicalGraphFactory.createRootNode { defaultSource(id, name, fqn) }

    private fun addChild(
        root: HGRootNode,
        parent: HGNode,
        id: Long,
        name: String? = null,
        fqn: String? = null
    ): HGNode = HierarchicalGraphFactory.createNode(root, parent) { defaultSource(id, name, fqn) }

    private fun defaultSource(id: Long, name: String?, fqn: String?): DefaultNodeSource {
        val props = mutableMapOf<String, String>()
        if (name != null) props["name"] = name
        if (fqn != null) props["fqn"] = fqn
        return DefaultNodeSource(identifier = id, properties = props)
    }
}
