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

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.Origin
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.parquet.ParquetWriter
import io.hierograph.graphstore.jvm.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DetailQueryEngineTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var store: GraphStore
    private lateinit var engine: DetailQueryEngine

    private fun type(fqn: String, refs: List<Ref> = emptyList()) =
        ClassFacts(fqn, JavaKinds.CLASS, 0, emptyList(), refs, classHash = "h:$fqn")

    @BeforeEach
    fun setUp() {
        // Type graph: Sub extends Base; Other unrelated.
        store = GraphStore()
        store.registerArtifact(ArtifactInfo("app", "app", "app", Origin.MODULE_MAIN))
        store.applyClass("app", type("com.foo.Base"))
        store.applyClass("app", type("com.foo.Sub", listOf(Ref("com.foo.Sub", "com.foo.Base", RefKind.EXTENDS, count = 1))))
        store.applyClass("app", type("com.foo.Other"))
        store.applyClass("app", type("com.foo.Api"))

        // Member edges.
        ParquetWriter.writeDetailRefs(
            dir,
            listOf(
                DetailRef("com.foo.Base#m()V", "com.foo.Base", "com.foo.Other#x()V", "com.foo.Other", "calls", 10),
                DetailRef("com.foo.Sub#n()V", "com.foo.Sub", "com.foo.Other#f", "com.foo.Other", "reads_field", 20),
                DetailRef("com.foo.Other#x()V", "com.foo.Other", "com.foo.Base#m()V", "com.foo.Base", "calls", 30),
                // a write into a field of Base, by a method of Other (outside the {Sub,Base} forward scope)
                DetailRef("com.foo.Other#x()V", "com.foo.Other", "com.foo.Base#f", "com.foo.Base", "writes_field", 40),
                // Api#p(Other a, @Deprecated Other b): one type at two positions
                DetailRef("com.foo.Api#p(Other,Other)V", "com.foo.Api", "com.foo.Other", "com.foo.Other", "parameter_type", 50, parameterIndex = 1, parameterName = "b"),
                DetailRef("com.foo.Api#p(Other,Other)V", "com.foo.Api", "com.foo.Other", "com.foo.Other", "parameter_type", 50, parameterIndex = 0, parameterName = "a"),
                DetailRef("com.foo.Api#p(Other,Other)V", "com.foo.Api", "java.lang.Deprecated", "java.lang.Deprecated", "parameter_annotated_by", null, parameterIndex = 1, parameterName = "b"),
            ),
        )
        engine = DetailQueryEngine(dir)
    }

    @Test
    fun `scope expansion includes the subtree and its supertypes`() {
        assertThat(DetailScope.expand(store, "com.foo.Sub")).containsExactlyInAnyOrder("com.foo.Sub", "com.foo.Base")
        assertThat(DetailScope.expand(store, "pkg:app:com.foo"))
            .containsExactlyInAnyOrder("com.foo.Base", "com.foo.Sub", "com.foo.Other", "com.foo.Api")
    }

    @Test
    fun `querying Sub includes inherited Base members and excludes unrelated sources`() {
        val from = DetailScope.expand(store, "com.foo.Sub") // {Sub, Base}
        val result = engine.query(DetailQuery(fromTypes = from))

        assertThat(result.total).isEqualTo(2)
        assertThat(result.edges.map { it.fromKey })
            .containsExactlyInAnyOrder("com.foo.Base#m()V", "com.foo.Sub#n()V") // Base#m is inherited into Sub's scope
        assertThat(result.edges.map { it.fromKey }).doesNotContain("com.foo.Other#x()V")
        assertThat(result.byRelationship).containsExactlyInAnyOrderEntriesOf(mapOf("calls" to 1, "reads_field" to 1))
        assertThat(result.bySourceType).containsExactlyInAnyOrder("com.foo.Base" to 1, "com.foo.Sub" to 1)
    }

    @Test
    fun `relationship filter narrows to one kind`() {
        val from = DetailScope.expand(store, "com.foo.Sub")
        val result = engine.query(DetailQuery(fromTypes = from, relationship = "calls"))

        assertThat(result.total).isEqualTo(1)
        assertThat(result.edges.single().fromKey).isEqualTo("com.foo.Base#m()V")
    }

    @Test
    fun `parameterIndex keeps the rows of the parameter at that position`() {
        val from = DetailScope.expand(store, "com.foo.Api")

        val first = engine.query(DetailQuery(fromTypes = from, relationship = "parameter_type", parameterIndex = 0))
        assertThat(first.edges.map { it.parameterIndex to it.parameterName }).containsExactly(0 to "a")

        // Without a relationship, a position selects the parameter rows of both parameter relationships.
        val second = engine.query(DetailQuery(fromTypes = from, parameterIndex = 1))
        assertThat(second.edges.map { it.relationship to it.parameterName })
            .containsExactly("parameter_type" to "b", "parameter_annotated_by" to "b") // by target type: com.foo.Other < java.lang.Deprecated
        assertThat(engine.query(DetailQuery(fromTypes = from, parameterIndex = 2)).total).isEqualTo(0)
    }

    @Test
    fun `parameter rows of one type come out in parameter order`() {
        val result = engine.query(DetailQuery(fromTypes = DetailScope.expand(store, "com.foo.Api"), relationship = "parameter_type"))
        assertThat(result.edges.map { it.parameterIndex }).containsExactly(0, 1)
    }

    @Test
    fun `toTypes constrains the target side`() {
        val from = DetailScope.expand(store, "com.foo.Sub")
        assertThat(engine.query(DetailQuery(fromTypes = from, toTypes = setOf("com.foo.Other"))).total).isEqualTo(2)
        assertThat(engine.query(DetailQuery(fromTypes = from, toTypes = setOf("com.foo.Base"))).total).isEqualTo(0)
    }

    @Test
    fun `read_by returns the readers of fields in the scope, endpoints swapped`() {
        // "what reads fields declared in Other" — scope anchors on the field side (to_type = Other).
        val result = engine.query(DetailQuery(fromTypes = DetailScope.expand(store, "com.foo.Other"), relationship = "read_by"))

        assertThat(result.total).isEqualTo(1)
        val edge = result.edges.single()
        assertThat(edge.relationship).isEqualTo("read_by")
        assertThat(edge.fromKey).isEqualTo("com.foo.Other#f")   // the field is the source
        assertThat(edge.toKey).isEqualTo("com.foo.Sub#n()V")    // the reading method is the target
        assertThat(edge.line).isEqualTo(20)
    }

    @Test
    fun `written_by returns the writers of fields in the scope, endpoints swapped`() {
        val result = engine.query(DetailQuery(fromTypes = DetailScope.expand(store, "com.foo.Base"), relationship = "written_by"))

        assertThat(result.total).isEqualTo(1)
        val edge = result.edges.single()
        assertThat(edge.relationship).isEqualTo("written_by")
        assertThat(edge.fromKey).isEqualTo("com.foo.Base#f")      // the field is the source
        assertThat(edge.toKey).isEqualTo("com.foo.Other#x()V")    // the writing method is the target
    }

    @Test
    fun `paging returns one page at a time with a next offset`() {
        val from = DetailScope.expand(store, "com.foo.Sub")
        val first = engine.query(DetailQuery(fromTypes = from, limit = 1, offset = 0))
        assertThat(first.edges).hasSize(1)
        assertThat(first.total).isEqualTo(2)
        assertThat(first.nextOffset).isEqualTo(1)

        val second = engine.query(DetailQuery(fromTypes = from, limit = 1, offset = 1))
        assertThat(second.edges).hasSize(1)
        assertThat(second.nextOffset).isNull()
        assertThat((first.edges + second.edges).map { it.fromKey })
            .containsExactlyInAnyOrder("com.foo.Base#m()V", "com.foo.Sub#n()V")
    }
}
