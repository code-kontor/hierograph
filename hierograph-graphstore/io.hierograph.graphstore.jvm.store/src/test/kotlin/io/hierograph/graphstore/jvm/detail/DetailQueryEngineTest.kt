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
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.ParquetWriter
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
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

    private fun type(fqn: String) = ClassFacts(fqn, JavaNodeKind.CLASS, 0, emptyList(), classHash = "h:$fqn")

    @BeforeEach
    fun setUp() {
        // Type graph: Sub extends Base; Other unrelated.
        store = GraphStore()
        store.registerArtifact(ArtifactInfo("app", "app", "app"))
        store.applyClass("app", type("com.foo.Base"), emptyList())
        store.applyClass("app", type("com.foo.Sub"), listOf(Ref("com.foo.Base", DependencyKind.EXTENDS, count = 1)))
        store.applyClass("app", type("com.foo.Other"), emptyList())

        // Member edges.
        ParquetWriter.writeDependencies(
            dir,
            listOf(
                Dependency("com.foo.Base#m()V", "com.foo.Base", "com.foo.Other#x()V", "com.foo.Other", DependencyKind.CALLS, 10),
                Dependency("com.foo.Sub#n()V", "com.foo.Sub", "com.foo.Other#f", "com.foo.Other", DependencyKind.READS_FIELD, 20),
                Dependency("com.foo.Other#x()V", "com.foo.Other", "com.foo.Base#m()V", "com.foo.Base", DependencyKind.CALLS, 30),
                // a write into a field of Base, by a method of Other (outside the {Sub,Base} forward scope)
                Dependency("com.foo.Other#x()V", "com.foo.Other", "com.foo.Base#f", "com.foo.Base", DependencyKind.WRITES_FIELD, 40),
            ),
        )
        engine = DetailQueryEngine(dir)
    }

    @Test
    fun `scope expansion includes the subtree and its supertypes`() {
        assertThat(DetailScope.expand(store, "com.foo.Sub")).containsExactlyInAnyOrder("com.foo.Sub", "com.foo.Base")
        assertThat(DetailScope.expand(store, "pkg:app:com.foo"))
            .containsExactlyInAnyOrder("com.foo.Base", "com.foo.Sub", "com.foo.Other")
    }

    @Test
    fun `querying Sub includes inherited Base members and excludes unrelated sources`() {
        val from = DetailScope.expand(store, "com.foo.Sub") // {Sub, Base}
        val edges = engine.query(DetailQuery(fromTypes = from))

        assertThat(edges.map { it.fromKey })
            .containsExactly("com.foo.Base#m()V", "com.foo.Sub#n()V") // Base#m is inherited into Sub's scope; sorted by from_type
        assertThat(edges.map { it.kind.value }).containsExactly("calls", "reads_field")
    }

    @Test
    fun `relationship filter narrows to one kind`() {
        val from = DetailScope.expand(store, "com.foo.Sub")
        val edges = engine.query(DetailQuery(fromTypes = from, kind = DependencyKind.CALLS))

        assertThat(edges.single().fromKey).isEqualTo("com.foo.Base#m()V")
    }

    @Test
    fun `toTypes constrains the target side`() {
        val from = DetailScope.expand(store, "com.foo.Sub")
        assertThat(engine.query(DetailQuery(fromTypes = from, toTypes = setOf("com.foo.Other")))).hasSize(2)
        assertThat(engine.query(DetailQuery(fromTypes = from, toTypes = setOf("com.foo.Base")))).isEmpty()
    }

    @Test
    fun `read_by returns the readers of fields in the scope, endpoints swapped`() {
        // "what reads fields declared in Other" — scope anchors on the field side (to_type = Other).
        val edge = engine.query(DetailQuery(fromTypes = DetailScope.expand(store, "com.foo.Other"), kind = DependencyKind.READ_BY)).single()

        assertThat(edge.kind).isEqualTo(DependencyKind.READ_BY)
        assertThat(edge.fromKey).isEqualTo("com.foo.Other#f")   // the field is the source
        assertThat(edge.toKey).isEqualTo("com.foo.Sub#n()V")    // the reading method is the target
        assertThat(edge.line).isEqualTo(20)
    }

    @Test
    fun `written_by returns the writers of fields in the scope, endpoints swapped`() {
        val edge = engine.query(DetailQuery(fromTypes = DetailScope.expand(store, "com.foo.Base"), kind = DependencyKind.WRITTEN_BY)).single()

        assertThat(edge.kind).isEqualTo(DependencyKind.WRITTEN_BY)
        assertThat(edge.fromKey).isEqualTo("com.foo.Base#f")      // the field is the source
        assertThat(edge.toKey).isEqualTo("com.foo.Other#x()V")    // the writing method is the target
    }

    @Test
    fun `rows are sorted by from type, from key, to type, to key, kind and line`(@TempDir other: Path) {
        val a = "com.foo.A"
        fun row(fromKey: String, toType: String, toKey: String, kind: DependencyKind, line: Int?) =
            Dependency(fromKey, a, toKey, toType, kind, line)
        val expected = listOf(
            row("$a#m()V", "com.foo.B", "com.foo.B#b()V", DependencyKind.CALLS, 3),
            row("$a#m()V", "com.foo.B", "com.foo.B#b()V", DependencyKind.CALLS, 9),    // same up to the line
            row("$a#m()V", "com.foo.B", "com.foo.B#c()V", DependencyKind.CALLS, 1),    // to_key
            row("$a#m()V", "com.foo.C", "com.foo.C", DependencyKind.CASTS, 2),         // to_type
            row("$a#m()V", "com.foo.C", "com.foo.C", DependencyKind.INSTANTIATES, 2),  // kind
            row("$a#n()V", "com.foo.B", "com.foo.B#b()V", DependencyKind.CALLS, 5),    // from_key
        )
        ParquetWriter.writeDependencies(other, expected.reversed())

        assertThat(DetailQueryEngine(other).query(DetailQuery(fromTypes = setOf(a)))).containsExactlyElementsOf(expected)
    }
}
