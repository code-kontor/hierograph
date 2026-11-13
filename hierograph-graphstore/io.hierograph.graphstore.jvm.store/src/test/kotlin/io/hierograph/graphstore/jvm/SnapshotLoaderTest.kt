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
package io.hierograph.graphstore.jvm

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.DependencyFixture
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.MemberFixture
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.TypeFixture
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.graphstore.jvm.archive.ParquetWriter
import io.hierograph.graphstore.jvm.archive.ParquetWriter.ArtifactClass
import io.hierograph.graphstore.jvm.archive.SnapshotFormatException
import io.hierograph.graphstore.jvm.archive.model.ArtifactClasspath
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.ClasspathEntry
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [SnapshotLoader]: a snapshot's facts loaded into a [GraphStore], with the type-level aggregate rolled up
 * from the dependency rows. Reading the tables themselves — archives, exploded and collect forms, the
 * format check — is tested with `ParquetReader` in the archive module.
 */
class SnapshotLoaderTest {

    @Test
    fun `loads types members and dependencies into the GraphStore via the applyClass path`(@TempDir dir: Path) {
        ParquetTestWriter.writeTypes(
            dir,
            listOf(
                TypeFixture("app", "com.foo.A", "CLASS", sourceFile = "A.java", classHash = "h1"),
                TypeFixture("app", "com.foo.B", "INTERFACE", classHash = "h2"),
            ),
        )
        ParquetTestWriter.writeMembers(
            dir,
            listOf(
                MemberFixture("com.foo.A", "com.foo.A#a()V", "METHOD", "a", "a()V", lineFrom = 1, lineTo = 3),
                MemberFixture("com.foo.A", "com.foo.A#x", "FIELD", "x", "x"),
            ),
        )
        val a = "com.foo.A#a()V"
        ParquetTestWriter.writeDependencies(
            dir,
            listOf(DependencyFixture("com.foo.A", "com.foo.B", "implements")) +
                (1..4).map { DependencyFixture("com.foo.A", "java.util.List", "calls", fromKey = a, toKey = "java.util.List#int size()", line = it) },
        )

        val store = GraphStore()
        SnapshotLoader().load(dir, store)

        // scanned types
        val typeA = store.typeByFqn("com.foo.A")!!
        assertThat(typeA.kind).isEqualTo(JavaNodeKind.CLASS)
        assertThat(typeA.placeholder).isFalse
        assertThat(store.typeByFqn("com.foo.B")!!.kind).isEqualTo(JavaNodeKind.INTERFACE)

        // members are materialized as artifact-scoped children of their type
        assertThat(store.childKeysOf(store.typeKey("app", "com.foo.A")))
            .containsExactlyInAnyOrder(store.memberKey("app", "com.foo.A#a()V"), store.memberKey("app", "com.foo.A#x"))
        assertThat(store.memberByKey("com.foo.A#x")!!.kind).isEqualTo(JavaNodeKind.FIELD)

        // external ref target became a placeholder under the external module
        val list = store.typeByFqn("java.util.List")!!
        assertThat(list.placeholder).isTrue
        assertThat(list.kind).isEqualTo(JavaNodeKind.EXTERNAL_TYPE)

        // hierarchy projection
        val rows = store.nodes().map { tuple(it.kind, it.fqn, it.parent?.let(store::node)?.fqn) }
        assertThat(rows).contains(
            tuple(JavaNodeKind.MODULE, "app", null),
            tuple(JavaNodeKind.CLASS, "com.foo.A", "com.foo"),
            tuple(JavaNodeKind.INTERFACE, "com.foo.B", "com.foo"),
            tuple(JavaNodeKind.EXTERNAL_TYPE, "java.util.List", "java.util"),
        )

        // dependencies: rolled up from the dependency rows, four calls weigh 4
        val deps = store.typeDependencies().associateBy { it.toFqn }
        assertThat(deps.getValue("com.foo.B").implements).isTrue
        val toList = deps.getValue("java.util.List")
        assertThat(toList.other).isTrue
        assertThat(toList.weight).isEqualTo(4)
    }

