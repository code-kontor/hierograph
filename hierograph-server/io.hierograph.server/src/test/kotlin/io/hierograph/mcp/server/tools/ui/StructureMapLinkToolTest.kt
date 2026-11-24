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
package io.hierograph.mcp.server.tools.ui

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.instance.ServerInstance
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.views.ViewRegistry
import io.hierograph.server.graphservice.views.ViewService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.context.support.StaticApplicationContext
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path

/** `structure_map_link`: the structure map's URL with the dependencies in it (#68). */
@Suppress("UNCHECKED_CAST")
class StructureMapLinkToolTest {

    @TempDir
    lateinit var tmp: Path

    /** A module with three classes: a → b (2), a → c (1); nothing from b or c. */
    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private fun node(kind: Any): HGNode =
        HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }.also { it.kind = kind }
    private val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
    private val module = node(JavaNodeKind.MODULE).also { HierarchyFactory.addChild(hierarchy, root, it) }
    private fun cls() = node(JavaNodeKind.CLASS).also { HierarchyFactory.addChild(hierarchy, module, it) }
    private val a = cls()
    private val b = cls()
    private val c = cls()

    init {
        repeat(2) { HGGraphFactory.createCoreDependency(a, b, "USES") { DefaultDependencySource(nextId++) } }
        HGGraphFactory.createCoreDependency(a, c, "USES") { DefaultDependencySource(nextId++) }
    }

    private fun id(node: HGNode) = node.identifier as Long

    private fun tool(): StructureMapLinkTool {
        val graphService = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
        val environment = MockEnvironment().apply { setProperty("hierograph.server.url", "http://localhost:9000/mcp") }
        val beans = StaticApplicationContext()
        val instance = ServerInstance(
            environment,
            graphService,
            beans.getBeanProvider(BuildProperties::class.java),
            beans.getBeanProvider(GitProperties::class.java),
        )
        val viewService = ViewService(graphService, ViewRegistry(tmp.resolve("project"), tmp.resolve("state")))
        return StructureMapLinkTool(instance, graphService, viewService)
    }

    private fun dep(source: Any, target: Any) = mapOf("sourceId" to source, "targetId" to target)

    @Test
    fun `the map's URL with the pairs, a source's targets grouped, without duplicates`() {
        val response = tool().structureMapLink(
            listOf(dep(id(a), id(b)), dep(id(a), id(c)), dep(id(a), id(b))),
            null,
        )

        assertThat(response["url"])
            .isEqualTo("http://localhost:9000/ui/structure?dependency_ids=${id(a)}-${id(b)}.${id(c)}")
        assertThat(response["view"]).isNull()
        val summary = response["summary"] as Map<String, Any?>
        assertThat(summary["dependencies"]).isEqualTo(2)
        assertThat(summary["unknown_ids"] as List<*>).isEmpty()
        assertThat(summary["without_dependency"] as List<*>).isEmpty()
    }

    @Test
    fun `ids as strings, and the expansion when one is given`() {
        val response = tool().structureMapLink(
            listOf(dep(id(a).toString(), id(b).toString())),
            listOf(id(module)),
        )

        assertThat(response["url"] as String)
            .endsWith("?dependency_ids=${id(a)}-${id(b)}&expanded_ids=${id(module)}")
    }

    @Test
    fun `a skipped package to expand is linked as its chain's end, the box the folded map shows`() {
        // module ─ io › io.acme (class d): io holds nothing but io.acme.
        val io = node(JavaNodeKind.PACKAGE).also { HierarchyFactory.addChild(hierarchy, module, it) }
        val acme = node(JavaNodeKind.PACKAGE).also { HierarchyFactory.addChild(hierarchy, io, it) }
        node(JavaNodeKind.CLASS).also { HierarchyFactory.addChild(hierarchy, acme, it) }

        val response = tool().structureMapLink(listOf(dep(id(a), id(b))), listOf(id(module), id(io), id(acme)))

        assertThat(response["url"] as String).endsWith("&expanded_ids=${id(module)},${id(acme)}")
    }

    @Test
    fun `reports ids that name no node and pairs without a dependency, and still links them`() {
        val response = tool().structureMapLink(listOf(dep(id(b), id(a)), dep(424242L, id(a))), null)

        val summary = response["summary"] as Map<String, Any?>
        assertThat(summary["unknown_ids"] as List<*>).containsExactly(424242L)
        assertThat(summary["without_dependency"] as List<*>)
            .containsExactly(mapOf("sourceId" to id(b), "targetId" to id(a)))
        assertThat(response["url"] as String).contains("424242-${id(a)}")
    }

    @Test
    fun `an entry that is not a pair of ids is an invalid parameter`() {
        val response = tool().structureMapLink(listOf(mapOf("sourceId" to id(a))), null)

        assertThat((response["error"] as Map<String, Any?>)["code"]).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `more dependencies than a link carries are rejected`() {
        val many = (1..StructureMapLinkTool.MAX_DEPENDENCIES + 1).map { dep(id(a), 1000L + it) }

        val response = tool().structureMapLink(many, null)

        assertThat((response["error"] as Map<String, Any?>)["code"]).isEqualTo("TOO_MANY_DEPENDENCIES")
    }
}
