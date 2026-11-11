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
import io.hierograph.server.views.ViewFinding.Code
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [ViewResolver] on the [TestReactor]. */
class ViewResolverTest {

    private val r = TestReactor()
    private val hierarchy = r.hierarchy
    private val root = r.root
    private val byName = r.byName
    private val app = r.app
    private val appTest = r.appTest
    private val core = r.core
    private val coreUtil = r.coreUtil
    private val appPkg = r.appPkg
    private val main = r.main
    private val mainHelper = r.mainHelper
    private val appTestPkg = r.appTestPkg
    private val mainTest = r.mainTest
    private val corePkg = r.corePkg
    private val engine = r.engine
    private val engineImpl = r.engineImpl
    private val spiPkg = r.spiPkg
    private val plugin = r.plugin
    private val utilPkg = r.utilPkg
    private val strings = r.strings

    private fun resolve(json: String): ResolvedView = ViewResolver(hierarchy).resolve(ViewDocumentParser.parse(json))

    private fun view(vararg groups: String) = """{ "name": "v", "groups": [ ${groups.joinToString()} ] }"""

    private fun ResolvedView.group(path: String): ResolvedGroup =
        flatten(groups).single { it.path == path }

    private fun flatten(groups: List<ResolvedGroup>): List<ResolvedGroup> = groups.flatMap { listOf(it) + flatten(it.groups) }

    private fun ResolvedView.codes() = report.findings.map { it.code }

    @Test
    fun `groups modules by pattern below the root`() {
        val view = resolve(
            view(
                """{ "name": "core", "members": ["acme:core*:*"] }""",
                """{ "name": "app", "members": ["acme:app:*", "acme:app:*:test"] }""",
            ),
        )

        assertThat(view.report.findings).isEmpty()
        assertThat(view.groups.map { it.name }).containsExactly("core", "app")
        assertThat(view.group("core").members).containsExactly(core, coreUtil)
        assertThat(view.group("core").parent).isSameAs(root)
        assertThat(view.group("app").members).containsExactly(app, appTest)
    }

    @Test
    fun `members are in base-hierarchy order, whatever the selector order`() {
        val view = resolve(view("""{ "name": "all", "members": ["acme:core-util:1.0", "acme:app:1.0:test", "acme:core:1.0", "acme:app:1.0"] }"""))

        assertThat(view.group("all").members).containsExactly(app, appTest, core, coreUtil)
    }

    @Test
    fun `nested groups resolve with paths and share the outer group's parent`() {
        val view = resolve(
            view(
                """{ "name": "all", "groups": [
                     { "name": "core", "members": ["acme:core*:*"] },
                     { "name": "app", "members": ["acme:app:**"] } ] }""",
            ),
        )

        assertThat(view.report.findings).isEmpty()
        val all = view.groups.single()
        assertThat(all.path).isEqualTo("all")
        assertThat(all.members).isEmpty()
        assertThat(all.parent).isSameAs(root)
        assertThat(all.groups.map { it.path }).containsExactly("all/core", "all/app")
        assertThat(view.group("all/app").members).containsExactly(app, appTest)
    }

    @Test
    fun `a group below a package groups types`() {
        val view = resolve(view("""{ "name": "engine", "members": [{ "match": "com.acme.core.Engine*", "kind": "types" }] }"""))

        assertThat(view.report.findings).isEmpty()
        assertThat(view.group("engine").parent).isSameAs(corePkg)
        assertThat(view.group("engine").members).containsExactly(engine, engineImpl)
    }

    @Test
    fun `kind narrows a match, and an alias expands to its kinds`() {
        val resolver = ViewResolver(hierarchy)

        assertThat(resolver.select(MemberSelector("com.acme.core*"))).containsExactly(corePkg, engine, engineImpl, spiPkg, plugin)
        assertThat(resolver.select(MemberSelector("com.acme.core*", kind = "java.package"))).containsExactly(corePkg, spiPkg)
        assertThat(resolver.select(MemberSelector("com.acme.core*", kind = "types"))).containsExactly(engine, engineImpl, plugin)
        assertThat(resolver.select(MemberSelector("com.acme.core*", kind = "packages"))).containsExactly(corePkg, spiPkg)
    }

