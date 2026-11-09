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

import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Event-driven [GraphWatcher] backed by `io.methvin:directory-watcher` (macOS FSEvents / Linux inotify /
 * Windows, via JNA). Opt-in (`hierograph.graph.watch.mode=native`); best on a bare JVM, where it reacts in
 * well under a second. Inside Docker Desktop on macOS/Windows native events do not cross the bind mount,
 * so [PollingGraphWatcher] remains the default there.
 *
 * Only the module output directories are watched natively. The manifest file is checked with a cheap
 * `stat` on the flush tick instead — that keeps the watcher off the `.hierograph/` directory, so the
 * Parquet snapshots the server writes there never feed events back to it.
 *
 * Raw events are coalesced: a module is reported only once no event has arrived for it for [settleMs], so
 * a burst of compiler writes becomes a single rescan.
 *
 * A native watch can only be registered on an existing directory. The flush tick therefore also checks
 * which module directories exist: when one appears (a module compiled for the first time) or disappears
 * (`mvn clean`), the module is reported as changed and the native watch is re-registered on the directories
 * that exist now.
 */
class NativeGraphWatcher(private val settleMs: Long) : GraphWatcher {

    private val log = LoggerFactory.getLogger(NativeGraphWatcher::class.java)

    private var watcher: DirectoryWatcher? = null
    private var watchExec: ExecutorService? = null
    private var flushExec: ScheduledExecutorService? = null

    private val lock = Any()
    private val pending = HashSet<String>()
    private var lastEventAt = 0L

    private lateinit var moduleByDir: List<Pair<Path, String>> // (normalized dir, gav), longest path first
    private lateinit var manifestPath: Path
    private var manifestSig = 0L
    private var watchedRoots: Set<Path> = emptySet() // the module directories the native watch is registered on

    override fun start(targets: WatchTargets, onChange: (Set<String>) -> Unit, onManifestChange: () -> Unit) {
        manifestPath = targets.manifest.toAbsolutePath().normalize()
        manifestSig = fileSignature(manifestPath)
        moduleByDir = targets.modules.entries
            .map { it.value.toAbsolutePath().normalize() to it.key }
            .sortedByDescending { it.first.toString().length }

        watchExec = Executors.newSingleThreadExecutor { r -> Thread(r, "hierograph-native-watcher").apply { isDaemon = true } }
        register(existingRoots())

        val tick = maxOf(50L, settleMs / 2)
        val fe = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hierograph-watch-flush").apply { isDaemon = true } }
        flushExec = fe
        fe.scheduleWithFixedDelay(
            { runCatching { flush(onChange, onManifestChange) }.onFailure { log.warn("Watch flush failed", it) } },
            tick, tick, TimeUnit.MILLISECONDS,
        )
        log.info("Native watcher started on {} of {} director(ies)", watchedRoots.size, moduleByDir.size)
    }

    private fun existingRoots(): Set<Path> = moduleByDir.map { it.first }.filterTo(LinkedHashSet()) { Files.isDirectory(it) }

    /** (Re-)registers the native watch on [roots], replacing any previous registration. */
    private fun register(roots: Set<Path>) {
        runCatching { watcher?.close() }
        watcher = null
        watchedRoots = roots
        val exec = watchExec ?: return
        if (roots.isEmpty()) return
        val w = DirectoryWatcher.builder()
            .paths(roots.toList())
            .fileHashing(false) // we coalesce by settle; avoid hashing every class file
            .listener { event -> onRawEvent(event) }
            .build()
        watcher = w
        w.watchAsync(exec)
    }

    /** Reports modules whose directory appeared or disappeared since the last tick, and re-registers. */
    private fun checkRoots() {
        val existing = existingRoots()
        if (existing == watchedRoots) return
        val flipped = (existing - watchedRoots) + (watchedRoots - existing)
        log.info("Module output director(ies) appeared or disappeared: {}; re-registering the native watch", flipped)
        register(existing)
        val gavs = moduleByDir.filter { it.first in flipped }.map { it.second }
        synchronized(lock) {
            pending += gavs
            lastEventAt = System.currentTimeMillis()
        }
    }

    private fun onRawEvent(event: DirectoryChangeEvent) {
        val path = event.path()?.toAbsolutePath()?.normalize() ?: return
        val gav = moduleByDir.firstOrNull { path.startsWith(it.first) }?.second ?: return
        synchronized(lock) {
            pending += gav
            lastEventAt = System.currentTimeMillis()
        }
    }

    private fun flush(onChange: (Set<String>) -> Unit, onManifestChange: () -> Unit) {
        val sig = fileSignature(manifestPath)
        if (sig != manifestSig) {
            manifestSig = sig
            onManifestChange()
            return
        }
        checkRoots()
        val drained: Set<String> = synchronized(lock) {
            if (pending.isEmpty() || System.currentTimeMillis() - lastEventAt < settleMs) emptySet()
            else HashSet(pending).also { pending.clear() }
        }
        if (drained.isNotEmpty()) onChange(drained)
    }

    private fun fileSignature(file: Path): Long {
        val attrs = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull() ?: return 0L
        return attrs.size() * 31L + attrs.lastModifiedTime().toMillis()
    }

    override fun close() {
        flushExec?.shutdownNow()
        flushExec = null
        runCatching { watcher?.close() }
        watcher = null
        watchExec?.shutdownNow()
        watchExec = null
    }
}
