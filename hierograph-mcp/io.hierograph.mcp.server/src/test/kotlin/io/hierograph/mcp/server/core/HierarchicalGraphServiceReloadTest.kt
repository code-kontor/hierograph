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
package io.hierograph.mcp.server.core

import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import io.hierograph.mcp.server.core.pagination.DataHash
import io.hierograph.mcp.server.core.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Covers the snapshot-swap behavior added for `reload_graph`: installing a new snapshot swaps the
 * model, its search index, and its data hash together, and [DataHashProvider] observes the change
 * live. [HierarchicalGraphService.seed] is the non-Bolt entry point that performs the exact same
 * atomic swap as [HierarchicalGraphService.reload], so exercising it validates the swap and the
 * live delegation without needing a live Bolt store.
 */
class HierarchicalGraphServiceReloadTest {

    private fun buildGraph(recipe: List<Int>): HGModel {
        var nextId = 1L
        val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph, nodeSource)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        val nodes = ArrayList<HGNode>()
        recipe.forEachIndexed { i, parentOrdinal ->
            val parent = if (parentOrdinal < 0) root else nodes[parentOrdinal]
            val node = HGGraphFactory.createNode(graph, nodeSource)
            node.kind = "kind$i"
            HierarchyFactory.addChild(hierarchy, parent, node)
            nodes.add(node)
        }
        return HGModel(graph, hierarchy)
    }

    @Test
    fun `seed installs the model, its search index, and its data hash together`() {
        val model = buildGraph(listOf(-1, 0, 0))
        val service = HierarchicalGraphService().also { it.seed(model) }

        assertThat(service.model).isSameAs(model)
        assertThat(service.dataHash).isEqualTo(DataHash.fingerprint(model))
        assertThat(service.searchIndex.entries).isNotEmpty()
    }

    @Test
    fun `re-seeding swaps in the new snapshot and its new data hash`() {
        val first = buildGraph(listOf(-1, 0, 0))
        val second = buildGraph(listOf(-1, 0, 0, -1)) // one extra node -> different fingerprint
        val service = HierarchicalGraphService().also { it.seed(first) }
        val firstHash = service.dataHash

        service.seed(second)

        assertThat(service.model).isSameAs(second)
        assertThat(service.dataHash)
            .isEqualTo(DataHash.fingerprint(second))
            .isNotEqualTo(firstHash)
    }

    @Test
    fun `DataHashProvider observes the swapped hash live`() {
        val first = buildGraph(listOf(-1, 0, 0))
        val second = buildGraph(listOf(-1, 0, 0, -1))
        val service = HierarchicalGraphService().also { it.seed(first) }
        val provider = DataHashProvider(service)
        val before = provider.dataHash

        service.seed(second)

        assertThat(provider.dataHash)
            .isEqualTo(service.dataHash)
            .isNotEqualTo(before)
    }

    @Test
    fun `reading the graph before it is loaded fails clearly`() {
        val service = HierarchicalGraphService()
        assertThrows<IllegalStateException> { service.model }
    }
}
