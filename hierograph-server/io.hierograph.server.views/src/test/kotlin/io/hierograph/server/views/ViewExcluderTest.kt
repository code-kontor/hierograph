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

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.server.views.ViewFinding.Code
import io.hierograph.server.views.ViewFinding.Severity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A view's exclusions (views specification, §5.4), applied by [ViewMaterializer] on the [TestReactor]. */
class ViewExcluderTest {

    private val r = TestReactor()

    private fun materialize(json: String) = ViewMaterializer.materialize(r.model, ViewDocumentParser.parse(json))

    private val noTests = """{ "name": "no-test", "exclude": ["**:test"] }"""

    @Test
    fun `an excluded module leaves the view with everything inside it`() {
        val view = materialize(noTests)
        val h = view.model.hierarchy

        assertThat(h.childrenOf(r.root)).containsExactly(r.app, r.core, r.coreUtil)
        assertThat(h.lookupNode(r.appTest.identifier)).isNull()
        assertThat(h.lookupNode(r.mainTest.identifier)).isNull()
        assertThat(h.descendantsOf(r.root)).doesNotContain(r.appTestPkg, r.mainTest)
        assertThat(view.excluded).containsExactly(r.appTest)
        // the module, com, com.acme, com.acme.app and MainTest
        assertThat(view.excludedCount).isEqualTo(5)
    }

    @Test
    fun `the report says what each exclusion leaves out`() {
        val view = materialize(noTests)

        val finding = view.report.findings.single()
        assertThat(finding.code).isEqualTo(Code.NODES_EXCLUDED)
        assertThat(finding.severity).isEqualTo(Severity.INFO)
        assertThat(finding.group).isEqualTo("exclude[0]")
        assertThat(finding.nodes).containsExactly(r.appTest)
        assertThat(finding.message).isEqualTo("selector **:test leaves out acme:app:1.0:test: 5 nodes in all, 1 type")
        assertThat(view.report.hasErrors).isFalse()
    }

    @Test
    fun `dependencies to and from excluded nodes are gone, and a cycle only through them disappears`() {
        r.dependency(r.main, r.engine, 2)
        r.dependency(r.mainTest, r.engine, 3)
        r.dependency(r.engine, r.mainTest, 1)
        val before = r.model.hierarchy
        assertThat(before.getAggregatedDependency(r.core, r.appTest)).isNotNull()

        val h = materialize(noTests).model.hierarchy

        assertThat(h.accumulatedIncoming(r.core).sumOf { it.weight }).isEqualTo(2)
        assertThat(h.accumulatedOutgoing(r.core)).isEmpty()
        assertThat(h.outgoingOf(r.engine)).isEmpty()
        assertThat(h.incomingOf(r.engine).map { it.from }).containsExactly(r.main)
        assertThat(h.getAggregatedDependency(r.core, r.app)).isNull()
        // the base hierarchy keeps them
        assertThat(before.accumulatedIncoming(r.core).sumOf { it.weight }).isEqualTo(5)
    }

    @Test
    fun `groups are resolved without the excluded nodes`() {
        val view = materialize(
            """{ "name": "v", "exclude": ["**:test"], "groups": [
                 { "name": "app", "members": ["acme:app:**"] },
                 { "name": "tests", "members": ["**:test"] } ] }""",
        )
        val h = view.model.hierarchy

        assertThat(h.childrenOf(view.groupNodes.getValue("app"))).containsExactly(r.app)
        assertThat(view.groupNodes).doesNotContainKey("tests")
        assertThat(view.report.findings.map { it.code })
            .contains(Code.SELECTOR_MATCHES_NOTHING, Code.EMPTY_GROUP)
    }

    @Test
    fun `a node inside an excluded node goes with it and is counted once`() {
        val view = materialize(
            """{ "name": "v", "exclude": ["acme:app:1.0:test", { "match": "com.acme.app.MainTest", "kind": "types" }] }""",
        )

        assertThat(view.excluded).containsExactly(r.appTest)
        assertThat(view.excludedCount).isEqualTo(5)
        assertThat(view.report.findings.map { it.group }).containsExactly("exclude[0]")
    }

    @Test
    fun `an exclusion may select packages and types, with kind and within`() {
        val view = materialize(
            """{ "name": "v", "exclude": [ { "match": "com.acme.core.spi", "kind": "packages", "within": "acme:core:*" },
                                           { "match": "com.acme.app.MainHelper", "kind": "types" } ] }""",
        )
        val h = view.model.hierarchy

        // in the order of the exclusions
        assertThat(view.excluded).containsExactly(r.spiPkg, r.mainHelper)
        assertThat(h.childrenOf(r.corePkg)).containsExactly(r.engine, r.engineImpl)
        assertThat(h.childrenOf(r.appPkg)).containsExactly(r.main)
        assertThat(view.excludedCount).isEqualTo(3)
    }

    @Test
    fun `an exclusion that selects nothing is a warning, and nothing is forked`() {
        val view = materialize("""{ "name": "v", "exclude": ["acme:nothing:*"] }""")

        assertThat(view.model.hierarchy).isSameAs(r.hierarchy)
        assertThat(view.excludedCount).isZero()
        val finding = view.report.findings.single()
        assertThat(finding.code).isEqualTo(Code.EXCLUDE_SELECTS_NOTHING)
        assertThat(finding.group).isEqualTo("exclude[0]")
    }