    @Test
    fun `type facts read only the type's own refs, external targets included`(@TempDir dir: Path) {
        ParquetTestWriter.writeTypes(dir, listOf(TypeFixture("app", "com.foo.A", "CLASS"), TypeFixture("app", "com.foo.C", "CLASS")))
        ParquetTestWriter.writeDependencies(
            dir,
            listOf(
                DependencyFixture("com.foo.A", "java.lang.Object", "extends"),
                DependencyFixture("com.foo.A", "com.foo.B", "implements"),
                DependencyFixture("com.foo.C", "com.foo.A", "extends"),
            ),
        )

        val facts = SnapshotLoader().readTypeFacts(dir, "app", "com.foo.A")!!

        assertThat(facts.refs.map { it.kind.name to it.toKey })
            .containsExactlyInAnyOrder("EXTENDS" to "java.lang.Object", "IMPLEMENTS" to "com.foo.B") // not C's ref to A
        assertThat(SnapshotLoader().readTypeFacts(dir, "app", "com.foo.Missing")).isNull()
    }

    @Test
    fun `missing members and dependencies tables load the types only`(@TempDir dir: Path) {
        ParquetTestWriter.writeTypes(dir, listOf(TypeFixture("app", "com.foo.A", "CLASS")))

        val store = GraphStore()
        SnapshotLoader().load(dir, store)

        assertThat(store.typeByFqn("com.foo.A")!!.placeholder).isFalse
        assertThat(store.childKeysOf(store.typeKey("app", "com.foo.A"))).isEmpty()
        assertThat(store.typeDependencies().toList()).isEmpty()
    }

