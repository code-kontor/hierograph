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
package io.hierograph.manifest

import com.google.gson.JsonObject
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * A module the build knows, as a producer hands it to [ManifestWriter.merge]: its current [gav] (to keep its
 * previous manifest entry when the descriptor is missing), a display [name] for messages, and its descriptor —
 * either computed in memory ([descriptor]) or written to [descriptorFile] by an earlier build step.
 */
data class ModuleSource(
    val gav: String,
    val name: String,
    val descriptorFile: Path? = null,
    val descriptor: ModuleDescriptor? = null,
)

/**
 * Everything a merge needs from the build tool. [modules] are all modules of the build, also those not built in
 * this run; [buildFiles] the files that define the build.
 */
data class MergeRequest(
    val manifestDir: Path,
    val generator: String,
    val reactorRoot: String,
    val buildTool: BuildTool?,
    val regenerate: List<String>?,
    val modules: List<ModuleSource>,
    val buildFiles: Collection<Path>,
)

/** What a merge did: the number of modules merged, whether the manifest file was rewritten, and where it is. */
data class MergeOutcome(val modules: Int, val written: Boolean, val file: Path)

/**
 * Writes descriptors and merges them into the manifest, independent of the build tool.
 *
 * - **Merge.** One entry per module of the build with a descriptor, given in memory or as a file. A module whose
 *   descriptor file is missing (deleted by a clean, not rebuilt yet) keeps its entry from the existing manifest. A module no longer in the build
 *   drops out; a module whose GAV changed replaces its previous entry. Two modules with the same GAV: the first
 *   is kept and [merge]'s `warn` is told. Modules are sorted by GAV, build files by path.
 * - **Write only on change.** The manifest is compared with the existing file as a JSON tree, ignoring
 *   `generatedAt`, and rewritten only when something else differs: a watching server treats a changed manifest
 *   as a module-set change. Descriptors follow the same rule.
 * - **Atomic and serialised.** Files are written to a temporary file and moved into place. Merges are serialised
 *   within the JVM (parallel module builds) and across processes by an exclusive lock on `manifest.lock`.
 */
object ManifestWriter {

    private const val LOCK_FILE = "manifest.lock"

    private val jvmLock = Any()

    /** Content hashes keyed by path, reused while size and mtime are unchanged (a full build merges once per module). */
    private val hashCache = HashMap<Path, Triple<Long, Long, String>>()

    /** Writes [descriptor] to [file] atomically, and only when its content changed. Returns whether it wrote. */
    fun writeDescriptor(descriptor: ModuleDescriptor, file: Path): Boolean {
        val json = ManifestJson.toJson(descriptor)
        if (Files.isRegularFile(file) && Files.readString(file) == json) return false
        Files.createDirectories(file.parent)
        writeAtomically(file, json)
        return true
    }

    fun merge(request: MergeRequest, warn: (String) -> Unit = {}): MergeOutcome = synchronized(jvmLock) {
        Files.createDirectories(request.manifestDir)
        withFileLock(request.manifestDir.resolve(LOCK_FILE)) {
            val out = request.manifestDir.resolve(MANIFEST_FILE)
            val existing = if (Files.isRegularFile(out)) ManifestJson.parseObject(Files.readString(out)) else null
            val manifest = Manifest(
                schemaVersion = SCHEMA_VERSION,
                generator = request.generator,
                generatedAt = OffsetDateTime.now().toString(), // local time with its UTC offset
                reactorRoot = request.reactorRoot,
                buildTool = request.buildTool,
                regenerate = request.regenerate,
                modules = collectModules(request.modules, existing?.let(ManifestJson::readModules).orEmpty(), warn),
                buildFiles = request.buildFiles.map { it.toAbsolutePath().normalize() }.distinct().sorted()
                    .filter { Files.isRegularFile(it) }.map { BuildFile(it.toString(), sha256(it)) },
            )
            val written = existing == null ||
                withoutTimestamp(existing) != withoutTimestamp(ManifestJson.gson.toJsonTree(manifest).asJsonObject)
            if (written) writeAtomically(out, ManifestJson.toJson(manifest))
            MergeOutcome(manifest.modules.size, written, out)
        }
    }

    private fun collectModules(sources: List<ModuleSource>, previous: List<ModuleDescriptor>, warn: (String) -> Unit): List<ModuleDescriptor> {
        val previousByGav = previous.associateBy { it.gav }
        val seen = HashMap<String, String>() // gav -> the module that provided it
        val modules = ArrayList<ModuleDescriptor>()
        for (source in sources) {
            val file = source.descriptorFile
            val module = when {
                source.descriptor != null -> source.descriptor
                file != null && Files.isRegularFile(file) -> {
                    val obj = ManifestJson.parseObject(Files.readString(file))
                    obj?.let(ManifestJson::readDescriptor) ?: run {
                        warn("could not read descriptor $file")
                        null
                    }
                }
                else -> previousByGav[source.gav]
            } ?: continue
            val first = seen.putIfAbsent(module.gav, source.name)
            if (first != null) {
                warn("duplicate module '${module.gav}' from ${source.name}; keeping the one from $first")
            } else {
                modules += module
            }
        }
        modules.sortBy { it.gav }
        return modules
    }

    private fun withoutTimestamp(manifest: JsonObject): JsonObject = manifest.deepCopy().apply { remove("generatedAt") }

    private fun sha256(file: Path): String {
        val size = Files.size(file)
        val mtime = Files.getLastModifiedTime(file).toMillis()
        hashCache[file]?.let { (s, m, hash) -> if (s == size && m == mtime) return hash }
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
        val hash = digest.joinToString("") { "%02x".format(it) }
        hashCache[file] = Triple(size, mtime, hash)
        return hash
    }

    private fun writeAtomically(target: Path, content: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, REPLACE_EXISTING)
        }
    }

    /**
     * Runs [block] holding an exclusive lock on [lockFile], so two build processes never merge at once. A lock
     * held by this JVM through another class loader surfaces as [OverlappingFileLockException]; that case is
     * retried until the holder releases it.
     */
    private fun <T> withFileLock(lockFile: Path, block: () -> T): T {
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            while (true) {
                val lock = try {
                    channel.lock()
                } catch (e: OverlappingFileLockException) {
                    Thread.sleep(20)
                    continue
                }
                lock.use { return block() }
            }
        }
    }
}
