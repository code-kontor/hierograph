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

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.childKeysOf
import io.hierograph.graphstore.jvm.parquet.ParquetTestWriter.MemberFixture
import io.hierograph.graphstore.jvm.parquet.ParquetTestWriter.DependencyFixture
import io.hierograph.graphstore.jvm.parquet.ParquetTestWriter.TypeFixture
import io.hierograph.graphstore.jvm.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ParquetReaderTest {

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
        ParquetReader().load(dir, store)

        // scanned types
        val typeA = store.typeByFqn("com.foo.A")!!
        assertThat(typeA.kind).isEqualTo(JavaKinds.CLASS)
        assertThat(typeA.placeholder).isFalse
        assertThat(store.typeByFqn("com.foo.B")!!.kind).isEqualTo(JavaKinds.INTERFACE)

        // members are materialized as artifact-scoped children of their type
        assertThat(store.childKeysOf(store.typeKey("app", "com.foo.A")))
            .containsExactlyInAnyOrder(store.memberKey("app", "com.foo.A#a()V"), store.memberKey("app", "com.foo.A#x"))
        assertThat(store.memberByKey("com.foo.A#x")!!.kind).isEqualTo(JavaKinds.FIELD)

        // external ref target became a placeholder under the external module
        val list = store.typeByFqn("java.util.List")!!
        assertThat(list.placeholder).isTrue
        assertThat(list.kind).isEqualTo(JavaKinds.EXTERNAL_TYPE)

        // hierarchy projection
        val rows = store.nodes().map { tuple(it.kind, it.fqn, it.parent?.let(store::node)?.fqn) }
        assertThat(rows).contains(
            tuple(JavaKinds.MODULE, "app", null),
            tuple(JavaKinds.CLASS, "com.foo.A", "com.foo"),
            tuple(JavaKinds.INTERFACE, "com.foo.B", "com.foo"),
            tuple(JavaKinds.EXTERNAL_TYPE, "java.util.List", "java.util"),
        )

        // dependencies: rolled up from the dependency rows, four calls weigh 4
        val deps = store.typeDependencies().associateBy { it.toFqn }
        assertThat(deps.getValue("com.foo.B").implements).isTrue
        val toList = deps.getValue("java.util.List")
        assertThat(toList.other).isTrue
        assertThat(toList.weight).isEqualTo(4)
    }

    @Test
    fun `scoped type and member reads return only the requested FQNs`(@TempDir dir: Path) {
        ParquetTestWriter.writeTypes(
            dir,
            listOf(
                TypeFixture("app", "com.foo.A", "CLASS", sourceFile = "A.java", classHash = "h1"),
                TypeFixture("lib", "com.foo.A", "CLASS", classHash = "h1-lib"),   // same FQN, other artifact
                TypeFixture("app", "com.foo.B", "INTERFACE", classHash = "h2"),
            ),
        )
        ParquetTestWriter.writeMembers(
            dir,
            listOf(
                MemberFixture("com.foo.A", "com.foo.A#a()V", "METHOD", "a", "a()V"),
                MemberFixture("com.foo.A", "com.foo.A#x", "FIELD", "x", "x"),
                MemberFixture("com.foo.B", "com.foo.B#b()V", "METHOD", "b", "b()V"),
            ),
        )
        val reader = ParquetReader()

        val rows = reader.readTypeRows(dir, listOf("com.foo.A"))
        assertThat(rows.map { it.artifact to it.classHash }).containsExactlyInAnyOrder("app" to "h1", "lib" to "h1-lib")
        assertThat(rows.first { it.artifact == "app" }.sourceFile).isEqualTo("A.java")

        val members = reader.readMembersByOwner(dir, listOf("com.foo.A"))
        assertThat(members.keys).containsExactly("com.foo.A")
        assertThat(members.getValue("com.foo.A").map { it.key }).containsExactlyInAnyOrder("com.foo.A#a()V", "com.foo.A#x")

        assertThat(reader.readTypeRows(dir, emptyList())).isEmpty()
        assertThat(reader.readMembersByOwner(dir, emptyList())).isEmpty()
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

        val facts = ParquetReader().readTypeFacts(dir, "app", "com.foo.A")!!

        assertThat(facts.refs.map { it.kind.name to it.toKey })
            .containsExactlyInAnyOrder("EXTENDS" to "java.lang.Object", "IMPLEMENTS" to "com.foo.B") // not C's ref to A
        assertThat(ParquetReader().readTypeFacts(dir, "app", "com.foo.Missing")).isNull()
    }

    @Test
    fun `missing members and dependencies tables load the types only`(@TempDir dir: Path) {
        ParquetTestWriter.writeTypes(dir, listOf(TypeFixture("app", "com.foo.A", "CLASS")))

        val store = GraphStore()
        ParquetReader().load(dir, store)

        assertThat(store.typeByFqn("com.foo.A")!!.placeholder).isFalse
        assertThat(store.childKeysOf(store.typeKey("app", "com.foo.A"))).isEmpty()
        assertThat(store.typeDependencies().toList()).isEmpty()
    }

    @Test
    fun `an artifact of format 1 or without a version is rejected, one of a newer format too`(@TempDir dir: Path) {
        val types = listOf(TypeFixture("app", "com.foo.A", "CLASS"))

        val format1 = dir.resolve("format1").also { java.nio.file.Files.createDirectories(it) }
        ParquetTestWriter.writeTypes(format1, types)
        ParquetTestWriter.writeFormat1Refs(format1)
        assertThatThrownBy { ParquetReader().load(format1, GraphStore()) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("refs.parquet").hasMessageContaining("Rescan")

        val unversioned = dir.resolve("unversioned").also { java.nio.file.Files.createDirectories(it) }
        ParquetTestWriter.writeTypesInFormat(unversioned, null, types)
        assertThatThrownBy { ParquetReader().load(unversioned, GraphStore()) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("no format version")

        val newer = dir.resolve("newer").also { java.nio.file.Files.createDirectories(it) }
        ParquetTestWriter.writeTypesInFormat(newer, "${ParquetSchema.FORMAT_VERSION + 1}", types)
        assertThatThrownBy { ParquetReader().load(newer, GraphStore()) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("Update Hierograph")
    }
}