    @Test
    fun `the base model is never changed`() {
        val before = r.hierarchy.descendantsOf(r.root).map { it to r.hierarchy.parentOf(it) }

        materialize(noTests)

        assertThat(r.hierarchy.descendantsOf(r.root).map { it to r.hierarchy.parentOf(it) }).isEqualTo(before)
        assertThat(r.hierarchy.lookupNode(r.mainTest.identifier)).isSameAs(r.mainTest)
    }

    @Test
    fun `a restructuring view moves without the excluded nodes, and its effect leaves them out`() {
        r.dependency(r.mainTest, r.engine, 3)
        r.dependency(r.main, r.strings, 1)
        val view = materialize(
            """{ "name": "v", "restructure": true, "exclude": ["**:test"],
                 "moves": [ { "select": { "match": "com.acme.app.MainTest", "kind": "types" }, "to": "acme:core:*" },
                            { "select": "acme:core-util:*", "to": "acme:core:*" } ] }""",
        )

        assertThat(view.report.findings.map { it.code }).contains(Code.MOVE_SELECTS_NOTHING, Code.NODES_EXCLUDED)
        assertThat(view.model.hierarchy.lookupNode(r.mainTest.identifier)).isNull()
        val effect = view.effect!!
        val modules = effect.added.flatMap { listOf(it.from, it.to) } + effect.removed.flatMap { listOf(it.from, it.to) } +
            effect.changed.flatMap { listOf(it.from, it.to) } + effect.cycles.flatten() + effect.baseCycles.flatten()
        assertThat(modules).noneMatch { it.endsWith(":test") }
        assertThat(view.excludedCount).isEqualTo(5)
    }

    /** External Types › org.junit › Test, Assertions; and › org.slf4j › Logger, each referenced as given. */
    private inner class Externals {
        val module = r.node(GraphStore.EXTERNAL_ARTIFACT_NAME, JavaNodeKind.MODULE, r.root)
        val junit = r.pkg(module, "org.junit")
        val test = external(junit, "Test")
        val assertions = external(junit, "Assertions")
        val slf4j = r.pkg(module, "org.slf4j")
        val logger = external(slf4j, "Logger")

        private fun external(pkg: HGNode, simple: String) =
            r.node("${pkg.nodeSource.qualifiedName}.$simple", null, pkg).also { it.kind = JavaNodeKind.EXTERNAL_TYPE }
    }

    @Test
    fun `external types only the excluded nodes referenced leave the view, with the packages they empty`() {
        val ext = Externals()
        r.dependency(r.mainTest, ext.test)
        r.dependency(r.mainTest, ext.assertions)
        r.dependency(r.mainTest, ext.logger)
        r.dependency(r.main, ext.logger)

        val view = materialize(noTests)
        val h = view.model.hierarchy

        assertThat(h.lookupNode(ext.test.identifier)).isNull()
        assertThat(h.lookupNode(ext.assertions.identifier)).isNull()
        assertThat(h.descendantsOf(ext.module)).doesNotContain(ext.junit).contains(ext.slf4j, ext.logger)
        // Test, Assertions and org.junit; org stays, as it still holds org.slf4j.
        assertThat(h.lookupNode(ext.junit.identifier)).isNull()
        assertThat(view.excludedCount).isEqualTo(5 + 3)
        // Only the selected nodes are named as excluded.
        assertThat(view.excluded).containsExactly(r.appTest)
        val finding = view.report.findings.single { it.code == Code.EXTERNAL_TYPES_UNREFERENCED }
        assertThat(finding.severity).isEqualTo(Severity.INFO)
        assertThat(finding.message)
            .isEqualTo("2 external types only the excluded nodes referenced leave the view: 3 nodes in all")
    }

    @Test
    fun `the External Types module goes when the sweep empties it`() {
        val ext = Externals()
        r.dependency(r.mainTest, ext.test)
        r.dependency(r.mainTest, ext.assertions)
        r.dependency(r.mainTest, ext.logger)

        val h = materialize(noTests).model.hierarchy

        assertThat(h.childrenOf(r.root)).containsExactly(r.app, r.core, r.coreUtil)
        assertThat(h.lookupNode(ext.module.identifier)).isNull()
    }

    @Test
    fun `an external type nothing referenced before stays, and nothing is swept without exclusions`() {
        val ext = Externals()
        r.dependency(r.mainTest, ext.test)

        val h = materialize(noTests).model.hierarchy
        assertThat(h.lookupNode(ext.test.identifier)).isNull()
        // Never referenced in the base: not the view's doing.
        assertThat(h.lookupNode(ext.assertions.identifier)).isSameAs(ext.assertions)
        assertThat(h.lookupNode(ext.logger.identifier)).isSameAs(ext.logger)

        val unexcluded = materialize("""{ "name": "all", "groups": [] }""").model.hierarchy
        assertThat(unexcluded.lookupNode(ext.test.identifier)).isSameAs(ext.test)
    }
}
