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
 * Verifies that the target side of a detail query is not extended by its supertypes: a reference to a
 * supertype counts for an implementing type only as the load-derived virtual call into its method.
 *
 * ```
 * com.example.sort.ByName        implements java.util.Comparator (external)
 * com.example.price.Pricer       interface: int price()
 * com.example.price.TaxPricer    implements Pricer: int price()
 * com.example.app.Caller
 *     void sort()                calls Comparator#reversed() (interface), Comparator#naturalOrder() (static)
 *     void bill(Pricer)          parameter_type Pricer; calls Pricer#price()
 * ```
 */
@Suppress("UNCHECKED_CAST")
class DetailTargetScopeTest {

    private val graphService = HierarchicalGraphService()
    private val outgoing: OutgoingDependenciesTool

    init {
        val dir = Files.createTempDirectory("hierograph-detail-target-scope")
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

    private fun edges(fromFqn: String, toFqn: String): List<Map<String, Any?>> {
        val resp = outgoing.outgoingDependencies(id(fromFqn), id(toFqn), "detail", null, null, null)
        assertThat(resp["error"]).isNull()
        return resp["edges"] as List<Map<String, Any?>>
    }

    @Test
    fun `a call against an external interface is not a use of a type that implements it`() {
        // Before: the closure put Comparator into ByName's scope, so both Comparator calls of sort() matched.
        assertThat(edges(CALLER, BY_NAME)).isEmpty()
    }

    @Test
    fun `a call against an owned interface reaches its implementation through the derived virtual call`() {
        val rows = edges(CALLER, TAX_PRICER)
        // Neither the parameter type Pricer nor the call compiled against Pricer#price: only the dispatch target.
        assertThat(rows).singleElement().satisfies({ row ->
            assertThat(row["relationship"]).isEqualTo("calls")
            assertThat(row["to"]).isEqualTo(id("$TAX_PRICER#int price()"))
            assertThat(row).doesNotContainKey("inherited")
        })
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
        type(BY_NAME, JavaNodeKind.CLASS, method(BY_NAME, "int compare(java.lang.Object)")),
        type(PRICER, JavaNodeKind.INTERFACE, method(PRICER, "int price()", abstract = true)),
        type(TAX_PRICER, JavaNodeKind.CLASS, method(TAX_PRICER, "int price()")),
        type(CALLER, JavaNodeKind.CLASS, method(CALLER, "void sort()"), method(CALLER, "void bill($PRICER)")),
    )

    private fun dependencies() = listOf(
        Dependency(BY_NAME, BY_NAME, COMPARATOR, COMPARATOR, DependencyKind.IMPLEMENTS),
        Dependency(TAX_PRICER, TAX_PRICER, PRICER, PRICER, DependencyKind.IMPLEMENTS),
        Dependency("$CALLER#void sort()", CALLER, "$COMPARATOR#$COMPARATOR reversed()", COMPARATOR, DependencyKind.CALLS, 10),
        Dependency("$CALLER#void sort()", CALLER, "$COMPARATOR#$COMPARATOR naturalOrder()", COMPARATOR, DependencyKind.CALLS, 11),
        Dependency("$CALLER#void bill($PRICER)", CALLER, PRICER, PRICER, DependencyKind.PARAMETER_TYPE),
        Dependency("$CALLER#void bill($PRICER)", CALLER, "$PRICER#int price()", PRICER, DependencyKind.CALLS, 20),
    )

    private companion object {
        const val ARTIFACT = "app"
        const val COMPARATOR = "java.util.Comparator"
        const val BY_NAME = "com.example.sort.ByName"
        const val PRICER = "com.example.price.Pricer"
        const val TAX_PRICER = "com.example.price.TaxPricer"
        const val CALLER = "com.example.app.Caller"
    }
}
