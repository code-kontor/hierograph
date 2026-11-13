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

import io.hierograph.graphstore.jvm.archive.ParquetTestWriter.TypeFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The edges of [SnapshotSources]: paths that hold no snapshot, and archives the ZIP reader cannot read.
 * The archive layouts it reads are tested in [HierographRoundTripTest].
 */
class SnapshotSourcesTest {

    private val types = listOf(TypeFixture("app", "com.foo.A", "CLASS"))

    @Test
    fun `a path that is neither a directory nor an archive reads as an empty snapshot`(@TempDir dir: Path) {
        val reader = ParquetReader()
        val missing = dir.resolve("missing")
        val plainFile = Files.writeString(dir.resolve("notes.txt"), "not a snapshot")

        for (path in listOf(missing, plainFile)) {
            assertThat(reader.readTypeRows(path, listOf("com.foo.A"))).isEmpty()
            assertThat(reader.readDependencies(path)).isEmpty()
        }
    }

    @Test
    fun `a file that is no ZIP archive is rejected`(@TempDir dir: Path) {
        val archive = Files.write(dir.resolve("a.hierograph"), ByteArray(64) { 0x2A })

        assertThatThrownBy { SnapshotSources.of(archive) }
            .isInstanceOf(IOException::class.java).hasMessageContaining("Not a ZIP archive")
    }

    @Test
    fun `a ZIP64 archive is rejected`(@TempDir dir: Path) {
        // An empty archive whose end-of-central-directory record carries the ZIP64 marker as its entry count.
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x06054b50).putShort(0).putShort(0)
            .putShort(0xFFFF.toShort()).putShort(0xFFFF.toShort())
            .putInt(0).putInt(0).putShort(0)
        val archive = Files.write(dir.resolve("a.hierograph"), eocd.array())

