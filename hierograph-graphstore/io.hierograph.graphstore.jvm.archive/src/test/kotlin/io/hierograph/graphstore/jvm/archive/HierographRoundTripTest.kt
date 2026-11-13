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

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.jvm.archive.model.ArtifactClasspath
import io.hierograph.graphstore.jvm.archive.model.ClasspathEntry
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.archive.model.ParameterFacts
import io.hierograph.graphstore.jvm.archive.ParquetWriter.ArtifactClass
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Per-artifact `.hierograph` round-trip: [ParquetWriter.writeArtifacts] emits one archive per artifact,
 * and [ParquetReader] reads a directory of them **across the set** — type rows, scoped detail reads and
 * single-member reads all span the archives, whether they are archives, exploded directories, collect
 * containers or a mix. Partitioning puts each detail ref in the archive of the artifact owning its `from`
 * side. Loading such a directory into a GraphStore is tested with `SnapshotLoader` in the store module.
 */
class HierographRoundTripTest {

    private val classes = listOf(
        ArtifactClass(
            "a.jar",
            ClassFacts(
                fqn = "com.a.A", kind = JavaNodeKind.CLASS, modifiers = 0,
                members = listOf(
                    MemberFacts(
                        key = "com.a.A#foo(java.lang.String)", kind = JavaNodeKind.METHOD,
                        signature = "foo(java.lang.String)", name = "foo",
                        parameters = listOf(ParameterFacts(0, "x", "java.lang.String")),
                    ),
                ),
                classHash = "h1", sourceFile = "A.java",
            ),
        ),
        ArtifactClass(
            "b.jar",
            ClassFacts(
                fqn = "com.b.B", kind = JavaNodeKind.CLASS, modifiers = 0, members = emptyList(),
                classHash = "h2", sourceFile = null,
            ),
        ),
    )

    private val dependencies = listOf(
        Dependency("com.a.A#foo(java.lang.String)", "com.a.A", "java.lang.String", "java.lang.String", DependencyKind.RETURNS, 1),
        Dependency("com.b.B#bar()", "com.b.B", "com.a.A#foo(java.lang.String)", "com.a.A", DependencyKind.CALLS, 2),
    )

    @Test
    fun `writeArtifacts stops before the next artifact once cancelled`(@TempDir dir: Path) {
        var checks = 0
        assertThatThrownBy {
            ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED) { checks++ >= 1 }
        }.isInstanceOf(CancellationException::class.java)

