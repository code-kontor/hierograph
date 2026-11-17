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
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.ParquetWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DetailQueryEngineTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var engine: DetailQueryEngine

    @BeforeEach
    fun setUp() {
        // Member edges of three types: Sub (whose supertype is Base), Base and Other.
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
    fun `fromTypes selects the rows of exactly those depending types`() {
        val from = setOf("com.foo.Sub", "com.foo.Base") // a caller's scope of Sub, extended by its supertype
        val edges = engine.query(DetailQuery(fromTypes = from))

        assertThat(edges.map { it.fromKey })
            .containsExactly("com.foo.Base#m()V", "com.foo.Sub#n()V") // Other's rows are left out; sorted by from_type
        assertThat(edges.map { it.kind.value }).containsExactly("calls", "reads_field")
    }

    @Test
    fun `relationship filter narrows to one kind`() {
        val from = setOf("com.foo.Sub", "com.foo.Base")
        val edges = engine.query(DetailQuery(fromTypes = from, kind = DependencyKind.CALLS))

        assertThat(edges.single().fromKey).isEqualTo("com.foo.Base#m()V")
    }

    @Test
    fun `toTypes constrains the target side`() {
        val from = setOf("com.foo.Sub", "com.foo.Base")
        assertThat(engine.query(DetailQuery(fromTypes = from, toTypes = setOf("com.foo.Other")))).hasSize(2)
        assertThat(engine.query(DetailQuery(fromTypes = from, toTypes = setOf("com.foo.Base")))).isEmpty()
    }

    @Test
    fun `read_by returns the readers of fields in the scope, endpoints swapped`() {
        // "what reads fields declared in Other" — scope anchors on the field side (to_type = Other).
        val edge = engine.query(DetailQuery(fromTypes = setOf("com.foo.Other"), kind = DependencyKind.READ_BY)).single()

        assertThat(edge.kind).isEqualTo(DependencyKind.READ_BY)
        assertThat(edge.fromKey).isEqualTo("com.foo.Other#f")   // the field is the source
        assertThat(edge.toKey).isEqualTo("com.foo.Sub#n()V")    // the reading method is the target
        assertThat(edge.line).isEqualTo(20)
    }

    @Test
    fun `written_by returns the writers of fields in the scope, endpoints swapped`() {
        val edge = engine.query(DetailQuery(fromTypes = setOf("com.foo.Base"), kind = DependencyKind.WRITTEN_BY)).single()

        assertThat(edge.kind).isEqualTo(DependencyKind.WRITTEN_BY)
        assertThat(edge.fromKey).isEqualTo("com.foo.Base#f")      // the field is the source
        assertThat(edge.toKey).isEqualTo("com.foo.Other#x()V")    // the writing method is the target
    }

    @Test
    fun `parameterIndex keeps the facts of the parameter at that position, in parameter order`(@TempDir other: Path) {
        val api = "com.foo.Api"
        val p = "$api#void p(com.foo.Other,com.foo.Other)"
        ParquetWriter.writeDependencies(
            other,
            listOf(
                Dependency(p, api, "com.foo.Other", "com.foo.Other", DependencyKind.PARAMETER_TYPE, 5, parameterIndex = 1, parameterName = "b"),
                Dependency(p, api, "com.foo.Other", "com.foo.Other", DependencyKind.PARAMETER_TYPE, 5, parameterIndex = 0, parameterName = "a"),
                Dependency(p, api, "java.lang.Deprecated", "java.lang.Deprecated", DependencyKind.PARAMETER_ANNOTATED_BY, null, parameterIndex = 1, parameterName = "b"),
                Dependency(p, api, "void", "void", DependencyKind.RETURNS, 5),
            ),
        )
        val engine = DetailQueryEngine(other)
        val from = setOf(api)

        assertThat(engine.query(DetailQuery(fromTypes = from, kind = DependencyKind.PARAMETER_TYPE)).map { it.parameterName })
            .containsExactly("a", "b") // same type twice: ordered by position
        assertThat(engine.query(DetailQuery(fromTypes = from, kind = DependencyKind.PARAMETER_TYPE, parameterIndex = 0)).map { it.parameterName })
            .containsExactly("a")
        // Without a kind, a position selects the facts of both parameter kinds (sorted by target type first).
        assertThat(engine.query(DetailQuery(fromTypes = from, parameterIndex = 1)).map { it.kind })
            .containsExactly(DependencyKind.PARAMETER_TYPE, DependencyKind.PARAMETER_ANNOTATED_BY)
        assertThat(engine.query(DetailQuery(fromTypes = from, parameterIndex = 2))).isEmpty()
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
