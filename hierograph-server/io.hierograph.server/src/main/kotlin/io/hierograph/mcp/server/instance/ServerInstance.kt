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
 * The running server's description of itself ([identity]), which the [runtime file][RuntimeFile] holds.
 *
 * Once the application is ready, the instance writes the runtime file and logs one ready line with the MCP
 * URL; on shutdown it deletes the runtime file again. Both happen only when a web server was started, so a
 * test that boots the application context without one writes nothing.
 *
 * Settings:
 * - `hierograph.server.url`: the MCP URL to advertise, when clients reach the server under another address than
 *   `http://localhost:<port>/mcp`, for example through a container's port mapping.
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
            ?: port?.let { "http://localhost:$it" + environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp") }

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

    /**
     * Who the server is, as the runtime file records it: what stays the same while the server runs. `version`
     * comes from the build info and `commit` from the git info baked into the jar; both are `null` when the
     * server runs from classes without them, for example in an IDE.
     */
    fun identity(): Map<String, Any?> {
        val build = buildProperties.ifAvailable
        val git = gitProperties.ifAvailable
        return linkedMapOf(
            "url" to url,
            "port" to port,
            "pid" to pid,
            "version" to (build?.version ?: ServerInstance::class.java.`package`?.implementationVersion),
            "commit" to git?.let { linkedMapOf("id" to it.shortCommitId, "dirty" to it.get("dirty")?.toBoolean(), "time" to it.commitTime?.toString()) },
            "build_time" to build?.time?.toString(),
            "started_at" to startedAt.toString(),
            "workspace" to workspace,
            "state_dir" to graphService.stateDirectory.toString(),
            "source" to graphService.sourceSettings(),
        )
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
        graphService.artifactStatus()?.let { parts += "${it["scanned"]} of ${it["declared"]} artifacts" }
        parts += graphService.snapshotStatus()?.let { "${it["total_nodes"]} nodes" } ?: "no graph loaded"
        return parts.joinToString(", ")
    }
}
