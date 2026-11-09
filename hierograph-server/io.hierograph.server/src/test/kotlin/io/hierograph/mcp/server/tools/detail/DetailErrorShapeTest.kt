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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies that the detail tools and the detail-level engine report errors in the nested shape every
 * tool uses: `{"error": {"code", "message", "recovery"}}`.
 */
class DetailErrorShapeTest {

    private val graphService = HierarchicalGraphService().also {
        val graph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 1L) }
        it.seed(HGModel(graph, HierarchyFactory.createHierarchy(graph, root)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun error(resp: Map<String, Any?>): Map<String, Any?> {
        assertThat(resp["error"]).isInstanceOf(Map::class.java)
        val error = resp["error"] as Map<String, Any?>
        assertThat(error.keys).contains("code", "message", "recovery")
        assertThat(error.keys.first()).isEqualTo("code")
        assertThat(error.keys.last()).isEqualTo("recovery")
        return error
    }

    @Test
    fun `method_details and field_details nest NODE_NOT_FOUND`() {
        assertThat(error(ParquetMethodDetails(graphService).methodDetails(99L))["code"]).isEqualTo("NODE_NOT_FOUND")
        assertThat(error(ParquetFieldDetails(graphService).fieldDetails(99L))["code"]).isEqualTo("NODE_NOT_FOUND")
    }

    @Test
    fun `the detail-level engine nests INVALID_RELATIONSHIP and DETAIL_UNAVAILABLE`() {
        val engine = ParquetDetailDependencies(graphService, DataHashProvider(graphService))
        val spec = OutgoingDependenciesTool.DETAIL_SPEC
        assertThat(error(engine.detailDependencies(1L, 1L, "bogus", null, null, spec))["code"])
            .isEqualTo("INVALID_RELATIONSHIP")
        assertThat(error(engine.detailDependencies(1L, 1L, null, null, null, spec))["code"])
            .isEqualTo("DETAIL_UNAVAILABLE")
    }
}