    @Test
    fun `within restricts a match to the strict descendants of the nodes it selects`() {
        val resolver = ViewResolver(hierarchy)

        assertThat(resolver.select(MemberSelector("com.acme.app", kind = "java.package"))).containsExactly(appPkg, appTestPkg)
        assertThat(resolver.select(MemberSelector("com.acme.app", within = "acme:app:*"))).containsExactly(appPkg)
        assertThat(resolver.select(MemberSelector("com.acme.app", within = "acme:app:*:test"))).containsExactly(appTestPkg)
        assertThat(resolver.select(MemberSelector("com.acme.app", within = "com.acme.app"))).isEmpty()
        assertThat(resolver.select(MemberSelector("com", within = "acme:*:*"))).hasSize(3)
    }

    @Test
    fun `the root is never selected`() {
        assertThat(ViewResolver(hierarchy).select(MemberSelector("**"))).doesNotContain(root).contains(app, strings)
    }

    @Test
    fun `a selector that matches nothing is a warning, and a group left empty is not created`() {
        val view = resolve(
            view(
                """{ "name": "core", "members": ["acme:core:*", "acme:missing:*"] }""",
                """{ "name": "ghost", "members": ["acme:ghost:*"] }""",
                """{ "name": "hollow" }""",
            ),
        )

        assertThat(view.groups.map { it.name }).containsExactly("core")
        assertThat(view.codes()).containsExactly(
            Code.SELECTOR_MATCHES_NOTHING, Code.SELECTOR_MATCHES_NOTHING, Code.EMPTY_GROUP, Code.EMPTY_GROUP,
        )
        assertThat(view.report.findings.map { it.group }).containsExactly("core", "ghost", "ghost", "hollow")
        assertThat(view.report.findings[0].selector).isEqualTo(MemberSelector("acme:missing:*"))
        assertThat(view.report.hasErrors).isFalse()
    }

    @Test
    fun `a within that matches nothing is reported on its own`() {
        val view = resolve(view("""{ "name": "g", "members": [{ "match": "com", "within": "acme:nowhere:*" }] }"""))

        assertThat(view.codes()).containsExactly(Code.WITHIN_MATCHES_NOTHING, Code.EMPTY_GROUP)
    }

    @Test
    fun `a node selected by two groups stays in the first, in document order`() {
        val view = resolve(
            view(
                """{ "name": "outer", "groups": [ { "name": "inner", "members": ["acme:core:1.0"] } ] }""",
                """{ "name": "second", "members": ["acme:core:1.0", "acme:core-util:1.0"] }""",
            ),
        )

        val finding = view.report.findings.single()
        assertThat(finding.code).isEqualTo(Code.NODE_IN_TWO_GROUPS)
        assertThat(finding.severity).isEqualTo(ViewFinding.Severity.ERROR)
        assertThat(finding.group).isEqualTo("second")
        assertThat(finding.nodes).containsExactly(core)
        assertThat(finding.message).contains("'outer/inner'", "'second'")
        assertThat(view.group("outer/inner").members).containsExactly(core)
        assertThat(view.group("second").members).containsExactly(coreUtil)
    }

    @Test
    fun `a member inside another group's member is dropped`() {
        val view = resolve(
            view(
                """{ "name": "modules", "members": ["acme:core:1.0"] }""",
                """{ "name": "packages", "members": [{ "match": "com.acme.core", "within": "acme:core:*" }] }""",
            ),
        )

        assertThat(view.codes()).containsExactly(Code.MEMBER_INSIDE_OTHER_GROUPS_MEMBER, Code.EMPTY_GROUP)
        val finding = view.report.findings[0]
        assertThat(finding.group).isEqualTo("packages")
        assertThat(finding.nodes).containsExactly(corePkg, core)
        assertThat(view.groups.map { it.name }).containsExactly("modules")
    }

