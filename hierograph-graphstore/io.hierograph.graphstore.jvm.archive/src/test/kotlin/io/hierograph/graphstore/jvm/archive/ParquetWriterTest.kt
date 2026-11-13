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

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.jvm.archive.ParquetWriter.ArtifactClass
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.archive.model.MemberModifiers
import io.hierograph.graphstore.jvm.archive.model.ParameterFacts
import io.hierograph.graphstore.jvm.archive.model.TypeModifiers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException

class ParquetWriterTest {

    /** A's detail facts: it extends B, and its method `a` calls `List.size()` seven times. */
    private val facts = listOf(Dependency("com.foo.A", "com.foo.A", "com.foo.B", "com.foo.B", DependencyKind.EXTENDS)) +
        (1..7).map { Dependency("com.foo.A#a()V", "com.foo.A", "java.util.List#int size()", "java.util.List", DependencyKind.CALLS, it) }

    private val classes = listOf(
        ArtifactClass(
            "app",
            ClassFacts(
                fqn = "com.foo.A",
                kind = JavaNodeKind.CLASS,
                modifiers = TypeModifiers.pack("public", abstract = true),
                members = listOf(MemberFacts("com.foo.A#a()V", JavaNodeKind.METHOD, "a()V", lineFrom = 1, lineTo = 2)),
                classHash = "h1",
                sourceFile = "A.java",
            ),
        ),
        ArtifactClass("app", ClassFacts("com.foo.B", JavaNodeKind.CLASS, TypeModifiers.pack(null, final = true, static = true), emptyList(), "h2")),
    )

    @Test
    fun `type modifiers round-trip through types parquet`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, emptyList(), CompressionCodec.UNCOMPRESSED)

        val rows = ParquetReader().readTypeRows(dir, listOf("com.foo.A", "com.foo.B")).associateBy { it.fqn }
        assertThat(TypeModifiers.modifiers(rows.getValue("com.foo.A").modifiers!!)).containsExactly("public", "abstract")
        assertThat(TypeModifiers.modifiers(rows.getValue("com.foo.B").modifiers!!)).containsExactly("package-private", "final", "static")
    }

    @Test
    fun `a types table without the modifiers column reads its modifiers as unknown`(@TempDir dir: Path) {
        ParquetTestWriter.writeLegacyTypes(dir, listOf(ParquetTestWriter.TypeFixture("app", "com.foo.A", "CLASS")))

        assertThat(ParquetReader().readTypeRows(dir, listOf("com.foo.A")).single().modifiers).isNull()
    }

    @Test
    fun `writeSnapshot round-trips through the reader uncompressed`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, facts, CompressionCodec.UNCOMPRESSED)
        assertRoundTrip(dir)
    }

    @Test
    fun `writeSnapshot round-trips through the reader with zstd`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, facts) // default ZSTD
        assertRoundTrip(dir)
    }

    @Test
    fun `member modifiers, parameter annotations and an empty class hash round-trip`(@TempDir dir: Path) {
        val method = MemberFacts(
            "com.foo.C#m(java.lang.String,int)", JavaNodeKind.METHOD, "m(java.lang.String,int)", name = "m",
            modifiers = MemberModifiers.pack("public", static = true),
            parameters = listOf(
                ParameterFacts(0, "s", "java.lang.String", listOf("a.NotNull", "a.Size")),
                ParameterFacts(1, "i", "int"),
            ),
        )
        val field = MemberFacts("com.foo.C#f", JavaNodeKind.FIELD, "f", name = "f")   // package-private: no bits
        val unhashed = ClassFacts("com.foo.C", JavaNodeKind.CLASS, 0, listOf(method, field), classHash = "")
        ParquetWriter.writeSnapshot(dir, listOf(ArtifactClass("app", unhashed)), emptyList(), CompressionCodec.UNCOMPRESSED)
        val reader = ParquetReader()

        assertThat(reader.readTypeRows(dir, listOf("com.foo.C")).single().classHash).isNull()
        val members = reader.readMembersByOwner(dir, listOf("com.foo.C")).getValue("com.foo.C").associateBy { it.key }
        assertThat(MemberModifiers.methodModifiers(members.getValue(method.key).modifiers)).containsExactly("public", "static")
        assertThat(members.getValue(field.key).modifiers).isZero()
        assertThat(reader.readMember(dir, method.key)!!.parameters).isEqualTo(method.parameters)
    }

    @Test
    fun `members are sorted by owner and keep their declaration order within a type`(@TempDir dir: Path) {
        fun member(owner: String, name: String) = MemberFacts("$owner#$name()V", JavaNodeKind.METHOD, "$name()V", name = name)
        fun type(fqn: String, vararg names: String) =
            ArtifactClass("app", ClassFacts(fqn, JavaNodeKind.CLASS, 0, names.map { member(fqn, it) }, classHash = fqn))
        // Scan order is not FQN order, and declaration order is not alphabetical.
        val scanned = listOf(type("com.foo.Z", "zeta", "alpha"), type("com.foo.A", "omega", "beta", "gamma"), type("com.foo.M", "mu"))
        ParquetWriter.writeSnapshot(dir, scanned, emptyList(), CompressionCodec.UNCOMPRESSED)

        assertThat(ParquetReader().readAllMembers(dir).map { it.key }).containsExactly(
            "com.foo.A#omega()V", "com.foo.A#beta()V", "com.foo.A#gamma()V",
            "com.foo.M#mu()V",
            "com.foo.Z#zeta()V", "com.foo.Z#alpha()V",
        )
    }

    @Test
    fun `a table that cannot be written fails the write with its own exception`(@TempDir dir: Path) {
        val notADirectory = Files.writeString(dir.resolve("snapshot"), "a file where the tables should go")

        val failure = catchThrowable { ParquetWriter.writeSnapshot(notADirectory, classes, facts, CompressionCodec.UNCOMPRESSED) }

        assertThat(failure).isNotNull().isNotInstanceOf(ExecutionException::class.java)
        assertThat(failure.stackTraceToString()).contains(notADirectory.toString())
    }

    /** Only facts are stored: the types, the members and every dependency row come back as written. */
    private fun assertRoundTrip(dir: Path) {
        val reader = ParquetReader()
        assertThat(reader.readTypeRows(dir, listOf("com.foo.A", "com.foo.B")).map { it.fqn to it.kind })
            .containsExactlyInAnyOrder("com.foo.A" to "CLASS", "com.foo.B" to "CLASS")
        assertThat(reader.readMembersByOwner(dir, listOf("com.foo.A")).getValue("com.foo.A").map { it.key to it.kind })
            .containsExactly("com.foo.A#a()V" to JavaNodeKind.METHOD)
        assertThat(reader.readDependencies(dir).map { it.copy(fromArtifact = null) }).containsExactlyInAnyOrderElementsOf(facts)
    }
}
