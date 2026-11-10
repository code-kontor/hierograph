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
package io.hierograph.server.graphservice.views

import io.hierograph.server.views.ViewDocument
import io.hierograph.server.views.ViewDocumentException
import io.hierograph.server.views.ViewDocumentParser
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** Where a view document comes from (views specification, §7.1). */
enum class ViewSource {
    /** `<build root>/.hierograph/views`: kept with the code, versioned and reviewed; read-only for the server. */
    PROJECT,

    /** The server's state directory: views defined at runtime through the tools. */
    STATE,
}

/**
 * One view document as the registry knows it: valid, with its [document], or invalid, with the [problems]
 * that keep it from being applied. An invalid document is listed under its file name without `.json`.
 */
class ViewEntry(
    val name: String,
    val source: ViewSource,
    val file: Path,
    /** The document's text as read from [file]. */
    val text: String,
    val document: ViewDocument?,
    val problems: List<String>,
    /** For a [ViewSource.STATE] entry: whether it hides a project view of the same name. */
    val shadowsProjectView: Boolean = false,
) {
    val isValid: Boolean get() = document != null
}

/** A view name that no project or state document defines. */
class UnknownViewException(val name: String, val known: List<String>) : NoSuchElementException(
    "Unknown view '$name'. " + if (known.isEmpty()) "No views are defined." else "Known views: ${known.joinToString()}.",
)

/**
 * The view documents of the project and the state directory (views specification, §7).
 *
 * Every `*.json` file directly in [projectDir] or [stateDir] is a view document, named by its `name` field.
 * On a name defined in both, the state document wins and the entry says it shadows the project one. Within
 * one directory, a name defined twice is taken from the first file in file-name order; the others are listed
 * as invalid.
 *
 * The registry follows the directories on access: each read checks the files' modification times and sizes
 * and re-reads the documents when anything changed, so an edited, added or removed file takes effect with the
 * next request. Only [stateDir] is written to, and [projectDir] may be absent or read-only. All methods are
 * thread-safe.
 */
class ViewRegistry(
    private val projectDir: Path?,
    private val stateDir: Path,
) {
    companion object {
        private val log = LoggerFactory.getLogger(ViewRegistry::class.java)
    }

    private data class Stamp(val modified: FileTime, val size: Long)

    private var stamps: Map<Path, Stamp>? = null
    private var active: Map<String, ViewEntry> = emptyMap()
    private var invalid: List<ViewEntry> = emptyList()

    /** The applicable view of each name, valid or not, sorted by name; plus the invalid duplicates. */
    @Synchronized
    fun entries(): List<ViewEntry> {
        refresh()
        return (active.values + invalid).sortedWith(compareBy({ it.name }, { it.source }, { it.file.name }))
    }

    /** The applicable view named [name], or `null`. */
    @Synchronized
    fun find(name: String): ViewEntry? {
        refresh()
        return active[name]
    }

    /** The names of the applicable views, sorted. */
    @Synchronized
    fun names(): List<String> {
        refresh()
        return active.keys.sorted()
    }

    /**
     * Parses [json] and stores it as `<state dir>/<name>.json`, replacing a state view of the same name.
     * Fails with [ViewDocumentException] when the document is invalid; nothing is written then.
     */
    @Synchronized
    fun define(json: String): ViewEntry {
        val document = ViewDocumentParser.parse(json)
        Files.createDirectories(stateDir)
        // A state view of this name may live in a file of another name; it is replaced, not duplicated.
        refresh()
        active[document.name]?.takeIf { it.source == ViewSource.STATE && it.file != stateFile(document.name) }
            ?.let { Files.deleteIfExists(it.file) }
        val target = stateFile(document.name)
        val temp = Files.createTempFile(stateDir, ".${document.name}", ".tmp")
        try {
            Files.writeString(temp, json)
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: IOException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        stamps = null
        refresh()
        return active.getValue(document.name)
    }

    /**
     * Deletes the state view named [name] and returns the view that applies afterwards: the project view it
     * shadowed, or `null`. Fails with [IllegalArgumentException] for a project view, which the server does not
     * delete, and with [UnknownViewException] for an unknown name.
     */
    @Synchronized
    fun delete(name: String): ViewEntry? {
        refresh()
        val entry = active[name] ?: throw UnknownViewException(name, active.keys.sorted())
        require(entry.source == ViewSource.STATE) {
            "View '$name' is a project view (${entry.file}); it is changed or removed in the project, not through the server"
        }
        Files.deleteIfExists(entry.file)
        stamps = null
        refresh()
        return active[name]
    }

    private fun stateFile(name: String): Path = stateDir.resolve("$name.json")

    private fun refresh() {
        val current = scan()
        if (current == stamps) return
        stamps = current
        val project = load(projectDirIfDistinct(), ViewSource.PROJECT)
        val state = load(stateDir, ViewSource.STATE)
        val merged = LinkedHashMap<String, ViewEntry>()
        project.first.forEach { merged[it.name] = it }
        for (entry in state.first) {
            val shadows = merged[entry.name]?.source == ViewSource.PROJECT
            merged[entry.name] = if (shadows) entry.copyShadowing() else entry
        }
        active = merged
        invalid = project.second + state.second
    }

    private fun ViewEntry.copyShadowing() = ViewEntry(name, source, file, text, document, problems, shadowsProjectView = true)

    /** [projectDir], unless it is the state directory: then its documents are state views. */
    private fun projectDirIfDistinct(): Path? =
        projectDir?.takeUnless { it.toAbsolutePath().normalize() == stateDir.toAbsolutePath().normalize() }

    private fun scan(): Map<Path, Stamp> =
        listOfNotNull(projectDirIfDistinct(), stateDir).flatMap { files(it) }.associateWith { file ->
            try {
                Stamp(Files.getLastModifiedTime(file), Files.size(file))
            } catch (e: IOException) {
                Stamp(FileTime.fromMillis(0), -1)
            }
        }

    private fun files(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { paths ->
                paths.filter { it.isRegularFile() && it.extension == "json" && !it.name.startsWith(".") }.sorted().toList()
            }
        } catch (e: IOException) {
            log.warn("Cannot list view documents in {}: {}", dir, e.message)
            emptyList()
        }
    }

    /** The entries of [dir]: the first of each name, and the invalid rest. */
    private fun load(dir: Path?, source: ViewSource): Pair<List<ViewEntry>, List<ViewEntry>> {
        if (dir == null) return emptyList<ViewEntry>() to emptyList()
        val byName = LinkedHashMap<String, ViewEntry>()
        val invalid = mutableListOf<ViewEntry>()
        for (file in files(dir)) {
            val entry = read(file, source)
            val first = byName[entry.name]
            if (first == null) {
                byName[entry.name] = entry
            } else {
                invalid += ViewEntry(
                    entry.name, source, file, entry.text, null,
                    listOf("the view name '${entry.name}' is already defined in ${first.file.name}"),
                )
            }
        }
        return byName.values.toList() to invalid
    }

    private fun read(file: Path, source: ViewSource): ViewEntry {
        val text = try {
            Files.readString(file)
        } catch (e: IOException) {
            return ViewEntry(file.nameWithoutExtension, source, file, "", null, listOf("cannot be read: ${e.message}"))
        }
        return try {
            val document = ViewDocumentParser.parse(text)
            ViewEntry(document.name, source, file, text, document, emptyList())
        } catch (e: ViewDocumentException) {
            ViewEntry(file.nameWithoutExtension, source, file, text, null, e.problems)
        }
    }
}
