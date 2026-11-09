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
 * Tracks whether the manifest still reflects the build's build files and, in [Mode.REGENERATE], runs the
 * build that rewrites it. The manifest records each build file with the hash of the content it was merged
 * from; a build file whose current content differs is **stale** ([ManifestGraphSource.staleBuildFiles]). A
 * stale build file means the module set or a classpath may have changed without the manifest knowing.
 *
 * [refresh] re-evaluates the state from [inspect] (the current manifest's stale build files); the service
 * calls it whenever the manifest is (re)applied and whenever the watcher reports edited build files.
 *
 * Regeneration runs a command in the build root on its own thread, one run at a time: a request during a run
 * schedules exactly one follow-up run, because the running build may have read the build files before the edit.
 * The monitor knows no build tool: the command is [commandOverride] when configured, otherwise the one the
 * manifest names ([Inspection.command]), whose `./`-relative arguments resolve against the build root. With
 * neither, and for a relocated manifest (a container without the build host's tools), the monitor behaves as in
 * [Mode.NOTIFY].
 */
class BuildFileMonitor(
    private val mode: Mode,
    private val commandOverride: List<String>?,
    private val timeoutMs: Long,
    private val stateDir: Path,
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

    /**
     * The current manifest as seen by the monitor: its stale build files, the local build root, whether it was
     * relocated, the producing build tool (informational) and the regeneration command it names.
     */
    data class Inspection(
        val staleFiles: List<Path>,
        val buildRoot: Path,
        val relocated: Boolean,
        val buildTool: String? = null,
        val command: List<String>? = null,
    )

    /** What `reload_graph` and `graph_overview` report as `build_state`. */
    data class State(
        val status: Status,
        val changedFiles: List<Path> = emptyList(),
        val message: String? = null,
        val buildTool: String? = null,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "status" to status.value,
            "changed_files" to changedFiles.map { it.toString() },
        ).apply {
            if (buildTool != null) put("build_tool", buildTool)
            if (message != null) put("message", message)
        }
    }

    private val log = LoggerFactory.getLogger(BuildFileMonitor::class.java)
    private val lock = Any()
    private var running = false
    private var followUp = false
    private var failure: String? = null
    private var warnedRelocated = false
    private var warnedNoCommand = false

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
        val command = command(inspection)
        val regenerate = mode == Mode.REGENERATE && !inspection.relocated && command != null
        if (mode == Mode.REGENERATE && inspection.relocated && !warnedRelocated) {
            warnedRelocated = true
            log.warn("Manifest is relocated (recorded under another build root); build files are reported, not regenerated")
        }
        if (mode == Mode.REGENERATE && command == null && !warnedNoCommand) {
            warnedNoCommand = true
            log.warn(
                "Neither hierograph.graph.regenerate.command nor the manifest names a regeneration command; " +
                    "build files are reported, not regenerated",
            )
        }
        val start = synchronized(lock) {
            if (stale.isEmpty()) failure = null
            val startRun = triggerRegeneration && regenerate && stale.isNotEmpty()
            if (startRun && running) followUp = true
            val newRun = startRun && !running
            if (newRun) running = true
            val tool = inspection.buildTool
            state = when {
                running -> State(Status.REGENERATING, stale, buildTool = tool)
                stale.isEmpty() -> State(Status.CURRENT, buildTool = tool)
                failure != null -> State(Status.REGENERATION_FAILED, stale, failure, tool)
                else -> State(Status.STALE, stale, buildTool = tool)
            }
            newRun
        }
        if (start) {
            log.info("Build files changed: {}; regenerating the manifest", stale)
            runner.submit { runUntilSettled(command!!, inspection.buildRoot) }
        } else if (stale.isNotEmpty() && !regenerate) {
            log.info("Build files changed: {}; the graph is stale until the build runs", stale)
        }
    }

    /** [commandOverride] or the manifest's command, with `./`-relative arguments resolved against the build root. */
    private fun command(inspection: Inspection): List<String>? {
        val cmd = commandOverride ?: inspection.command ?: return null
        return cmd.map { if (it.startsWith("./")) inspection.buildRoot.resolve(it.removePrefix("./")).toString() else it }
    }

    private fun runUntilSettled(cmd: List<String>, buildRoot: Path) {
        while (true) {
            val result = runCatching { regenerate(cmd, buildRoot) }.getOrElse { it.message ?: it.javaClass.simpleName }
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
    private fun regenerate(cmd: List<String>, buildRoot: Path): String? {
        val logFile = stateDir.resolve("regenerate.log")
        Files.createDirectories(logFile.parent)
        log.info("Running {} in {}", cmd.joinToString(" "), buildRoot)
        // Requests until now are covered by this run, which reads the build files after starting.
        synchronized(lock) { followUp = false }
        val p = ProcessBuilder(cmd).directory(buildRoot.toFile()).redirectErrorStream(true).redirectOutput(logFile.toFile()).start()
        process = p
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach { it.destroyForcibly() }
                p.destroyForcibly()
                return "`${cmd.joinToString(" ")}` timed out after ${timeoutMs}ms; see $logFile"
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
        return "`${cmd.joinToString(" ")}` failed with exit code ${p.exitValue()}; last lines of $logFile:\n" + tail.joinToString("\n")
    }

    /**
     * Generates a missing manifest **synchronously**, before anything is loaded: with [Mode.REGENERATE] and a
     * configured [commandOverride] (there is no manifest yet to name a command). Returns `true` when the command
     * succeeded; a failure is logged and kept as the [state], so `build_state` explains why no graph loaded.
     */
    fun generateMissingManifest(buildRoot: Path): Boolean {
        val cmd = commandOverride
        if (mode != Mode.REGENERATE || cmd == null) return false
        log.info("No manifest yet; generating it")
        synchronized(lock) {
            if (running) return false
            running = true
            state = State(Status.REGENERATING)
        }
        val result = runCatching { regenerate(cmd, buildRoot) }.getOrElse { it.message ?: it.javaClass.simpleName }
        synchronized(lock) {
            running = false
            failure = result
            state = if (result == null) State(Status.CURRENT) else State(Status.REGENERATION_FAILED, message = result)
        }
        return result == null
    }

    override fun close() {
        process?.let { p -> p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        runner.shutdownNow()
    }

    private companion object {
        const val TAIL_LINES = 20
    }
}
