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
package io.hierograph.graphstore.parquet

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A `.hierograph` archive: the per-artifact Parquet snapshot packaged as a single ZIP — one artifact's
 * `types` / `members` / `parameters` / `refs` / `detail_refs` tables. It is the unit of scanning,
 * caching and exchange: a project snapshot is a directory of these, one per artifact (jar / module), so a
 * library is scanned once and its `.hierograph` reused.
 *
 * Entries are **STORED** (not re-compressed): the Parquet tables are already ZSTD-compressed, so deflating
 * them again only burns CPU. On read, archives are [extracted] to a temp directory once and cached by
 * (path, last-modified), so repeated scoped reads during a session pay the unzip cost a single time and a
 * changed snapshot (e.g. after a re-scan) is re-extracted automatically.
 */
object HierographArchive {

    const val EXTENSION = ".hierograph"

    /** The archive file name for an artifact key, dropping a trailing `.jar`/`.zip` (`spring-core-7.0.8.hierograph`). */
    fun fileName(artifactKey: String): String =
        // The key may be a GAV (`g:a:v`) from the Maven plugin; `:` and path separators are not filesystem
        // -safe, so sanitize them for the archive file name. The key itself (stored inside) is unchanged.
        artifactKey.removeSuffix(".jar").removeSuffix(".zip").replace(Regex("[:/\\\\]"), "_") + EXTENSION

    /** Writes every regular file directly under [sourceDir] into [target] as a STORED ZIP. */
    fun zip(sourceDir: Path, target: Path) {
        ZipOutputStream(Files.newOutputStream(target).buffered()).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            Files.list(sourceDir).use { files ->
                files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    val bytes = Files.readAllBytes(file)
                    val entry = ZipEntry(file.fileName.toString()).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }

    /** Extracts every entry of [archive] (flat) into [targetDir]. */
    fun unzip(archive: Path, targetDir: Path) {
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

    /** The extracted table directory for [archive], unzipped once and cached by (path, last-modified). */
    fun extracted(archive: Path): Path =
        cache.computeIfAbsent("$archive|${Files.getLastModifiedTime(archive).toMillis()}") {
            Files.createTempDirectory("hierograph-").also { unzip(archive, it) }
        }

    private val cache = ConcurrentHashMap<String, Path>()
}
