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

import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.Hierarchy
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import io.hierograph.hierarchicalgraph.core.model.internal.HGGraphImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DescendantsTest {

    @Test
    fun `no filter walks the whole subtree in pre-order`() {
        // root
        //  └── module
        //       └── pkg
        //            ├── A (Class)
        //            │    ├── f (Field)
        //            │    └── m (Method)
        //            └── I (Interface)
        val g = Tree()
        val module = g.add(g.root, JavaKinds.MODULE)
        val pkg = g.add(module, JavaKinds.PACKAGE)
        val a = g.add(pkg, JavaKinds.CLASS)
        val f = g.add(a, JavaKinds.FIELD)
        val m = g.add(a, JavaKinds.METHOD)
        val i = g.add(pkg, JavaKinds.INTERFACE)

        assertThat(module.descendants(g.hierarchy))
            .containsExactly(pkg, a, f, m, i)
    }

    @Test
    fun `excludes the receiver itself`() {
        val g = Tree()
        val a = g.add(g.root, JavaKinds.CLASS)
        val b = g.add(a, JavaKinds.METHOD)

        assertThat(a.descendants(g.hierarchy)).containsExactly(b)
        assertThat(a.descendants(g.hierarchy)).doesNotContain(a)
    }

    @Test
    fun `kind filter restricts result but does not prune traversal`() {
        // pkg
        //  ├── A (Class)
        //  │    ├── inner (Class)
        //  │    │    └── m (Method)
        //  │    └── f (Field)
        //  └── enumTy (Enum)
        val g = Tree()
        val pkg = g.add(g.root, JavaKinds.PACKAGE)
        val a = g.add(pkg, JavaKinds.CLASS)
        val inner = g.add(a, JavaKinds.CLASS)
        val m = g.add(inner, JavaKinds.METHOD)
        val f = g.add(a, JavaKinds.FIELD)
        val enumTy = g.add(pkg, JavaKinds.ENUM)

        // Filter on METHOD — `m` lives under (Class A) → (Class inner). If the
        // traversal pruned non-matching ancestors, we'd miss `m`.
        assertThat(pkg.descendants(g.hierarchy, JavaKinds.METHOD)).containsExactly(m)

        // Filter on multiple kinds.
        assertThat(pkg.descendants(g.hierarchy, JavaKinds.CLASS, JavaKinds.ENUM))
            .containsExactly(a, inner, enumTy)
    }

    @Test
    fun `empty vararg is equivalent to no filter`() {
        val g = Tree()
        val pkg = g.add(g.root, JavaKinds.PACKAGE)
        val a = g.add(pkg, JavaKinds.CLASS)

        assertThat(pkg.descendants(g.hierarchy)).containsExactly(a)
    }

    @Test
    fun `leaf returns empty list`() {
        val g = Tree()
        val a = g.add(g.root, JavaKinds.METHOD)

        assertThat(a.descendants(g.hierarchy)).isEmpty()
    }

    @Test
    fun `nodes with unset kind are excluded under any filter`() {
        // A child without a kind never matches a kind filter.
        val g = Tree()
        val pkg = g.add(g.root, JavaKinds.PACKAGE)
        val noKind = g.add(pkg, kind = null)
        val a = g.add(pkg, JavaKinds.CLASS)

        assertThat(pkg.descendants(g.hierarchy, JavaKinds.CLASS)).containsExactly(a)
        assertThat(pkg.descendants(g.hierarchy)).containsExactlyInAnyOrder(noKind, a)
    }

    // ── collection variant ─────────────────────────────────────────────

    @Test
    fun `collection variant unions descendants in seed order`() {
        // pkgA → A (Class), pkgB → B (Class)
        val g = Tree()
        val pkgA = g.add(g.root, JavaKinds.PACKAGE)
        val a = g.add(pkgA, JavaKinds.CLASS)
        val pkgB = g.add(g.root, JavaKinds.PACKAGE)
        val b = g.add(pkgB, JavaKinds.CLASS)

        assertThat(listOf(pkgA, pkgB).descendants(g.hierarchy)).containsExactly(a, b)
        assertThat(listOf(pkgB, pkgA).descendants(g.hierarchy)).containsExactly(b, a)
    }

    @Test
    fun `collection variant deduplicates shared descendants`() {
        // Same node reached as a descendant of two seeds (one seed is the parent of
        // the other) — appears only once, at first occurrence.
        val g = Tree()
        val pkg = g.add(g.root, JavaKinds.PACKAGE)
        val a = g.add(pkg, JavaKinds.CLASS)

        assertThat(listOf(g.root, pkg).descendants(g.hierarchy, JavaKinds.CLASS))
            .containsExactly(a)
    }

    @Test
    fun `collection variant filter applies to all seeds`() {
        val g = Tree()
        val pkg = g.add(g.root, JavaKinds.PACKAGE)
        val a = g.add(pkg, JavaKinds.CLASS)
        val m = g.add(a, JavaKinds.METHOD)
        val pkg2 = g.add(g.root, JavaKinds.PACKAGE)
        val b = g.add(pkg2, JavaKinds.CLASS)

        assertThat(listOf(pkg, pkg2).descendants(g.hierarchy, JavaKinds.CLASS))
            .containsExactly(a, b)
        assertThat(listOf(pkg, pkg2).descendants(g.hierarchy, JavaKinds.METHOD))
            .containsExactly(m)
    }

    @Test
    fun `collection variant on empty collection returns empty`() {
        val g = Tree()
        assertThat(emptyList<HGNode>().descendants(g.hierarchy)).isEmpty()
        assertThat(emptyList<HGNode>().descendants(g.hierarchy, JavaKinds.CLASS)).isEmpty()
    }

    // ── test fixture ───────────────────────────────────────────────────

    private class Tree {
        val root: HGNode
        val hierarchy: Hierarchy
        private val graph: HGGraphImpl = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        private val nodeSource = { DefaultNodeSource(identifier = nextId++) }

        init {
            root = HGGraphFactory.createNode(graph, nodeSource)
            hierarchy = HierarchyFactory.createHierarchy(graph, root)
        }

        fun add(parent: HGNode, kind: JavaNodeKind?): HGNode {
            val node = HGGraphFactory.createNode(graph, nodeSource)
            HierarchyFactory.addChild(hierarchy, parent, node)
            node.kind = kind
            return node
        }
    }
}
