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

import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.MemberModifiers
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.model.TypeModifiers
import io.hierograph.graphstore.jvm.parquet.ParquetWriter
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
 * com.example.money.Money                Money plus(Money), Money times(int),
 *                                        @Deprecated int cents (java.lang.Deprecated has no node)
 * ```
 */
class DetailFixture {

    val graphService = HierarchicalGraphService()
    val outgoing: OutgoingDependenciesTool
    val incoming: IncomingDependenciesTool

    init {
        val dir = Files.createTempDirectory("hierograph-detail-fixture")
        ParquetWriter.writeArtifacts(
            dir, classes(), detailRefs(), packaging = ParquetWriter.Packaging.EXPLODED,
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
        MemberFacts("$owner#$signature", JavaKinds.METHOD, signature, signature.substringAfter(' ').substringBefore('('),
            modifiers = MemberModifiers.pack("public", abstract = abstract))

    private fun classes() = listOf(
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            DOC, JavaKinds.CLASS, TypeModifiers.pack("public", abstract = true, generic = true),
            members = listOf(method(DOC, "void render()"), method(DOC, "$MONEY total()")),
            refs = listOf(Ref(DOC, MONEY, RefKind.DEPENDS_ON, count = 1)), classHash = "d",
        )),
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            RENDERABLE, JavaKinds.INTERFACE, TypeModifiers.pack("public", abstract = true),
            members = listOf(method(RENDERABLE, "void render()", abstract = true)),
            refs = emptyList(), classHash = "r",
        )),
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            INVOICE, JavaKinds.CLASS, TypeModifiers.pack("public"),
            members = listOf(
                method(INVOICE, "void render()"), method(INVOICE, "$MONEY net()"),
                method(INVOICE, "$MONEY tax()"), method(INVOICE, "void validate()"),
            ),
            refs = listOf(
                Ref(INVOICE, DOC, RefKind.EXTENDS, count = 1),
                Ref(INVOICE, RENDERABLE, RefKind.IMPLEMENTS, count = 1),
                Ref(INVOICE, MONEY, RefKind.DEPENDS_ON, count = 3),
            ),
            classHash = "i",
        )),
        ParquetWriter.ArtifactClass(ARTIFACT, ClassFacts(
            MONEY, JavaKinds.RECORD, TypeModifiers.pack("public", final = true),
            members = listOf(
                method(MONEY, "$MONEY plus($MONEY)"), method(MONEY, "$MONEY times(int)"),
                MemberFacts("$MONEY#int cents", JavaKinds.FIELD, "int cents", "cents", modifiers = MemberModifiers.pack("private", final = true)),
            ),
            refs = emptyList(), classHash = "m",
        )),
    )

    private fun detailRefs() = listOf(
        DetailRef("$DOC#$MONEY total()", DOC, "$MONEY#$MONEY plus($MONEY)", MONEY, "calls", 5),
        DetailRef("$INVOICE#$MONEY net()", INVOICE, MONEY, MONEY, "returns", 9),
        DetailRef("$INVOICE#$MONEY net()", INVOICE, "$MONEY#$MONEY plus($MONEY)", MONEY, "calls", 10),
        DetailRef("$INVOICE#$MONEY net()", INVOICE, "$DOC#$MONEY total()", DOC, "calls", 11),
        DetailRef("$INVOICE#$MONEY tax()", INVOICE, "$MONEY#$MONEY times(int)", MONEY, "calls", 20),
        DetailRef("$INVOICE#void validate()", INVOICE, "$DOC#void render()", DOC, "calls", 30),
        DetailRef("$MONEY#int cents", MONEY, "int", "int", "has_type"),
        DetailRef("$MONEY#int cents", MONEY, "java.lang.Deprecated", "java.lang.Deprecated", "annotated_by"),
    )

    companion object {
        const val ARTIFACT = "billing-core"
        const val DOC = "com.example.doc.AbstractDoc"
        const val RENDERABLE = "com.example.doc.Renderable"
        const val INVOICE = "com.example.billing.Invoice"
        const val MONEY = "com.example.money.Money"
    }
}
