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
package io.hierograph.mcp.server.tools.navigation

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.mapping.GraphStoreToHGModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Verifies the stats and vocabularies of [GraphOverviewTool]. */
@Suppress("UNCHECKED_CAST")
class GraphOverviewToolTest {

    private val store = GraphStore().apply {
        registerArtifact(ArtifactInfo("billing-core", "billing-core", "billing-core"))
        applyClass(
            "billing-core",
            ClassFacts(
                "com.example.billing.Invoice", JavaKinds.CLASS, 0, emptyList(),
                refs = listOf(
                    Ref("org.slf4j.Logger", RefKind.INVOKES, count = 1),
                    Ref("com.example.money.Money", RefKind.INVOKES, count = 2),
                ),
                classHash = "h",
            ),
        )
        applyClass("billing-core", ClassFacts("com.example.money.Money", JavaKinds.RECORD, 0, emptyList(), emptyList(), "m"))
    }
    private val graphService = HierarchicalGraphService().also { it.seed(GraphStoreToHGModel.build(store)) }
    private val overview = GraphOverviewTool(graphService, NodeRefFactory(graphService)).graphOverview()

    @Test
    fun `node counts include the modules and every node below the root`() {
        val stats = overview["stats"] as Map<String, Any?>
        val byKind = stats["nodes_by_kind"] as Map<String, Int>
        assertThat(byKind["java.module"]).isEqualTo(2) // billing-core and External Types
        assertThat(byKind["external.type"]).isEqualTo(1)
        assertThat(byKind["java.record"]).isEqualTo(1)
        assertThat(stats["total_nodes"]).isEqualTo(store.nodes().size)
        assertThat(byKind.values.sum()).isEqualTo(stats["total_nodes"])
    }

    @Test
    fun `the kinds vocabulary names external types`() {
        val structural = (overview["kinds"] as Map<String, Any?>)["structural"] as List<Map<String, Any?>>
        assertThat(structural.map { it["kind"] }).contains("java.module", "external.type")
    }

    @Test
    fun `the detail vocabulary lists the relationships the snapshot holds`() {
        val detail = (overview["relationships"] as Map<String, Any?>)["detail_level"] as List<Map<String, Any?>>
        assertThat(detail.map { it["kind"] }).contains("raises", "throws", "parameter_annotated_by")
    }

    @Test
    fun `module coupling counts only dependencies that cross the module boundary`() {
        val modules = (overview["hierarchy"] as List<Map<String, Any?>>).associateBy { it["name"] }
        // Invoice -> Money stays inside billing-core; Invoice -> Logger leaves it.
        assertThat(modules["billing-core"]!!["outgoing_dep_count"]).isEqualTo(1)
        assertThat(modules["billing-core"]!!["incoming_dep_count"]).isEqualTo(0)
        assertThat(modules["External Types"]!!["incoming_dep_count"]).isEqualTo(1)
    }

    @Test
    fun `the model text names the camelCase parameters`() {
        val text = overview["model"].toString()
        assertThat(text).contains("sourceIds", "detailLevel").doesNotContain("source_ids", "detail_level")
    }
}
