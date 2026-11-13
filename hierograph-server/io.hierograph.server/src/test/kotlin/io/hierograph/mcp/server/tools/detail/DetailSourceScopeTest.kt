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
 * Verifies that a supertype lying in the target scope adds nothing to the depending side: its code only
 * references the target, so its rows describe the target's internals.
 *
 * ```
 * com.example.ext.Root           void boot()   calls Util#fmt()
 * com.example.lib.Base           extends Root
 *     void run()                 calls Base#helper()       (the target talking to itself)
 *     void log()                 calls Util#fmt()          (the target talking to itself)
 *     void send()                calls Sender#send()       (an inherited use of a third package)
 * com.example.lib.Util           void fmt()
 * com.example.ext.Sender         void send()
 * com.example.app.Sub            extends Base; <init>() calls Base#<init>()
 * ```
 */
@Suppress("UNCHECKED_CAST")
class DetailSourceScopeTest {

    private val graphService = HierarchicalGraphService()
    private val outgoing: OutgoingDependenciesTool

    init {
        val dir = Files.createTempDirectory("hierograph-detail-source-scope")
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

    private fun name(id: Any?): String {
        val hierarchy = graphService.model.hierarchy
        var found: String? = null
        hierarchy.traverse(hierarchy.rootNode) { if (it.identifier == id) found = it.nodeSource.qualifiedName }
        return found ?: error("no node $id")
    }

    /** The rows of the query as `from relationship to (inherited)`, with the qualified names of the endpoints. */
    private fun rows(fromFqn: String, toFqn: String): List<String> {
        val resp = outgoing.outgoingDependencies(id(fromFqn), id(toFqn), "detail", null, null, null)
        assertThat(resp["error"]).isNull()
        return (resp["edges"] as List<Map<String, Any?>>).map {
            "${name(it["from"])} ${it["relationship"]} ${name(it["to"])}" + if (it["inherited"] == true) " (inherited)" else ""
        }
    }

    @Test
    fun `a supertype in the target scope contributes none of its own rows`() {
        assertThat(rows(APP, LIB)).containsExactlyInAnyOrder(
            "$SUB extends $BASE",
            "$SUB#void <init>() calls $BASE#void <init>()",
            // Root lies outside the target, so its code still counts, although it is reached through Base.
            "$ROOT#void boot() calls $UTIL#void fmt() (inherited)",
        )
    }

    @Test
    fun `an inherited use of a third scope is kept`() {
        // Root lies in the target and drops out; Base does not, so its use of ext and its own `extends` stay.
        assertThat(rows(APP, EXT)).containsExactlyInAnyOrder(
            "$BASE extends $ROOT (inherited)",
            "$BASE#void send() calls $SENDER#void send() (inherited)",
        )
    }

    @Test
    fun `a type of both scopes keeps its own rows`() {
        // Base is the source here, so its references into its own package are its own code, not inherited.
        assertThat(rows(BASE, LIB)).containsExactlyInAnyOrder(
            "$BASE#void run() calls $BASE#void helper()",
            "$BASE#void log() calls $UTIL#void fmt()",
            "$ROOT#void boot() calls $UTIL#void fmt() (inherited)",
        )
    }

    private fun method(owner: String, signature: String) =
        MemberFacts("$owner#$signature", JavaNodeKind.METHOD, signature, signature.substringAfter(' ').substringBefore('('),
            modifiers = MemberModifiers.pack("public"))

    private fun constructor(owner: String) =
        MemberFacts("$owner#void <init>()", JavaNodeKind.CONSTRUCTOR, "void <init>()", "<init>", modifiers = MemberModifiers.pack("public"))

    private fun type(fqn: String, vararg members: MemberFacts) = ParquetWriter.ArtifactClass(
        ARTIFACT,
        ClassFacts(fqn, JavaNodeKind.CLASS, TypeModifiers.pack("public"), members = members.toList(), classHash = fqn),
    )

    private fun classes() = listOf(
        type(ROOT, method(ROOT, "void boot()")),
        type(BASE, constructor(BASE), method(BASE, "void run()"), method(BASE, "void helper()"),
            method(BASE, "void log()"), method(BASE, "void send()")),
        type(UTIL, method(UTIL, "void fmt()")),
        type(SENDER, method(SENDER, "void send()")),
        type(SUB, constructor(SUB)),
    )

    private fun dependencies() = listOf(
        Dependency(BASE, BASE, ROOT, ROOT, DependencyKind.EXTENDS),
        Dependency(SUB, SUB, BASE, BASE, DependencyKind.EXTENDS),
        Dependency("$ROOT#void boot()", ROOT, "$UTIL#void fmt()", UTIL, DependencyKind.CALLS, 3),
        Dependency("$BASE#void run()", BASE, "$BASE#void helper()", BASE, DependencyKind.CALLS, 10),
        Dependency("$BASE#void log()", BASE, "$UTIL#void fmt()", UTIL, DependencyKind.CALLS, 11),
        Dependency("$BASE#void send()", BASE, "$SENDER#void send()", SENDER, DependencyKind.CALLS, 12),
        Dependency("$SUB#void <init>()", SUB, "$BASE#void <init>()", BASE, DependencyKind.CALLS, 5),
    )

    private companion object {
        const val ARTIFACT = "app"
        const val APP = "com.example.app"
        const val LIB = "com.example.lib"
        const val EXT = "com.example.ext"
        const val ROOT = "com.example.ext.Root"
        const val BASE = "com.example.lib.Base"
        const val UTIL = "com.example.lib.Util"
        const val SENDER = "com.example.ext.Sender"
        const val SUB = "com.example.app.Sub"
    }
}
