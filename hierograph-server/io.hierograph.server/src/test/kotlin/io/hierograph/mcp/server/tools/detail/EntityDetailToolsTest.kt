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
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.model.TypeModifiers
import io.hierograph.graphstore.jvm.parquet.ParquetWriter
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.DOC
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.INVOICE
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.MONEY
import io.hierograph.mcp.server.tools.detail.DetailFixture.Companion.RENDERABLE
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** Verifies `type_details`, `method_details` and `field_details` on a real snapshot. */
@Suppress("UNCHECKED_CAST")
class EntityDetailToolsTest {

    private val f = DetailFixture()
    private val types = ParquetTypeDetails(f.graphService, NodeRefFactory(f.graphService))
    private val methods = ParquetMethodDetails(f.graphService)
    private val fields = ParquetFieldDetails(f.graphService)

    private fun error(resp: Map<String, Any?>) = resp["error"] as Map<String, Any?>

    @Test
    fun `type_details reports whether a type declares type parameters`() {
        assertThat(types.typeDetails(f.id(DOC))["is_generic"]).isEqualTo(true)
        assertThat(types.typeDetails(f.id(INVOICE))["is_generic"]).isEqualTo(false)
    }

    @Test
    fun `a non-member id gets WRONG_NODE_KIND pointing to the right tool`() {
        val onType = error(methods.methodDetails(f.id(INVOICE)))
        assertThat(onType["code"]).isEqualTo("WRONG_NODE_KIND")
        assertThat(onType["actual_kind"]).isEqualTo("java.class")
        assertThat(onType["recovery"] as String).contains("type_details(typeId: ${f.id(INVOICE)})")

        val onPackage = error(fields.fieldDetails(f.id("com.example.money")))
        assertThat(onPackage["actual_kind"]).isEqualTo("java.package")
        assertThat(onPackage["recovery"] as String).contains("list_descendants")

        assertThat(error(methods.methodDetails(424242L))["code"]).isEqualTo("NODE_NOT_FOUND")
    }

    @Test
    fun `field annotations without a node are kept by name`() {
        val annotations = fields.fieldDetails(f.id("$MONEY#int cents"))["annotations"] as List<Map<String, Any?>>
        assertThat(annotations).containsExactly(mapOf("type" to null, "type_name" to "java.lang.Deprecated"))
    }

    @Test
    fun `method_details lists the annotations of each parameter`() {
        val params = methods.methodDetails(f.id("$MONEY#$MONEY times(int)"))["parameters"] as List<Map<String, Any?>>
        val annotations = params.single()["annotations"] as List<Map<String, Any?>>
        assertThat(annotations.map { (it["type"] as Map<String, Any?>)["qualified_name"] }).containsExactly("javax.annotation.Nonnull")
    }

    @Test
    fun `overrides lists every overridden method`() {
        val overrides = methods.methodDetails(f.id("$INVOICE#void render()"))["overrides"] as List<Map<String, Any?>>
        assertThat(overrides.map { it["qualified_name"] })
            .containsExactly("$DOC#void render()", "$RENDERABLE#void render()")
        assertThat(methods.methodDetails(f.id("$INVOICE#void validate()"))["overrides"] as List<*>).isEmpty()
    }

    @Test
    fun `type_details reads each definition of a duplicated FQN from its own artifact`() {
        val twin = "com.example.dup.Twin"
        fun twinIn(artifact: String, memberCount: Int, iface: String) = ParquetWriter.ArtifactClass(
            artifact, ClassFacts(
                twin, JavaKinds.CLASS, TypeModifiers.pack("public"),
                members = List(memberCount) { MemberFacts("$twin#void m$it()", JavaKinds.METHOD, "void m$it()", "m$it") },
                refs = listOf(Ref(iface, RefKind.IMPLEMENTS, count = 1)), classHash = artifact,
            ),
        )
        val dir = Files.createTempDirectory("hierograph-twin")
        // One write per artifact: the facts of a duplicated FQN are filed with the artifact written alongside them.
        for ((artifact, count, iface) in listOf(Triple("lib-a", 1, "java.io.Serializable"), Triple("lib-b", 3, "java.lang.Runnable"))) {
            ParquetWriter.writeArtifacts(
                dir, listOf(twinIn(artifact, count, iface)),
                listOf(DetailRef(twin, twin, iface, iface, "implements")), packaging = ParquetWriter.Packaging.EXPLODED,
            )
        }
        val service = HierarchicalGraphService().also { it.seedFromParquet(dir) }
        val details = ParquetTypeDetails(service, NodeRefFactory(service))

        for ((artifact, count, iface) in listOf(Triple("lib-a", 1, "java.io.Serializable"), Triple("lib-b", 3, "java.lang.Runnable"))) {
            val id = NodeHandle.of(service.graphStore!!.typeKey(artifact, twin))
            val resp = details.typeDetails(id)
            assertThat((resp["member_summary"] as Map<String, Any?>)["method_count"]).isEqualTo(count)
            assertThat((resp["interfaces"] as List<Map<String, Any?>>).map { it["qualified_name"] }).containsExactly(iface)
        }
    }

    @Test
    fun `members are named by signature and a constructor is a java_method`() {
        val ctor = methods.methodDetails(f.id("$INVOICE#void <init>()"))
        val ref = ctor["method"] as Map<String, Any?>
        assertThat(ref["name"]).isEqualTo("void <init>()")
        assertThat(ref["kind"]).isEqualTo("java.method")
        assertThat(ctor["is_constructor"]).isEqualTo(true)
    }

    @Test
    fun `field_details reads type and constancy from the declaration, and has no location`() {
        val scale = fields.fieldDetails(f.id("$MONEY#int SCALE"))
        assertThat(scale["type_name"]).isEqualTo("int")
        assertThat(scale["is_constant"]).isEqualTo(true)
        assertThat(scale).doesNotContainKey("location")
        assertThat(fields.fieldDetails(f.id("$MONEY#int cents"))["is_constant"]).isEqualTo(false)
    }

    @Test
    fun `an access through a subclass counts for the inherited field`() {
        val pages = fields.fieldDetails(f.id("$DOC#int pages"))
        assertThat((pages["read_access"] as Map<String, Any?>)["method_count"]).isEqualTo(1)
    }

    @Test
    fun `type_details counts constructors among the methods and omits declaring_type for a package`() {
        val summary = types.typeDetails(f.id(INVOICE))["member_summary"] as Map<String, Any?>
        assertThat(summary["method_count"]).isEqualTo(5)
        assertThat(summary["constructor_count"]).isEqualTo(1)
        assertThat(error(types.typeDetails(f.id("com.example.money")))).doesNotContainKey("declaring_type")
    }
}
