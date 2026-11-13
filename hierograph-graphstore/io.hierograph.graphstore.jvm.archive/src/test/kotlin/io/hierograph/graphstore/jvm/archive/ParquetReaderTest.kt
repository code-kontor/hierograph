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
package io.hierograph.graphstore.jvm.archive

import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.DependencyFixture
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.MemberFixture
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.TypeFixture
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ParquetReaderTest {

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
    fun `every member is read, an unknown member key reads as null`(@TempDir dir: Path) {
        ParquetTestWriter.writeMembers(
            dir,
            listOf(
                MemberFixture("com.foo.A", "com.foo.A#a()V", "METHOD", "a", "a()V", lineFrom = 3, lineTo = 5),
                MemberFixture("com.foo.B", "com.foo.B#x", "FIELD", "x", "x"),
            ),
        )
        val reader = ParquetReader()

        assertThat(reader.readAllMembers(dir).map { it.key to it.lineFrom })
            .containsExactlyInAnyOrder("com.foo.A#a()V" to 3, "com.foo.B#x" to null)
        assertThat(reader.readMember(dir, "com.foo.A#b()V")).isNull()
    }

    @Test
    fun `forEachDependencyFact visits every row, or only the rows of one type`(@TempDir dir: Path) {
        ParquetWriter.writeDependencies(
            dir,
            listOf(
                Dependency("com.foo.A", "com.foo.A", "com.foo.Base", "com.foo.Base", DependencyKind.EXTENDS, null),
                Dependency("com.foo.A#a()V", "com.foo.A", "com.foo.B#b()V", "com.foo.B", DependencyKind.CALLS, 7),
                Dependency("com.foo.B#b()V", "com.foo.B", "com.foo.A#x", "com.foo.A", DependencyKind.READS_FIELD, 9),
            ),
        )
        val source = SnapshotSources.of(dir).single()
        val reader = ParquetReader()
        fun visited(fromType: String?) = buildList {
            reader.forEachDependencyFact(source, fromType) { from, fromKey, to, kind -> add(listOf(from, fromKey, to, kind.value)) }
        }

        assertThat(visited(null)).containsExactlyInAnyOrder(
            listOf("com.foo.A", "com.foo.A", "com.foo.Base", "extends"),
            listOf("com.foo.A", "com.foo.A#a()V", "com.foo.B", "calls"),
            listOf("com.foo.B", "com.foo.B#b()V", "com.foo.A", "reads_field"),
        )
        assertThat(visited("com.foo.A")).containsExactlyInAnyOrder(
            listOf("com.foo.A", "com.foo.A", "com.foo.Base", "extends"),
            listOf("com.foo.A", "com.foo.A#a()V", "com.foo.B", "calls"),
        )
        assertThat(visited("com.foo.C")).isEmpty()
    }

    @Test
    fun `forEachDependencyFact visits nothing without a dependencies table and rejects an unknown kind`(@TempDir dir: Path) {
        val reader = ParquetReader()
        val empty = Files.createDirectories(dir.resolve("empty"))
        ParquetTestWriter.writeTypes(empty, listOf(TypeFixture("app", "com.foo.A", "CLASS")))
        var visits = 0
        reader.forEachDependencyFact(SnapshotSources.of(empty).single()) { _, _, _, _ -> visits++ }
        reader.forEachDependencyFact(SnapshotSources.of(empty).single(), "com.foo.A") { _, _, _, _ -> visits++ }
        assertThat(visits).isZero()

        val foreign = Files.createDirectories(dir.resolve("foreign"))
        ParquetTestWriter.writeDependencies(foreign, listOf(DependencyFixture("com.foo.A", "com.foo.B", "depends_on")))
        val source = SnapshotSources.of(foreign).single()
        assertThatThrownBy { reader.forEachDependencyFact(source) { _, _, _, _ -> } }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("Unknown dependency kind 'depends_on'")
        assertThatThrownBy { reader.forEachDependencyFact(source, "com.foo.A") { _, _, _, _ -> } }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("Unknown dependency kind 'depends_on'")
    }

    @Test
    fun `an artifact of format 1, of an older or unreadable version or without one is rejected, one of a newer format too`(@TempDir dir: Path) {
        val types = listOf(TypeFixture("app", "com.foo.A", "CLASS"))
        val reader = ParquetReader()
        fun check(snapshot: Path) = SnapshotSources.of(snapshot).forEach { reader.checkFormat(it) }

        val format1 = Files.createDirectories(dir.resolve("format1"))
        ParquetTestWriter.writeTypes(format1, types)
        ParquetTestWriter.writeFormat1Refs(format1)
        assertThatThrownBy { check(format1) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("refs.parquet").hasMessageContaining("Rescan")

        val unversioned = Files.createDirectories(dir.resolve("unversioned"))
        ParquetTestWriter.writeTypesInFormat(unversioned, null, types)
        assertThatThrownBy { check(unversioned) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("no format version")

        val older = Files.createDirectories(dir.resolve("older"))
        ParquetTestWriter.writeTypesInFormat(older, "1", types)
        assertThatThrownBy { check(older) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("written in format 1").hasMessageContaining("Rescan")

        val garbled = Files.createDirectories(dir.resolve("garbled"))
        ParquetTestWriter.writeTypesInFormat(garbled, "two", types)
        assertThatThrownBy { check(garbled) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("written in format two")

        val newer = Files.createDirectories(dir.resolve("newer"))
        ParquetTestWriter.writeTypesInFormat(newer, "${ParquetSchema.FORMAT_VERSION + 1}", types)
        assertThatThrownBy { check(newer) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("Update Hierograph")

        val current = Files.createDirectories(dir.resolve("current"))
        ParquetTestWriter.writeTypes(current, types)
        check(current)   // the current format passes
    }
}
