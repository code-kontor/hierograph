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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tracks whether the manifest still reflects the reactor's build files and, in [Mode.REGENERATE], runs the
 * build that rewrites it. The manifest records each build file with the hash of the content it was merged
 * from; a build file whose current content differs is **stale** ([ManifestGraphSource.staleBuildFiles]). A
 * stale build file means the module set or a classpath may have changed without the manifest knowing.
 *
 * [refresh] re-evaluates the state from [inspect] (the current manifest's stale build files); the service
 * calls it whenever the manifest is (re)applied and whenever the watcher reports edited build files.
 *
 * Regeneration runs the command in the reactor root on its own thread, one run at a time: a request during a
 * run schedules exactly one follow-up run, because the running build may have read the POMs before the edit.
 * It is never run for a relocated manifest (a container without the build host's Maven and repository); the
 * monitor then behaves as in [Mode.NOTIFY].
 */
class BuildFileMonitor(
    private val mode: Mode,
    private val command: List<String>?,
    private val timeoutMs: Long,
    private val inspect: () -> Inspection?,
) : AutoCloseable {

    /** How the server reacts to stale build files. */
    enum class Mode {
        /** Build files are not watched; the state is always [Status.CURRENT]. */
        OFF,

        /** Stale build files are reported in the [state]; the developer runs the build. */
        NOTIFY,

        /** Stale build files trigger the regeneration command. */
        REGENERATE;

        companion object {
            fun parse(value: String): Mode = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown build-file mode '$value' (expected off, notify or regenerate)")
        }
    }

    enum class Status(val value: String) { CURRENT("current"), STALE("stale"), REGENERATING("regenerating"), REGENERATION_FAILED("regeneration-failed") }

    /** The current manifest as seen by the monitor: its stale build files, the local reactor root, and whether it was relocated. */
    data class Inspection(val staleFiles: List<Path>, val reactorRoot: Path, val relocated: Boolean)

    /** What `reload_graph` and `graph_overview` report as `build_state`. */
    data class State(val status: Status, val changedFiles: List<Path> = emptyList(), val message: String? = null) {
        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "status" to status.value,
            "changed_files" to changedFiles.map { it.toString() },
        ).apply { if (message != null) put("message", message) }
    }

    private val log = LoggerFactory.getLogger(BuildFileMonitor::class.java)
    private val lock = Any()
    private var running = false
    private var followUp = false
    private var failure: String? = null
    private var warnedRelocated = false

    @Volatile
    private var process: Process? = null

    @Volatile
    var state: State = State(Status.CURRENT)
        private set

    /** Whether the watcher should observe build files at all. */
    val watchesBuildFiles: Boolean get() = mode != Mode.OFF

    private val runner = Executors.newSingleThreadExecutor { r -> Thread(r, "hierograph-regenerator").apply { isDaemon = true } }

    /**
     * Re-evaluates the [state] from [inspect]; with [triggerRegeneration], also starts a regeneration when
     * build files are stale and the mode allows it.
     */
    fun refresh(triggerRegeneration: Boolean = true) {
        if (mode == Mode.OFF) return
        val inspection = inspect() ?: return
        val stale = inspection.staleFiles
        val regenerate = mode == Mode.REGENERATE && !inspection.relocated
        if (mode == Mode.REGENERATE && inspection.relocated && !warnedRelocated) {
            warnedRelocated = true
            log.warn("Manifest is relocated (recorded under another reactor root); build files are reported, not regenerated")
        }
        val start = synchronized(lock) {
            if (stale.isEmpty()) failure = null
            val startRun = triggerRegeneration && regenerate && stale.isNotEmpty()
            if (startRun && running) followUp = true
            val newRun = startRun && !running
            if (newRun) running = true
            state = when {
                running -> State(Status.REGENERATING, stale)
                stale.isEmpty() -> State(Status.CURRENT)
                failure != null -> State(Status.REGENERATION_FAILED, stale, failure)
                else -> State(Status.STALE, stale)
            }
            newRun
        }
        if (start) {
            log.info("Build files changed: {}; regenerating the manifest", stale)
            runner.submit { runUntilSettled(inspection.reactorRoot) }
        } else if (stale.isNotEmpty() && !regenerate) {
            log.info("Build files changed: {}; the graph is stale until the build runs", stale)
        }
    }

    private fun runUntilSettled(reactorRoot: Path) {
        while (true) {
            val result = runCatching { regenerate(reactorRoot) }.getOrElse { it.message ?: it.javaClass.simpleName }
            val again = synchronized(lock) {
                failure = result
                if (followUp) {
                    followUp = false
                    true
                } else {
                    running = false
                    false
                }
            }
            if (!again) break
        }
        refresh(triggerRegeneration = false)
    }

    /** Runs the regeneration command once; returns `null` on success or a failure description. */
    private fun regenerate(reactorRoot: Path): String? {
        val cmd = command ?: defaultCommand(reactorRoot)
        val logFile = reactorRoot.resolve(".hierograph/regenerate.log")
        Files.createDirectories(logFile.parent)
        log.info("Running {} in {}", cmd.joinToString(" "), reactorRoot)
        // Requests until now are covered by this run, which reads the build files after starting.
        synchronized(lock) { followUp = false }
        val p = ProcessBuilder(cmd).directory(reactorRoot.toFile()).redirectErrorStream(true).redirectOutput(logFile.toFile()).start()
        process = p
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
                return "regeneration timed out after ${timeoutMs}ms; see $logFile"
            }
        } finally {
            process = null
        }
        if (p.exitValue() == 0) {
            log.info("Regeneration finished")
            return null
        }
        val tail = runCatching { Files.readAllLines(logFile).takeLast(TAIL_LINES) }.getOrDefault(emptyList())
        log.warn("Regeneration failed with exit code {}; see {}", p.exitValue(), logFile)
        return "exit code ${p.exitValue()}; last lines of $logFile:\n" + tail.joinToString("\n")
    }

    override fun close() {
        process?.let { p -> p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        runner.shutdownNow()
    }

    companion object {
        private const val TAIL_LINES = 20

        /**
         * `mvnw` in [reactorRoot] when present, otherwise `mvn` from the `PATH`, running `-q -B process-classes`.
         * `process-classes` rather than a bare `hierograph:create-descriptor`: without a lifecycle phase, Maven 3
         * does not resolve reactor siblings from their output directories.
         */
        fun defaultCommand(reactorRoot: Path): List<String> {
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val wrapper = reactorRoot.resolve(if (windows) "mvnw.cmd" else "mvnw")
            val maven = if (Files.isRegularFile(wrapper)) wrapper.toString() else if (windows) "mvn.cmd" else "mvn"
            return listOf(maven, "-q", "-B", "process-classes")
        }
    }
}
