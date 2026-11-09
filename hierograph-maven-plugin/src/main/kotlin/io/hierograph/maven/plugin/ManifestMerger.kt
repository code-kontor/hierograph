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
package io.hierograph.maven.plugin

import com.google.gson.GsonBuilder
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.logging.Log
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant

/** What a [ManifestMerger.merge] did: the number of modules merged and whether the manifest file was rewritten. */
internal data class MergeOutcome(val modules: Int, val written: Boolean, val file: File)

/**
 * Folds every reactor module's `module.json` descriptor into the top-level `manifest.json`. Shared by
 * `hierograph:create-descriptor` (which merges after writing its own descriptor, so any build reaching
 * `process-classes` keeps the manifest current) and `hierograph:merge-descriptors`.
 *
 * The merge runs over `session.allProjects`, which is the full reactor even when the build is narrowed with
 * `-pl`: a module removed from `<modules>` drops out, a renamed module replaces its old GAV, and a module not
 * built in this run contributes its last descriptor unchanged. A module whose descriptor is missing (deleted by
 * `mvn clean`, not rebuilt yet) keeps its entry from the existing manifest, so a narrowed or partial build after
 * `clean` does not drop it.
 *
 * The manifest is rewritten only when its content differs from the existing file (ignoring `generatedAt`), so
 * a rebuild of an unchanged module set does not signal a module-set change to a watching server. Writes are
 * atomic (temp file + move) and serialised, within the JVM for `-T` builds and across processes by a file lock.
 */
internal object ManifestMerger {

    private const val LOCK_FILE = "manifest.lock"

    /** Serialises merges of parallel module builds in one Maven process. */
    private val jvmLock = Any()

    private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun merge(session: MavenSession, manifestDir: File, generator: String, log: Log): MergeOutcome = synchronized(jvmLock) {
        manifestDir.mkdirs()
        withFileLock(File(manifestDir, LOCK_FILE).toPath()) {
            val out = File(manifestDir, MANIFEST_FILE)
            val existing = readManifest(out)
            val manifest = Manifest(
                schemaVersion = SCHEMA_VERSION,
                generator = generator,
                generatedAt = Instant.now().toString(),
                reactorRoot = session.executionRootDirectory,
                modules = collectDescriptors(session, existing, log),
            )
            val written = existing == null || existing.copy(generatedAt = "") != manifest.copy(generatedAt = "")
            if (written) writeAtomically(out.toPath(), gson.toJson(manifest) + "\n")
            MergeOutcome(manifest.modules.size, written, out)
        }
    }

    /** Writes [descriptor] to [file] atomically, and only when its content changed. Returns whether it wrote. */
    fun writeDescriptor(descriptor: ModuleDescriptor, file: File): Boolean {
        val json = gson.toJson(descriptor) + "\n"
        if (file.isFile && file.readText() == json) return false
        file.parentFile.mkdirs()
        writeAtomically(file.toPath(), json)
        return true
    }

    private fun collectDescriptors(session: MavenSession, existing: Manifest?, log: Log): List<ModuleDescriptor> {
        val previous = existing?.modules.orEmpty().associateBy { it.gav }
        val seen = HashMap<String, String>() // gav -> the module that provided it
        val modules = ArrayList<ModuleDescriptor>()
        for (project in session.allProjects) {
            val descriptorFile = File(File(project.build.directory, DESCRIPTOR_SUBDIR), DESCRIPTOR_FILE)
            if (!descriptorFile.isFile) {
                val kept = previous["${project.groupId}:${project.artifactId}:${project.version}"]
                if (kept != null && seen.putIfAbsent(kept.gav, project.artifactId) == null) modules += kept
                continue
            }
            val module = try {
                gson.fromJson(descriptorFile.readText(), ModuleDescriptor::class.java)
            } catch (e: Exception) {
                log.warn("hierograph: could not read descriptor $descriptorFile: ${e.message}")
                continue
            } ?: continue
            val first = seen.putIfAbsent(module.gav, project.artifactId)
            if (first != null) {
                log.warn("hierograph: duplicate module '${module.gav}' from ${project.artifactId}; keeping the one from $first")
            } else {
                modules += module
            }
        }
        modules.sortBy { it.gav }
        return modules
    }

    private fun readManifest(file: File): Manifest? =
        if (!file.isFile) null else runCatching { gson.fromJson(file.readText(), Manifest::class.java) }.getOrNull()

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
     * Runs [block] holding an exclusive lock on [lockFile], so two Maven processes (a CLI build and an IDE
     * build) never merge at once. A lock held by this JVM through another plugin class loader surfaces as
     * [OverlappingFileLockException]; that case is retried until the holder releases it.
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
