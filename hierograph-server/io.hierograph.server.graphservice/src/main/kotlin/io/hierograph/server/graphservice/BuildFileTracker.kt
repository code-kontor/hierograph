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
package io.hierograph.server.graphservice

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Detects edits to the reactor's build files by polling a cheap `(size, mtime)` signature per file. Both
 * watchers use it on their regular tick: a reactor has tens to hundreds of build files, so polling them is
 * cheap and behaves the same on every OS and across a mounted volume, whereas native events on module
 * directories would also deliver every source-file save.
 *
 * A rename or a version bump touches many POMs at once, so changes are collected until **no** build file has
 * changed for [settleMs], and then reported together, once. A file edited and reverted within the burst is
 * not reported.
 */
internal class BuildFileTracker(files: Set<Path>, private val settleMs: Long) {

    private val reported = files.associateWithTo(HashMap()) { signature(it) }
    private val last = HashMap(reported)
    private val pending = LinkedHashSet<Path>()
    private var lastChangeAt = 0L

    /** Returns the build files whose edits have settled since the last report, or an empty set. */
    fun poll(now: Long): Set<Path> {
        for (file in last.keys) {
            val sig = signature(file)
            if (sig != last[file]) {
                last[file] = sig
                pending.add(file)
                lastChangeAt = now
            }
        }
        if (pending.isEmpty() || now - lastChangeAt < settleMs) return emptySet()
        val changed = pending.filterTo(LinkedHashSet()) { last[it] != reported[it] }
        changed.forEach { reported[it] = last.getValue(it) }
        pending.clear()
        return changed
    }

    private fun signature(file: Path): Long {
        val attrs = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull() ?: return 0L
        return attrs.size() * 31L + attrs.lastModifiedTime().toMillis()
    }
}
