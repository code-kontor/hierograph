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

import dev.hardwood.InputFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * One artifact of a snapshot: opens its tables (`types.parquet`, …) as Hardwood [InputFile]s, or `null`
 * for a table the artifact does not have.
 */
interface ArtifactSource {
    val name: String
    fun table(file: String): InputFile?
}

/**
 * Resolves a snapshot to its [ArtifactSource]s, reading archives **in place**: a table inside a
 * `.hierograph` archive is read from its byte range in the archive file, never extracted. See
 * `spec/hierograph-graphstore-format.md` §2.
 *
 * A snapshot directory yields one source per `*.hierograph` entry, in name order: a directory (exploded
 * artifact) is read as it is; a file is an archive, or a `collect` container whose entries are archives —
 * then each inner archive is a source of its own. A directory without such entries holds the tables
 * directly (the unpacked layout) and is one source. A path to a single `.hierograph` file is read like a
 * snapshot directory holding just that file.
 *
 * Every table is read with positional reads ([RangeInputFile]), never memory-mapped, so a snapshot can be
 * deleted while it is open. The parsed central directory of each archive file is cached by (path, size,
 * last-modified); entries for files that no longer exist are dropped on the next resolution.
 */
object SnapshotSources {

    fun of(parquetDir: Path): List<ArtifactSource> {
        purge()
        if (!Files.isDirectory(parquetDir)) {
            return if (Files.isRegularFile(parquetDir) && parquetDir.fileName.toString().endsWith(HierographArchive.EXTENSION)) {
                archiveFile(parquetDir)
            } else {
                listOf(DirectorySource(parquetDir))
            }
        }
        val artifacts = Files.list(parquetDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(HierographArchive.EXTENSION) }.sorted().toList()
        }
        if (artifacts.isEmpty()) return listOf(DirectorySource(parquetDir))
        return artifacts.flatMap { if (Files.isDirectory(it)) listOf(DirectorySource(it)) else archiveFile(it) }
    }

    private class Cached(val size: Long, val modified: Long, val sources: List<ArtifactSource>)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /** The sources of one archive file: itself, or the archives inside it when it is a container. */
    private fun archiveFile(path: Path): List<ArtifactSource> {
        val size = Files.size(path)
        val modified = Files.getLastModifiedTime(path).toMillis()
        cache[path]?.let { if (it.size == size && it.modified == modified) return it.sources }
        val blob = Blob.FileRange(path, 0, size)
        val entries = ZipIndex.read(blob)
        val inner = entries.filter { it.name.endsWith(HierographArchive.EXTENSION) }
        val sources = if (inner.isEmpty()) {
            listOf(ArchiveSource(path.fileName.toString(), blob, entries))
        } else {
            inner.sortedBy { it.name }.map { e -> nestedSource("${path.fileName}!/${e.name}", blob, e) }
        }
        cache[path] = Cached(size, modified, sources)
        return sources
    }

    /**
     * An archive inside a container. STORED (as `hierograph:collect` writes it), it is a byte range of the
     * container and its index is parsed once; otherwise it is inflated into memory on each table read, and
     * nothing of it is cached.
     */
    private fun nestedSource(label: String, container: Blob, entry: ZipEntryRef): ArtifactSource =
        if (entry.method == ZipIndex.STORED) {
            val blob = container.slice(entry.dataOffset, entry.size)
            ArchiveSource(label, blob, ZipIndex.read(blob))
        } else {
            object : ArtifactSource {
                override val name = label
                override fun table(file: String): InputFile? {
                    val blob = ZipIndex.content(container, entry)
                    return ArchiveSource(label, blob, ZipIndex.read(blob)).table(file)
                }
            }
        }

    private fun purge() {
        cache.keys.removeIf { !Files.exists(it) }
    }
}

/** An exploded artifact, or the unpacked layout: the tables are files in [dir]. */
internal class DirectorySource(private val dir: Path) : ArtifactSource {
    override val name: String = dir.fileName?.toString() ?: dir.toString()
    override fun table(file: String): InputFile? {
        val path = dir.resolve(file)
        if (!Files.isRegularFile(path)) return null
        return RangeInputFile(Blob.FileRange(path, 0, Files.size(path)), "$name/$file")
    }
}

/** An archive: each table is an entry of [blob]; a STORED one is read in place, any other inflated. */
internal class ArchiveSource(
    override val name: String,
    private val blob: Blob,
    entries: List<ZipEntryRef>,
) : ArtifactSource {
    private val byName = entries.associateBy { it.name }
    override fun table(file: String): InputFile? {
        val entry = byName[file] ?: return null
        val content = ZipIndex.content(blob, entry)
        return RangeInputFile(content, "$name!/$file")
    }
}

/** Bytes to read from: a range of a file, or a buffer in memory. */
internal sealed interface Blob {
    val length: Long
    fun read(offset: Long, length: Int): ByteBuffer
    fun slice(offset: Long, length: Long): Blob

    class FileRange(val path: Path, val start: Long, override val length: Long) : Blob {
        override fun read(offset: Long, length: Int): ByteBuffer =
            FileChannel.open(path, StandardOpenOption.READ).use { readFully(it, start + offset, length) }

        override fun slice(offset: Long, length: Long): Blob = FileRange(path, start + offset, length)
    }

