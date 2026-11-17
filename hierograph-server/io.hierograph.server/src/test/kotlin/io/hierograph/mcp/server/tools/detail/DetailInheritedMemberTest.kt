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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphstore.jvm.archive.ParquetWriter
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.archive.model.MemberModifiers
import io.hierograph.graphstore.jvm.archive.model.TypeModifiers
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Verifies that a member reference compiled against a type that only inherits the member names the
 * declaration, with the compiled type as `receiver`.
 *
 * ```
 * com.example.base.Base          void configure(), int size
 * com.example.base.Api           interface: void write()
 * com.example.impl.Impl          extends Base
 * com.example.impl.Svc           interface, extends Api
 * com.example.impl.Err           extends java.lang.RuntimeException (not scanned)
 * com.example.impl.Impl2         extends Impl; configure() (line 20) overrides Base#configure() and calls
 *                                super.configure(), compiled as Impl#configure(), on line 20
 * com.example.app.Caller#run()   calls Impl#configure() (10), reads Impl#size (11),
 *                                calls Svc#write() (12), calls Err#getMessage() (13)
 * ```
 */
@Suppress("UNCHECKED_CAST")
class DetailInheritedMemberTest {

    private val graphService = HierarchicalGraphService()
    private val outgoing: OutgoingDependenciesTool

    init {
        val dir = Files.createTempDirectory("hierograph-detail-inherited-member")
        ParquetWriter.writeArtifacts(dir, classes(), dependencies(), packaging = ParquetWriter.Packaging.EXPLODED)
        graphService.seedFromParquet(dir)
        val engine = ParquetDetailDependencies(graphService, DataHashProvider(graphService))
        outgoing = OutgoingDependenciesTool(graphService, NodeRefFactory(graphService), engine, DataHashProvider(graphService))
    }

    private fun id(fqn: String): Long {
        val hierarchy = graphService.model.hierarchy
        var found: Long? = null
        hierarchy.traverse(hierarchy.rootNode) { if (it.nodeSource.qualifiedName == fqn) found = it.identifier as Long }
        return found ?: error("no node $fqn")
    }

    private fun query(toId: Long): Pair<List<Map<String, Any?>>, Map<String, Map<String, Any?>>> {
        val resp = outgoing.outgoingDependencies(id(APP), toId, "detail", null, null, null)
        assertThat(resp["error"]).isNull()
        return resp["edges"] as List<Map<String, Any?>> to resp["nodes"] as Map<String, Map<String, Any?>>
    }

    /** The stored rows by line: the load-derived virtual call into Impl2 shares line 10 and is left out. */
    private fun byLine(rows: List<Map<String, Any?>>) = rows.filter { it["to"] != id("$IMPL2#void configure()") }
        .associateBy { (it["location"] as Map<String, Any?>)["line_number"] }

    @Test
    fun `a member the receiver only inherits is named by its declaration, with the receiver`() {
        val (rows, nodes) = query(id(IMPL_PKG))
        val line = byLine(rows)
        // The four stored rows, and the virtual call of line 10 dispatched into Impl2's override.
        assertThat(rows).hasSize(5)
        assertThat(line).hasSize(4)

        // A method of the base class, called on the subclass.
        assertThat(line[10L]!!["to"]).isEqualTo(id("$BASE#void configure()"))
        assertThat(line[10L]!!["to_parent"]).isEqualTo(id(BASE))
        assertThat(line[10L]!!["receiver"]).isEqualTo(id(IMPL))
        // A field of the base class, read through the subclass.
        assertThat(line[11L]!!["to"]).isEqualTo(id("$BASE#int size"))
        assertThat(line[11L]!!["receiver"]).isEqualTo(id(IMPL))
        // A method of a super-interface.
        assertThat(line[12L]!!["to"]).isEqualTo(id("$API#void write()"))
        assertThat(line[12L]!!["receiver"]).isEqualTo(id(SVC))
        assertThat(nodes[line[12L]!!["receiver"].toString()]!!["qualified_name"]).isEqualTo(SVC)
    }

    @Test
    fun `a member declared outside the scanned code keeps its compiled key as an external member`() {
        val (rows, nodes) = query(id(IMPL_PKG))
        val getMessage = byLine(rows)[13L]!!
        assertThat(getMessage).doesNotContainKey("receiver")
        assertThat(getMessage["to_parent"]).isEqualTo(id(ERR))
        assertThat(nodes[getMessage["to"].toString()]!!["kind"]).isEqualTo("external.member")
    }