    @Test
    fun `an artifact the reader rejects is rejected before anything is applied`(@TempDir dir: Path) {
        val current = Files.createDirectories(dir.resolve("snapshot/a.hierograph"))
        ParquetTestWriter.writeTypes(current, listOf(TypeFixture("a.jar", "com.a.A", "CLASS")))
        val format1 = Files.createDirectories(dir.resolve("snapshot/b.hierograph"))
        ParquetTestWriter.writeTypes(format1, listOf(TypeFixture("b.jar", "com.b.B", "CLASS")))
        ParquetTestWriter.writeFormat1Refs(format1)

        val store = GraphStore()
        assertThatThrownBy { SnapshotLoader().load(dir.resolve("snapshot"), store) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("refs.parquet")
        // the valid artifact a was not applied either: no module, no type
        assertThat(store.node("a.jar")).isNull()
        assertThat(store.typeByFqn("com.a.A")).isNull()
    }

    @Test
    fun `an artifact that fails while it is read fails the load with its own exception, before anything is applied`(@TempDir dir: Path) {
        // All three pass the format check; b fails only when its rows are read, on a reader thread.
        for (name in listOf("a", "b", "c")) {
            val artifact = Files.createDirectories(dir.resolve("snapshot/$name.hierograph"))
            val kind = if (name == "b") "STRUCT" else "CLASS"
            ParquetTestWriter.writeTypes(artifact, listOf(TypeFixture("$name.jar", "com.$name.T", kind)))
        }

        val store = GraphStore()
        assertThatThrownBy { SnapshotLoader().load(dir.resolve("snapshot"), store) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("Unknown type kind 'STRUCT'")
        // nothing is applied, not even the artifact read before the failing one
        assertThat(store.node("a.jar")).isNull()
        assertThat(store.typeByFqn("com.a.T")).isNull()
    }

    @Test
    fun `a snapshot without the modifiers column still loads`(@TempDir dir: Path) {
        ParquetTestWriter.writeLegacyTypes(dir, listOf(TypeFixture("app", "com.foo.A", "CLASS")))

        val store = GraphStore()
        SnapshotLoader().load(dir, store)
        assertThat(store.typeByFqn("com.foo.A")!!.placeholder).isFalse
    }

    @Test
    fun `writeSnapshot round-trips through the loader, which rolls the aggregate up again`(@TempDir dir: Path) {
        // A extends B, and its method `a` calls `List.size()` seven times.
        val facts = listOf(Dependency("com.foo.A", "com.foo.A", "com.foo.B", "com.foo.B", DependencyKind.EXTENDS)) +
            (1..7).map { Dependency("com.foo.A#a()V", "com.foo.A", "java.util.List#int size()", "java.util.List", DependencyKind.CALLS, it) }
        val classes = listOf(
            ArtifactClass("app", ClassFacts("com.foo.A", JavaNodeKind.CLASS, 0, listOf(MemberFacts("com.foo.A#a()V", JavaNodeKind.METHOD, "a()V")), "h1")),
            ArtifactClass("app", ClassFacts("com.foo.B", JavaNodeKind.CLASS, 0, emptyList(), "h2")),
        )
        ParquetWriter.writeSnapshot(dir, classes, facts) // default ZSTD

        val store = GraphStore()
        SnapshotLoader().load(dir, store)

        assertThat(store.typeByFqn("com.foo.A")!!.placeholder).isFalse
        assertThat(store.memberByKey("com.foo.A#a()V")!!.kind).isEqualTo(JavaNodeKind.METHOD)   // members round-trip as nodes
        assertThat(store.typeByFqn("java.util.List")!!.placeholder).isTrue
        val deps = store.typeDependencies().associateBy { it.toFqn }
        assertThat(deps.getValue("com.foo.B").extends).isTrue
        assertThat(deps.getValue("java.util.List").weight).isEqualTo(7)
    }

    private val artifactClasses = listOf(
        ArtifactClass("a.jar", ClassFacts("com.a.A", JavaNodeKind.CLASS, 0, emptyList(), "h1")),
        ArtifactClass("b.jar", ClassFacts("com.b.B", JavaNodeKind.CLASS, 0, emptyList(), "h2")),
    )
    private val artifactDependencies = listOf(
        Dependency("com.b.B#bar()", "com.b.B", "com.a.A#foo()", "com.a.A", DependencyKind.CALLS, 2),
    )

    @Test
    fun `a directory of per-artifact archives loads into one store, a deleted archive is dropped`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, artifactClasses, artifactDependencies, CompressionCodec.UNCOMPRESSED)

        val store = GraphStore()
        SnapshotLoader().load(dir, store)
        assertThat(store.typeByFqn("com.a.A")!!.placeholder).isFalse
        assertThat(store.typeByFqn("com.b.B")!!.placeholder).isFalse
        assertThat(store.typeDependencies().map { it.fromFqn to it.toFqn }.toList()).containsExactly("com.b.B" to "com.a.A")

        Files.delete(dir.resolve("b.hierograph"))
        val reloaded = GraphStore()
        SnapshotLoader().load(dir, reloaded)
        assertThat(reloaded.typeByFqn("com.a.A")).isNotNull()
        assertThat(reloaded.typeByFqn("com.b.B")).isNull()
    }

    @Test
    fun `classpath manifests provide the artifacts' display names on load`(@TempDir dir: Path) {
        val classpaths = mapOf(
            "a.jar" to ArtifactClasspath("a.jar", "A", emptyList()),
            "b.jar" to ArtifactClasspath("b.jar", "B", listOf(ClasspathEntry("a.jar", internal = true))),
        )
        ParquetWriter.writeArtifacts(dir, artifactClasses, artifactDependencies, CompressionCodec.UNCOMPRESSED, classpaths)

        // load picks up the manifest's display name for the module node (vs the raw artifact key).
        val store = GraphStore()
        SnapshotLoader(ParquetReader()).load(dir, store)
        assertThat(store.node("a.jar")?.name).isEqualTo("A")
        assertThat(store.node("b.jar")?.name).isEqualTo("B")
    }

    @Test
    fun `a combined table directory still loads (legacy combined layout)`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, artifactClasses, artifactDependencies, CompressionCodec.UNCOMPRESSED)

        val store = GraphStore()
        SnapshotLoader().load(dir, store)
        assertThat(store.typeByFqn("com.a.A")).isNotNull()
        assertThat(store.typeByFqn("com.b.B")).isNotNull()
    }
}
