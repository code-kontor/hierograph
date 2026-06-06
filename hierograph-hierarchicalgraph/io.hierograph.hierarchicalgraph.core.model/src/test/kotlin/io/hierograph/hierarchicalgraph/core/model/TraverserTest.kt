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
    fun `traverse visits all descendants`() {
        val visited = mutableListOf<CoreNode>()
        g.hierarchy.traverse(g.root) { visited.add(it) }
        assertThat(visited).hasSize(6)
        assertThat(visited).contains(g.a1, g.a2, g.a3, g.b1, g.b2, g.b3)
    }

    @Test
    fun `traverse subtree`() {
        val visited = mutableListOf<CoreNode>()
        g.hierarchy.traverse(g.a1) { visited.add(it) }
        assertThat(visited).hasSize(2)
        assertThat(visited).containsExactly(g.a2, g.a3)
    }

    @Test
    fun `traverse with filter prunes subtrees`() {
        // filter returns true only for direct children of root (depth 1)
        // so a2, a3, b2, b3 are not visited because a1/b1's children are pruned
        val visited = mutableListOf<CoreNode>()
        g.hierarchy.traverse(g.root, { visited.add(it) }) { node ->
            // only allow root's direct children (a1, b1) -- prune deeper
            g.hierarchy.parentOf(node) === g.root
        }
        assertThat(visited).hasSize(2)
        assertThat(visited).contains(g.a1, g.b1)
    }

    @Test
    fun `traverse with filter that accepts all behaves like traverse`() {
        val visited = mutableListOf<CoreNode>()
        g.hierarchy.traverse(g.root, { visited.add(it) }) { true }
        assertThat(visited).hasSize(6)
        assertThat(visited).contains(g.a1, g.a2, g.a3, g.b1, g.b2, g.b3)
    }

    @Test
    fun `traverse with filter that rejects all visits nothing`() {
        val visited = mutableListOf<CoreNode>()
        g.hierarchy.traverse(g.root, { visited.add(it) }) { false }
        assertThat(visited).isEmpty()
    }

    @Test
    fun `traverse leaf node visits nothing`() {
        val visited = mutableListOf<CoreNode>()
        g.hierarchy.traverse(g.a3) { visited.add(it) }
        assertThat(visited).isEmpty()
    }
}
