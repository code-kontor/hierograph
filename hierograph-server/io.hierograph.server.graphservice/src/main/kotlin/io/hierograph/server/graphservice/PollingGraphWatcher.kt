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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A dependency-free [GraphWatcher] that polls each module's class directory on a fixed interval, comparing
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

    // gav -> signature last reported as changed (the baseline we compare against)
    private val reported = HashMap<String, Long>()
    // gav -> most recent observed signature, and when it last changed (for the settle window)
    private val lastSig = HashMap<String, Long>()
    private val lastChangeAt = HashMap<String, Long>()

    private var reportedManifest = 0L
    private var lastManifestSig = 0L
    private var manifestChangedAt = 0L

    override fun start(targets: WatchTargets, onChange: (Set<String>) -> Unit, onManifestChange: () -> Unit) {
        // Baseline = current state, so only changes after start are reported.
        for ((gav, dir) in targets.modules) {
            val sig = signature(dir)
            reported[gav] = sig
            lastSig[gav] = sig
        }
        reportedManifest = fileSignature(targets.manifest)
        lastManifestSig = reportedManifest

        val e = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hierograph-poll-watcher").apply { isDaemon = true }
        }
        exec = e
        e.scheduleWithFixedDelay(
            { runCatching { poll(targets, onChange, onManifestChange) }.onFailure { log.warn("Watch poll failed", it) } },
            intervalMs, intervalMs, TimeUnit.MILLISECONDS,
        )
    }

    private fun poll(targets: WatchTargets, onChange: (Set<String>) -> Unit, onManifestChange: () -> Unit) {
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

        val changed = HashSet<String>()
        for ((gav, dir) in targets.modules) {
            val sig = signature(dir)
            val prev = lastSig[gav]
            if (sig != prev) {
                lastSig[gav] = sig
                lastChangeAt[gav] = now
            } else if (sig != reported[gav] && now - (lastChangeAt[gav] ?: 0L) >= settleMs) {
                reported[gav] = sig
                changed += gav
            }
        }
        if (changed.isNotEmpty()) onChange(changed)
    }

    private fun signature(dir: Path): Long = outputSignature(dir)

    private fun fileSignature(file: Path): Long {
        val attrs = runCatching { Files.readAttributes(file, BasicFileAttributes::class.java) }.getOrNull() ?: return 0L
        return attrs.size() * 31L + attrs.lastModifiedTime().toMillis()
    }

    override fun close() {
        exec?.shutdownNow()
        exec = null
    }
}
