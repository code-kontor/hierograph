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
package io.hierograph.server.views

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.hierarchicalgraph.model.SyntheticNodeSource
import io.hierograph.server.views.ViewFinding.Code
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [ViewMaterializer] on the [TestReactor]. */
class ViewMaterializerTest {

    private val r = TestReactor()

    private fun materialize(json: String) = ViewMaterializer.materialize(r.model, ViewDocumentParser.parse(json))

    private val modulesView = """
        { "name": "layers", "groups": [
            { "name": "core", "members": ["acme:core*:*"] },
            { "name": "app", "members": ["acme:app:**"] } ] }
    """

    @Test
    fun `creates a group node per applied group with kind, names and a handle from view and path`() {
        val view = materialize(modulesView)

        val core = view.groupNodes.getValue("core")
        assertThat(core.kind).isEqualTo(ViewGroups.KIND)
        assertThat(ViewGroups.isGroup(core)).isTrue()
        assertThat(NodeMetadata.getName(core)).isEqualTo("core")
        assertThat(NodeMetadata.getQualifiedName(core)).isEqualTo("layers:core")
        assertThat(core.identifier).isEqualTo(NodeHandle.of("view:layers:core"))
        assertThat(view.model.hierarchy.lookupNode(core.identifier)).isSameAs(core)
        assertThat(view.model.coreGraph.lookupNode(core.identifier)).isNull()
    }

    @Test
    fun `members move below their group, and the groups take their place below the parent`() {
        val view = materialize(modulesView)
        val h = view.model.hierarchy
        val core = view.groupNodes.getValue("core")
        val app = view.groupNodes.getValue("app")

        assertThat(h.childrenOf(r.root)).containsExactly(core, app)
        assertThat(h.childrenOf(core)).containsExactly(r.core, r.coreUtil)
        assertThat(h.childrenOf(app)).containsExactly(r.app, r.appTest)
        assertThat(h.predecessorsOf(r.engine)).contains(r.core, core, r.root)
    }

    @Test
    fun `nested groups nest, and paths name them`() {
        val view = materialize(
            """{ "name": "v", "groups": [ { "name": "all", "groups": [
                 { "name": "core", "members": ["acme:core*:*"] }, { "name": "app", "members": ["acme:app:**"] } ] } ] }""",
        )
        val h = view.model.hierarchy

        assertThat(view.groupNodes.keys).containsExactly("all", "all/core", "all/app")
        val all = view.groupNodes.getValue("all")
        assertThat(h.childrenOf(r.root)).containsExactly(all)
        assertThat(h.childrenOf(all)).containsExactly(view.groupNodes["all/core"], view.groupNodes["all/app"])
        assertThat(NodeMetadata.getQualifiedName(view.groupNodes.getValue("all/core"))).isEqualTo("v:all/core")
    }

    @Test
    fun `a group below a package groups types and leaves the rest of the package in place`() {
        val view = materialize("""{ "name": "v", "groups": [ { "name": "engine", "members": [{ "match": "com.acme.core.Engine*", "kind": "types" }] } ] }""")
        val h = view.model.hierarchy
        val engine = view.groupNodes.getValue("engine")

        assertThat(h.parentOf(engine)).isSameAs(r.corePkg)
        assertThat(h.childrenOf(r.corePkg)).containsExactly(r.spiPkg, engine)
        assertThat(h.childrenOf(engine)).containsExactly(r.engine, r.engineImpl)
    }

    @Test
    fun `the base model is never changed`() {
        val before = r.hierarchy.descendantsOf(r.root).map { it to r.hierarchy.parentOf(it) }

        val view = materialize(modulesView)

        assertThat(view.model).isNotSameAs(r.model)
        assertThat(r.hierarchy.descendantsOf(r.root).map { it to r.hierarchy.parentOf(it) }).isEqualTo(before)
        assertThat(r.hierarchy.localNodes).isEmpty()
    }

