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
package io.hierograph.mcp.server.instance

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.mcp.server.tools.admin.ServerInfoTool
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
import java.util.Properties

/** Verifies what [ServerInstance] and `server_info` report, and the runtime file's lifecycle. */
@Suppress("UNCHECKED_CAST")
class ServerInstanceTest {

    @TempDir
    lateinit var stateDir: Path

    private val environment = MockEnvironment()

    private fun graphService(seeded: Boolean = true) = HierarchicalGraphService().also { service ->
        ReflectionTestUtils.setField(service, "stateDirSetting", stateDir.toString())
        if (seeded) {
            val store = GraphStore().apply {
                registerArtifact(ArtifactInfo("billing-core", "billing-core", "billing-core"))
                applyClass("billing-core", ClassFacts("com.example.Invoice", JavaNodeKind.CLASS, 0, emptyList(), classHash = "h"), emptyList())
            }
            service.seed(GraphStoreToHGModel.build(store))
        }
    }

    private fun instance(service: HierarchicalGraphService = graphService(), withBuildInfo: Boolean = false): ServerInstance {
        val beans = DefaultListableBeanFactory()
        if (withBuildInfo) {
            beans.registerSingleton("build", BuildProperties(Properties().apply {
                setProperty("version", "0.3.0-SNAPSHOT")
                setProperty("time", "2026-11-14T13:36:00Z")
            }))
            beans.registerSingleton("git", GitProperties(Properties().apply {
                setProperty("commit.id", "cd897fe0123456789")
                setProperty("commit.id.abbrev", "cd897fe0")
                setProperty("dirty", "true")
            }))
        }
        return ServerInstance(environment, service, beans.getBeanProvider(BuildProperties::class.java), beans.getBeanProvider(GitProperties::class.java))
    }

    @Test
    fun `server_info describes the instance and the graph it serves`() {
        val instance = instance(withBuildInfo = true).apply { serverStarted(8765) }
        val info = ServerInfoTool(instance).serverInfo()

        assertThat(info["url"]).isEqualTo("http://localhost:8765/mcp")
        assertThat(info["ui_url"]).isEqualTo("http://localhost:8765/ui/")
        assertThat(info["port"]).isEqualTo(8765)
        assertThat(info["pid"]).isEqualTo(ProcessHandle.current().pid())
        assertThat(info["version"]).isEqualTo("0.3.0-SNAPSHOT")
        assertThat(info["commit"] as Map<String, Any?>).containsEntry("id", "cd897fe0").containsEntry("dirty", true)
        assertThat(info["build_time"]).isEqualTo("2026-11-14T13:36:00Z")
        assertThat(info["state_dir"]).isEqualTo(stateDir.toString())
        assertThat(info["source"] as Map<String, Any?>).containsEntry("kind", null)
        assertThat(info["artifacts"]).isNull() // not in manifest mode
        val snapshot = info["snapshot"] as Map<String, Any?>
        assertThat(snapshot["total_nodes"] as Int).isGreaterThan(0)
        assertThat(snapshot["data_hash"] as String).hasSize(16)
        assertThat(info["runtime_file"]).isNull()
    }

    @Test
    fun `without a graph server_info still answers`() {
        val info = instance(graphService(seeded = false)).info()
        assertThat(info["snapshot"]).isNull()
        assertThat(info["url"]).isNull() // no web server started
        assertThat(info["ui_url"]).isNull()
    }

    @Test
    fun `the advertised url and workspace can be set, as a container does`() {
        environment.setProperty("hierograph.server.url", "http://localhost:9000/mcp")
        environment.setProperty("hierograph.server.workspace", "/home/dev/project")
        val info = instance().apply { serverStarted(8765) }.info()
        assertThat(info["url"]).isEqualTo("http://localhost:9000/mcp")
        assertThat(info["ui_url"]).isEqualTo("http://localhost:9000/ui/")
        assertThat(info["workspace"]).isEqualTo("/home/dev/project")
        assertThat(info["port"]).isEqualTo(8765)
    }

    @Test
    fun `once ready the server writes the runtime file, and deletes it on shutdown`() {
        val instance = instance().apply { serverStarted(8765) }
        instance.ready()

        val file = RuntimeFile.inStateDir(stateDir)
        val recorded = file.read()!!
        assertThat(recorded).containsEntry("url", "http://localhost:8765/mcp").containsEntry("port", 8765)
        assertThat((recorded["pid"] as Number).toLong()).isEqualTo(ProcessHandle.current().pid())
        assertThat(recorded.keys).containsAll(instance.identity().keys)
        assertThat(instance.info()["runtime_file"]).isEqualTo(file.path.toString())

        instance.closed()
        assertThat(file.path).doesNotExist()
    }

    @Test
    fun `shutdown keeps a runtime file another server has written since`() {
        val instance = instance().apply { serverStarted(8765) }
        instance.ready()
        val file = RuntimeFile.inStateDir(stateDir)
        file.write(mapOf("pid" to 1, "port" to 9000))
        instance.closed()
        assertThat(file.read()).containsEntry("port", 9000)
    }

    @Test
    fun `without a web server nothing is written`() {
        instance().ready()
        assertThat(RuntimeFile.inStateDir(stateDir).path).doesNotExist()
    }

    @Test
    fun `the runtime file can be turned off`() {
        environment.setProperty("hierograph.server.runtime-file.enabled", "false")
        instance().apply { serverStarted(8765) }.ready()
        assertThat(RuntimeFile.inStateDir(stateDir).path).doesNotExist()
    }

    @Test
    fun `the ready line names the workspace and the graph size`() {
        environment.setProperty("hierograph.server.workspace", "/ws")
        assertThat(instance().readySummary()).matches("workspace /ws, \\d+ nodes")
        assertThat(instance(graphService(seeded = false)).readySummary()).isEqualTo("workspace /ws, no graph loaded")
    }
}