    private val module: Long
        get() {
            val h = graphService.model.hierarchy
            var m: Long? = null
            h.traverse(h.rootNode) { if (it.kind == JavaNodeKind.MODULE) m = it.identifier as Long }
            return m!!
        }

    @Test
    fun `a call is listed once when receiver and declaration both lie in the target`() {
        val (rows, _) = query(module)
        val configure = rows.filter { it["to"] == id("$BASE#void configure()") }
        assertThat(configure).singleElement().satisfies({ assertThat(it["receiver"]).isEqualTo(id(IMPL)) })
        assertThat(rows.filter { it["to"] == id("$API#void write()") }).hasSize(1)
    }

    @Test
    fun `an override on the line of its super call is not taken for the call's duplicate`() {
        // Impl2#configure() overrides Base#configure() and calls super.configure() on the same line, which javac
        // compiles against Impl (its direct superclass, which only inherits it).
        val resp = outgoing.outgoingDependencies(id(IMPL2), module, "detail", null, null, null)
        val toBase = (resp["edges"] as List<Map<String, Any?>>).filter { it["to"] == id("$BASE#void configure()") }
        assertThat(toBase.map { it["relationship"] }).containsExactlyInAnyOrder("overrides", "calls")
    }

    @Test
    fun `the declaring scope still sees the call through the resolved target`() {
        val (rows, _) = query(id(BASE_PKG))
        assertThat(rows.map { it["to"] }).containsExactlyInAnyOrder(id("$BASE#void configure()"), id("$API#void write()"))
        assertThat(rows).allSatisfy { assertThat(it).doesNotContainKey("receiver") }
    }

    private fun method(owner: String, signature: String, abstract: Boolean = false) =
        MemberFacts("$owner#$signature", JavaNodeKind.METHOD, signature, signature.substringAfter(' ').substringBefore('('),
            modifiers = MemberModifiers.pack("public", abstract = abstract))

    private fun type(fqn: String, kind: JavaNodeKind, vararg members: MemberFacts) = ParquetWriter.ArtifactClass(
        ARTIFACT,
        ClassFacts(fqn, kind, TypeModifiers.pack("public", abstract = kind == JavaNodeKind.INTERFACE),
            members = members.toList(), classHash = fqn),
    )

    private fun classes() = listOf(
        type(BASE, JavaNodeKind.CLASS, method(BASE, "void configure()"),
            MemberFacts("$BASE#int size", JavaNodeKind.FIELD, "int size", "size", modifiers = MemberModifiers.pack("public"))),
        type(API, JavaNodeKind.INTERFACE, method(API, "void write()", abstract = true)),
        type(IMPL, JavaNodeKind.CLASS),
        type(SVC, JavaNodeKind.INTERFACE),
        type(ERR, JavaNodeKind.CLASS),
        type(CALLER, JavaNodeKind.CLASS, method(CALLER, "void run()")),
        type(IMPL2, JavaNodeKind.CLASS, method(IMPL2, "void configure()").copy(lineFrom = 20)),
    )

    private fun dependencies() = listOf(
        Dependency(IMPL, IMPL, BASE, BASE, DependencyKind.EXTENDS),
        Dependency(SVC, SVC, API, API, DependencyKind.EXTENDS),
        Dependency(ERR, ERR, "java.lang.RuntimeException", "java.lang.RuntimeException", DependencyKind.EXTENDS),
        Dependency("$CALLER#void run()", CALLER, "$IMPL#void configure()", IMPL, DependencyKind.CALLS, 10),
        Dependency("$CALLER#void run()", CALLER, "$IMPL#int size", IMPL, DependencyKind.READS_FIELD, 11),
        Dependency("$CALLER#void run()", CALLER, "$SVC#void write()", SVC, DependencyKind.CALLS, 12),
        Dependency("$CALLER#void run()", CALLER, "$ERR#java.lang.String getMessage()", ERR, DependencyKind.CALLS, 13),
        Dependency(IMPL2, IMPL2, IMPL, IMPL, DependencyKind.EXTENDS),
        Dependency("$IMPL2#void configure()", IMPL2, "$IMPL#void configure()", IMPL, DependencyKind.CALLS, 20),
    )

    private companion object {
        const val ARTIFACT = "app"
        const val APP = "com.example.app"
        const val BASE_PKG = "com.example.base"
        const val IMPL_PKG = "com.example.impl"
        const val BASE = "com.example.base.Base"
        const val API = "com.example.base.Api"
        const val IMPL = "com.example.impl.Impl"
        const val SVC = "com.example.impl.Svc"
        const val ERR = "com.example.impl.Err"
        const val IMPL2 = "com.example.impl.Impl2"
        const val CALLER = "com.example.app.Caller"
    }
}
