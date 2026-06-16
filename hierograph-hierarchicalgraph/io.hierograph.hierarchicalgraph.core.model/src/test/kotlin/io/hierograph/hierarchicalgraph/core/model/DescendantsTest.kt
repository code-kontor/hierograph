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
package io.hierograph.hierarchicalgraph.core.model

import io.hierograph.hierarchicalgraph.core.model.internal.HGGraphImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Exercises [Hierarchy.descendantsOf] through the [HierarchyScope] `descendants` extensions. Kinds
 * are arbitrary marker objects ([HierarchyScope] is kind-agnostic); the strings below stand in for
 * whatever vocabulary a consumer assigns to [HGNode.kind].
 */
class DescendantsTest {

    private val MODULE = "module"
    private val PACKAGE = "package"
    private val CLASS = "class"
    private val INTERFACE = "interface"
    private val ENUM = "enum"
    private val FIELD = "field"
    private val METHOD = "method"

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
        val module = g.add(g.root, MODULE)
        val pkg = g.add(module, PACKAGE)
        val a = g.add(pkg, CLASS)
        val f = g.add(a, FIELD)
        val m = g.add(a, METHOD)
        val i = g.add(pkg, INTERFACE)

        g.scope {
            assertThat(module.descendants()).containsExactly(pkg, a, f, m, i)
        }
    }

    @Test
    fun `excludes the receiver itself`() {
        val g = Tree()
        val a = g.add(g.root, CLASS)
        val b = g.add(a, METHOD)

        g.scope {
            assertThat(a.descendants()).containsExactly(b)
            assertThat(a.descendants()).doesNotContain(a)
        }
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
        val pkg = g.add(g.root, PACKAGE)
        val a = g.add(pkg, CLASS)
        val inner = g.add(a, CLASS)
        val m = g.add(inner, METHOD)
        val f = g.add(a, FIELD)
        val enumTy = g.add(pkg, ENUM)

        g.scope {
            // Filter on METHOD — `m` lives under (Class A) → (Class inner). If the
            // traversal pruned non-matching ancestors, we'd miss `m`.
            assertThat(pkg.descendants(METHOD)).containsExactly(m)

            // Filter on multiple kinds.
            assertThat(pkg.descendants(CLASS, ENUM)).containsExactly(a, inner, enumTy)
        }
    }

    @Test
    fun `empty vararg is equivalent to no filter`() {
        val g = Tree()
        val pkg = g.add(g.root, PACKAGE)
        val a = g.add(pkg, CLASS)

        g.scope {
            assertThat(pkg.descendants()).containsExactly(a)
        }
    }

    @Test
    fun `leaf returns empty list`() {
        val g = Tree()
        val a = g.add(g.root, METHOD)

        g.scope {
            assertThat(a.descendants()).isEmpty()
        }
    }

    @Test
    fun `nodes with unset kind are excluded under any filter`() {
        // A child without a kind never matches a kind filter.
        val g = Tree()
        val pkg = g.add(g.root, PACKAGE)
        val noKind = g.add(pkg, kind = null)
        val a = g.add(pkg, CLASS)

        g.scope {
            assertThat(pkg.descendants(CLASS)).containsExactly(a)
            assertThat(pkg.descendants()).containsExactlyInAnyOrder(noKind, a)
        }
    }

    // ── collection variant ─────────────────────────────────────────────

    @Test
    fun `collection variant unions descendants in seed order`() {
        // pkgA → A (Class), pkgB → B (Class)
        val g = Tree()
        val pkgA = g.add(g.root, PACKAGE)
        val a = g.add(pkgA, CLASS)
        val pkgB = g.add(g.root, PACKAGE)
        val b = g.add(pkgB, CLASS)

        g.scope {
            assertThat(listOf(pkgA, pkgB).descendants()).containsExactly(a, b)
            assertThat(listOf(pkgB, pkgA).descendants()).containsExactly(b, a)
        }
    }

    @Test
    fun `collection variant deduplicates shared descendants`() {
        // Same node reached as a descendant of two seeds (one seed is the parent of
        // the other) — appears only once, at first occurrence.
        val g = Tree()
        val pkg = g.add(g.root, PACKAGE)
        val a = g.add(pkg, CLASS)

        g.scope {
            assertThat(listOf(g.root, pkg).descendants(CLASS)).containsExactly(a)
        }
    }

    @Test
    fun `collection variant filter applies to all seeds`() {
        val g = Tree()
        val pkg = g.add(g.root, PACKAGE)
        val a = g.add(pkg, CLASS)
        val m = g.add(a, METHOD)
        val pkg2 = g.add(g.root, PACKAGE)
        val b = g.add(pkg2, CLASS)

        g.scope {
            assertThat(listOf(pkg, pkg2).descendants(CLASS)).containsExactly(a, b)
            assertThat(listOf(pkg, pkg2).descendants(METHOD)).containsExactly(m)
        }
    }

    @Test
    fun `collection variant on empty collection returns empty`() {
        val g = Tree()
        g.scope {
            assertThat(emptyList<HGNode>().descendants()).isEmpty()
            assertThat(emptyList<HGNode>().descendants(CLASS)).isEmpty()
        }
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

        fun add(parent: HGNode, kind: Any?): HGNode {
            val node = HGGraphFactory.createNode(graph, nodeSource)
            HierarchyFactory.addChild(hierarchy, parent, node)
            node.kind = kind
            return node
        }

        fun scope(block: HierarchyScope.() -> Unit) = HierarchyScope(hierarchy).run(block)
    }
}
