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
package io.hierograph.mcp.server.tools.views

import com.fasterxml.jackson.databind.ObjectMapper
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.Origin
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.mcp.server.core.GraphAvailabilityToolCallbackProvider
import io.hierograph.mcp.server.core.ViewParameter
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.AggregatedDependenciesTool
import io.hierograph.mcp.server.tools.navigation.FindNodeTool
import io.hierograph.mcp.server.tools.navigation.GraphOverviewTool
import io.hierograph.mcp.server.tools.navigation.ListChildrenTool
import io.hierograph.mcp.server.tools.navigation.ListDescendantsTool
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.NodeSearch
import io.hierograph.server.graphservice.views.ViewRegistry
import io.hierograph.server.graphservice.views.ViewService
import io.hierograph.server.mapping.GraphStoreToHGModel
import io.hierograph.server.views.ViewGroups
import io.hierograph.server.views.ViewNodes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The tools called through [GraphAvailabilityToolCallbackProvider] as a client calls them: JSON in, JSON
 * out, with and without the `view` parameter, and the view tools that manage the documents.
 */
@Suppress("UNCHECKED_CAST")
class ViewToolCallsTest {

    @TempDir
    lateinit var tmp: Path

    private val mapper = ObjectMapper()

    /** Three modules; app → core (weight 2), app → util (1), core → util (1). */
    private val store = GraphStore().apply {
        for (name in listOf("app", "core", "util")) registerArtifact(ArtifactInfo("acme:$name:1.0", name, "acme:$name:1.0", Origin.MODULE_MAIN))
        applyClass(
            "acme:app:1.0",
            ClassFacts(
                "com.acme.app.App", JavaKinds.CLASS, 0, emptyList(),
                refs = listOf(
                    Ref("com.acme.app.App", "com.acme.core.Core", RefKind.DEPENDS_ON, count = 2),
                    Ref("com.acme.app.App", "com.acme.util.Strings", RefKind.DEPENDS_ON, count = 1),
                ),
                classHash = "a",
            ),
        )
        applyClass(
            "acme:core:1.0",
            ClassFacts(
                "com.acme.core.Core", JavaKinds.CLASS, 0, emptyList(),
                refs = listOf(Ref("com.acme.core.Core", "com.acme.util.Strings", RefKind.DEPENDS_ON, count = 1)),
                classHash = "c",
            ),
        )
        applyClass("acme:util:1.0", ClassFacts("com.acme.util.Strings", JavaKinds.CLASS, 0, emptyList(), emptyList(), "u"))
    }

    private val graphService = HierarchicalGraphService().also { it.seed(GraphStoreToHGModel.build(store)) }
    private val viewService by lazy { ViewService(graphService, ViewRegistry(tmp.resolve("project"), tmp.resolve("state"))) }

    private val tools: Map<String, ToolCallback> by lazy {
        val refs = NodeRefFactory(graphService)
        val delegate = MethodToolCallbackProvider.builder().toolObjects(
            GraphOverviewTool(graphService, refs, viewService),
            ListChildrenTool(graphService, refs),
            FindNodeTool(NodeSearch(graphService), refs),
            AggregatedDependenciesTool(graphService, refs),
            ListDescendantsTool(graphService, refs, DataHashProvider(graphService)),
            ViewTools(viewService, graphService),
        ).build()
        val viewTools = setOf("define_view", "list_views", "get_view", "delete_view")
        GraphAvailabilityToolCallbackProvider(
            delegate, graphService,
            toolsAllowedWithoutGraph = setOf("list_views", "get_view", "delete_view"),
            viewService = viewService,
            toolsWithoutView = viewTools,
        ).toolCallbacks.associateBy { it.toolDefinition.name() }
    }

    private fun call(tool: String, vararg args: Pair<String, Any?>): Map<String, Any?> =
        mapper.readValue(tools.getValue(tool).call(mapper.writeValueAsString(mapOf(*args))), Map::class.java) as Map<String, Any?>

    private val layers = """{ "name": "layers", "description": "front and back",
        "groups": [ { "name": "front", "members": ["acme:app:*"] }, { "name": "back", "members": ["acme:{core,util}:*"] } ] }"""

    private fun project(file: String, text: String) {
        Files.createDirectories(tmp.resolve("project"))
        tmp.resolve("project").resolve(file).writeText(text)
    }

    private fun rootId(): Long = graphService.model.hierarchy.rootNode.identifier as Long

    private fun groupId(view: String, path: String): Long = ViewGroups.handle(view, path)

