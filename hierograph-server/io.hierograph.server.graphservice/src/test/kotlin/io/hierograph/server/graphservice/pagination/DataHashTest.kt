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
package io.hierograph.server.graphservice.pagination

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DataHashTest {

    /**
     * Builds a fresh graph from a recipe of (parent-index -> child) so independent runs produce
     * structurally identical graphs but with their own node-source counters.
     *
     * The recipe is a list of parent ordinals: entry i creates a node whose parent is the node with
     * ordinal `recipe[i]` (or the root when the ordinal is negative). Each node is given a stable kind.
     */
    private fun buildGraph(recipe: List<Int>, kindOf: (Int) -> Any = { "kind$it" }): HGModel {
        var nextId = 1L
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        val nodes = ArrayList<HGNode>()
        recipe.forEachIndexed { i, parentOrdinal ->
            val parent = if (parentOrdinal < 0) root else nodes[parentOrdinal]
            val node = HGGraphFactory.createNode(graph, nodeSource)
            node.kind = kindOf(i)
            HierarchyFactory.addChild(hierarchy, parent, node)
            nodes.add(node)
        }
        return HGModel(graph, hierarchy)
    }

    @Test
    fun `the fingerprint is deterministic for the same graph`() {
        val model = buildGraph(listOf(-1, 0, 0, -1))
        assertThat(DataHash.fingerprint(model)).isEqualTo(DataHash.fingerprint(model))
    }

    @Test
    fun `structurally identical graphs share a fingerprint`() {
        // Same shape and same node identifiers (both counters start at 1), independently built.
        val a = buildGraph(listOf(-1, 0, 0, -1))
        val b = buildGraph(listOf(-1, 0, 0, -1))
        assertThat(DataHash.fingerprint(a)).isEqualTo(DataHash.fingerprint(b))
    }

    @Test
    fun `adding a node changes the fingerprint`() {
        val small = buildGraph(listOf(-1, 0, 0))
        val larger = buildGraph(listOf(-1, 0, 0, -1))
        assertThat(DataHash.fingerprint(small)).isNotEqualTo(DataHash.fingerprint(larger))
    }

    @Test
    fun `changing a node kind changes the fingerprint`() {
        val model = buildGraph(listOf(-1, 0))
        val baseline = DataHash.fingerprint(model)
        model.hierarchy.childrenOf(model.hierarchy.rootNode).first().kind = "mutated"
        assertThat(DataHash.fingerprint(model)).isNotEqualTo(baseline)
    }

    @Test
    fun `an enum kind hashes like its text, so the fingerprint is stable across processes`() {
        // An enum's own hashCode() is an identity hash that differs between JVM runs; the fingerprint
        // must depend only on the kind's text.
        val kinds = listOf(JavaNodeKind.MODULE, JavaNodeKind.PACKAGE, JavaNodeKind.CLASS, JavaNodeKind.METHOD)
        val withEnums = buildGraph(listOf(-1, 0, 1, 2)) { kinds[it] }
        val withText = buildGraph(listOf(-1, 0, 1, 2)) { kinds[it].toString() }
        assertThat(DataHash.fingerprint(withEnums)).isEqualTo(DataHash.fingerprint(withText))
    }

    @Test
    fun `the fingerprint of a fixed graph is a fixed value`() {
        // Pins the fingerprint across JVM runs: a cursor issued before a restart must still resume.
        val kinds = listOf(JavaNodeKind.MODULE, JavaNodeKind.PACKAGE, JavaNodeKind.CLASS, JavaNodeKind.METHOD)
        assertThat(DataHash.fingerprint(buildGraph(listOf(-1, 0, 1, 2)) { kinds[it] })).isEqualTo(PINNED)
    }

    @Test
    fun `the fingerprint is a 16-character url-safe string`() {
        val model = buildGraph(listOf(-1, 0))
        assertThat(DataHash.fingerprint(model)).hasSize(16).matches("[A-Za-z0-9_-]+")
    }

    private companion object {
        const val PINNED = "beZ9AKiu3E3AwoCt"
    }
}