    class Memory(private val buffer: ByteBuffer) : Blob {
        override val length: Long get() = buffer.limit().toLong()
        override fun read(offset: Long, length: Int): ByteBuffer = buffer.slice(offset.toInt(), length)
        override fun slice(offset: Long, length: Long): Blob = Memory(buffer.slice(offset.toInt(), length.toInt()))
    }

    companion object {
        fun readFully(channel: FileChannel, position: Long, length: Int): ByteBuffer {
            val buf = ByteBuffer.allocate(length)
            var pos = position
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos)
                if (n < 0) throw IOException("Unexpected end of file at $pos")
                pos += n
            }
            return buf.flip()
        }
    }
}

/**
 * A Hardwood [InputFile] over a [Blob]. A file range is served by positional reads on a channel held
 * between [open] and [close]; no memory mapping, so the file can be deleted while the input is open.
 */
internal class RangeInputFile(private val blob: Blob, private val name: String) : InputFile {
    private var channel: FileChannel? = null

    override fun open() {
        if (blob is Blob.FileRange && channel == null) channel = FileChannel.open(blob.path, StandardOpenOption.READ)
    }

    override fun readRange(offset: Long, length: Int): ByteBuffer {
        if (offset < 0 || length < 0 || offset + length > blob.length) {
            throw IndexOutOfBoundsException("$name: range $offset+$length outside 0..${blob.length}")
        }
        return when (blob) {
            is Blob.FileRange -> Blob.readFully(checkNotNull(channel) { "$name is not open" }, blob.start + offset, length)
            is Blob.Memory -> blob.read(offset, length)
        }
    }

    override fun length(): Long = blob.length

    override fun name(): String = name

    override fun close() {
        channel?.close()
        channel = null
    }
}

/** A ZIP entry as found in the central directory, with the offset of its data. */
internal data class ZipEntryRef(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val dataOffset: Long,
)

/** Reads the central directory of a ZIP held in a [Blob]. ZIP64 archives are not supported. */
internal object ZipIndex {
    const val STORED = 0
    const val DEFLATED = 8

    private const val EOCD_SIG = 0x06054b50
    private const val CEN_SIG = 0x02014b50
    private const val LOC_SIG = 0x04034b50
    private const val EOCD_MIN = 22
    private const val LOC_HEADER = 30

    fun read(blob: Blob): List<ZipEntryRef> {
        val tailLen = minOf(blob.length, (EOCD_MIN + 0xFFFF).toLong()).toInt()
        val tail = blob.read(blob.length - tailLen, tailLen).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = tailLen - EOCD_MIN
        while (eocd >= 0 && tail.getInt(eocd) != EOCD_SIG) eocd--
        if (eocd < 0) throw IOException("Not a ZIP archive (no end of central directory)")
        val count = tail.getShort(eocd + 10).toInt() and 0xFFFF
        val cenSize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cenOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (count == 0xFFFF || cenSize == 0xFFFFFFFFL || cenOffset == 0xFFFFFFFFL) {
            throw IOException("ZIP64 archives are not supported")
        }
        val cen = blob.read(cenOffset, cenSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<ZipEntryRef>(count)
        var p = 0
        repeat(count) {
            if (cen.getInt(p) != CEN_SIG) throw IOException("Corrupt ZIP central directory at entry ${entries.size}")
            val method = cen.getShort(p + 10).toInt() and 0xFFFF
            val compressed = cen.getInt(p + 20).toLong() and 0xFFFFFFFFL
            val size = cen.getInt(p + 24).toLong() and 0xFFFFFFFFL
            val nameLen = cen.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cen.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cen.getShort(p + 32).toInt() and 0xFFFF
            val localOffset = cen.getInt(p + 42).toLong() and 0xFFFFFFFFL
            val nameBytes = ByteArray(nameLen).also { cen.get(p + 46, it) }
            val name = String(nameBytes, Charsets.UTF_8)
            if (!name.endsWith("/")) {
                // The data starts after the local header, whose name and extra lengths may differ from the
                // central directory's.
                val loc = blob.read(localOffset, LOC_HEADER).order(ByteOrder.LITTLE_ENDIAN)
                if (loc.getInt(0) != LOC_SIG) throw IOException("Corrupt ZIP local header for '$name'")
                val locName = loc.getShort(26).toInt() and 0xFFFF
                val locExtra = loc.getShort(28).toInt() and 0xFFFF
                entries += ZipEntryRef(name.substringAfterLast('/'), method, compressed, size, localOffset + LOC_HEADER + locName + locExtra)
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        return entries
    }

    /** The entry's content: a slice of [blob] when STORED, else inflated into memory. */
    fun content(blob: Blob, entry: ZipEntryRef): Blob = when (entry.method) {
        STORED -> blob.slice(entry.dataOffset, entry.size)
        DEFLATED -> {
            val input = blob.read(entry.dataOffset, entry.compressedSize.toInt())
            val out = ByteBuffer.allocate(entry.size.toInt())
            val inflater = Inflater(true)
            try {
                inflater.setInput(input)
                while (out.hasRemaining() && !inflater.finished()) {
                    if (inflater.inflate(out) == 0 && inflater.needsInput()) break
                }
            } finally {
                inflater.end()
            }
            if (out.hasRemaining()) throw IOException("Truncated ZIP entry '${entry.name}'")
            Blob.Memory(out.flip())
        }
        else -> throw IOException("Unsupported ZIP compression method ${entry.method} for '${entry.name}'")
    }
}
