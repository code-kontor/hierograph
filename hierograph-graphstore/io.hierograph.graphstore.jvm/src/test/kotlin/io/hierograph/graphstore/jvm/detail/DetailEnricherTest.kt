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

import io.hierograph.graphstore.jvm.model.DependencyKind
import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.Dependency
import io.hierograph.graphstore.jvm.parquet.ParquetWriter
import io.hierograph.graphstore.jvm.parquet.ParquetWriter.ArtifactClass
import io.hierograph.graphstore.jvm.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
        store.applyClass("app", ClassFacts("com.shop.OrderService", JavaKinds.CLASS, 0, emptyList(), emptyList(), "h1"))
        store.applyClass("app", ClassFacts("com.shop.PaymentGateway", JavaKinds.INTERFACE, 0, emptyList(), emptyList(), "h2"))

        ParquetWriter.writeSnapshot(
            membersDir,
            listOf(
                ArtifactClass(
                    "app",
                    ClassFacts(
                        "com.shop.OrderService", JavaKinds.CLASS, 0,
                        members = listOf(
                            MemberFacts(place, JavaKinds.METHOD, "place(com.shop.Order):void", name = "place"),
                            MemberFacts(log, JavaKinds.FIELD, "com.shop.Order log", name = "log"),
                            MemberFacts(ctor, JavaKinds.CONSTRUCTOR, "<init>()", name = "<init>"),
                        ),
                        refs = emptyList(), classHash = "h1",
                    ),
                ),
                ArtifactClass(
                    "app",
                    ClassFacts(
                        "com.shop.PaymentGateway", JavaKinds.INTERFACE, 0,
                        members = listOf(MemberFacts(charge, JavaKinds.METHOD, "charge(com.shop.Money):com.shop.Receipt", name = "charge")),
                        refs = emptyList(), classHash = "h2",
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
}
