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
package io.hierograph.mcp.server.graphql

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.mcp.server.instance.ServerInstance
import io.hierograph.mcp.server.tools.admin.ServerInfoTool
import io.hierograph.server.graphservice.GraphState
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.mapping.GraphStoreToHGModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.util.ReflectionTestUtils
import java.nio.file.Path

/** `server` over a loaded graph: the graph status and counts agree with what `server_info` reports. */
@Suppress("UNCHECKED_CAST")
class ServerInfoControllerTest {

    @TempDir
    lateinit var stateDir: Path

    private fun graphService() = HierarchicalGraphService().also { service ->
        ReflectionTestUtils.setField(service, "stateDirSetting", stateDir.toString())
        val store = GraphStore().apply {
            registerArtifact(ArtifactInfo("billing-core", "billing-core", "billing-core"))
            applyClass("billing-core", ClassFacts("com.example.Invoice", JavaNodeKind.CLASS, 0, emptyList(), classHash = "h"), emptyList())
        }
        service.seed(GraphStoreToHGModel.build(store))
    }

    private fun instance(service: HierarchicalGraphService): ServerInstance {
        val beans = DefaultListableBeanFactory()
        return ServerInstance(
            MockEnvironment(),
            service,
            beans.getBeanProvider(BuildProperties::class.java),
            beans.getBeanProvider(GitProperties::class.java),
        )
    }

    @Test
    fun `a loaded graph is reported with its generation, hash and counts, as server_info has them`() {
        val service = graphService()
        val instance = instance(service)
        val controller = ServerInfoController(instance, service)
        val snapshot = ServerInfoTool(instance).serverInfo()["snapshot"] as Map<String, Any?>

        val graph = controller.graph(controller.server())
        assertThat(graph.state).isEqualTo(GraphState.LOADED)
        assertThat(graph.update).isNull() // no updater outside manifest mode
        assertThat(graph.generation).isEqualTo(snapshot["generation"].toString())
        assertThat(graph.dataHash).isEqualTo(snapshot["data_hash"])
        assertThat(graph.loadedAt).isNotNull()

        val counts = controller.counts(graph)!!
        assertThat(counts.modules).isEqualTo(1)
        assertThat(counts.types).isEqualTo(1)
        assertThat(counts.total).isEqualTo(snapshot["total_nodes"])
    }

    @Test
    fun `the source and artifacts are the graph service's, as server_info has them`() {
        val service = graphService()
        val controller = ServerInfoController(instance(service), service)

        assertThat(controller.source(controller.server()).toMap()).isEqualTo(service.sourceSettings())
        assertThat(controller.artifacts(controller.server())).isNull() // not in manifest mode
    }

    @Test
    fun `the heap reports what the last collection left in use`() {
        val service = graphService()
        val controller = ServerInfoController(instance(service), service)
        System.gc()

        val heap = controller.jvm(controller.server()).heap
        assertThat(heap.usedAfterGcBytes).isNotNull().isPositive().isLessThanOrEqualTo(heap.committedBytes)
    }

    @Test
    fun `the JVM's metrics are read on each request`() {
        val service = graphService()
        val controller = ServerInfoController(instance(service), service)

        val jvm = controller.jvm(controller.server())
        assertThat(jvm.javaVersion).isEqualTo(System.getProperty("java.version"))
        assertThat(jvm.heap.usedBytes).isPositive().isLessThanOrEqualTo(jvm.heap.committedBytes)
        jvm.heap.maxBytes?.let { assertThat(it).isGreaterThanOrEqualTo(jvm.heap.committedBytes) }
        jvm.nonHeap.maxBytes?.let { assertThat(it).isPositive() } // often no limit
        assertThat(jvm.nonHeap.usedAfterGcBytes).isNull()
        assertThat(jvm.garbageCollectors).isNotEmpty().allSatisfy { assertThat(it.count).isNotNegative() }
        assertThat(jvm.threads.live).isLessThanOrEqualTo(jvm.threads.peak)
        jvm.processCpuLoad?.let { assertThat(it).isBetween(0.0, 1.0) }
        assertThat(controller.jvm(controller.server()).uptimeMs).isGreaterThanOrEqualTo(jvm.uptimeMs)
    }
}