    @Test
    fun `the structural tools declare the view parameter, the view tools do not`() {
        val schema = { name: String -> mapper.readTree(tools.getValue(name).toolDefinition.inputSchema()) }

        assertThat(schema("list_children").path("properties").has(ViewParameter.NAME)).isTrue()
        assertThat(schema("list_children").path("properties").has("nodeId")).isTrue()
        assertThat(schema("graph_overview").path("properties").path(ViewParameter.NAME).path("type").asText()).isEqualTo("string")
        assertThat(schema("define_view").path("properties").has(ViewParameter.NAME)).isFalse()
        assertThat(schema("list_views").path("properties").has(ViewParameter.NAME)).isFalse()
    }

    @Test
    fun `list_children in a view shows the groups, without one the modules`() {
        project("layers.json", layers)

        val inView = call("list_children", "nodeId" to rootId(), "view" to "layers")
        val base = call("list_children", "nodeId" to rootId())

        val groups = (inView["results"] as List<Map<String, Any?>>)
        // External Types is a module no group takes
        assertThat(groups.map { it["name"] }).containsExactlyInAnyOrder("front", "back", "External Types")
        val back = groups.single { it["name"] == "back" }
        assertThat(back["kind"]).isEqualTo(ViewGroups.KIND)
        assertThat(back["qualified_name"]).isEqualTo("layers:back")
        assertThat(back["child_count"]).isEqualTo(2)
        assertThat(back["descendant_module_count"]).isEqualTo(2)
        assertThat(back["descendant_type_count"]).isEqualTo(2)
        assertThat((base["results"] as List<Map<String, Any?>>).map { it["name"] }).contains("app", "core", "util")
    }

    @Test
    fun `a group id is a scope wherever a node id is`() {
        project("layers.json", layers)
        val front = groupId("layers", "front")
        val back = groupId("layers", "back")

        val inView = call("aggregated_dependencies", "sourceIds" to listOf(front), "targetIds" to listOf(back), "view" to "layers")
        val children = call("list_children", "nodeId" to back, "view" to "layers")

        assertThat((inView["edges"] as List<Map<String, Any?>>).single()["weight"]).isEqualTo(3)
        assertThat((children["results"] as List<Map<String, Any?>>).map { it["name"] }).containsExactlyInAnyOrder("core", "util")
        // outside the view, the group does not exist
        assertThat(call("list_children", "nodeId" to back)["error"].toString()).contains("NODE_NOT_FOUND")
    }

    @Test
    fun `find_node in a view finds the groups, also by their kind`() {
        project("layers.json", layers)

        val found = call("find_node", "name" to "back", "kindFilter" to listOf(ViewGroups.KIND), "view" to "layers")
        val outside = call("find_node", "name" to "back", "kindFilter" to listOf(ViewGroups.KIND))

        assertThat((found["results"] as List<Map<String, Any?>>).map { it["qualified_name"] }).containsExactly("layers:back")
        assertThat(outside["results"] as List<*>).isEmpty()
    }

    @Test
    fun `graph_overview in a view lists the view's top level and its report`() {
        project("partial.json", """{ "name": "partial", "groups": [
            { "name": "front", "members": ["acme:app:*"] }, { "name": "ghost", "members": ["acme:ghost:*"] } ] }""")

        val overview = call("graph_overview", "view" to "partial")

        val top = (overview["hierarchy"] as List<Map<String, Any?>>).map { it["name"] }
        assertThat(top).contains("front", "core", "util").doesNotContain("app")
        val view = overview["view"] as Map<String, Any?>
        assertThat(view["name"]).isEqualTo("partial")
        assertThat(view["source"]).isEqualTo("project")
        assertThat(view["group_count"]).isEqualTo(1)
        assertThat(view["warnings"]).isEqualTo(2)
        val codes = ((view["report"] as Map<String, Any?>)["findings"] as List<Map<String, Any?>>).map { it["code"] }
        assertThat(codes).containsExactly("SELECTOR_MATCHES_NOTHING", "EMPTY_GROUP")
        val kinds = ((overview["kinds"] as Map<String, Any?>)["structural"] as List<Map<String, Any?>>).map { it["kind"] }
        assertThat(kinds).contains(ViewGroups.KIND)
        assertThat(call("graph_overview")).doesNotContainKey("view")
    }

    @Test
    fun `a cursor holds only in the view it was issued in`() {
        project("layers.json", layers)
        val first = call("list_descendants", "nodeId" to rootId(), "limit" to 2, "view" to "layers")
        val cursor = first["next_cursor"] as String

        val next = call("list_descendants", "nodeId" to rootId(), "limit" to 2, "cursor" to cursor, "view" to "layers")
        val elsewhere = call("list_descendants", "nodeId" to rootId(), "limit" to 2, "cursor" to cursor)

        assertThat(next).doesNotContainKey("error")
        assertThat((elsewhere["error"] as Map<String, Any?>)["code"]).isEqualTo("STALE_CURSOR_DATA")
    }

