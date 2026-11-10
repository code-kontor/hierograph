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

import com.fasterxml.jackson.databind.ObjectMapper
import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.views.ViewGroups
import io.hierograph.server.views.ViewNodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.graphql.ExecutionGraphQlService
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest
import java.util.UUID

/**
 * Views through the running application on the Spring 7.0.8 graph: the MCP tools as a client calls them
 * (JSON through the registered [ToolCallbackProvider], `view` parameter included) and the GraphQL API.
 */
@Suppress("UNCHECKED_CAST")
class ViewToolsIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired
    private lateinit var tools: ToolCallbackProvider

    @Autowired
    private lateinit var graphQl: ExecutionGraphQlService

    @Autowired
    private lateinit var graphService: HierarchicalGraphService

    private val mapper = ObjectMapper()

    private val springModules = mapOf(
        "name" to "spring-modules",
        "groups" to listOf(
            mapOf("name" to "core-container", "members" to listOf("spring-{core,beans,context,expression,aop}-7.0.8.jar")),
            mapOf("name" to "data-access", "members" to listOf("spring-{jdbc,tx,orm,r2dbc,oxm,jms}-*.jar")),
            mapOf("name" to "web", "members" to listOf("spring-web-*.jar", "spring-web{mvc,flux,socket}-*.jar")),
        ),
    )

    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> {
        val callback = tools.toolCallbacks.single { it.toolDefinition.name() == tool }
        return mapper.readValue(callback.call(mapper.writeValueAsString(mapOf(*args))), Map::class.java) as Map<String, Any?>
    }

    private fun graphQl(document: String): Map<String, Any?> =
        graphQl.execute(DefaultExecutionGraphQlRequest(document, null, null, null, UUID.randomUUID().toString(), null))
            .block()!!.toMap()

    private fun rootId(): Long = graphService.model.hierarchy.rootNode.identifier as Long

    @BeforeEach
    fun define() {
        val defined = call("define_view", "document" to springModules)
        assertThat((defined["report"] as Map<String, Any?>)["errors"]).isEqualTo(0)
    }

    @AfterEach
    fun delete() {
        call("delete_view", "name" to "spring-modules")
    }

    @Test
    fun `every structural tool declares the view parameter`() {
        val withView = tools.toolCallbacks
            .filter { mapper.readTree(it.toolDefinition.inputSchema()).path("properties").has("view") }
            .map { it.toolDefinition.name() }

        assertThat(withView).contains(
            "graph_overview", "list_children", "list_descendants", "find_node",
            "aggregated_dependencies", "pairwise_dependencies", "outgoing_dependencies", "incoming_dependencies",
            "affected_by", "find_dependency_path", "type_details", "method_details", "field_details",
        ).doesNotContain("reload_graph", "define_view", "list_views", "get_view", "delete_view")
    }

    @Test
    fun `graph_overview in the view lists the three groups and the jars no group takes`() {
        val overview = call("graph_overview", "view" to "spring-modules")

        val top = overview["hierarchy"] as List<Map<String, Any?>>
        assertThat(top).hasSize(22 - 15 + 3)
        assertThat(top.filter { it["kind"] == ViewGroups.KIND }.map { it["name"] })
            .containsExactlyInAnyOrder("core-container", "data-access", "web")
        assertThat((overview["view"] as Map<String, Any?>)["group_count"]).isEqualTo(3)
        assertThat(call("graph_overview")["hierarchy"] as List<*>).hasSize(22)
    }

    @Test
    fun `aggregated dependencies between groups are the sums over their member jars`() {
        val web = ViewGroups.handle("spring-modules", "web")
        val core = ViewGroups.handle("spring-modules", "core-container")
        val members = { group: Long ->
            (call("list_children", "nodeId" to group, "view" to "spring-modules")["results"] as List<Map<String, Any?>>)
                .map { (it["id"] as Number).toLong() }
        }

        val groupEdge = (call("aggregated_dependencies", "sourceIds" to listOf(web), "targetIds" to listOf(core), "view" to "spring-modules")
            ["edges"] as List<Map<String, Any?>>).single()
        val memberEdges = call("aggregated_dependencies", "sourceIds" to members(web), "targetIds" to members(core))["edges"] as List<Map<String, Any?>>

        assertThat(groupEdge["weight"]).isEqualTo(memberEdges.sumOf { (it["weight"] as Number).toInt() })
        assertThat(groupEdge["weight"] as Int).isPositive()
    }

    @Test
    fun `list_views reports the defined view as a runtime view`() {
        val views = call("list_views")["views"] as List<Map<String, Any?>>

        val view = views.single { it["name"] == "spring-modules" }
        assertThat(view["source"]).isEqualTo("state")
        assertThat(view["group_count"]).isEqualTo(3)
        assertThat(view["file"].toString()).startsWith(VIEWS_DIR.toString())
    }

    @Test
    fun `list_views describes the scanned hierarchy as default, not as a view`() {
        val result = call("list_views")

        val default = result["default"] as Map<String, Any?>
        assertThat(default["top_level_count"]).isEqualTo((call("graph_overview")["hierarchy"] as List<*>).size)
        assertThat((result["views"] as List<Map<String, Any?>>).map { it["name"] }).doesNotContain("default")
    }

    @Test
    fun `GraphQL answers a request in the view it names`() {
        val result = graphQl("""{ hierarchicalGraph(view: "spring-modules") { rootNode { children { nodes { text type } } } } }""")

        assertThat(result["errors"]).isNull()
        val nodes = (((((result["data"] as Map<String, Any?>)["hierarchicalGraph"] as Map<String, Any?>)
            ["rootNode"] as Map<String, Any?>)["children"] as Map<String, Any?>)["nodes"] as List<Map<String, Any?>>)
        assertThat(nodes.filter { it["type"] == ViewGroups.KIND }.map { it["text"] })
            .containsExactlyInAnyOrder("spring-modules:core-container", "spring-modules:data-access", "spring-modules:web")
        assertThat(nodes).hasSize(10)
    }

    @Test
    fun `GraphQL finds group nodes in the view by id and by name`() {
        val web = ViewGroups.handle("spring-modules", "web")

        val result = graphQl(
            """{ hierarchicalGraph(view: "spring-modules") { node(id: "$web") { text children { nodes { text } } }
                 findNodes(name: "data-access") { text } } }""",
        )

        val graph = (result["data"] as Map<String, Any?>)["hierarchicalGraph"] as Map<String, Any?>
        assertThat((graph["node"] as Map<String, Any?>)["text"]).isEqualTo("spring-modules:web")
        assertThat(((graph["node"] as Map<String, Any?>)["children"] as Map<String, Any?>)["nodes"] as List<*>).hasSize(4)
        assertThat((graph["findNodes"] as List<Map<String, Any?>>).map { it["text"] }).contains("spring-modules:data-access")
    }

    @Test
    fun `GraphQL reports an unknown view, and a request that mixes views`() {
        val unknown = graphQl("""{ hierarchicalGraph(view: "nope") { identifier } }""")
        val mixed = graphQl("""{ a: hierarchicalGraph(view: "spring-modules") { identifier } b: hierarchicalGraph { identifier } }""")

        val unknownError = (unknown["errors"] as List<Map<String, Any?>>).single()
        assertThat((unknownError["extensions"] as Map<String, Any?>)["classification"]).isEqualTo("VIEW_NOT_FOUND")
        assertThat(unknownError["message"].toString()).contains("Known views: spring-modules")
        val mixedError = (mixed["errors"] as List<Map<String, Any?>>).single()
        assertThat((mixedError["extensions"] as Map<String, Any?>)["classification"]).isEqualTo("BAD_REQUEST")
        assertThat(mixedError["message"].toString()).contains("one view")
    }

    @Test
    fun `a tool call in the view leaves the hierarchy as scanned untouched`() {
        call("list_descendants", "nodeId" to rootId(), "limit" to 5, "view" to "spring-modules")

        val base = call("list_children", "nodeId" to rootId())["results"] as List<Map<String, Any?>>
        assertThat(base).hasSize(22).noneMatch { it["kind"] == ViewGroups.KIND }
    }

    private val extractCoreIo = mapOf(
        "name" to "extract-core-io", "restructure" to true,
        "modules" to listOf(mapOf("name" to "spring-core-io")),
        "moves" to listOf(
            mapOf(
                "select" to mapOf("match" to "org.springframework.core.io", "kind" to "java.package", "within" to "spring-core-7.0.8.jar"),
                "to" to "spring-core-io",
            ),
        ),
    )

    @Test
    fun `extracting core io from spring-core is reported as a new module cycle`() {
        try {
            val defined = call("define_view", "document" to extractCoreIo)

            val effect = defined["restructuring"] as Map<String, Any?>
            assertThat(effect["introduces_cycles"]).isEqualTo(true)
            assertThat(effect["new_cycles"] as List<List<String>>).anySatisfy { assertThat(it).contains("spring-core-io", "spring-core-7.0.8.jar") }
            val added = (effect["added"] as List<Map<String, Any?>>).map { it["from"] to it["to"] }
            assertThat(added).contains("spring-core-io" to "spring-core-7.0.8.jar", "spring-core-7.0.8.jar" to "spring-core-io")
            assertThat((defined["report"] as Map<String, Any?>)["errors"]).isEqualTo(0)
        } finally {
            call("delete_view", "name" to "extract-core-io")
        }
    }

    @Test
    fun `a type moved into a virtual module keeps its id and its details`() {
        try {
            call("define_view", "document" to extractCoreIo)
            val resource = (call("find_node", "name" to "Resource", "kindFilter" to listOf("types"))["results"] as List<Map<String, Any?>>)
                .single { it["qualified_name"] == "org.springframework.core.io.Resource" }
            val id = (resource["id"] as Number).toLong()

            val inView = call("type_details", "typeId" to id, "view" to "extract-core-io")
            val base = call("type_details", "typeId" to id)
            val path = call("list_descendants", "nodeId" to ViewNodes.moduleHandle("extract-core-io", "spring-core-io"),
                "kindFilter" to listOf("packages"), "view" to "extract-core-io")["results"] as List<Map<String, Any?>>

            assertThat(inView).doesNotContainKey("error")
            // the facts come from spring-core's snapshot although the type now sits in a virtual module
            assertThat(inView["member_summary"]).isEqualTo(base["member_summary"])
            assertThat((inView["member_summary"] as Map<String, Any?>)["method_count"] as Int).isPositive()
            assertThat(inView["interfaces"]).isEqualTo(base["interfaces"])
            assertThat(inView["location"]).isNotNull().isEqualTo(base["location"])
            assertThat(path.map { it["qualified_name"] }).contains("org", "org.springframework", "org.springframework.core", "org.springframework.core.io")
            assertThat(path.filter { it["qualified_name"] != "org.springframework.core.io" && it["qualified_name"].toString().count { c -> c == '.' } < 3 })
                .allMatch { it["virtual"] == true }
        } finally {
            call("delete_view", "name" to "extract-core-io")
        }
    }
}
