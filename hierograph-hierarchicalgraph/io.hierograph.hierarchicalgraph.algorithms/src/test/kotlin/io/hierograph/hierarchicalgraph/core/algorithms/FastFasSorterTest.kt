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
package io.hierograph.hierarchicalgraph.core.algorithms

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FastFasSorterTest {

    private lateinit var g: AlgorithmTestGraph

    @BeforeEach
    fun setup() { g = AlgorithmTestGraph() }

    @Test
    fun `sort returns all nodes`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(g.nodes, g.hierarchy)
        assertThat(result.orderedNodes).hasSize(8)
        assertThat(result.orderedNodes).containsExactlyInAnyOrderElementsOf(g.nodes)
    }

    @Test
    fun `sort of acyclic graph has no upward dependencies`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(listOf(g.n4, g.n5), g.hierarchy)
        assertThat(result.upwardDependencies).isEmpty()
    }

    @Test
    fun `sort of cyclic graph has upward dependencies`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(listOf(g.n1, g.n2, g.n3), g.hierarchy)
        assertThat(result.upwardDependencies).isNotEmpty()
    }

    @Test
    fun `sort preserves node identity`() {
        val sorter = GraphUtils.createFasNodeSorter()
        val result = sorter.sort(g.nodes, g.hierarchy)
        for (node in g.nodes) {
            assertThat(result.orderedNodes).contains(node)
        }
    }
}
