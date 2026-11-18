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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.hierograph.mcp.server.tools.admin.ServerInfoTool
import io.hierograph.mcp.server.tools.views.ViewTools
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * `server` and `views` through `/graphql` on a running server without a graph: both answer, and agree with
 * the MCP tools `server_info` and `list_views`.
 */
@Suppress("UNCHECKED_CAST")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerAndViewsGraphQlTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val stateDir = Files.createTempDirectory("hierograph-graphql-server-test")
            val views = Files.createDirectories(stateDir.resolve("runtime-views"))
            Files.writeString(
                views.resolve("layers.json"),
                """{"name": "layers", "description": "By layer", "groups": [{"name": "core", "members": ["com.example.**"]}]}""",
            )
            Files.writeString(views.resolve("broken.json"), "{ not json")
            registry.add("hierograph.graph.stateDir") { stateDir.toString() }
            registry.add("hierograph.graph.manifest") { stateDir.resolve("no-manifest.json").toString() }
            registry.add("hierograph.server.single-instance") { "false" }
            registry.add("hierograph.server.runtime-file.enabled") { "false" }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var serverInfoTool: ServerInfoTool

    @Autowired
    private lateinit var viewTools: ViewTools

    private val mapper = ObjectMapper()

    private fun query(query: String): JsonNode {
        val body = mapper.writeValueAsString(mapOf("query" to query))
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(response.statusCode()).isEqualTo(200)
        val json = mapper.readTree(response.body())
        assertThat(json.path("errors").isMissingNode).describedAs(response.body()).isTrue()
        return json.path("data")
    }

    @Test
    fun `server answers without a graph, as server_info does`() {
        val server = query(
            """{ server { url uiUrl port pid version startedAt workspace stateDir
                 source { kind manifest buildRoot includeTests watchMode buildFilesMode parquetDir }
                 artifacts { declared }
                 graph { state update generation dataHash loadedAt counts { total } } } }""",
        ).path("server")
        val info = serverInfoTool.serverInfo()

        assertThat(server.path("url").asText()).isEqualTo(info["url"])
        assertThat(server.path("uiUrl").asText()).isEqualTo(info["ui_url"]).endsWith("/ui/")
        assertThat(server.path("port").asInt()).isEqualTo(port).isEqualTo(info["port"])
        assertThat(server.path("pid").asLong()).isEqualTo(info["pid"])
        assertThat(server.path("startedAt").asText()).isEqualTo(info["started_at"])
        assertThat(server.path("workspace").asText()).isEqualTo(info["workspace"])
        assertThat(server.path("stateDir").asText()).isEqualTo(info["state_dir"])

        val source = info["source"] as Map<String, Any?>
        assertThat(server.path("source").path("kind").asText()).isEqualTo(source["kind"])
        assertThat(server.path("source").path("manifest").asText()).isEqualTo(source["manifest"])
        assertThat(server.path("source").path("buildRoot").asText()).isEqualTo(source["build_root"])

        val graph = server.path("graph")
        assertThat(graph.path("state").asText()).isEqualTo("UNAVAILABLE")
        assertThat(graph.path("generation").isNull).isTrue()
        assertThat(graph.path("counts").isNull).isTrue()
        assertThat(info["snapshot"]).isNull()
    }

    @Test
    fun `server reports the JVM's metrics`() {
        val jvm = query(
            """{ server { jvm { javaVersion javaVendor vmName uptimeMs availableProcessors
                 heap { usedBytes committedBytes maxBytes usedAfterGcBytes } nonHeap { usedBytes committedBytes maxBytes usedAfterGcBytes }
                 garbageCollectors { name count timeMs } threads { live peak daemon } processCpuLoad } } }""",
        ).path("server").path("jvm")

        assertThat(jvm.path("javaVersion").asText()).isEqualTo(System.getProperty("java.version"))
        assertThat(jvm.path("availableProcessors").asInt()).isEqualTo(Runtime.getRuntime().availableProcessors())
        assertThat(jvm.path("uptimeMs").asLong()).isPositive()
        val heap = jvm.path("heap")
        assertThat(heap.path("usedBytes").asLong()).isPositive()
            .isLessThanOrEqualTo(heap.path("committedBytes").asLong())
        assertThat(jvm.path("nonHeap").path("usedAfterGcBytes").isNull).isTrue()
        assertThat(jvm.path("garbageCollectors").size()).isPositive()
        assertThat(jvm.path("threads").path("live").asInt()).isPositive()
            .isLessThanOrEqualTo(jvm.path("threads").path("peak").asInt())
    }

    @Test
    fun `views lists the valid and the invalid view, as list_views does`() {
        val views = query("{ views { name description source file shadowsProjectView valid problems groupCount restructure } }")
            .path("views")
        val listed = viewTools.listViews()["views"] as List<Map<String, Any?>>

        assertThat(views.map { it.path("name").asText() })
            .containsExactlyInAnyOrderElementsOf(listed.map { it["name"] as String })
            .contains("layers", "broken")

        for (view in views) {
            val entry = listed.single { it["name"] == view.path("name").asText() }
            assertThat(view.path("source").asText()).isEqualTo(entry["source"])
            assertThat(view.path("file").asText()).isEqualTo(entry["file"])
            assertThat(view.path("valid").asBoolean()).isEqualTo(entry["valid"])
            assertThat(view.path("shadowsProjectView").asBoolean()).isEqualTo(entry["shadows_project_view"])
            assertThat(view.path("groupCount").isNull).isTrue() // not applied without a graph
        }

        val layers = views.single { it.path("name").asText() == "layers" }
        assertThat(layers.path("description").asText()).isEqualTo("By layer")
        assertThat(layers.path("source").asText()).isEqualTo("state")
        assertThat(layers.path("problems").size()).isZero()

        val broken = views.single { it.path("name").asText() == "broken" }
        assertThat(broken.path("valid").asBoolean()).isFalse()
        assertThat(broken.path("problems").size()).isPositive()
        assertThat(broken.path("problems").map { it.asText() })
            .isEqualTo(listed.single { it["name"] == "broken" }["problems"])
    }

    @Test
    fun `polling the graph state needs nothing but the status`() {
        val graph = query("{ server { graph { state generation } } }").path("server").path("graph")

        assertThat(graph.path("state").asText()).isEqualTo("UNAVAILABLE")
        assertThat(graph.has("counts")).isFalse()
    }
}
