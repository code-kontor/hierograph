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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun `writeArtifacts stops before the next artifact once cancelled`(@TempDir dir: Path) {
        var checks = 0
        assertThatThrownBy {
            ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED) { checks++ >= 1 }
        }.isInstanceOf(CancellationException::class.java)

        // the first artifact was written, the second was not started
        val written = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertThat(written).containsExactly("a.hierograph")
        assertThat(checks).isEqualTo(2)
    }

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
        val read = reader.readDetailRefs(dir)
        // Rows read from a per-artifact snapshot are tagged with the artifact that holds them.
        assertThat(read.map { it.copy(fromArtifact = null) }.toSet()).isEqualTo(detailRefs.map { it.copy(fromArtifact = null) }.toSet())
        assertThat(read).allSatisfy { assertThat(it.fromArtifact).isEqualTo(it.fromType.substringAfter("com.").substringBefore('.') + ".jar") }
        assertThat(reader.readMember(dir, "com.a.A#foo(java.lang.String)")!!.parameters.map { it.name })
            .containsExactly("x")
    }

    @Test
    fun `archive and exploded packaging write identical tables`(@TempDir dir: Path) {
        val archives = dir.resolve("archives")
        val exploded = dir.resolve("exploded")
        ParquetWriter.writeArtifacts(archives, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        ParquetWriter.writeArtifacts(exploded, classes, detailRefs, CompressionCodec.UNCOMPRESSED,
            packaging = ParquetWriter.Packaging.EXPLODED)

        for (name in listOf("a.hierograph", "b.hierograph")) {
            assertThat(exploded.resolve(name)).isDirectory()
            val unzipped = dir.resolve("unzipped").resolve(name)
            HierographArchive.unzip(archives.resolve(name), unzipped)
            assertThat(tableBytes(exploded.resolve(name))).isEqualTo(tableBytes(unzipped))
        }
        assertReadsBoth(exploded, ParquetReader().readDetailRefs(archives).toSet())

        // zipping an exploded artifact yields an archive that reads equal
        val rezipped = Files.createDirectories(dir.resolve("rezipped"))
        for (name in listOf("a.hierograph", "b.hierograph")) HierographArchive.zip(exploded.resolve(name), rezipped.resolve(name))
        assertReadsBoth(rezipped, ParquetReader().readDetailRefs(archives).toSet())
    }

    @Test
    fun `writing leaves nothing outside the output and replaces either form`(@TempDir dir: Path) {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val tmpBefore = hierographEntries(tmp)

        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        assertThat(Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
            .containsExactly("a.hierograph", "b.hierograph")
        assertThat(hierographEntries(tmp)).isEqualTo(tmpBefore)

        // the archives become exploded directories of the same name, and back
        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED,
            packaging = ParquetWriter.Packaging.EXPLODED)
        assertThat(dir.resolve("a.hierograph")).isDirectory()
        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        assertThat(dir.resolve("a.hierograph")).isRegularFile()
        assertThat(Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() })
            .containsExactly("a.hierograph", "b.hierograph")
        assertReadsBoth(dir, detailRefs.toSet())
    }

    private fun tableBytes(dir: Path): Map<String, List<Byte>> =
        Files.list(dir).use { s -> s.toList() }.associate { it.fileName.toString() to Files.readAllBytes(it).toList() }

    @Test
    fun `a collect container is read in place, in a snapshot directory or on its own`(@TempDir dir: Path) {
        val artifacts = dir.resolve("artifacts")
        ParquetWriter.writeArtifacts(artifacts, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        val archived = ParquetReader().readDetailRefs(artifacts).toSet()

        val snapshot = Files.createDirectories(dir.resolve("snapshot"))
        val container = snapshot.resolve("project-collect.hierograph")
        HierographArchive.zip(artifacts, container)

        assertReadsBoth(snapshot, archived)
        assertReadsBoth(container, archived)
    }

    @Test
    fun `an archive with deflated entries is read`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        val archived = ParquetReader().readDetailRefs(dir).toSet()

        for (name in listOf("a.hierograph", "b.hierograph")) deflate(dir.resolve(name))
        assertReadsBoth(dir, archived)
    }

    @Test
    fun `reading archives creates no files`(@TempDir dir: Path) {
        val snapshot = dir.resolve("snapshot")
        ParquetWriter.writeArtifacts(snapshot, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val tmpBefore = hierographEntries(tmp)
        val snapshotBefore = Files.walk(dir).use { it.toList() }.toSet()

        assertReadsBoth(snapshot, ParquetReader().readDetailRefs(snapshot).toSet())
        ParquetReader().readClasspath(snapshot)

        assertThat(hierographEntries(tmp)).isEqualTo(tmpBefore)
        assertThat(Files.walk(dir).use { it.toList() }.toSet()).isEqualTo(snapshotBefore)
    }

    @Test
    fun `a replaced archive is read anew and a deleted one is dropped`(@TempDir dir: Path) {
        ParquetWriter.writeArtifacts(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)
        assertThat(ParquetReader().readDetailRefs(dir)).hasSize(2)

        // rewrite b without its detail ref: the cached index of the old b.hierograph must not be used
        Files.delete(dir.resolve("b.hierograph"))
        ParquetWriter.writeArtifacts(dir, classes.filter { it.artifactKey == "b.jar" }, emptyList(), CompressionCodec.UNCOMPRESSED)
        assertThat(ParquetReader().readDetailRefs(dir).map { it.relationship }).containsExactly("returns")

        Files.delete(dir.resolve("b.hierograph"))
        val store = GraphStore()
        ParquetReader().load(dir, store)
        assertThat(store.typeByFqn("com.a.A")).isNotNull()
        assertThat(store.typeByFqn("com.b.B")).isNull()
    }

    private fun hierographEntries(dir: Path): Set<String> =
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith("hierograph-") }.toList() }.toSet()

    /** Repacks [archive] with DEFLATED entries, as a tool other than Hierograph might. */
    private fun deflate(archive: Path) {
        val tables = archive.resolveSibling(archive.fileName.toString() + ".tables")
        HierographArchive.unzip(archive, tables)
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
    fun `a combined table directory still loads (legacy combined layout)`(@TempDir dir: Path) {
        ParquetWriter.writeSnapshot(dir, classes, detailRefs, CompressionCodec.UNCOMPRESSED)

        val store = GraphStore()
        ParquetReader().load(dir, store)
        assertThat(store.typeByFqn("com.a.A")).isNotNull()
        assertThat(store.typeByFqn("com.b.B")).isNotNull()
        assertThat(ParquetReader().readDetailRefs(dir)).hasSize(2)
    }
}
