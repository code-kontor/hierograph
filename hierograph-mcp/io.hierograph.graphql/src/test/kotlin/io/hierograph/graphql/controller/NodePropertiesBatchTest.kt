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

import io.hierograph.graphql.HierarchicalGraphProvider
import io.hierograph.graphql.NodePropertiesProvider
import io.hierograph.graphql.model.MapEntryModel
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NodePropertiesBatchTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private fun node(): HGNode = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val root = node()
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
    private val graphProvider = HierarchicalGraphProvider { hierarchy }

    @Test
    fun `all nodes of a batch are delegated in a single provider call`() {
        val a = node()
        val b = node()
        val calls = mutableListOf<List<HGNode>>()
        val provider = NodePropertiesProvider { nodes ->
            calls += nodes
            mapOf(a to mapOf("fqn" to "com.foo.A", "typeCount" to "3"))
        }

        val result = NodeController(graphProvider, provider).properties(listOf(a, b))

        assertThat(calls).containsExactly(listOf(a, b))
        assertThat(result.getValue(a)).containsExactly(MapEntryModel("fqn", "com.foo.A"), MapEntryModel("typeCount", "3"))
        // a node the provider did not answer still gets an (empty) entry: the schema field is non-null
        assertThat(result.getValue(b)).isEmpty()
    }

    @Test
    fun `without a provider every node gets an empty property list`() {
        val a = node()

        val result = NodeController(graphProvider).properties(listOf(a))

        assertThat(result).containsOnlyKeys(a)
        assertThat(result.getValue(a)).isEmpty()
    }
}
