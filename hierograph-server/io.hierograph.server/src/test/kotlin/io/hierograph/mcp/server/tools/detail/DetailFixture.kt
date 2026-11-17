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

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.archive.model.MemberModifiers
import io.hierograph.graphstore.jvm.archive.model.ParameterFacts
import io.hierograph.graphstore.jvm.archive.model.TypeModifiers
import io.hierograph.graphstore.jvm.archive.ParquetWriter
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.IncomingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import java.nio.file.Files

/**
 * A small Parquet snapshot for tests of the detail-level engine, loaded through the same path as
 * `parquet` mode (detail data included):
 *
 * ```
 * com.example.doc.AbstractDoc<T>         void render(), Money total()   total() calls Money#plus
 * com.example.doc.Renderable             interface: void render()
 * com.example.billing.Invoice            extends AbstractDoc, implements Renderable
 *     void render()                      overrides AbstractDoc#render and Renderable#render (derived)
 *     Money net()                        returns Money; calls Money#plus; calls AbstractDoc#total
 *     Money tax()                        calls Money#times
 *     void validate()                    calls AbstractDoc#render
 *     void transfer(Money from, Money to)   Money at positions 0 and 1 (only with withParameterMethod)
 * com.example.money.Money                Money plus(Money), Money times(int),
 *                                        @Deprecated int cents (java.lang.Deprecated has no node)
 * ```
 */
class DetailFixture(private val withParameterMethod: Boolean = false) {

    val graphService = HierarchicalGraphService()
    val outgoing: OutgoingDependenciesTool
    val incoming: IncomingDependenciesTool

    init {
        val dir = Files.createTempDirectory("hierograph-detail-fixture")
        ParquetWriter.writeArtifacts(
            dir, classes(), dependencies(), packaging = ParquetWriter.Packaging.EXPLODED,
        )
        graphService.seedFromParquet(dir)
        val engine = ParquetDetailDependencies(graphService, DataHashProvider(graphService))
        val refs = NodeRefFactory(graphService)
        outgoing = OutgoingDependenciesTool(graphService, refs, engine, DataHashProvider(graphService))
        incoming = IncomingDependenciesTool(outgoing, engine)
    }

    /** The node with qualified name [fqn] (a package, type or `<owner>#<signature>` member key). */
    fun node(fqn: String): HGNode {
        var found: HGNode? = null
        val hierarchy = graphService.model.hierarchy
        hierarchy.traverse(hierarchy.rootNode) { if (it.nodeSource.qualifiedName == fqn) found = it }
        return found ?: error("no node $fqn")
    }

    fun id(fqn: String): Long = node(fqn).identifier as Long

    private fun method(owner: String, signature: String, abstract: Boolean = false) =
        MemberFacts("$owner#$signature", JavaNodeKind.METHOD, signature, signature.substringAfter(' ').substringBefore('('),
            modifiers = MemberModifiers.pack("public", abstract = abstract))