    @Test
    fun `an unknown view is an error naming the known ones, and the tool does not run`() {
        project("layers.json", layers)

        val error = call("list_children", "nodeId" to rootId(), "view" to "nope")["error"] as Map<String, Any?>

        assertThat(error["code"]).isEqualTo("VIEW_NOT_FOUND")
        assertThat(error["message"].toString()).contains("Known views: layers")
    }

    @Test
    fun `a view whose document does not parse is a VIEW_INVALID error`() {
        project("broken.json", """{ "name": "broken", "groups": [ {} ] }""")

        val error = call("graph_overview", "view" to "broken")["error"] as Map<String, Any?>

        assertThat(error["code"]).isEqualTo("VIEW_INVALID")
        assertThat(error["message"].toString()).contains("groups[0].name: required")
    }

    @Test
    fun `a blank view means the hierarchy as scanned`() {
        val result = call("list_children", "nodeId" to rootId(), "view" to " ")

        assertThat((result["results"] as List<Map<String, Any?>>).map { it["name"] }).contains("app", "core", "util")
    }

    @Test
    fun `define_view stores and applies a document and returns its groups and report`() {
        val document = mapper.readValue(layers, Map::class.java)

        val defined = call("define_view", "document" to document)

        assertThat(defined["name"]).isEqualTo("layers")
        assertThat(defined["source"]).isEqualTo("state")
        assertThat(defined["shadows_project_view"]).isEqualTo(false)
        val groups = defined["groups"] as List<Map<String, Any?>>
        assertThat(groups.map { it["path"] }).containsExactly("front", "back")
        assertThat(groups.single { it["path"] == "back" }["id"]).isEqualTo(groupId("layers", "back"))
        assertThat(groups.single { it["path"] == "back" }["parent_id"]).isEqualTo(rootId())
        assertThat((defined["report"] as Map<String, Any?>)["errors"]).isEqualTo(0)
        assertThat(Files.readString(tmp.resolve("state").resolve("layers.json"))).contains("\"front\"")
        // and the other tools can use it right away
        assertThat((call("list_children", "nodeId" to rootId(), "view" to "layers")["results"] as List<Map<String, Any?>>).map { it["name"] })
            .contains("front", "back")
    }

    @Test
    fun `define_view rejects an invalid document with its problems and stores nothing`() {
        val result = call("define_view", "document" to mapOf("name" to "Bad Name", "groups" to listOf(mapOf("members" to listOf("x")))))

        val error = result["error"] as Map<String, Any?>
        assertThat(error["code"]).isEqualTo("INVALID_VIEW_DOCUMENT")
        assertThat(error["problems"] as List<String>).anyMatch { it.startsWith("name:") }.anyMatch { it.startsWith("groups[0].name") }
        assertThat(Files.exists(tmp.resolve("state").resolve("layers.json"))).isFalse()
    }

    @Test
    fun `list_views lists valid and invalid views with their summaries`() {
        project("layers.json", layers)
        project("broken.json", """{ "name": "broken", "groups": 1 }""")

        val views = (call("list_views")["views"] as List<Map<String, Any?>>).associateBy { it["name"] }

        assertThat(views.keys).containsExactlyInAnyOrder("layers", "broken")
        assertThat(views.getValue("layers")["description"]).isEqualTo("front and back")
        assertThat(views.getValue("layers")["valid"]).isEqualTo(true)
        assertThat(views.getValue("layers")["group_count"]).isEqualTo(2)
        assertThat(views.getValue("broken")["valid"]).isEqualTo(false)
        assertThat(views.getValue("broken")["problems"] as List<*>).isNotEmpty()
        assertThat(views.getValue("broken")).doesNotContainKey("group_count")
    }

    @Test
    fun `list_views describes the scanned hierarchy as default beside the views`() {
        project("layers.json", layers)

        val result = call("list_views")
        val default = result["default"] as Map<String, Any?>

        assertThat(default["description"] as String).contains("Omit view")
        assertThat(default["top_level_count"]).isEqualTo(call("graph_overview")["hierarchy"].let { (it as List<*>).size })
        assertThat((result["views"] as List<Map<String, Any?>>).map { it["name"] }).containsExactly("layers")
        assertThat((result["summary"] as Map<String, Any?>)["total"]).isEqualTo(1)
    }

    @Test
    fun `get_view returns the document, the groups and the report, an invalid one its problems`() {
        project("layers.json", layers)
        project("broken.json", """{ "name": "broken", "groups": 1 }""")

        val view = call("get_view", "name" to "layers")
        val broken = call("get_view", "name" to "broken")

        assertThat((view["document"] as Map<String, Any?>)["name"]).isEqualTo("layers")
        assertThat((view["groups"] as List<*>)).hasSize(2)
        assertThat(view).containsKey("report")
        assertThat(broken["valid"]).isEqualTo(false)
        assertThat(broken["problems"] as List<*>).isNotEmpty()
        assertThat(call("get_view", "name" to "nope")["error"].toString()).contains("VIEW_NOT_FOUND")
    }

