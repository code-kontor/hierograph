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
package io.hierograph.mcp.server.instance

import io.hierograph.mcp.server.web.WebUiConfiguration
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.time.Instant

/**
 * The running server's description of itself: who it is ([identity]) and what it serves right now ([info]).
 * `server_info` returns [info]; the [runtime file][RuntimeFile] holds [identity].
 *
 * Once the application is ready, the instance writes the runtime file and logs one ready line with the MCP
 * URL; on shutdown it deletes the runtime file again. Both happen only when a web server was started, so a
 * test that boots the application context without one writes nothing.
 *
 * Settings:
 * - `hierograph.server.url`: the MCP URL to advertise, when clients reach the server under another address than
 *   `http://localhost:<port>/mcp`, for example through a container's port mapping. The web UI's URL
 *   (`ui_url`) is derived from it.
 * - `hierograph.server.workspace`: the workspace to report, when the server sees the project under another
 *   path than the client does, for example `/workspace` in a container.
 * - `hierograph.server.runtime-file.enabled` (default `true`): write the runtime file.
 */
@Component
class ServerInstance(
    private val environment: Environment,
    private val graphService: HierarchicalGraphService,
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val gitProperties: ObjectProvider<GitProperties>,
) {

    companion object {
        private val log = LoggerFactory.getLogger(ServerInstance::class.java)
    }

    val pid: Long = ProcessHandle.current().pid()

    /** When the JVM started, which is when the server process started. */
    val startedAt: Instant = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime)

    /** The port the web server listens on; `null` until it is started, and when the context has none. */
    val port: Int? get() = startedPort

    @Volatile
    private var startedPort: Int? = null

    /** The MCP URL clients connect to: `hierograph.server.url`, else `http://localhost:<port><endpoint>`. */
    val url: String?
        get() = environment.getProperty("hierograph.server.url")?.trim()?.takeIf { it.isNotEmpty() }
            ?: port?.let { "http://localhost:$it$mcpEndpoint" }

    /**
     * The web UI's URL: the address of [url] with `/ui/` instead of the MCP endpoint, so it follows an
     * advertised `hierograph.server.url` (e.g. a container's port mapping).
     */
    val uiUrl: String?
        get() = url?.let { it.removeSuffix(mcpEndpoint).trimEnd('/') + WebUiConfiguration.UI_PATH + "/" }

    private val mcpEndpoint: String
        get() = environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp")

    /** The workspace the server serves: `hierograph.server.workspace`, else the build root. */
    val workspace: String
        get() = environment.getProperty("hierograph.server.workspace")?.trim()?.takeIf { it.isNotEmpty() }
            ?: graphService.buildRootDirectory.toString()

    val runtimeFile: RuntimeFile get() = RuntimeFile.inStateDir(graphService.stateDirectory)

    private val runtimeFileEnabled: Boolean
        get() = environment.getProperty("hierograph.server.runtime-file.enabled", Boolean::class.java, true)

    /** Whether this server wrote the runtime file and has not deleted it since. */
    @Volatile
    private var wroteRuntimeFile = false

    /** The server's version, from the build info baked into the jar; `null` when it runs without, e.g. in an IDE. */
    val version: String?
        get() = buildProperties.ifAvailable?.version ?: ServerInstance::class.java.`package`?.implementationVersion

    /** The commit the server was built from, from the git info baked into the jar; `null` without it. */
    val commit: Commit?
        get() = gitProperties.ifAvailable?.let { Commit(it.shortCommitId, it.get("dirty")?.toBoolean(), it.commitTime?.toString()) }

    /** When the server was built, from the build info; `null` without it. */
    val buildTime: String?
        get() = buildProperties.ifAvailable?.time?.toString()

    /** Where the server writes its state. */
    val stateDir: String
        get() = graphService.stateDirectory.toString()

    /** A commit: its short [id], whether the tree was [dirty], and its [time]. */
    data class Commit(val id: String?, val dirty: Boolean?, val time: String?)

    /**
     * Who the server is, as the runtime file records it: what stays the same while the server runs. `version`
     * comes from the build info and `commit` from the git info baked into the jar; both are `null` when the
     * server runs from classes without them, for example in an IDE.
     */
    fun identity(): Map<String, Any?> = linkedMapOf(
        "url" to url,
        "ui_url" to uiUrl,
        "port" to port,
        "pid" to pid,
        "version" to version,
        "commit" to commit?.let { linkedMapOf("id" to it.id, "dirty" to it.dirty, "time" to it.time) },
        "build_time" to buildTime,
        "started_at" to startedAt.toString(),
        "workspace" to workspace,
        "state_dir" to stateDir,
        "source" to graphService.sourceSettings(),
    )

    /**
     * Everything `server_info` returns: the [identity], and what the server serves right now — the
     * declared and scanned artifacts, the graph and detail snapshot in service, the build state, and the
     * runtime file.
     */
    fun info(): Map<String, Any?> = LinkedHashMap(identity()).apply {
        put("artifacts", graphService.artifactStatus())
        put("snapshot", graphService.snapshotStatus())
        graphService.buildState()?.let { put("build_state", it) }
        graphService.updateState()?.let { put("graph_update", it) }
        put("runtime_file", if (wroteRuntimeFile) runtimeFile.path.toString() else null)
    }

    @EventListener
    fun onWebServerInitialized(event: WebServerInitializedEvent) {
        // The management server, if any, has its own namespace; the MCP endpoint is on the main one.
        if (event.applicationContext.serverNamespace == null) serverStarted(event.webServer.port)
    }

    @EventListener
    fun onReady(event: ApplicationReadyEvent) = ready()

    @EventListener
    fun onClose(event: ContextClosedEvent) = closed()

    internal fun serverStarted(port: Int) {
        startedPort = port
    }

    /** Writes the runtime file and logs the ready line, once the web server is started. */
    internal fun ready() {
        if (port == null) return
        if (runtimeFileEnabled) {
            try {
                runtimeFile.write(identity())
                wroteRuntimeFile = true
            } catch (e: Exception) {
                log.warn("Could not write the runtime file {}: {}", runtimeFile.path, e.message)
            }
        }
        log.info("Hierograph MCP ready: {} ({})", url, readySummary())
    }

    /** Deletes the runtime file this server wrote, unless another server has replaced it since. */
    internal fun closed() {
        if (!wroteRuntimeFile) return
        runCatching { runtimeFile.deleteIfOwnedBy(pid) }
            .onFailure { log.warn("Could not delete the runtime file {}: {}", runtimeFile.path, it.message) }
        wroteRuntimeFile = false
    }

    /** `workspace …, 24 of 26 artifacts, 9017 nodes` — what the ready line says the server serves. */
    internal fun readySummary(): String {
        val parts = mutableListOf("workspace $workspace")
        graphService.artifacts()?.let { parts += "${it.scanned} of ${it.declared} artifacts" }
        parts += graphService.nodeCounts()?.let { "${it.total} nodes" } ?: "no graph loaded"
        return parts.joinToString(", ")
    }
}