    private fun classes() = listOf(
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            DOC, JavaNodeKind.CLASS, TypeModifiers.pack("public", abstract = true, generic = true),
            members = listOf(
                method(DOC, "void render()"), method(DOC, "$MONEY total()"),
                MemberFacts("$DOC#int pages", JavaNodeKind.FIELD, "int pages", "pages", modifiers = MemberModifiers.pack("protected")),
            ),
            classHash = "d",
        )),
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            RENDERABLE, JavaNodeKind.INTERFACE, TypeModifiers.pack("public", abstract = true),
            members = listOf(method(RENDERABLE, "void render()", abstract = true)),
            classHash = "r",
        )),
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            INVOICE, JavaNodeKind.CLASS, TypeModifiers.pack("public"),
            members = listOf(
                MemberFacts("$INVOICE#void <init>()", JavaNodeKind.CONSTRUCTOR, "void <init>()", "<init>", modifiers = MemberModifiers.pack("public")),
                method(INVOICE, "void render()"), method(INVOICE, "$MONEY net()"),
                method(INVOICE, "$MONEY tax()"), method(INVOICE, "void validate()"),
            ) + listOfNotNull(
                method(INVOICE, "void transfer($MONEY,$MONEY)").copy(
                    parameters = listOf(ParameterFacts(0, "from", MONEY), ParameterFacts(1, "to", MONEY)),
                ).takeIf { withParameterMethod },
            ),
            classHash = "i",
        )),
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            MONEY, JavaNodeKind.RECORD, TypeModifiers.pack("public", final = true),
            members = listOf(
                method(MONEY, "$MONEY plus($MONEY)"),
                method(MONEY, "$MONEY times(int)").copy(
                    parameters = listOf(ParameterFacts(0, "factor", "int", annotations = listOf("javax.annotation.Nonnull"))),
                ),
                MemberFacts("$MONEY#int cents", JavaNodeKind.FIELD, "int cents", "cents", modifiers = MemberModifiers.pack("private", final = true)),
                MemberFacts("$MONEY#int SCALE", JavaNodeKind.FIELD, "int SCALE", "SCALE",
                    modifiers = MemberModifiers.pack("public", static = true, final = true, constant = true)),
            ),
            classHash = "m",
        )),
    )

    /** A type's aggregated references, rolled up from its detail facts as the scanner does. */

    private fun dependencies() = listOf(
        Dependency(INVOICE, INVOICE, DOC, DOC, DependencyKind.EXTENDS),
        Dependency(INVOICE, INVOICE, RENDERABLE, RENDERABLE, DependencyKind.IMPLEMENTS),
        Dependency("$DOC#$MONEY total()", DOC, "$MONEY#$MONEY plus($MONEY)", MONEY, DependencyKind.CALLS, 5),
        Dependency("$INVOICE#$MONEY net()", INVOICE, MONEY, MONEY, DependencyKind.RETURNS, 9),
        Dependency("$INVOICE#$MONEY net()", INVOICE, "$MONEY#$MONEY plus($MONEY)", MONEY, DependencyKind.CALLS, 10),
        Dependency("$INVOICE#$MONEY net()", INVOICE, "$DOC#$MONEY total()", DOC, DependencyKind.CALLS, 11),
        Dependency("$INVOICE#$MONEY tax()", INVOICE, "$MONEY#$MONEY times(int)", MONEY, DependencyKind.CALLS, 20),
        Dependency("$INVOICE#void validate()", INVOICE, "$DOC#void render()", DOC, DependencyKind.CALLS, 30),
        Dependency("$MONEY#int cents", MONEY, "int", "int", DependencyKind.HAS_TYPE),
        // net() reads the inherited field pages through the subclass, as javac compiles `this.pages`
        Dependency("$INVOICE#$MONEY net()", INVOICE, "$INVOICE#int pages", INVOICE, DependencyKind.READS_FIELD, 12),
        Dependency("$MONEY#int cents", MONEY, "java.lang.Deprecated", "java.lang.Deprecated", DependencyKind.ANNOTATED_BY),
    ) + if (!withParameterMethod) emptyList() else listOf(
        // one parameter_type fact per parameter, carrying its position and name
        Dependency("$INVOICE#void transfer($MONEY,$MONEY)", INVOICE, MONEY, MONEY, DependencyKind.PARAMETER_TYPE, 40, parameterIndex = 0, parameterName = "from"),
        Dependency("$INVOICE#void transfer($MONEY,$MONEY)", INVOICE, MONEY, MONEY, DependencyKind.PARAMETER_TYPE, 40, parameterIndex = 1, parameterName = "to"),
    )

    companion object {
        const val ARTIFACT = "billing-core"
        const val DOC = "com.example.doc.AbstractDoc"
        const val RENDERABLE = "com.example.doc.Renderable"
        const val INVOICE = "com.example.billing.Invoice"
        const val MONEY = "com.example.money.Money"
    }
}
