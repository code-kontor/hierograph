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
package io.hierograph.server.graphservice.views

import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.hierarchicalgraph.model.SyntheticNodeSource
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.views.ViewDocumentException
import io.hierograph.server.views.ViewGroups
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

class ViewServiceTest {

    @TempDir
    lateinit var tmp: Path

    private val projectDir: Path get() = tmp.resolve("project")
    private val stateDir: Path get() = tmp.resolve("state")

    /** Three modules `acme:<name>:1.0`, each with one package and one type; `app` depends on `core`. */
    private fun model(vararg names: String = arrayOf("app", "core", "util")): HGModel {
        val graph = HGGraphFactory.createHGGraph()
        fun node(qualifiedName: String, kind: JavaNodeKind?) =
            HGGraphFactory.createNode(graph) { SyntheticNodeSource(NodeHandle.of(qualifiedName), qualifiedName.substringAfterLast('.'), qualifiedName) }
                .also { it.kind = kind }
        val root = node("", null)
        val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        val types = HashMap<String, HGNode>()
        for (name in names) {
            val module = node("acme:$name:1.0", JavaNodeKind.MODULE).also { HierarchyFactory.addChild(hierarchy, root, it) }
            val pkg = node("com.acme.$name", JavaNodeKind.PACKAGE).also { HierarchyFactory.addChild(hierarchy, module, it) }
            types[name] = node("com.acme.$name.Type", JavaNodeKind.CLASS).also { HierarchyFactory.addChild(hierarchy, pkg, it) }
        }
        if ("app" in types && "core" in types) {
            HGGraphFactory.createCoreDependency(types.getValue("app"), types.getValue("core"), "DEPENDS_ON") {
                io.hierograph.hierarchicalgraph.model.DefaultDependencySource(identifier = "dep")
            }
        }
        return HGModel(graph, hierarchy)
    }

    private val graphService = HierarchicalGraphService().also { it.seed(model()) }
    private val service by lazy { ViewService(graphService, ViewRegistry(projectDir, stateDir)) }

    private val layers = """{ "name": "layers", "groups": [
        { "name": "front", "members": ["acme:app:*"] },
        { "name": "back", "members": ["acme:{core,util}*:*"] } ] }"""

    private fun project(file: String, text: String): Path {
        Files.createDirectories(projectDir)
        return projectDir.resolve(file).also { it.writeText(text) }
    }

    @Test
    fun `a view is the base snapshot with the view's model, search index and data hash`() {
        project("layers.json", layers)
        val base = graphService.liveSnapshot()!!

        val view = service.view("layers")
        val snapshot = view.snapshot

        assertThat(snapshot.view).isEqualTo("layers")
        assertThat(view.base).isSameAs(base)
        assertThat(snapshot.generation).isEqualTo(base.generation)
        assertThat(snapshot.graphStore).isSameAs(base.graphStore)
        assertThat(snapshot.model).isNotSameAs(base.model)
        assertThat(snapshot.model.coreGraph).isSameAs(base.model.coreGraph)
        val h = snapshot.model.hierarchy
        assertThat(h.childrenOf(h.rootNode).map { it.nodeSource.name }).containsExactly("front", "back")
        assertThat(h.getAggregatedDependency(view.materialized.groupNodes.getValue("front"), view.materialized.groupNodes.getValue("back"))).isNotNull
        // the base hierarchy is unchanged
        assertThat(base.model.hierarchy.childrenOf(base.model.hierarchy.rootNode)).hasSize(3)
    }

    @Test
    fun `the view's search index finds the group nodes and still every real node`() {
        project("layers.json", layers)
        val base = graphService.liveSnapshot()!!

        val index = service.view("layers").snapshot.searchIndex

        assertThat(index.entries).hasSize(base.searchIndex.entries.size + 2)
        assertThat(index.entries.filter { ViewGroups.isGroup(it.node) }.map { it.fqnLower }).containsExactly("layers:front", "layers:back")
        assertThat(base.searchIndex.entries.none { ViewGroups.isGroup(it.node) }).isTrue()
    }

    @Test
    fun `the data hash differs from the graph's, is stable, and changes with the document`() {
        val file = project("layers.json", layers)
        val graphHash = graphService.liveSnapshot()!!.dataHash

        val first = service.view("layers").snapshot.dataHash
        assertThat(first).isNotEqualTo(graphHash)
        assertThat(ViewService(graphService, ViewRegistry(projectDir, stateDir)).view("layers").snapshot.dataHash).isEqualTo(first)

        file.writeText(layers.replace("front", "frontend"))
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        assertThat(service.view("layers").snapshot.dataHash).isNotEqualTo(first)
    }

    @Test
    fun `a view is materialized once per snapshot and document`() {
        project("layers.json", layers)

        val first = service.view("layers")
        val second = service.view("layers")

        assertThat(second).isSameAs(first)
        assertThat(service.materializationCount).isEqualTo(1)
    }

