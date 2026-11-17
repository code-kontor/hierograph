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

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A dependency-free [GraphWatcher] that polls each module's class directories on a fixed interval, comparing
 * a cheap per-file `(path, size, mtime)` signature. It works identically on every OS and across a mounted
 * volume (it only `stat`s files), which is why it is the default — notably on Docker Desktop, where native
 * file events do not reach the container.
 *
 * Debounce: a module is reported only once its signature has been **stable for [settleMs]** and differs
 * from the last reported one, so a burst of compiler writes coalesces into a single rescan and a module is
 * never scanned mid-compile. Latency is therefore roughly [intervalMs] + [settleMs].
 */
class PollingGraphWatcher(
    private val intervalMs: Long,
    private val settleMs: Long,
) : GraphWatcher {

    private val log = LoggerFactory.getLogger(PollingGraphWatcher::class.java)

    private var exec: ScheduledExecutorService? = null
    // Set by close(), before the executors are shut down, so a poll they interrupt is known to be a stop, not a failure.
    @Volatile private var closed = false
    // walks the module directories concurrently, for the baseline and every poll
    private var walkers: ExecutorService? = null

    // gav -> signature last reported as changed (the baseline we compare against)
    private val reported = HashMap<String, Long>()
    // gav -> most recent observed signature, and when it last changed (for the settle window)
    private val lastSig = HashMap<String, Long>()
    private val lastChangeAt = HashMap<String, Long>()

    private var reportedManifest = 0L
    private var lastManifestSig = 0L
    private var manifestChangedAt = 0L

    private var buildFiles: BuildFileTracker? = null

    override fun start(
        targets: WatchTargets,
        onChange: (Set<String>) -> Unit,
        onManifestChange: () -> Unit,
        onBuildFilesChange: (Set<Path>) -> Unit,
    ) {
        walkers = signaturePool()
        // Baseline = current state, so only changes after start are reported.
        for ((gav, sig) in signatures(targets.modules)) {
            reported[gav] = sig
            lastSig[gav] = sig
        }
        reportedManifest = fileSignature(targets.manifest)
        lastManifestSig = reportedManifest
        buildFiles = BuildFileTracker(targets.buildFiles, settleMs)

        val e = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hierograph-poll-watcher").apply { isDaemon = true }
        }
        exec = e
        e.scheduleWithFixedDelay(
            { pollOnce(targets, onChange, onManifestChange, onBuildFilesChange) },
            intervalMs, intervalMs, TimeUnit.MILLISECONDS,
        )
    }

    /**
     * One poll, failures logged so the next one still runs. [close] interrupts a poll that is still walking the
     * module directories (re-targeting the watcher after a manifest change does that); such a poll ends quietly.
     */
    private fun pollOnce(
        targets: WatchTargets,
        onChange: (Set<String>) -> Unit,
        onManifestChange: () -> Unit,
        onBuildFilesChange: (Set<Path>) -> Unit,
    ) {
        try {
            poll(targets, onChange, onManifestChange, onBuildFilesChange)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!closed) log.warn("Watch poll interrupted", e)
        } catch (e: Throwable) {
            if (!closed) log.warn("Watch poll failed", e)
        }
    }

    private fun poll(
        targets: WatchTargets,
        onChange: (Set<String>) -> Unit,
        onManifestChange: () -> Unit,
        onBuildFilesChange: (Set<Path>) -> Unit,
    ) {
        val now = System.currentTimeMillis()

        // Manifest file: report once it has settled.
        val mSig = fileSignature(targets.manifest)
        if (mSig != lastManifestSig) {
            lastManifestSig = mSig
            manifestChangedAt = now
        } else if (mSig != reportedManifest && now - manifestChangedAt >= settleMs) {
            reportedManifest = mSig
            // Module baselines are rebuilt when the watcher is restarted with new targets.
            onManifestChange()
            return
        }

        buildFiles?.poll(now)?.takeIf { it.isNotEmpty() }?.let(onBuildFilesChange)

        val changed = HashSet<String>()
        for ((gav, sig) in signatures(targets.modules)) {
            val prev = lastSig[gav]
            if (sig != prev) {
                lastSig[gav] = sig
                lastChangeAt[gav] = now
            } else if (sig != reported[gav] && now - (lastChangeAt[gav] ?: 0L) >= settleMs) {
                reported[gav] = sig
                changed += gav
            }
        }
        if (changed.isNotEmpty() && !closed) onChange(changed)
    }

    /**
     * One signature per module over all of its directories, so a change in any of them reports the module;
     * the modules are walked concurrently.
     */
    private fun signatures(modules: Map<String, List<Path>>): Map<String, Long> =
        modules.keys.zip(outputSignatures(modules.values.toList(), walkers)).toMap()

    private fun fileSignature(file: Path): Long {
        val attrs = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull() ?: return 0L
        return attrs.size() * 31L + attrs.lastModifiedTime().toMillis()
    }

    override fun close() {
        closed = true
        exec?.shutdownNow()
        exec = null
        walkers?.shutdownNow()
        walkers = null
    }
}
