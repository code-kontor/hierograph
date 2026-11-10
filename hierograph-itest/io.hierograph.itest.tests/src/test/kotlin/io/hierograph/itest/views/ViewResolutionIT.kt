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

import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.itest.fwk.AbstractHierarchicalGraphIntegrationTest
import io.hierograph.server.views.MemberSelector
import io.hierograph.server.views.ResolvedView
import io.hierograph.server.views.ViewDocumentParser
import io.hierograph.server.views.ViewFinding.Code
import io.hierograph.server.views.ViewResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * View documents resolved against the real Spring 7.0.8 model: jar grouping, the package ambiguity across
 * jars that `kind` and `within` resolve, and the pitfalls of patterns over jar names.
 */
class ViewResolutionIT : AbstractHierarchicalGraphIntegrationTest() {

    private val resolver by lazy { ViewResolver(model.hierarchy) }

    private fun resolve(json: String): ResolvedView = resolver.resolve(ViewDocumentParser.parse(json))

    private fun names(nodes: List<io.hierograph.hierarchicalgraph.model.HGNode>) = nodes.map { NodeMetadata.getName(it) }

    @Test
    fun `the Spring jars group into core container, data access and web`() {
        val view = resolve(
            """
            {
              "name": "spring-modules",
              "groups": [
                { "name": "core-container", "members": ["spring-{core,beans,context,expression,aop}-7.0.8.jar"] },
                { "name": "data-access",    "members": ["spring-{jdbc,tx,orm,r2dbc,oxm,jms}-*.jar"] },
                { "name": "web",            "members": ["spring-web-*.jar", "spring-web{mvc,flux,socket}-*.jar"] }
              ]
            }
            """,
        )

        assertThat(view.report.findings).isEmpty()
        assertThat(view.groups.map { it.name }).containsExactly("core-container", "data-access", "web")
        assertThat(view.groups).allSatisfy { assertThat(it.parent).isSameAs(model.hierarchy.rootNode) }
        assertThat(names(view.groups[0].members)).containsExactlyInAnyOrder(
            "spring-core-7.0.8.jar", "spring-beans-7.0.8.jar", "spring-context-7.0.8.jar",
            "spring-expression-7.0.8.jar", "spring-aop-7.0.8.jar",
        )
        assertThat(view.groups[1].members).hasSize(6)
        assertThat(names(view.groups[2].members)).containsExactlyInAnyOrder(
            "spring-web-7.0.8.jar", "spring-webmvc-7.0.8.jar", "spring-webflux-7.0.8.jar", "spring-websocket-7.0.8.jar",
        )
        // in base-hierarchy order
        val rootOrder = model.hierarchy.childrenOf(model.hierarchy.rootNode)
        for (group in view.groups) {
            assertThat(group.members).isSortedAccordingTo(compareBy { rootOrder.indexOf(it) })
        }
    }

    @Test
    fun `a star after a jar name also matches longer jar names`() {
        // `spring-context-*.jar` matches spring-context-support and -indexer too: the star runs over "support-7.0.8"
        assertThat(names(resolver.select(MemberSelector("spring-context-*.jar"))))
            .containsExactlyInAnyOrder("spring-context-7.0.8.jar", "spring-context-indexer-7.0.8.jar", "spring-context-support-7.0.8.jar")
        assertThat(names(resolver.select(MemberSelector("spring-context-7.0.8.jar"))))
            .containsExactly("spring-context-7.0.8.jar")
    }

    @Test
    fun `a package name shared by every jar selects one package per jar, which cannot form a group`() {
        val selected = resolver.select(MemberSelector("org.springframework", kind = "java.package"))
        assertThat(selected).hasSizeGreaterThanOrEqualTo(21)

        val view = resolve(
            """{ "name": "v", "groups": [ { "name": "spring", "members": [{ "match": "org.springframework", "kind": "packages" }] } ] }""",
        )

        assertThat(view.groups).isEmpty()
        val finding = view.report.findings.single()
        assertThat(finding.code).isEqualTo(Code.MEMBERS_WITH_DIFFERENT_PARENTS)
        assertThat(finding.nodes).hasSize(selected.size)
    }

    @Test
    fun `within picks the package of one jar and groups below it`() {
        val view = resolve(
            """
            {
              "name": "core-io",
              "groups": [
                { "name": "resources",
                  "members": [{ "match": "org.springframework.core.{io,codec}", "kind": "java.package", "within": "spring-core-*.jar" }] }
              ]
            }
            """,
        )

        assertThat(view.report.findings).isEmpty()
        val group = view.groups.single()
        assertThat(group.members.map { NodeMetadata.getQualifiedName(it) })
            .containsExactlyInAnyOrder("org.springframework.core.io", "org.springframework.core.codec")
        assertThat(NodeMetadata.getQualifiedName(group.parent)).isEqualTo("org.springframework.core")
        assertThat(model.hierarchy.predecessorsOf(group.parent).map { NodeMetadata.getName(it) }).contains("spring-core-7.0.8.jar")
        assertThat(group.parent.kind).isEqualTo(JavaNodeKind.PACKAGE)
    }

    @Test
    fun `a star crosses package boundaries, so a type pattern can reach into sub-packages`() {
        // `*` stops only at ':': `core.io.*Resource` also matches core.io.support.EncodedResource and others
        val view = resolve(
            """{ "name": "v", "groups": [ { "name": "resources",
                 "members": [{ "match": "org.springframework.core.io.*Resource", "kind": "types", "within": "spring-core-*.jar" }] } ] }""",
        )

        assertThat(view.groups).isEmpty()
        assertThat(view.report.findings.single().code).isEqualTo(Code.MEMBERS_WITH_DIFFERENT_PARENTS)
        assertThat(view.report.findings.single().nodes.map { NodeMetadata.getQualifiedName(it) })
            .contains("org.springframework.core.io", "org.springframework.core.io.support")
    }

    @Test
    fun `alternatives select the types of one package precisely`() {
        val view = resolve(
            """{ "name": "v", "groups": [ { "name": "resources",
                 "members": [{ "match": "org.springframework.core.io.{,ClassPath,FileSystem,Url}Resource", "kind": "types",
                               "within": "spring-core-*.jar" }] } ] }""",
        )

        assertThat(view.report.findings).isEmpty()
        val group = view.groups.single()
        assertThat(NodeMetadata.getQualifiedName(group.parent)).isEqualTo("org.springframework.core.io")
        assertThat(group.members.map { NodeMetadata.getName(it) })
            .containsExactlyInAnyOrder("Resource", "ClassPathResource", "FileSystemResource", "UrlResource")
    }

    @Test
    fun `selecting a jar and a package inside it keeps the jar and drops the package`() {
        val view = resolve(
            """{ "name": "v", "groups": [
                 { "name": "core", "members": ["spring-core-7.0.8.jar"] },
                 { "name": "io", "members": [{ "match": "org.springframework.core.io", "within": "spring-core-*.jar" }] } ] }""",
        )

        assertThat(view.report.findings.map { it.code })
            .containsExactly(Code.MEMBER_INSIDE_OTHER_GROUPS_MEMBER, Code.EMPTY_GROUP)
        assertThat(view.groups.map { it.name }).containsExactly("core")
    }
}
