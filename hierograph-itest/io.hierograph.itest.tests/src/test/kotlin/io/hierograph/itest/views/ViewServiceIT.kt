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
package io.hierograph.itest.views

import io.hierograph.hierarchicalgraph.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.itest.fwk.AbstractHierarchicalGraphIntegrationTest
import io.hierograph.server.graphservice.views.ViewRegistry
import io.hierograph.server.graphservice.views.ViewService
import io.hierograph.server.graphservice.views.ViewSource
import io.hierograph.server.views.ViewGroups
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * [ViewService] over the served Spring 7.0.8 graph: a project view materialized as a snapshot, its
 * group-level aggregation, search and data hash, and a runtime definition shadowing it.
 */
class ViewServiceIT : AbstractHierarchicalGraphIntegrationTest() {

    @TempDir
    lateinit var tmp: Path

    private val views by lazy { ViewService(graphService, ViewRegistry(tmp.resolve("project"), tmp.resolve("state"))) }

    private val springModules = """
        {
          "name": "spring-modules",
          "groups": [
            { "name": "core-container", "members": ["spring-{core,beans,context,expression,aop}-7.0.8.jar"] },
            { "name": "data-access",    "members": ["spring-{jdbc,tx,orm,r2dbc,oxm,jms}-*.jar"] },
            { "name": "web",            "members": ["spring-web-*.jar", "spring-web{mvc,flux,socket}-*.jar"] }
          ]
        }
    """

    private fun project(text: String) {
        Files.createDirectories(tmp.resolve("project"))
        tmp.resolve("project").resolve("spring-modules.json").writeText(text)
    }

    @Test
    fun `a project view becomes a snapshot whose top level is the groups and the ungrouped jars`() {
        project(springModules)

        val view = views.view("spring-modules")
        val h = view.snapshot.model.hierarchy

        assertThat(view.materialized.report.findings).isEmpty()
        val top = h.childrenOf(h.rootNode)
        assertThat(top).hasSize(22 - 15 + 3)
        assertThat(top.filter { ViewGroups.isGroup(it) }.map { NodeMetadata.getName(it) })
            .containsExactly("core-container", "data-access", "web")
        assertThat(model.hierarchy.childrenOf(model.hierarchy.rootNode)).hasSize(22)
    }

    @Test
    fun `the group-level DSM equals the sums over the member jars`() {
        project(springModules)
        val view = views.view("spring-modules")
        val h = view.snapshot.model.hierarchy
        val base = model.hierarchy
        val groups = listOf("core-container", "data-access", "web").map { view.materialized.groupNodes.getValue(it) }

        val matrix = GraphUtils.computeAdjacencyMatrix(groups, h)

        for (i in groups.indices) for (j in groups.indices) {
            if (i == j) continue
            val expected = h.childrenOf(groups[i]).sumOf { from ->
                h.childrenOf(groups[j]).sumOf { to -> base.getAggregatedDependency(from, to)?.aggregatedWeight ?: 0 }
            }
            assertThat(matrix[i][j]).isEqualTo(expected)
        }
        val web = groups.indexOfFirst { NodeMetadata.getName(it) == "web" }
        val core = groups.indexOfFirst { NodeMetadata.getName(it) == "core-container" }
        assertThat(matrix[web][core]).isPositive()
    }

    @Test
    fun `the view's search finds the groups and its data hash is its own`() {
        project(springModules)
        val base = graphService.liveSnapshot()!!

        val snapshot = views.view("spring-modules").snapshot

        assertThat(snapshot.searchIndex.entries.filter { it.fqnLower.startsWith("spring-modules:") }.map { it.nameLower })
            .containsExactlyInAnyOrder("core-container", "data-access", "web")
        assertThat(snapshot.searchIndex.entries).hasSize(base.searchIndex.entries.size + 3)
        assertThat(snapshot.dataHash).isNotEqualTo(base.dataHash)
        assertThat(views.view("spring-modules")).isSameAs(views.view("spring-modules"))
    }

    @Test
    fun `a runtime definition shadows the project view until it is deleted`() {
        project(springModules)

        val defined = views.define(springModules.replace("\"web\"", "\"web-stack\""))
        assertThat(defined.entry.source).isEqualTo(ViewSource.STATE)
        assertThat(defined.entry.shadowsProjectView).isTrue()
        assertThat(defined.materialized.groupNodes.keys).contains("web-stack").doesNotContain("web")

        views.delete("spring-modules")
        assertThat(views.view("spring-modules").materialized.groupNodes.keys).contains("web")
    }
}
