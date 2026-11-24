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
package io.hierograph.mcp.server.tools

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.LevelizedStructureTool
import io.hierograph.mcp.server.tools.navigation.ListChildrenTool
import io.hierograph.mcp.server.tools.navigation.ListDescendantsTool
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import io.hierograph.server.mapping.GraphStoreNodeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Chain folding in `list_children`, `list_descendants` and `levelized_structure` (#63). */
@Suppress("UNCHECKED_CAST")
class CompactTreeToolsTest {

    /**
     * ```
     * root ─ module com.acme:app:1.0
     *          └ io › io.acme › { io.acme.server › io.acme.server.mcp (Tool), io.acme.web (Page) }
     * ```
     * Tool and Page depend on each other.
     */
    private class Fixture {
        private var nextId = 1L
        val graph = HGGraphFactory.createHGGraph()
        val root: HGNode = HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, "", "", "") }
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        private fun add(parent: HGNode, kind: Any, name: String, fqn: String): HGNode =
            HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, fqn, fqn) }.also {
                it.kind = kind
                HierarchyFactory.addChild(hierarchy, parent, it)
            }
        val module = add(root, JavaNodeKind.MODULE, "com.acme:app:1.0", "com.acme:app:1.0")
        val io = add(module, JavaNodeKind.PACKAGE, "io", "io")
        val acme = add(io, JavaNodeKind.PACKAGE, "acme", "io.acme")
        val server = add(acme, JavaNodeKind.PACKAGE, "server", "io.acme.server")
        val mcp = add(server, JavaNodeKind.PACKAGE, "mcp", "io.acme.server.mcp")
        val tool = add(mcp, JavaNodeKind.CLASS, "Tool", "io.acme.server.mcp.Tool")
        val web = add(acme, JavaNodeKind.PACKAGE, "web", "io.acme.web")
        val page = add(web, JavaNodeKind.CLASS, "Page", "io.acme.web.Page")
        init {
            repeat(2) { HGGraphFactory.createCoreDependency(tool, page, "USES") { DefaultDependencySource(nextId++) } }
            HGGraphFactory.createCoreDependency(page, tool, "USES") { DefaultDependencySource(nextId++) }
        }
        val graphService = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
        val refs = NodeRefFactory(graphService)
    }

    private val f = Fixture()
    private fun id(node: HGNode) = node.identifier as Long

    @Test
    fun `list_children lists a chain's end, named in full with the packages it stands for`() {
        val response = ListChildrenTool(f.graphService, f.refs).listChildren(id(f.module), null, null, null, null)

        val child = (response["results"] as List<Map<String, Any?>>).single()
        assertThat(child).containsEntry("id", id(f.acme)).containsEntry("name", "io.acme")
            .containsEntry("compacted_from", listOf("io"))
    }

    @Test
    fun `list_children folds below a package too, relative to it, and lists the tree as it is with compact false`() {
        val tool = ListChildrenTool(f.graphService, f.refs)

        val below = tool.listChildren(id(f.acme), null, null, null, null)["results"] as List<Map<String, Any?>>
        assertThat(below.map { it["name"] }).containsExactly("server.mcp", "web")
        assertThat(below.first()).containsEntry("compacted_from", listOf("io.acme.server"))
        assertThat(below.last()).doesNotContainKey("compacted_from")

        val raw = tool.listChildren(id(f.module), null, null, null, null, compact = false)["results"]
            as List<Map<String, Any?>>
        assertThat(raw.map { it["id"] }).containsExactly(id(f.io))
        assertThat(raw.single()).doesNotContainKey("compacted_from")
    }

    @Test
    fun `list_descendants passes through skipped packages without listing them, and counts under folded parents`() {
        val tool = ListDescendantsTool(f.graphService, f.refs, DataHashProvider(f.graphService))

        val folded = tool.listDescendants(id(f.module), listOf("packages"), null, null, null, null)
        assertThat((folded["results"] as List<Map<String, Any?>>).map { it["id"] })
            .containsExactly(id(f.acme), id(f.mcp), id(f.web))
        val byParent = (folded["summary"] as Map<String, Any?>)["by_parent"] as List<Map<String, Any?>>
        assertThat(byParent.associate { (it["parent"] as Map<String, Any?>)["id"] to it["match_count"] })
            .isEqualTo(mapOf(id(f.module) to 1, id(f.acme) to 2))

        val raw = tool.listDescendants(id(f.module), listOf("packages"), null, null, null, null, compact = false)
        assertThat((raw["results"] as List<Map<String, Any?>>)).hasSize(5)
    }

    @Test
    fun `levelized_structure levelizes the chain ends and names their skipped packages`() {
        val response = LevelizedStructureTool(f.graphService, f.refs).levelizedStructure(id(f.acme), null)

        val tangle = (response["tangles"] as List<Map<String, Any?>>).single()
        assertThat(tangle["member_ids"] as List<Long>).containsExactlyInAnyOrder(id(f.mcp), id(f.web))
        val feedback = (response["feedback_dependencies"] as List<Map<String, Any?>>).single()
        assertThat(feedback).containsEntry("from", id(f.web)).containsEntry("to", id(f.mcp)).containsEntry("weight", 1)

        val nodes = response["nodes"] as Map<String, Map<String, Any?>>
        assertThat(nodes[id(f.mcp).toString()]).containsEntry("name", "server.mcp")
            .containsEntry("compacted_from", listOf("io.acme.server"))

        // The module's only display child is the chain's end.
        val module = LevelizedStructureTool(f.graphService, f.refs).levelizedStructure(id(f.module), null)
        assertThat((module["nodes"] as Map<String, Any?>).keys).containsExactly(id(f.acme).toString())
    }
}