    @Test
    fun `delete_view removes a runtime view and returns the project view it shadowed`() {
        project("layers.json", layers)
        call("define_view", "document" to mapper.readValue(layers.replace("\"front\"", "\"ui\""), Map::class.java))

        val deleted = call("delete_view", "name" to "layers")

        assertThat(deleted["deleted"]).isEqualTo("layers")
        assertThat((deleted["restored"] as Map<String, Any?>)["source"]).isEqualTo("project")
        val error = call("delete_view", "name" to "layers")["error"] as Map<String, Any?>
        assertThat(error["code"]).isEqualTo("PROJECT_VIEW")
    }

    private val extractUtil = mapOf(
        "name" to "extract-util", "restructure" to true,
        "modules" to listOf(mapOf("name" to "acme.text")),
        "moves" to listOf(mapOf("select" to mapOf("match" to "com.acme.util", "kind" to "java.package"), "to" to "acme.text")),
        "groups" to listOf(mapOf("name" to "base", "members" to listOf("acme.text", "acme:core:*"))),
    )

    @Test
    fun `define_view of a restructuring view returns its virtual modules and the module-level effect`() {
        val defined = call("define_view", "document" to extractUtil)

        val modules = defined["virtual_modules"] as List<Map<String, Any?>>
        assertThat(modules.single()["name"]).isEqualTo("acme.text")
        assertThat(modules.single()["child_count"]).isEqualTo(1)
        val effect = defined["restructuring"] as Map<String, Any?>
        assertThat(effect["added"] as List<Map<String, Any?>>).containsExactlyInAnyOrder(
            mapOf("from" to "acme:app:1.0", "to" to "acme.text", "weight" to 1),
            mapOf("from" to "acme:core:1.0", "to" to "acme.text", "weight" to 1),
        )
        assertThat((effect["removed"] as List<Map<String, Any?>>).map { it["from"] to it["to"] })
            .containsExactlyInAnyOrder("acme:app:1.0" to "acme:util:1.0", "acme:core:1.0" to "acme:util:1.0")
        assertThat(effect["introduces_cycles"]).isEqualTo(false)
        // the emptied real module stays and is reported
        val findings = (defined["report"] as Map<String, Any?>)["findings"] as List<Map<String, Any?>>
        assertThat(findings.map { it["code"] }).containsExactly("REAL_MODULE_EMPTY")
        assertThat(findings.single()["severity"]).isEqualTo("info")
    }

    @Test
    fun `in a restructuring view virtual nodes are marked and moved real nodes keep their ids`() {
        call("define_view", "document" to extractUtil)
        val moduleId = ViewNodes.moduleHandle("extract-util", "acme.text")
        val strings = graphService.model.hierarchy.descendantsOf(graphService.model.hierarchy.rootNode)
            .single { io.hierograph.hierarchicalgraph.model.NodeMetadata.getQualifiedName(it) == "com.acme.util.Strings" }

        val module = call("list_children", "nodeId" to groupId("extract-util", "base"), "view" to "extract-util")
        val packages = call("list_descendants", "nodeId" to moduleId, "view" to "extract-util")
        val found = call("find_node", "name" to "Strings", "view" to "extract-util")

        val text = (module["results"] as List<Map<String, Any?>>).single { it["name"] == "acme.text" }
        assertThat(text["virtual"]).isEqualTo(true)
        assertThat(text["kind"]).isEqualTo("java.module")
        val descendants = packages["results"] as List<Map<String, Any?>>
        assertThat(descendants.filter { it["kind"] == "java.package" }).allMatch { it["virtual"] == true || it["qualified_name"] == "com.acme.util" }
        assertThat(descendants.single { it["qualified_name"] == "com.acme.util" }).doesNotContainKey("virtual")
        val ref = (found["results"] as List<Map<String, Any?>>).single { it["qualified_name"] == "com.acme.util.Strings" }
        assertThat(ref["id"]).isEqualTo(strings.identifier)
        assertThat(ref).doesNotContainKey("virtual")
    }

    @Test
    fun `graph_overview of a restructuring view summarizes the restructuring`() {
        call("define_view", "document" to extractUtil)

        val view = call("graph_overview", "view" to "extract-util")["view"] as Map<String, Any?>

        assertThat(view["restructure"]).isEqualTo(true)
        assertThat(view["virtual_module_count"]).isEqualTo(1)
        assertThat(view["introduces_cycles"]).isEqualTo(false)
        assertThat(view).containsKeys("virtual_modules", "restructuring")
    }
}
