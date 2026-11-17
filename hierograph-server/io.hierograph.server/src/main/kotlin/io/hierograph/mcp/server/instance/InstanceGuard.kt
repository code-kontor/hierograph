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
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.context.ApplicationListener
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path

/**
 * One server per workspace: refuses to start when the [runtime file][RuntimeFile] of the state directory names
 * another server that is still running. Two servers on one workspace would both watch and scan it, unnoticed.
 *
 * Runs when the environment is ready, before the graph is built and before the port is bound, so a refused
 * start costs nothing and names the running server instead of failing with a port conflict. Registered by
 * `main`, so tests that boot the application context are not affected.
 *
 * `hierograph.server.single-instance=false` turns the check off.
 */
class InstanceGuard(
    private val ownPid: Long = ProcessHandle.current().pid(),
    private val isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    private val portAnswers: (Int) -> Boolean = ::localPortAnswers,
) : ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        val env = event.environment
        if (!env.getProperty("hierograph.server.single-instance", Boolean::class.java, true)) return
        val stateDir = HierarchicalGraphService.resolveStateDir(
            env.getProperty("hierograph.graph.stateDir"),
            env.getProperty("hierograph.graph.manifest", ".hierograph/manifest.json"),
        )
        check(stateDir)
    }

    /** Throws [InstanceAlreadyRunningException] when the runtime file in [stateDir] names a running server. */
    fun check(stateDir: Path) {
        val file = RuntimeFile.inStateDir(stateDir)
        val running = runningInstance(file) ?: return
        throw InstanceAlreadyRunningException(
            url = running["url"] as? String,
            pid = (running["pid"] as Number).toLong(),
            workspace = running["workspace"] as? String,
            runtimeFile = file.path,
        )
    }

    /**
     * The content of [file] when it names a running server, else `null`. A server counts as running when its
     * process is alive and its port accepts a connection. A file of a server that is gone, or that names this
     * process (a container restarted with the same pid), is stale and is overwritten once this server is ready.
     */
    fun runningInstance(file: RuntimeFile): Map<String, Any?>? {
        val content = file.read() ?: return null
        val pid = (content["pid"] as? Number)?.toLong() ?: return null
        val port = (content["port"] as? Number)?.toInt() ?: return null
        if (pid == ownPid || !isAlive(pid) || !portAnswers(port)) return null
        return content
    }
}

/** Whether something accepts TCP connections on [port] of the loopback interface. */
internal fun localPortAnswers(port: Int): Boolean =
    runCatching { Socket().use { it.connect(InetSocketAddress("localhost", port), 500) } }.isSuccess

/** Another server is already running for this workspace; see [InstanceGuard]. */
class InstanceAlreadyRunningException(
    val url: String?,
    val pid: Long,
    val workspace: String?,
    val runtimeFile: Path,
) : RuntimeException(
    "Hierograph is already running for workspace ${workspace ?: "?"} at ${url ?: "?"} (pid $pid; see $runtimeFile)"
)

/** Reports a refused start as a description of the running server and what to do, without a stack trace. */
class InstanceAlreadyRunningFailureAnalyzer : AbstractFailureAnalyzer<InstanceAlreadyRunningException>() {
    override fun analyze(rootFailure: Throwable, cause: InstanceAlreadyRunningException): FailureAnalysis =
        FailureAnalysis(
            "Hierograph is already running for workspace ${cause.workspace ?: "?"}:\n\n" +
                    "    url:  ${cause.url ?: "?"}\n" +
                    "    pid:  ${cause.pid}\n" +
                    "    file: ${cause.runtimeFile}\n\n" +
                    "Two servers would watch and scan the same workspace.",
            "Connect the MCP client to the running server at ${cause.url ?: "the url in ${cause.runtimeFile}"}, " +
                    "or stop it (pid ${cause.pid}) before starting a new one.",
            cause,
        )
}
