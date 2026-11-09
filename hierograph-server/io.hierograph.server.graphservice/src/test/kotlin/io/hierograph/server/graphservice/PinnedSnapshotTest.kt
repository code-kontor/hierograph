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
package io.hierograph.server.graphservice

import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Verifies [HierarchicalGraphService.withPinnedSnapshot]: a call sees one snapshot from start to end,
 * even when another one is swapped in meanwhile.
 */
class PinnedSnapshotTest {

    private fun model(children: Int): HGModel {
        var id = 1L
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = id++) }
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        repeat(children) { HierarchyFactory.addChild(hierarchy, root, HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = id++) }) }
        return HGModel(graph, hierarchy)
    }

    private val old = model(1)
    private val new = model(2)
    private val service = HierarchicalGraphService().also { it.seed(old) }

    @Test
    fun `a pinned call keeps reading the snapshot it started with`() {
        val oldHash = service.dataHash
        service.withPinnedSnapshot {
            service.seed(new) // a reload swaps in another graph mid-call
            assertThat(service.model).isSameAs(old)
            assertThat(service.dataHash).isEqualTo(oldHash)
        }
        assertThat(service.model).isSameAs(new)
        assertThat(service.dataHash).isNotEqualTo(oldHash)
    }

    @Test
    fun `the pin is per thread`() {
        service.withPinnedSnapshot {
            service.seed(new)
            val elsewhere = CompletableFuture.supplyAsync { service.model }.get()
            assertThat(elsewhere).isSameAs(new)
            assertThat(service.model).isSameAs(old)
        }
    }

    @Test
    fun `a nested pin keeps the outer snapshot`() {
        service.withPinnedSnapshot {
            service.seed(new)
            service.withPinnedSnapshot { assertThat(service.model).isSameAs(old) }
            assertThat(service.model).isSameAs(old)
        }
    }
}
