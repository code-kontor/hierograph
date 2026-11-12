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
package io.hierograph.graphstore.jvm.parquet

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DependencyRollup
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.TypeModifiers
import io.hierograph.graphstore.jvm.parquet.ParquetWriter.ArtifactClass
import io.hierograph.graphstore.jvm.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ParquetWriterTest {

    /** A's detail facts: it extends B, and its method `a` calls `List.size()` seven times. */
    private val facts = listOf(DetailRef("com.foo.A", "com.foo.A", "com.foo.B", "com.foo.B", "extends")) +
        (1..7).map { DetailRef("com.foo.A#a()V", "com.foo.A", "java.util.List#int size()", "java.util.List", "calls", it) }

    private val classes = listOf(
        ArtifactClass(
            "app",
            ClassFacts(
                fqn = "com.foo.A",
                kind = JavaKinds.CLASS,
                modifiers = TypeModifiers.pack("public", abstract = true),
                members = listOf(MemberFacts("com.foo.A#a()V", JavaKinds.METHOD, "a()V", lineFrom = 1, lineTo = 2)),
                refs = DependencyRollup.refsOf("com.foo.A", facts),
                classHash = "h1",
                sourceFile = "A.java",
            ),
        ),
        ArtifactClass("app", ClassFacts("com.foo.B", JavaKinds.CLASS, TypeModifiers.pack(null, final = true, static = true), emptyList(), emptyList(), "h2")),
    )

    @Test
    fun `type modifiers round-trip through types parquet`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, emptyList(), CompressionCodec.UNCOMPRESSED)

        val rows = ParquetReader().readTypeRows(dir, listOf("com.foo.A", "com.foo.B")).associateBy { it.fqn }
        assertThat(TypeModifiers.modifiers(rows.getValue("com.foo.A").modifiers!!)).containsExactly("public", "abstract")
        assertThat(TypeModifiers.modifiers(rows.getValue("com.foo.B").modifiers!!)).containsExactly("package-private", "final", "static")
    }

    @Test
    fun `a snapshot without the modifiers column still loads and reports modifiers as unknown`(@TempDir dir: Path) {
        ParquetTestWriter.writeLegacyTypes(dir, listOf(ParquetTestWriter.TypeFixture("app", "com.foo.A", "CLASS")))

        val store = GraphStore()
        ParquetReader().load(dir, store)
        assertThat(store.typeByFqn("com.foo.A")!!.placeholder).isFalse
        assertThat(ParquetReader().readTypeRows(dir, listOf("com.foo.A")).single().modifiers).isNull()
    }

    @Test
    fun `writeSnapshot round-trips through the loader uncompressed`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, facts, CompressionCodec.UNCOMPRESSED)
        assertRoundTrip(dir)
    }

    @Test
    fun `writeSnapshot round-trips through the loader with zstd`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, facts) // default ZSTD
        assertRoundTrip(dir)
    }

    /** The aggregate is not stored: the loader rolls it up again from the dependency rows. */
    private fun assertRoundTrip(dir: Path) {
        val store = GraphStore()
        ParquetReader().load(dir, store)

        assertThat(store.typeByFqn("com.foo.A")!!.placeholder).isFalse
        assertThat(store.memberByKey("com.foo.A#a()V")!!.kind).isEqualTo(JavaKinds.METHOD)   // members round-trip as nodes
        assertThat(store.typeByFqn("java.util.List")!!.placeholder).isTrue

        val deps = store.typeDependencies().associateBy { it.toFqn }
        assertThat(deps.getValue("com.foo.B").extends).isTrue
        assertThat(deps.getValue("java.util.List").weight).isEqualTo(7)
    }
}
