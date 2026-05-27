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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TraverserTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `traverse visits all nodes`() {
        val visited = mutableListOf<HGNode>()
        HGNodeTraverser.traverse(g.root) { visited.add(it) }
        assertThat(visited).hasSize(7)
        assertThat(visited).contains(g.root, g.a1, g.a2, g.a3, g.b1, g.b2, g.b3)
    }

    @Test
    fun `traverse with filter only executes action on matching nodes`() {
        val visited = mutableListOf<HGNode>()
        HGNodeTraverser.traverse(g.a1, { visited.add(it) }) { it === g.a2 }
        assertThat(visited).containsExactly(g.a2)
    }

    @Test
    fun `traverse with filter still visits children of non-matching nodes`() {
        val visited = mutableListOf<HGNode>()
        HGNodeTraverser.traverse(g.a1, { visited.add(it) }) { it === g.a3 }
        assertThat(visited).containsExactly(g.a3)
    }

    @Test
    fun `traverseWithPruning prunes subtrees`() {
        val visited = mutableListOf<HGNode>()
        HGNodeTraverser.traverseWithPruning(g.root, { visited.add(it) }) { it === g.root }
        assertThat(visited).hasSize(3)
        assertThat(visited).contains(g.root, g.a1, g.b1)
    }

    @Test
    fun `traverse subtree`() {
        val visited = mutableListOf<HGNode>()
        HGNodeTraverser.traverse(g.a1) { visited.add(it) }
        assertThat(visited).hasSize(3)
        assertThat(visited).containsExactly(g.a1, g.a2, g.a3)
    }
}
