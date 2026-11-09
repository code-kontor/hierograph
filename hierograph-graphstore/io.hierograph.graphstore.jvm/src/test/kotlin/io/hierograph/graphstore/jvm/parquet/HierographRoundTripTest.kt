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
import io.hierograph.graphstore.jvm.model.ArtifactClasspath
import io.hierograph.graphstore.jvm.model.ClasspathEntry
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.ParameterFacts
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.parquet.ParquetWriter.ArtifactClass
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-artifact `.hierograph` round-trip: [ParquetWriter.writeArtifacts] emits one archive per artifact,
 * and [ParquetReader] reads a directory of them **across the set** — load, scoped detail reads, and
 * single-member reads all span the archives. Partitioning puts each detail ref in the archive of the
 * artifact owning its `from` side.
 */
class HierographRoundTripTest {

    private val classes = listOf(
        ArtifactClass(
            "a.jar",
            ClassFacts(
                fqn = "com.a.A", kind = JavaKinds.CLASS, modifiers = 0,
                members = listOf(
                    MemberFacts(
                        key = "com.a.A#foo(java.lang.String)", kind = JavaNodeKind.METHOD,
                        signature = "foo(java.lang.String)", name = "foo",
                        parameters = listOf(ParameterFacts(0, "x", "java.lang.String")),
                    ),
                ),
                refs = emptyList(), classHash = "h1", sourceFile = "A.java",
            ),
        ),
        ArtifactClass(
            "b.jar",
            ClassFacts(
                fqn = "com.b.B", kind = JavaKinds.CLASS, modifiers = 0, members = emptyList(),
                refs = listOf(Ref("com.b.B", "com.a.A", RefKind.EXTENDS, count = 1)),
                classHash = "h2", sourceFile = null,
            ),
        ),
    )

    private val detailRefs = listOf(
        DetailRef("com.a.A#foo(java.lang.String)", "com.a.A", "java.lang.String", "java.lang.String", "returns", 1),
        DetailRef("com.b.B#bar()", "com.b.B", "com.a.A#foo(java.lang.String)", "com.a.A", "calls", 2),
    )

    @Test
    fun `writeArtifacts emits one archive per artifact, read back across the set`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)

        // one .hierograph per artifact, named with the .jar suffix stripped
        val archives = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertThat(archives).containsExactly("a.hierograph", "b.hierograph")

        // load across both archives into one store
        val store = GraphStore()
        ParquetReader().load(dir, store)
        assertThat(store.typeByFqn("com.a.A")).isNotNull()
        assertThat(store.typeByFqn("com.b.B")).isNotNull()

        // detail reads span the set; each ref landed in its from-side artifact's archive
        val reader = ParquetReader()
        assertThat(reader.readDetailRefs(dir)).hasSize(2)
        assertThat(reader.readDetailRefs(dir, fromTypes = listOf("com.b.B")).map { it.relationship })
            .containsExactly("calls")

        // a member + its parameters resolve from whichever archive holds them
        val member = reader.readMember(dir, "com.a.A#foo(java.lang.String)")
        assertThat(member).isNotNull()
        assertThat(member!!.parameters.map { it.name }).containsExactly("x")
    }

    @Test
    fun `exploded artifacts read like archives, also mixed with them`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        val archived = ParquetReader().readDetailRefs(dir).toSet()

        // explode b: its archive becomes a directory of the same name holding the same tables (mixed snapshot)
        explode(dir.resolve("b.hierograph"))
        assertThat(dir.resolve("b.hierograph")).isDirectory()
        assertReadsBoth(dir, archived)

        // explode a too: a snapshot of exploded artifacts only
        explode(dir.resolve("a.hierograph"))
        assertThat(dir.resolve("a.hierograph")).isDirectory()
        assertReadsBoth(dir, archived)
    }

    private fun explode(archive: Path) {
        val tmp = archive.resolveSibling(archive.fileName.toString() + ".tmp")
        Files.move(archive, tmp)
        HierographArchive.unzip(tmp, archive)
        Files.delete(tmp)
    }

    private fun assertReadsBoth(dir: Path, detailRefs: Set<DetailRef>) {
        val store = GraphStore()
        ParquetReader().load(dir, store)
        assertThat(store.typeByFqn("com.a.A")).isNotNull()
        assertThat(store.typeByFqn("com.b.B")).isNotNull()
        val reader = ParquetReader()
        assertThat(reader.readDetailRefs(dir).toSet()).isEqualTo(detailRefs)
        assertThat(reader.readMember(dir, "com.a.A#foo(java.lang.String)")!!.parameters.map { it.name })
            .containsExactly("x")
    }

    @Test
    fun `classpath manifests round-trip and provide artifact display names on load`(@TempDir dir: Path) {
        val classpaths = mapOf(
            "a.jar" to ArtifactClasspath("a.jar", "A", emptyList()),
            "b.jar" to ArtifactClasspath("b.jar", "B", listOf(ClasspathEntry("a.jar", internal = true))),
        )
        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED, classpaths)

        val reader = ParquetReader()
        val manifests = reader.readClasspath(dir)
        assertThat(manifests.keys).containsExactlyInAnyOrder("a.jar", "b.jar")
        assertThat(manifests.getValue("a.jar").name).isEqualTo("A")
        assertThat(manifests.getValue("a.jar").order).containsExactly("a.jar")          // index 0 = self only
        assertThat(manifests.getValue("b.jar").order).containsExactly("b.jar", "a.jar")  // self, then its dep

        // load picks up the manifest's display name for the module node (vs the raw artifact key).
        val store = GraphStore()
        reader.load(dir, store)
        assertThat(store.node("b.jar")?.name).isEqualTo("B")
    }

    @Test
    fun `a combined table directory still loads (legacy jQA export layout)`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)

        val store = GraphStore()
        ParquetReader().load(dir, store)
        assertThat(store.typeByFqn("com.a.A")).isNotNull()
        assertThat(store.typeByFqn("com.b.B")).isNotNull()
        assertThat(ParquetReader().readDetailRefs(dir)).hasSize(2)
    }
}