        // the first artifact was written, the second was not started
        val written = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertThat(written).containsExactly("a.hierograph")
        assertThat(checks).isEqualTo(2)
    }

    @Test
    fun `writeArtifacts emits one archive per artifact, read back across the set`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED)

        // one .hierograph per artifact, named with the .jar suffix stripped
        val archives = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertThat(archives).containsExactly("a.hierograph", "b.hierograph")

        // type rows and detail reads span the set; each ref landed in its from-side artifact's archive
        val reader = ParquetReader()
        assertThat(reader.readTypeRows(dir, listOf("com.a.A", "com.b.B")).map { it.artifact to it.fqn })
            .containsExactlyInAnyOrder("a.jar" to "com.a.A", "b.jar" to "com.b.B")
        assertThat(reader.readDependencies(dir)).hasSize(2)
        assertThat(reader.readDependencies(dir, fromTypes = listOf("com.b.B")).map { it.kind.value })
            .containsExactly("calls")

        // a member + its parameters resolve from whichever archive holds them
        val member = reader.readMember(dir, "com.a.A#foo(java.lang.String)")
        assertThat(member).isNotNull()
        assertThat(member!!.parameters.map { it.name }).containsExactly("x")
    }

    @Test
    fun `exploded artifacts read like archives, also mixed with them`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        val archived = ParquetReader().readDependencies(dir).toSet()

        // explode b: its archive becomes a directory of the same name holding the same tables (mixed snapshot)
        explode(dir.resolve("b.hierograph"))
        assertThat(dir.resolve("b.hierograph")).isDirectory()
        assertReads(dir, archived)

        // explode a too: a snapshot of exploded artifacts only
        explode(dir.resolve("a.hierograph"))
        assertThat(dir.resolve("a.hierograph")).isDirectory()
        assertReads(dir, archived)
    }

    private fun explode(archive: Path) {
        val tmp = archive.resolveSibling(archive.fileName.toString() + ".tmp")
        Files.move(archive, tmp)
        unzip(tmp, archive)
        Files.delete(tmp)
    }

    /** Every artifact of [dir] is read: both types, exactly [dependencies], and a member with its parameters. */
    private fun assertReads(dir: Path, dependencies: Set<Dependency>) {
        val reader = ParquetReader()
        assertThat(reader.readTypeRows(dir, listOf("com.a.A", "com.b.B")).map { it.artifact to it.fqn })
            .containsExactlyInAnyOrder("a.jar" to "com.a.A", "b.jar" to "com.b.B")
        val read = reader.readDependencies(dir)
        // Rows read from a per-artifact snapshot are tagged with the artifact that holds them.
        assertThat(read.map { it.copy(fromArtifact = null) }.toSet()).isEqualTo(dependencies.map { it.copy(fromArtifact = null) }.toSet())
        assertThat(read).allSatisfy { assertThat(it.fromArtifact).isEqualTo(it.fromType.substringAfter("com.").substringBefore('.') + ".jar") }
        assertThat(reader.readMember(dir, "com.a.A#foo(java.lang.String)")!!.parameters.map { it.name })
            .containsExactly("x")
    }

    @Test
    fun `archive and exploded packaging write identical tables`(@TempDir dir: Path) {
        val archives = dir.resolve("archives")
        val exploded = dir.resolve("exploded")
        ParquetWriter.writeArtifacts(archives, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        ParquetWriter.writeArtifacts(exploded, classes, dependencies, CompressionCodec.UNCOMPRESSED,
            packaging = ParquetWriter.Packaging.EXPLODED)

        for (name in listOf("a.hierograph", "b.hierograph")) {
            assertThat(exploded.resolve(name)).isDirectory()
            val unzipped = dir.resolve("unzipped").resolve(name)
            unzip(archives.resolve(name), unzipped)
            assertThat(tableBytes(exploded.resolve(name))).isEqualTo(tableBytes(unzipped))
        }
        assertReads(exploded, ParquetReader().readDependencies(archives).toSet())

        // zipping an exploded artifact yields an archive that reads equal
        val rezipped = Files.createDirectories(dir.resolve("rezipped"))
        for (name in listOf("a.hierograph", "b.hierograph")) HierographArchive.zip(exploded.resolve(name), rezipped.resolve(name))
        assertReads(rezipped, ParquetReader().readDependencies(archives).toSet())
    }

    @Test
    fun `writing leaves nothing outside the output and replaces either form`(@TempDir dir: Path) {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val tmpBefore = hierographEntries(tmp)

        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        assertThat(Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
            .containsExactly("a.hierograph", "b.hierograph")
        assertThat(hierographEntries(tmp)).isEqualTo(tmpBefore)

        // the archives become exploded directories of the same name, and back
        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED,
            packaging = ParquetWriter.Packaging.EXPLODED)
        assertThat(dir.resolve("a.hierograph")).isDirectory()
        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        assertThat(dir.resolve("a.hierograph")).isRegularFile()
        assertThat(Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
            .containsExactly("a.hierograph", "b.hierograph")
        assertReads(dir, dependencies.toSet())
    }

    private fun tableBytes(dir: Path): Map<String, List<Byte>> =
        Files.list(dir).use { s -> s.toList() }.associate { it.fileName.toString() to Files.readAllBytes(it).toList() }

    @Test
    fun `a collect container is read in place, in a snapshot directory or on its own`(@TempDir dir: Path) {
        val artifacts = dir.resolve("artifacts")
        ParquetWriter.writeArtifacts(artifacts, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        val archived = ParquetReader().readDependencies(artifacts).toSet()

        val snapshot = Files.createDirectories(dir.resolve("snapshot"))
        val container = snapshot.resolve("project-collect.hierograph")
        HierographArchive.zip(artifacts, container)

        assertReads(snapshot, archived)
        assertReads(container, archived)
    }

    @Test
    fun `an archive with deflated entries is read`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        val archived = ParquetReader().readDependencies(dir).toSet()

        for (name in listOf("a.hierograph", "b.hierograph")) deflate(dir.resolve(name))
        assertReads(dir, archived)
    }

    @Test
    fun `a collect container with deflated archives is read`(@TempDir dir: Path) {
        val artifacts = dir.resolve("artifacts")
        ParquetWriter.writeArtifacts(artifacts, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        val archived = ParquetReader().readDependencies(artifacts).toSet()

        val container = dir.resolve("project-collect.hierograph")
        HierographArchive.zip(artifacts, container)
        deflate(container)   // the nested archives are inflated on each table read

        assertReads(container, archived)
        assertReads(container, archived)   // and again: nothing of a deflated archive is cached
    }

    @Test
    fun `reading archives creates no files`(@TempDir dir: Path) {
        val snapshot = dir.resolve("snapshot")
        ParquetWriter.writeArtifacts(snapshot, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val tmpBefore = hierographEntries(tmp)
        val snapshotBefore = Files.walk(dir).use { it.toList() }.toSet()

        assertReads(snapshot, ParquetReader().readDependencies(snapshot).toSet())
        ParquetReader().readClasspath(snapshot)

        assertThat(hierographEntries(tmp)).isEqualTo(tmpBefore)
        assertThat(Files.walk(dir).use { it.toList() }.toSet()).isEqualTo(snapshotBefore)
    }

    @Test
    fun `a replaced archive is read anew and a deleted one is dropped`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED)
        assertThat(ParquetReader().readDependencies(dir)).hasSize(2)

        // rewrite b without its detail ref: the cached index of the old b.hierograph must not be used
        Files.delete(dir.resolve("b.hierograph"))
        ParquetWriter.writeArtifacts(dir, classes.filter { it.artifactKey == "b.jar" }, emptyList(), CompressionCodec.UNCOMPRESSED)
        assertThat(ParquetReader().readDependencies(dir).map { it.kind.value }).containsExactly("returns")

        Files.delete(dir.resolve("b.hierograph"))
        assertThat(ParquetReader().readTypeRows(dir, listOf("com.a.A", "com.b.B")).map { it.fqn }).containsExactly("com.a.A")
    }

    private fun hierographEntries(dir: Path): Set<String> =
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith("hierograph-") }.toList() }.toSet()

    /** Repacks [archive] with DEFLATED entries, as a tool other than Hierograph might. */
    private fun deflate(archive: Path) {
        val tables = archive.resolveSibling(archive.fileName.toString() + ".tables")
        unzip(archive, tables)
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            Files.list(tables).use { it.sorted().toList() }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.fileName.toString()).apply { method = ZipEntry.DEFLATED })
                Files.copy(f, zos)
                zos.closeEntry()
            }
        }
        Files.walk(tables).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `each copy of a type defined by two artifacts keeps its own dependencies, its parameters read once`(@TempDir dir: Path) {
        fun copy(artifact: String, parameter: String) = ArtifactClass(
            artifact,
            ClassFacts(
                fqn = "com.x.X", kind = JavaNodeKind.CLASS, modifiers = 0, classHash = artifact,
                members = listOf(
                    MemberFacts(
                        key = "com.x.X#m(int)", kind = JavaNodeKind.METHOD, signature = "m(int)", name = "m",
                        parameters = listOf(ParameterFacts(0, parameter, "int")),
                    ),
                ),
            ),
        )
        val shaded = listOf(copy("a.jar", "first"), copy("b.jar", "second"))
        // Each copy was scanned on its own, so each brings its own facts, tagged with its artifact.
        fun call(artifact: String?, target: String, line: Int) =
            Dependency("com.x.X#m(int)", "com.x.X", "$target#n()", target, DependencyKind.CALLS, line, artifact)
        val fromA = call("a.jar", "com.y.Y", 1)
        val fromB = call("b.jar", "com.z.Z", 2)
        val untagged = call(null, "com.w.W", 3)   // a caller that does not know the artifact
        ParquetWriter.writeArtifacts(dir, shaded, listOf(fromB, untagged, fromA), CompressionCodec.UNCOMPRESSED)
        val reader = ParquetReader()

        // each tagged fact lands in its own artifact; the untagged one with the first class that defines its type
        assertThat(reader.readDependencies(dir.resolve("a.hierograph"))).containsExactlyInAnyOrder(fromA, untagged.copy(fromArtifact = "a.jar"))
        assertThat(reader.readDependencies(dir.resolve("b.hierograph"))).containsExactly(fromB)

        // the member and its parameters come from one artifact, not from both
        assertThat(reader.readTypeRows(dir, listOf("com.x.X")).map { it.artifact }).containsExactlyInAnyOrder("a.jar", "b.jar")
        assertThat(reader.readMember(dir, "com.x.X#m(int)")!!.parameters.map { it.name }).containsExactly("first")
        assertThat(reader.readParameters(dir, "com.x.X#m(int)").map { it.name }).containsExactly("first")
        assertThat(reader.readParameters(dir, "com.x.X#absent()")).isEmpty()
    }

    @Test
    fun `classpath manifests round-trip`(@TempDir dir: Path) {
        val classpaths = mapOf(
            "a.jar" to ArtifactClasspath("a.jar", "A", emptyList()),
            "b.jar" to ArtifactClasspath("b.jar", "B", listOf(ClasspathEntry("a.jar", internal = true))),
        )
        ParquetWriter.writeArtifacts(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED, classpaths)

        val reader = ParquetReader()
        val manifests = reader.readClasspath(dir)
        assertThat(manifests.keys).containsExactlyInAnyOrder("a.jar", "b.jar")
        assertThat(manifests.getValue("a.jar").name).isEqualTo("A")
        assertThat(manifests.getValue("a.jar").order).containsExactly("a.jar")          // index 0 = self only
        assertThat(manifests.getValue("b.jar").name).isEqualTo("B")
        assertThat(manifests.getValue("b.jar").order).containsExactly("b.jar", "a.jar")  // self, then its dep
    }

    @Test
    fun `a combined table directory is read (legacy combined layout)`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, dependencies, CompressionCodec.UNCOMPRESSED)

        val reader = ParquetReader()
        assertThat(reader.readTypeRows(dir, listOf("com.a.A", "com.b.B")).map { it.fqn }).containsExactlyInAnyOrder("com.a.A", "com.b.B")
        assertThat(reader.readDependencies(dir)).hasSize(2)
    }

    /** Extracts every entry of [archive] (flat) into [targetDir]. */
    private fun unzip(archive: Path, targetDir: Path) {
        Files.createDirectories(targetDir)
        ZipInputStream(Files.newInputStream(archive).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = Path.of(entry.name).fileName.toString()
                    Files.newOutputStream(targetDir.resolve(name)).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
