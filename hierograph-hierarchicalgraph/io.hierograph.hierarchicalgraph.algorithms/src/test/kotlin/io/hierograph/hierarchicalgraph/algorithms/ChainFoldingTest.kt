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

import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Chain folding (specification, §8) on small trees; packages are the foldable nodes. */
class ChainFoldingTest {

    /**
     * ```
     * root
     *  ├ m1            module: a › a.b (a leaf package)
     *  ├ m2            module: x › x.y (types t1, t2)
     *  ├ m3            module: p › p.q › { p.q.r › p.q.r.u (leaf), p.q.s (type t3) }
     *  ├ m4            module: k (type t4, package k.l (type t5))
     *  ├ m5            module: type t6 only
     *  └ g             a group (not foldable) holding one module m6: c › c.d (type t7)
     * ```
     */
    private class Tree {
        val graph = HGGraphFactory.createHGGraph()
        private var nextId = 1L
        val packages = mutableSetOf<HGNode>()
        private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
        val root = node()
        val hierarchy: Hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun child(parent: HGNode) = node().also { HierarchyFactory.addChild(hierarchy, parent, it) }
        private fun pkg(parent: HGNode) = child(parent).also { packages += it }

        val m1 = child(root)
        val a = pkg(m1)
        val ab = pkg(a)

        val m2 = child(root)
        val x = pkg(m2)
        val xy = pkg(x)
        val t1 = child(xy)
        val t2 = child(xy)

        val m3 = child(root)
        val p = pkg(m3)
        val pq = pkg(p)
        val pqr = pkg(pq)
        val pqru = pkg(pqr)
        val pqs = pkg(pq)
        val t3 = child(pqs)

        val m4 = child(root)
        val k = pkg(m4)
        val t4 = child(k)
        val kl = pkg(k)
        val t5 = child(kl)

        val m5 = child(root)
        val t6 = child(m5)

        val g = child(root)
        val m6 = child(g)
        val c = pkg(m6)
        val cd = pkg(c)
        val t7 = child(cd)

        fun dep(from: HGNode, to: HGNode, weight: Int = 1) = repeat(weight) {
            HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { DefaultDependencySource(nextId++) }
        }

        fun folding(h: Hierarchy = hierarchy) = ChainFolding(h) { it in packages }
    }

    private val tree = Tree()
    private val folding = tree.folding()

    @Test
    fun `a chain to a leaf package folds to the leaf`() {
        assertThat(folding.displayChildrenOf(tree.m1)).containsExactly(DisplayChild(tree.ab, listOf(tree.a)))
    }

    @Test
    fun `a chain ends at the first package with types`() {
        assertThat(folding.displayChildrenOf(tree.m2)).containsExactly(DisplayChild(tree.xy, listOf(tree.x)))
        assertThat(folding.displayChildrenOf(tree.xy).map { it.node }).containsExactly(tree.t1, tree.t2)
    }

    @Test
    fun `a chain ends at the first package with several children, whose children fold in turn`() {
        assertThat(folding.displayChildrenOf(tree.m3)).containsExactly(DisplayChild(tree.pq, listOf(tree.p)))
        assertThat(folding.displayChildrenOf(tree.pq))
            .containsExactly(DisplayChild(tree.pqru, listOf(tree.pqr)), DisplayChild(tree.pqs, emptyList()))
    }

    @Test
    fun `a package with types and one subpackage is not folded`() {
        assertThat(folding.isSkipped(tree.k)).isFalse()
        assertThat(folding.displayChildrenOf(tree.m4)).containsExactly(DisplayChild(tree.k, emptyList()))
        assertThat(folding.displayChildrenOf(tree.k).map { it.node }).containsExactly(tree.t4, tree.kl)
    }

    @Test
    fun `children that aren't packages are shown as they are`() {
        assertThat(folding.displayChildrenOf(tree.m5)).containsExactly(DisplayChild(tree.t6, emptyList()))
    }

    @Test
    fun `a chain never crosses a node that isn't foldable`() {
        // The root's, the group's and a module's only child are no packages: nothing folds there.
        assertThat(folding.displayChildrenOf(tree.root).map { it.node })
            .containsExactly(tree.m1, tree.m2, tree.m3, tree.m4, tree.m5, tree.g)
        assertThat(folding.displayChildrenOf(tree.g)).containsExactly(DisplayChild(tree.m6, emptyList()))
        assertThat(folding.displayChildrenOf(tree.m6)).containsExactly(DisplayChild(tree.cd, listOf(tree.c)))
        assertThat(folding.isSkipped(tree.m1)).isFalse()
    }

    @Test
    fun `a folded node knows what it stands for, where it is shown, and its display ancestors`() {
        assertThat(folding.skippedAbove(tree.pqru)).containsExactly(tree.pqr)
        assertThat(folding.skippedAbove(tree.pq)).containsExactly(tree.p)
        assertThat(folding.skippedAbove(tree.pqs)).isEmpty()
        // A skipped node isn't shown: it stands for nothing, and is revealed at its chain's end.
        assertThat(folding.skippedAbove(tree.p)).isEmpty()
        assertThat(folding.displayNodeOf(tree.p)).isEqualTo(tree.pq)
        assertThat(folding.displayNodeOf(tree.pqr)).isEqualTo(tree.pqru)
        assertThat(folding.displayNodeOf(tree.t3)).isEqualTo(tree.t3)

        assertThat(folding.displayParentOf(tree.pqru)).isEqualTo(tree.pq)
        assertThat(folding.displayParentOf(tree.pq)).isEqualTo(tree.m3)
        assertThat(folding.displayPredecessorsOf(tree.pqru)).containsExactly(tree.pq, tree.m3, tree.root)
    }

    @Test
    fun `a move into a skipped package cuts the chain there, in the fork only`() {
        val fork = tree.hierarchy.fork()
        fork.move(tree.t6, tree.p)

        val inFork = tree.folding(fork)
        assertThat(inFork.isSkipped(tree.p)).isFalse()
        assertThat(inFork.displayChildrenOf(tree.m3)).containsExactly(DisplayChild(tree.p, emptyList()))
        assertThat(inFork.displayChildrenOf(tree.p).map { it.node }).containsExactly(tree.pq, tree.t6)
        // The base hierarchy still folds it.
        assertThat(folding.displayChildrenOf(tree.m3)).containsExactly(DisplayChild(tree.pq, listOf(tree.p)))
    }

    @Test
    fun `levelizing the display children names the chain ends, with the weights of the chains`() {
        // m1's and m2's chains depend on each other: a tangle between their ends.
        tree.dep(tree.ab, tree.t1, 3)
        tree.dep(tree.t2, tree.ab)
        val ends = listOf(tree.m1, tree.m2).flatMap { folding.displayChildrenOf(it) }.map { it.node }

        val levelization = GraphUtils.levelize(ends, tree.hierarchy)

        assertThat(levelization.tangles.single().members).containsExactlyInAnyOrder(tree.ab, tree.xy)
        val feedback = levelization.feedbackDependencies.single()
        assertThat(feedback.from to feedback.to).isEqualTo(tree.xy to tree.ab)
        assertThat(feedback.aggregatedWeight).isEqualTo(1)
        // The same weight as between the chains' starts: folding changes no aggregation.
        assertThat(tree.hierarchy.getAggregatedDependency(tree.a, tree.x)?.aggregatedWeight).isEqualTo(3)
        assertThat(tree.hierarchy.getAggregatedDependency(tree.ab, tree.xy)?.aggregatedWeight).isEqualTo(3)
    }
}