    @Test
    fun `a member inside a member of the same group is dropped with a warning`() {
        val view = resolve(view("""{ "name": "core", "members": ["acme:core:1.0", "com.acme.core.Engine"] }"""))

        assertThat(view.codes()).containsExactly(Code.MEMBER_INSIDE_OWN_MEMBER)
        assertThat(view.report.hasErrors).isFalse()
        assertThat(view.group("core").members).containsExactly(core)
    }

    @Test
    fun `members inside the group's own members are reported once per group, with their number`() {
        val view = resolve(view("""{ "name": "core", "members": ["acme:core:1.0", "com.acme.core*"] }"""))

        val finding = view.report.findings.single { it.code == Code.MEMBER_INSIDE_OWN_MEMBER }
        assertThat(finding.message).containsPattern("\\d+ selected nodes lie inside other members of 'core'")
        assertThat(finding.nodes).hasSize(2)
        assertThat(view.group("core").members).containsExactly(core)
    }

    @Test
    fun `members with different parents keep the group from being applied`() {
        val view = resolve(
            view(
                """{ "name": "app-packages", "members": [{ "match": "com.acme.app", "kind": "packages" }] }""",
                """{ "name": "core", "members": ["acme:core:1.0"] }""",
            ),
        )

        val finding = view.report.findings.single()
        assertThat(finding.code).isEqualTo(Code.MEMBERS_WITH_DIFFERENT_PARENTS)
        assertThat(finding.group).isEqualTo("app-packages")
        assertThat(finding.nodes).containsExactlyInAnyOrder(
            hierarchy.parentOf(appPkg), hierarchy.parentOf(appTestPkg),
        )
        assertThat(view.groups.map { it.name }).containsExactly("core")
    }

    @Test
    fun `a group whose subgroups have different parents is not applied, but its subgroups are`() {
        val view = resolve(
            view(
                """{ "name": "mixed", "groups": [
                     { "name": "modules", "members": ["acme:core*:*"] },
                     { "name": "types", "members": [{ "match": "com.acme.app.Main*", "kind": "types", "within": "acme:app:1.0" }] } ] }""",
            ),
        )

        assertThat(view.codes()).containsExactly(Code.MEMBERS_WITH_DIFFERENT_PARENTS)
        assertThat(view.report.findings.single().group).isEqualTo("mixed")
        assertThat(view.groups.map { it.path }).containsExactly("mixed/modules", "mixed/types")
        assertThat(view.group("mixed/modules").parent).isSameAs(root)
        assertThat(view.group("mixed/types").parent).isSameAs(appPkg)
        assertThat(view.group("mixed/types").members).containsExactly(main, mainHelper)
    }

    @Test
    fun `an empty subgroup does not keep its group from being applied`() {
        val view = resolve(
            view(
                """{ "name": "all", "members": ["acme:app:1.0"], "groups": [ { "name": "nothing", "members": ["x"] } ] }""",
            ),
        )

        assertThat(view.codes()).containsExactly(Code.SELECTOR_MATCHES_NOTHING, Code.EMPTY_GROUP)
        assertThat(view.groups.single().groups).isEmpty()
        assertThat(view.groups.single().members).containsExactly(app)
    }

    @Test
    fun `resolving leaves the hierarchy unchanged`() {
        val before = hierarchy.descendantsOf(root).map { it to hierarchy.parentOf(it) }

        resolve(view("""{ "name": "all", "members": ["acme:**"] }"""))

        assertThat(hierarchy.descendantsOf(root).map { it to hierarchy.parentOf(it) }).isEqualTo(before)
        assertThat(hierarchy.localNodes).isEmpty()
    }

    @Test
    fun `a view without groups resolves to nothing`() {
        val view = resolve("""{ "name": "empty" }""")

        assertThat(view.groups).isEmpty()
        assertThat(view.report.findings).isEmpty()
    }

    @Test
    fun `an exact selector finds a type in each artifact that has it`() {
        assertThat(ViewResolver(hierarchy).select(MemberSelector("com.acme.app.Main"))).containsExactly(main)
        assertThat(ViewResolver(hierarchy).select(MemberSelector("com.acme.app.MainTest"))).containsExactly(mainTest)
        assertThat(byName.getValue("com.acme")).hasSize(4)
    }
}