    @Test
    fun `a group aggregates the dependencies of its members`() {
        r.dependency(r.main, r.engine, 2)
        r.dependency(r.mainTest, r.engineImpl, 3)
        r.dependency(r.engine, r.strings, 5)
        val view = materialize(modulesView)
        val h = view.model.hierarchy

        assertThat(h.getAggregatedDependency(view.groupNodes.getValue("app"), view.groupNodes.getValue("core"))!!.aggregatedWeight)
            .isEqualTo(5)
        assertThat(h.getAggregatedDependency(view.groupNodes.getValue("core"), view.groupNodes.getValue("app"))).isNull()
        assertThat(h.accumulatedOutgoing(view.groupNodes.getValue("core")).sumOf { it.weight }).isEqualTo(5)
    }

    @Test
    fun `materializing the same document twice gives the same handles and the same structure`() {
        val first = materialize(modulesView)
        val second = materialize(modulesView)

        assertThat(second.groupNodes.mapValues { it.value.identifier }).isEqualTo(first.groupNodes.mapValues { it.value.identifier })
        fun shape(view: MaterializedView) = view.model.hierarchy.descendantsOf(r.root).map { it.identifier to view.model.hierarchy.parentOf(it)?.identifier }
        assertThat(shape(second)).isEqualTo(shape(first))
        assertThat(second.groupNodes.getValue("core")).isNotSameAs(first.groupNodes.getValue("core"))
    }

    @Test
    fun `the same path in two views gives distinct handles`() {
        val a = materialize("""{ "name": "a", "groups": [ { "name": "core", "members": ["acme:core:*"] } ] }""")
        val b = materialize("""{ "name": "b", "groups": [ { "name": "core", "members": ["acme:core:*"] } ] }""")

        assertThat(a.groupNodes.getValue("core").identifier).isNotEqualTo(b.groupNodes.getValue("core").identifier)
    }

    @Test
    fun `a view without applicable groups returns the base model and its report`() {
        val view = materialize("""{ "name": "v", "groups": [ { "name": "ghost", "members": ["acme:ghost:*"] } ] }""")

        assertThat(view.model.hierarchy).isSameAs(r.hierarchy)
        assertThat(view.groupNodes).isEmpty()
        assertThat(view.report.findings.map { it.code }).containsExactly(Code.SELECTOR_MATCHES_NOTHING, Code.EMPTY_GROUP)
    }

    @Test
    fun `groups lifted from a group that cannot be applied are placed below their own parents`() {
        val view = materialize(
            """{ "name": "v", "groups": [ { "name": "mixed", "groups": [
                 { "name": "modules", "members": ["acme:core*:*"] },
                 { "name": "types", "members": [{ "match": "com.acme.app.Main*", "kind": "types", "within": "acme:app:1.0" }] } ] } ] }""",
        )
        val h = view.model.hierarchy

        assertThat(view.groupNodes.keys).containsExactly("mixed/modules", "mixed/types")
        assertThat(h.parentOf(view.groupNodes.getValue("mixed/modules"))).isSameAs(r.root)
        assertThat(h.parentOf(view.groupNodes.getValue("mixed/types"))).isSameAs(r.appPkg)
        assertThat(view.report.findings.map { it.code }).containsExactly(Code.MEMBERS_WITH_DIFFERENT_PARENTS)
    }

    @Test
    fun `a group handle taken by a graph node keeps the whole view from being applied`() {
        // a real node that happens to carry the handle of the group "layers:core"
        val squatter: HGNode = io.hierograph.hierarchicalgraph.model.HGGraphFactory.createNode(r.graph) {
            SyntheticNodeSource(ViewGroups.handle("layers", "core"), "squatter", "acme.Squatter")
        }
        io.hierograph.hierarchicalgraph.model.HierarchyFactory.addChild(r.hierarchy, r.utilPkg, squatter)

        val view = materialize(modulesView)

        assertThat(view.groupNodes).isEmpty()
        assertThat(view.model.hierarchy).isSameAs(r.hierarchy)
        val finding = view.report.findings.single()
        assertThat(finding.code).isEqualTo(Code.GROUP_HANDLE_COLLISION)
        assertThat(finding.group).isEqualTo("core")
        assertThat(finding.nodes).containsExactly(squatter)
    }
}
