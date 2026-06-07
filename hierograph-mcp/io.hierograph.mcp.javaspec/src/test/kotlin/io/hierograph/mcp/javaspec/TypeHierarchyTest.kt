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

import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import io.hierograph.hierarchicalgraph.core.model.internal.HGGraphImpl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TypeHierarchyTest {

    // ── supertypes (upward) ─────────────────────────────────────────────

    @Test
    fun `chained extends produces full ancestor set`() {
        // A -extends-> B -extends-> C
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val c = g.type()
        g.extends(a, b)
        g.extends(b, c)

        assertThat(a.supertypes()).containsExactlyInAnyOrder(b, c)
    }

    @Test
    fun `implements edges are followed`() {
        // A -implements-> I -implements-> J
        val g = Graph()
        val a = g.type()
        val i = g.type()
        val j = g.type()
        g.implements(a, i)
        g.implements(i, j)

        assertThat(a.supertypes()).containsExactlyInAnyOrder(i, j)
    }

    @Test
    fun `extends and implements are mixed in the closure`() {
        // A -extends-> B, A -implements-> I, B -implements-> J
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val i = g.type()
        val j = g.type()
        g.extends(a, b)
        g.implements(a, i)
        g.implements(b, j)

        assertThat(a.supertypes()).containsExactlyInAnyOrder(b, i, j)
    }

    @Test
    fun `diamond inheritance deduplicates supertypes`() {
        // A -extends-> B, A -extends-> C, B -extends-> D, C -extends-> D
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val c = g.type()
        val d = g.type()
        g.extends(a, b)
        g.extends(a, c)
        g.extends(b, d)
        g.extends(c, d)

        assertThat(a.supertypes()).containsExactlyInAnyOrder(b, c, d)
    }

    @Test
    fun `non-extends-implements edges are ignored`() {
        // A -extends-> B, A -depends_on(other)-> X
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val x = g.type()
        g.extends(a, b)
        g.dependsOnOther(a, x)

        assertThat(a.supertypes()).containsExactly(b)
    }

    @Test
    fun `cycle is terminated by visited tracking`() {
        // A -extends-> B -extends-> A
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(a, b)
        g.extends(b, a)

        // a is reachable via the back-edge even with includeSelf = false
        assertThat(a.supertypes()).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `seed is not included when there is no back edge`() {
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(a, b)

        assertThat(a.supertypes()).doesNotContain(a)
    }

    @Test
    fun `node without supertype edges returns empty set`() {
        val g = Graph()
        val a = g.type()

        assertThat(a.supertypes()).isEmpty()
    }

    // ── subtypes (downward) ─────────────────────────────────────────────

    @Test
    fun `subtypes walks extends edges downward`() {
        // A <-extends- B <-extends- C
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val c = g.type()
        g.extends(b, a)
        g.extends(c, b)

        assertThat(a.subtypes()).containsExactlyInAnyOrder(b, c)
    }

    @Test
    fun `subtypes walks implements edges downward`() {
        // I <-implements- A <-implements- B
        val g = Graph()
        val i = g.type()
        val a = g.type()
        val b = g.type()
        g.implements(a, i)
        g.implements(b, a)

        assertThat(i.subtypes()).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `subtypes mixes extends and implements`() {
        // I <-implements- A, A <-extends- B, A <-extends- C
        val g = Graph()
        val i = g.type()
        val a = g.type()
        val b = g.type()
        val c = g.type()
        g.implements(a, i)
        g.extends(b, a)
        g.extends(c, a)

        assertThat(i.subtypes()).containsExactlyInAnyOrder(a, b, c)
    }

    @Test
    fun `subtypes diamond is deduplicated`() {
        // D <-extends- B, D <-extends- C, B <-extends- A, C <-extends- A
        val g = Graph()
        val d = g.type()
        val b = g.type()
        val c = g.type()
        val a = g.type()
        g.extends(b, d)
        g.extends(c, d)
        g.extends(a, b)
        g.extends(a, c)

        assertThat(d.subtypes()).containsExactlyInAnyOrder(b, c, a)
    }

    @Test
    fun `subtypes ignores non extends or implements incoming edges`() {
        // A <-extends- B, A <-depends_on_other- X
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val x = g.type()
        g.extends(b, a)
        g.dependsOnOther(x, a)

        assertThat(a.subtypes()).containsExactly(b)
    }

    @Test
    fun `subtypes terminates on cycle`() {
        // A <-extends- B <-extends- A
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(b, a)
        g.extends(a, b)

        assertThat(a.subtypes()).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `subtypes is empty for a leaf type with no subtypes`() {
        val g = Graph()
        val a = g.type()

        assertThat(a.subtypes()).isEmpty()
    }

    @Test
    fun `supertypes and subtypes are mirror operations`() {
        // C extends B extends A
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val c = g.type()
        g.extends(b, a)
        g.extends(c, b)

        assertThat(c.supertypes()).containsExactlyInAnyOrder(b, a)
        assertThat(a.subtypes()).containsExactlyInAnyOrder(b, c)
    }

    // ── includeSelf ─────────────────────────────────────────────────────

    @Test
    fun `supertypes with includeSelf prepends the receiver`() {
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(a, b)

        assertThat(a.supertypes(includeSelf = true)).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `subtypes with includeSelf prepends the receiver`() {
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(b, a)

        assertThat(a.subtypes(includeSelf = true)).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `supertypes with includeSelf on a leaf returns just the receiver`() {
        val g = Graph()
        val a = g.type()

        assertThat(a.supertypes(includeSelf = true)).containsExactly(a)
    }

    @Test
    fun `subtypes with includeSelf on a leaf returns just the receiver`() {
        val g = Graph()
        val a = g.type()

        assertThat(a.subtypes(includeSelf = true)).containsExactly(a)
    }

    @Test
    fun `default for includeSelf is false`() {
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(a, b)

        assertThat(a.supertypes()).isEqualTo(a.supertypes(includeSelf = false))
        assertThat(a.supertypes()).doesNotContain(a)
    }

    // ── collection seeds ────────────────────────────────────────────────

    @Test
    fun `supertypes on a collection unions per-seed closures`() {
        // A -extends-> B, C -implements-> I
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val c = g.type()
        val i = g.type()
        g.extends(a, b)
        g.implements(c, i)

        assertThat(listOf(a, c).supertypes()).containsExactlyInAnyOrder(b, i)
    }

    @Test
    fun `supertypes on a collection deduplicates shared ancestors`() {
        // A -extends-> X, B -extends-> X
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val x = g.type()
        g.extends(a, x)
        g.extends(b, x)

        assertThat(listOf(a, b).supertypes()).containsExactly(x)
    }

    @Test
    fun `supertypes on a collection includes a seed reached from another seed`() {
        // A -extends-> B, B has no out-edges; both A and B are seeds.
        // includeSelf = false, but B is reachable from A → B in result, A is not.
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(a, b)

        val closure = listOf(a, b).supertypes()
        assertThat(closure).containsExactly(b)
        assertThat(closure).doesNotContain(a)
    }

    @Test
    fun `supertypes on an empty collection returns empty set`() {
        assertThat(emptyList<HGNode>().supertypes()).isEmpty()
        assertThat(emptyList<HGNode>().supertypes(includeSelf = true)).isEmpty()
    }

    @Test
    fun `supertypes on a collection with includeSelf adds every seed`() {
        // A -extends-> B
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val c = g.type()
        g.extends(a, b)

        assertThat(listOf(a, c).supertypes(includeSelf = true))
            .containsExactlyInAnyOrder(a, b, c)
    }

    @Test
    fun `subtypes on a collection unions per-seed closures`() {
        // A <-extends- X, B <-implements- Y
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val x = g.type()
        val y = g.type()
        g.extends(x, a)
        g.implements(y, b)

        assertThat(listOf(a, b).subtypes()).containsExactlyInAnyOrder(x, y)
    }

    @Test
    fun `subtypes on a collection with includeSelf adds every seed`() {
        val g = Graph()
        val a = g.type()
        val b = g.type()
        val sub = g.type()
        g.extends(sub, a)

        assertThat(listOf(a, b).subtypes(includeSelf = true))
            .containsExactlyInAnyOrder(a, b, sub)
    }

    @Test
    fun `supertypes on a collection is a Sequence-compatible iterable`() {
        // Verify the extension works on any Iterable<HGNode>, not just List.
        val g = Graph()
        val a = g.type()
        val b = g.type()
        g.extends(a, b)

        val seeds: Sequence<HGNode> = sequenceOf(a)
        assertThat(seeds.asIterable().supertypes()).containsExactly(b)
    }

    // ── test fixture ────────────────────────────────────────────────────

    private class Graph {
        val root: HGNode
        private val graph: HGGraphImpl = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        private val depSource = { DefaultDependencySource(identifier = nextId++) }

        init {
            root = HGGraphFactory.createNode(graph, nodeSource)
            val h = HierarchyFactory.createHierarchy(graph, root)
        }

        fun type(): HGNode {
            val node = HGGraphFactory.createNode(graph, nodeSource)
            return node
        }

        fun extends(from: HGNode, to: HGNode): HGCoreDependency =
            dep(from, to, JavaEdgeAttributes.IS_EXTENDS)

        fun implements(from: HGNode, to: HGNode): HGCoreDependency =
            dep(from, to, JavaEdgeAttributes.IS_IMPLEMENTS)

        fun dependsOnOther(from: HGNode, to: HGNode): HGCoreDependency =
            dep(from, to, JavaEdgeAttributes.IS_DEPENDS_ON_OTHER)

        private fun dep(from: HGNode, to: HGNode, attribute: Int): HGCoreDependency {
            val d = HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON", depSource)
            d.attributesBitmap = JavaEdgeAttributes.set(d.attributesBitmap, attribute)
            return d
        }
    }
}