    @Test
    fun `concurrent first requests materialize a view once`() {
        project("layers.json", layers)
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val futures = (1..threads).map { pool.submit<ViewSnapshot> { start.await(); service.view("layers") } }
            start.countDown()
            val views = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(views.toSet()).hasSize(1)
            assertThat(service.materializationCount).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a new graph generation materializes the view again, with modules a pattern now matches`() {
        project("layers.json", layers)
        val before = service.view("layers")
        assertThat(before.materialized.groupNodes.getValue("back").let { before.snapshot.model.hierarchy.childrenOf(it) }).hasSize(2)

        graphService.seed(model("app", "core", "util", "core-api"))
        val after = service.view("layers")

        assertThat(after).isNotSameAs(before)
        assertThat(after.base).isSameAs(graphService.liveSnapshot())
        assertThat(after.snapshot.model.hierarchy.childrenOf(after.materialized.groupNodes.getValue("back"))).hasSize(3)
        assertThat(service.materializationCount).isEqualTo(2)
        // the handle of a group is the same in both generations
        assertThat(after.materialized.groupNodes.getValue("back").identifier)
            .isEqualTo(before.materialized.groupNodes.getValue("back").identifier)
    }

    @Test
    fun `a publication drops the views of the replaced snapshot`() {
        project("layers.json", layers)
        service.view("layers")
        assertThat(service.cachedViewCount).isEqualTo(1)

        graphService.seed(model())

        assertThat(service.cachedViewCount).isZero()
    }

    @Test
    fun `a publication between materializing and caching a view leaves nothing of the replaced snapshot cached`() {
        project("layers.json", layers)
        val old = graphService.liveSnapshot()!!
        service.beforeCacheWrite = { service.beforeCacheWrite = null; graphService.seed(model()) }

        val view = service.view("layers")

        assertThat(view.base).isSameAs(old)
        assertThat(service.cachedViewCount).isZero()
        assertThat(service.view("layers").base).isSameAs(graphService.liveSnapshot())
        assertThat(service.cachedViewCount).isEqualTo(1)
    }

    @Test
    fun `a call pinned to a replaced snapshot gets its own view without evicting the live one`() {
        project("layers.json", layers)
        val old = graphService.liveSnapshot()!!
        graphService.seed(model())
        val live = service.view("layers")

        val pinned = graphService.withPinnedSnapshot(old) { service.view("layers") }

        assertThat(pinned.base).isSameAs(old)
        assertThat(service.view("layers")).isSameAs(live)
        assertThat(service.materializationCount).isEqualTo(2)
    }

    @Test
    fun `define stores and applies a state view, delete restores the project view`() {
        project("layers.json", layers)

        val defined = service.define(layers.replace("\"front\"", "\"ui\""))
        assertThat(defined.entry.source).isEqualTo(ViewSource.STATE)
        assertThat(defined.entry.shadowsProjectView).isTrue()
        assertThat(defined.materialized.groupNodes.keys).containsExactly("ui", "back")
        assertThat(service.view("layers")).isSameAs(defined)

        val restored = service.delete("layers")
        assertThat(restored!!.source).isEqualTo(ViewSource.PROJECT)
        assertThat(service.view("layers").materialized.groupNodes.keys).containsExactly("front", "back")
    }

    @Test
    fun `defining an invalid document fails and changes nothing`() {
        assertThatThrownBy { service.define("""{ "name": "layers", "groups": 1 }""") }.isInstanceOf(ViewDocumentException::class.java)
        assertThat(service.entries()).isEmpty()
    }

    @Test
    fun `an unknown view names the known ones, an invalid one its problems`() {
        project("layers.json", layers)
        project("broken.json", """{ "name": "broken", "groups": [ {} ] }""")

        assertThatThrownBy { service.view("nope") }
            .isInstanceOf(UnknownViewException::class.java)
            .hasMessageContaining("Known views: broken, layers")
        assertThatThrownBy { service.view("broken") }
            .isInstanceOf(InvalidViewException::class.java)
            .hasMessageContaining("groups[0].name: required")
    }

    @Test
    fun `the report of a view travels with it`() {
        project("partial.json", """{ "name": "partial", "groups": [ { "name": "ghost", "members": ["acme:ghost:*"] }, { "name": "app", "members": ["acme:app:*"] } ] }""")

        val view = service.view("partial")

        assertThat(view.materialized.report.warnings).hasSize(2)
        assertThat(view.materialized.groupNodes.keys).containsExactly("app")
    }

    @Test
    fun `withView pins the view's snapshot and names the view`() {
        project("layers.json", layers)
        val view = service.view("layers")

        val (model, current) = service.withView(view) { graphService.model to service.currentView() }

        assertThat(model).isSameAs(view.snapshot.model)
        assertThat(current).isSameAs(view)
        assertThat(service.currentView()).isNull()
        assertThat(graphService.model).isSameAs(view.base.model)
    }

    @Test
    fun `asking for a view inside a view materializes it from the base, never on top of the view`() {
        project("layers.json", layers)
        project("other.json", """{ "name": "other", "groups": [ { "name": "all", "members": ["acme:*:*"] } ] }""")
        val layersView = service.view("layers")

        val other = service.withView(layersView) { service.view("other") }
        val same = service.withView(layersView) { service.view("layers") }

        assertThat(other.base).isSameAs(layersView.base)
        assertThat(other.materialized.report.findings).isEmpty()
        assertThat(same).isSameAs(layersView)
    }

    @Test
    fun `a view snapshot pinned without withView cannot be the base of another view`() {
        project("layers.json", layers)
        val view = service.view("layers")

        assertThatThrownBy { graphService.withPinnedSnapshot(view.snapshot) { service.view("layers") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("views do not stack")
    }

    @Test
    fun `withView fails inside a call already pinned to another snapshot`() {
        project("layers.json", layers)
        val view = service.view("layers")

        assertThatThrownBy { graphService.withPinnedSnapshot { service.withView(view) { } } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already pinned")
    }
}
