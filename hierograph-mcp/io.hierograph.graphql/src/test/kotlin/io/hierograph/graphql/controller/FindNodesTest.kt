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
package io.hierograph.graphql.controller

import io.hierograph.graphql.NodeSearchProvider
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FindNodesTest {

    private val graph = HGGraphFactory.createHGGraph()
    private val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 1L) }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
    private val hit = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 2L) }

    @Test
    fun `delegates name, kinds and limit to the search provider`() {
        val calls = mutableListOf<Triple<String, List<String>?, Int>>()
        val provider = NodeSearchProvider { name, kinds, limit -> calls += Triple(name, kinds, limit); listOf<HGNode>(hit) }

        val result = HierarchicalGraphController(provider).findNodes(hierarchy, "Handler", listOf("types"), 10)

        assertThat(result).containsExactly(hit)
        assertThat(calls).containsExactly(Triple("Handler", listOf("types"), 10))
    }

    @Test
    fun `rejects an out-of-range limit, a blank name, and a missing search index`() {
        val controller = HierarchicalGraphController { _, _, _ -> emptyList() }

        assertThatThrownBy { controller.findNodes(hierarchy, "x", null, 0) }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("limit")
        assertThatThrownBy { controller.findNodes(hierarchy, "x", null, HierarchicalGraphController.MAX_FIND_LIMIT + 1) }
            .isInstanceOf(InvalidArgumentException::class.java)
        assertThatThrownBy { controller.findNodes(hierarchy, "  ", null, 10) }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("name")
        assertThatThrownBy { HierarchicalGraphController().findNodes(hierarchy, "x", null, 10) }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("not available")
    }
}
