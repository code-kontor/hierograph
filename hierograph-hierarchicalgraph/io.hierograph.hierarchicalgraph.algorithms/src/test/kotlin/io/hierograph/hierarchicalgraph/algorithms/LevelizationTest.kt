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
package io.hierograph.hierarchicalgraph.algorithms

import io.hierograph.hierarchicalgraph.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A node set in rows by level: the longest chain beneath each node, tangles on one row, feedback against their order. */
class LevelizationTest {

    private class Builder {
        private var nextId = 1L
        private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        private val depSource = { DefaultDependencySource(identifier = nextId++) }
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        val names = HashMap<HGNode, String>()

        fun node(name: String, parent: HGNode = root): HGNode =
            HGGraphFactory.createNode(graph, nodeSource).also {
                HierarchyFactory.addChild(hierarchy, parent, it)
                names[it] = name
            }

        fun dep(from: HGNode, to: HGNode, weight: Int = 1) {
            repeat(weight) { HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON", depSource) }
        }

        fun levelize(vararg nodes: HGNode) = GraphAlgorithms.levelize(nodes.toList(), hierarchy)

        fun names(nodes: List<HGNode>) = nodes.map { names.getValue(it) }
        fun rows(rows: List<List<HGNode>>) = rows.map { names(it).toSet() }
        fun edges(deps: List<AggregatedDependency>) = deps.map { "${names.getValue(it.from)}>${names.getValue(it.to)}" }
    }

    @Test
    fun `in an acyclic set each node sits one row above the highest it uses, an unused node at the bottom`() {
        val b = Builder()
        val a = b.node("a"); val bb = b.node("b"); val c = b.node("c"); val d = b.node("d"); val e = b.node("e")
        // a uses b and c, b uses d, c uses d; e uses nothing and is used by nothing.
        b.dep(a, bb); b.dep(a, c); b.dep(bb, d); b.dep(c, d)

        val levelization = b.levelize(a, bb, c, d, e)

        assertThat(b.rows(levelization.levels)).containsExactly(setOf("d", "e"), setOf("b", "c"), setOf("a"))
        assertThat(levelization.levelOf(a)).isEqualTo(2)
        assertThat(levelization.tangles).isEmpty()
        assertThat(levelization.feedbackDependencies).isEmpty()
    }

    @Test
    fun `the level is the longest chain beneath, not the shortest`() {
        val b = Builder()
        val a = b.node("a"); val bb = b.node("b"); val c = b.node("c")
        b.dep(a, bb); b.dep(bb, c); b.dep(a, c)

        assertThat(b.rows(b.levelize(a, bb, c).levels)).containsExactly(setOf("c"), setOf("b"), setOf("a"))
    }

    @Test
    fun `a tangle shares one row, above what any member uses and below what uses any member`() {
        val b = Builder()
        val top = b.node("top"); val x = b.node("x"); val y = b.node("y"); val base = b.node("base")
        b.dep(top, x); b.dep(x, y, 3); b.dep(y, x); b.dep(y, base)

        val levelization = b.levelize(top, x, y, base)

        assertThat(b.rows(levelization.levels)).containsExactly(setOf("base"), setOf("x", "y"), setOf("top"))
        val tangle = levelization.tangles.single()
        assertThat(tangle.level).isEqualTo(1)
        // The heavy x → y points down; the light y → x is the feedback.
        assertThat(b.names(tangle.members)).containsExactly("x", "y")
        assertThat(b.edges(levelization.feedbackDependencies)).containsExactly("y>x")
        assertThat(levelization.feedbackDependencies.single().aggregatedWeight).isEqualTo(1)
    }

    @Test
    fun `a tangle's members in rows inside it, by the dependencies that point down`() {
        val b = Builder()
        val p = b.node("p"); val q = b.node("q"); val r = b.node("r")
        // A ring p → q → r → p, cheapest to cut at r → p.
        b.dep(p, q, 5); b.dep(q, r, 5); b.dep(r, p, 1)

        val tangle = b.levelize(p, q, r).tangles.single()

        assertThat(b.names(tangle.members)).containsExactly("p", "q", "r")
        assertThat(b.rows(tangle.rows)).containsExactly(setOf("r"), setOf("q"), setOf("p"))
    }

    @Test
    fun `the feedback dependencies of every tangle, heaviest first`() {
        val b = Builder()
        val a = b.node("a"); val bb = b.node("b"); val c = b.node("c"); val d = b.node("d")
        b.dep(a, bb, 5); b.dep(bb, a, 1)
        b.dep(c, d, 9); b.dep(d, c, 4)

        val levelization = b.levelize(a, bb, c, d)

        assertThat(levelization.tangles).hasSize(2)
        assertThat(b.edges(levelization.feedbackDependencies)).containsExactly("d>c", "b>a")
    }

    @Test
    fun `dependencies between subtrees are charged to the nodes of the set that contain them`() {
        val b = Builder()
        val api = b.node("api"); val impl = b.node("impl")
        val service = b.node("Service", api); val repository = b.node("Repository", impl); val dao = b.node("Dao", impl)
        b.dep(repository, service, 2); b.dep(dao, service)

        val levelization = b.levelize(api, impl)

        assertThat(b.rows(levelization.levels)).containsExactly(setOf("api"), setOf("impl"))
        // Dependencies inside a node's subtree don't lift it.
        b.dep(repository, dao)
        assertThat(b.rows(b.levelize(api, impl).levels)).containsExactly(setOf("api"), setOf("impl"))
    }

    @Test
    fun `an empty set has no rows, and a node outside the set no level`() {
        val b = Builder()
        val a = b.node("a"); val other = b.node("other")

        assertThat(b.levelize().levels).isEmpty()
        val single = b.levelize(a)
        assertThat(b.rows(single.levels)).containsExactly(setOf("a"))
        assertThat(single.levelOf(other)).isEqualTo(-1)
    }
}
