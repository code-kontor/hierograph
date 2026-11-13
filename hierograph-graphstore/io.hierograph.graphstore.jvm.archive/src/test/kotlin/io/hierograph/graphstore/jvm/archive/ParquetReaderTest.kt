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

import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.MemberFixture
import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.TypeFixture
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
    fun `an artifact of format 1 or without a version is rejected, one of a newer format too`(@TempDir dir: Path) {
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

        val newer = Files.createDirectories(dir.resolve("newer"))
        ParquetTestWriter.writeTypesInFormat(newer, "${ParquetSchema.FORMAT_VERSION + 1}", types)
        assertThatThrownBy { check(newer) }
            .isInstanceOf(SnapshotFormatException::class.java).hasMessageContaining("Update Hierograph")

        val current = Files.createDirectories(dir.resolve("current"))
        ParquetTestWriter.writeTypes(current, types)
        check(current)   // the current format passes
    }
}