        assertThatThrownBy { SnapshotSources.of(archive) }
            .isInstanceOf(IOException::class.java).hasMessageContaining("ZIP64 archives are not supported")
    }

    @Test
    fun `a table compressed with a method other than STORED or DEFLATED is rejected when it is read`(@TempDir dir: Path) {
        val tables = Files.createDirectories(dir.resolve("tables"))
        ParquetTestWriter.writeTypes(tables, types)
        val archive = dir.resolve("a.hierograph")
        HierographArchive.zip(tables, archive)
        setCompressionMethod(archive, 12)   // bzip2

        val reader = ParquetReader()
        assertThat(reader.readDependencies(archive)).isEmpty()   // an absent table is not touched
        assertThatThrownBy { reader.readTypeRows(archive, listOf("com.foo.A")) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("Unsupported ZIP compression method 12 for '${ParquetSchema.TYPES_FILE}'")
    }

    @Test
    fun `a table in a memory blob is read in range and rejects a read outside it`() {
        val input = RangeInputFile(Blob.Memory(ByteBuffer.wrap(ByteArray(16) { it.toByte() })), "t")
        input.open()

        assertThat(input.length()).isEqualTo(16)
        assertThat(input.readRange(4, 2).let { listOf(it.get(0), it.get(1)) }).containsExactly(4.toByte(), 5.toByte())
        assertThat(Blob.Memory(ByteBuffer.wrap(ByteArray(16) { it.toByte() })).slice(8, 4).read(0, 1).get(0)).isEqualTo(8.toByte())
        assertThatThrownBy { input.readRange(12, 8) }
            .isInstanceOf(IndexOutOfBoundsException::class.java).hasMessageContaining("t: range 12+8 outside 0..16")
        input.close()
    }

    @Test
    fun `an archive with several entries in a sub-directory is read by its entries' file names`(@TempDir dir: Path) {
        val tables = Files.createDirectories(dir.resolve("tables"))
        ParquetTestWriter.writeTypes(tables, types)
        val archive = dir.resolve("a.hierograph")
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(ZipEntry("nested/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("nested/${ParquetSchema.TYPES_FILE}"))
            Files.copy(tables.resolve(ParquetSchema.TYPES_FILE), zos)
            zos.closeEntry()
        }

        assertThat(ParquetReader().readTypeRows(archive, listOf("com.foo.A")).map { it.fqn }).containsExactly("com.foo.A")
    }

    @Test
    fun `an archive whose central directory or local header is corrupt is rejected`(@TempDir dir: Path) {
        val corruptDirectory = deflatedArchive(dir, "a.hierograph")
        patch(corruptDirectory, CEN_SIG) { bytes, p -> bytes.putInt(p, 0x12345678) }
        assertThatThrownBy { SnapshotSources.of(corruptDirectory) }
            .isInstanceOf(IOException::class.java).hasMessageContaining("Corrupt ZIP central directory at entry 0")

        val corruptHeader = deflatedArchive(dir, "b.hierograph")
        patch(corruptHeader, LOC_SIG) { bytes, p -> bytes.putInt(p, 0x12345678) }
        assertThatThrownBy { SnapshotSources.of(corruptHeader) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("Corrupt ZIP local header for '${ParquetSchema.TYPES_FILE}'")
    }

    @Test
    fun `a deflated table shorter than its declared size is rejected when it is read`(@TempDir dir: Path) {
        val archive = deflatedArchive(dir, "a.hierograph")
        patch(archive, CEN_SIG) { bytes, p -> bytes.putInt(p + 24, bytes.getInt(p + 24) + 100) }   // uncompressed size

        assertThatThrownBy { ParquetReader().readTypeRows(archive, listOf("com.foo.A")) }
            .isInstanceOf(IOException::class.java).hasMessageContaining("Truncated ZIP entry '${ParquetSchema.TYPES_FILE}'")
    }

    @Test
    fun `an archive rewritten to the same size is read anew when its modification time changes`(@TempDir dir: Path) {
        val archive = dir.resolve("a.hierograph")
        fun write(fqn: String) {
            val tables = Files.createDirectories(dir.resolve("tables-$fqn"))
            ParquetTestWriter.writeTypes(tables, listOf(TypeFixture("app", fqn, "CLASS")))
            HierographArchive.zip(tables, archive)
        }
        val reader = ParquetReader()
        write("com.foo.A")
        val size = Files.size(archive)
        val modified = Files.getLastModifiedTime(archive)
        assertThat(reader.readTypeRows(archive, listOf("com.foo.A", "com.foo.B")).map { it.fqn }).containsExactly("com.foo.A")

        write("com.foo.B")   // same length, so the same size
        assertThat(Files.size(archive)).isEqualTo(size)
        Files.setLastModifiedTime(archive, FileTime.fromMillis(modified.toMillis() + 10_000))

        assertThat(reader.readTypeRows(archive, listOf("com.foo.A", "com.foo.B")).map { it.fqn }).containsExactly("com.foo.B")
    }

    @Test
    fun `a table in a file range is read only while open, and rejects a negative range`(@TempDir dir: Path) {
        val file = Files.write(dir.resolve("t.bin"), ByteArray(32) { it.toByte() })
        val input = RangeInputFile(Blob.FileRange(file, 8, 16), "t")

        assertThatThrownBy { input.readRange(0, 4) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("t is not open")
        input.open()
        input.open()   // opening twice keeps the one channel
        assertThat(input.readRange(2, 1).get(0)).isEqualTo(10.toByte())
        assertThatThrownBy { input.readRange(-1, 4) }.isInstanceOf(IndexOutOfBoundsException::class.java)
        assertThatThrownBy { input.readRange(0, -1) }.isInstanceOf(IndexOutOfBoundsException::class.java)
        input.close()
        assertThatThrownBy { input.readRange(0, 4) }.isInstanceOf(IllegalStateException::class.java)
    }

    /** An archive holding a `types` table as its only, DEFLATED entry. */
    private fun deflatedArchive(dir: Path, name: String): Path {
        val tables = Files.createDirectories(dir.resolve("$name.tables"))
        ParquetTestWriter.writeTypes(tables, types)
        val archive = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(archive)).use { zos ->
            zos.putNextEntry(ZipEntry(ParquetSchema.TYPES_FILE))
            Files.copy(tables.resolve(ParquetSchema.TYPES_FILE), zos)
            zos.closeEntry()
        }
        return archive
    }

    /** Applies [change] at every position in [archive] where the 4-byte [signature] occurs. */
    private fun patch(archive: Path, signature: Int, change: (ByteBuffer, Int) -> Unit) {
        val bytes = ByteBuffer.wrap(Files.readAllBytes(archive)).order(ByteOrder.LITTLE_ENDIAN)
        val positions = (0 until bytes.limit() - 4).filter { bytes.getInt(it) == signature }
        assertThat(positions).isNotEmpty()
        positions.forEach { change(bytes, it) }
        Files.write(archive, bytes.array())
    }

    /** Overwrites the compression method of every entry in [archive]'s central directory. */
    private fun setCompressionMethod(archive: Path, method: Int) {
        val bytes = ByteBuffer.wrap(Files.readAllBytes(archive)).order(ByteOrder.LITTLE_ENDIAN)
        for (p in 0 until bytes.limit() - 4) {
            if (bytes.getInt(p) == CEN_SIG) bytes.putShort(p + 10, method.toShort())
        }
        Files.write(archive, bytes.array())
    }

    private companion object {
        const val CEN_SIG = 0x02014b50
        const val LOC_SIG = 0x04034b50
    }
}
