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
package io.hierograph.graphstore.jvm.detail

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.ParquetWriter
import io.hierograph.graphstore.jvm.archive.ParquetWriter.ArtifactClass
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.MemberFixture
import io.hierograph.graphstore.jvm.archive.SnapshotFormatException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DetailEnricherTest {

    private val place = "com.shop.OrderService#place(com.shop.Order):void"
    private val log = "com.shop.OrderService#log"
    private val ctor = "com.shop.OrderService#<init>()"
    private val charge = "com.shop.PaymentGateway#charge(com.shop.Money):com.shop.Receipt"

    @TempDir
    lateinit var membersDir: Path

    private lateinit var enricher: DetailEnricher

    @BeforeEach
    fun setUp() {
        val store = GraphStore()
        store.registerArtifact(ArtifactInfo("app", "app", "app"))
        store.applyClass("app", ClassFacts("com.shop.OrderService", JavaNodeKind.CLASS, 0, emptyList(), "h1"), emptyList())
        store.applyClass("app", ClassFacts("com.shop.PaymentGateway", JavaNodeKind.INTERFACE, 0, emptyList(), "h2"), emptyList())

        ParquetWriter.writeSnapshot(
            membersDir,
            listOf(
                ArtifactClass(
                    "app",
                    ClassFacts(
                        "com.shop.OrderService", JavaNodeKind.CLASS, 0,
                        members = listOf(
                            MemberFacts(place, JavaNodeKind.METHOD, "place(com.shop.Order):void", name = "place"),
                            MemberFacts(log, JavaNodeKind.FIELD, "com.shop.Order log", name = "log"),
                            MemberFacts(ctor, JavaNodeKind.CONSTRUCTOR, "<init>()", name = "<init>"),
                        ),
                        classHash = "h1",
                    ),
                ),
                ArtifactClass(
                    "app",
                    ClassFacts(
                        "com.shop.PaymentGateway", JavaNodeKind.INTERFACE, 0,
                        members = listOf(MemberFacts(charge, JavaNodeKind.METHOD, "charge(com.shop.Money):com.shop.Receipt", name = "charge")),
                        classHash = "h2",
                    ),
                ),
            ),
            emptyList(),
            CompressionCodec.UNCOMPRESSED,
        )
        enricher = DetailEnricher(store, membersDir)
    }

    @Test
    fun `nodes map carries member and type display, with tree names and kinds`() {
        val edges = listOf(
            Dependency(place, "com.shop.OrderService", charge, "com.shop.PaymentGateway", DependencyKind.CALLS, 50),
            Dependency(place, "com.shop.OrderService", log, "com.shop.OrderService", DependencyKind.READS_FIELD, 48),
            Dependency(ctor, "com.shop.OrderService", "java.math.BigDecimal", "java.math.BigDecimal", DependencyKind.HAS_TYPE, null),
        )

        val nodes = enricher.nodesFor(edges)

        // members: named by signature and kinded as in the containment tree (a constructor is a java.method)
        assertThat(nodes[place]).isEqualTo(NodeDisplay(place.substringAfter('#'), "java.method", place))
        assertThat(nodes[log]).isEqualTo(NodeDisplay(log.substringAfter('#'), "java.field", log))
        assertThat(nodes[ctor]).isEqualTo(NodeDisplay(ctor.substringAfter('#'), "java.method", ctor))
        assertThat(nodes[charge]).isEqualTo(NodeDisplay(charge.substringAfter('#'), "java.method", charge))

        // types: from the in-memory type graph
        assertThat(nodes["com.shop.OrderService"]).isEqualTo(NodeDisplay("OrderService", "java.class", "com.shop.OrderService"))
        assertThat(nodes["com.shop.PaymentGateway"]).isEqualTo(NodeDisplay("PaymentGateway", "java.interface", "com.shop.PaymentGateway"))

        // unresolved type reference: simple name + external kind
        assertThat(nodes["java.math.BigDecimal"]).isEqualTo(NodeDisplay("BigDecimal", "external.type", "java.math.BigDecimal"))
    }

    @Test
    fun `a member no artifact declares is shown with an unknown kind`() {
        val external = "java.util.List#int size()"
        val nodes = enricher.nodesFor(listOf(Dependency(place, "com.shop.OrderService", external, "java.util.List", DependencyKind.CALLS, 51)))

        assertThat(nodes[external]).isEqualTo(NodeDisplay("int size()", "unknown", external))
    }

    @Test
    fun `a member kind outside the vocabulary is rejected`() {
        val foreign = Files.createDirectories(membersDir.resolve("foreign"))
        ParquetTestWriter.writeMembers(foreign, listOf(MemberFixture("com.shop.X", "com.shop.X#x", "PROPERTY", "x", "x")))
        val edges = listOf(Dependency("com.shop.X#x", "com.shop.X", "com.shop.Y", "com.shop.Y", DependencyKind.HAS_TYPE, null))

        assertThatThrownBy { DetailEnricher(GraphStore(), foreign).nodesFor(edges) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("Unknown member kind 'PROPERTY'")
    }
}
