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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** Verifies [RuntimeFile] and the one-instance-per-workspace check of [InstanceGuard]. */
class InstanceGuardTest {

    @TempDir
    lateinit var stateDir: Path

    private val file get() = RuntimeFile.inStateDir(stateDir)

    /** A process that is alive and is not this one. */
    private val otherLivePid = ProcessHandle.current().parent().orElseThrow().pid()

    private fun record(pid: Long, port: Int) =
        file.write(linkedMapOf("url" to "http://localhost:$port/mcp", "port" to port, "pid" to pid, "workspace" to "/ws"))

    @Test
    fun `the runtime file round-trips its content`() {
        record(42, 8765)
        assertThat(file.read()).containsEntry("pid", 42).containsEntry("port", 8765).containsEntry("workspace", "/ws")
        assertThat(Files.list(stateDir).map { it.fileName.toString() }.toList()).containsExactly(RuntimeFile.NAME)
    }

    @Test
    fun `an unreadable runtime file reads as none`() {
        Files.writeString(file.path, "not json")
        assertThat(file.read()).isNull()
    }

    @Test
    fun `a server deletes only the runtime file that names it`() {
        record(42, 8765)
        assertThat(file.deleteIfOwnedBy(7)).isFalse()
        assertThat(file.path).exists()
        assertThat(file.deleteIfOwnedBy(42)).isTrue()
        assertThat(file.path).doesNotExist()
    }

    @Test
    fun `a running server for the workspace refuses the start and names its url`() {
        ServerSocket(0).use { socket ->
            record(otherLivePid, socket.localPort)
            assertThatThrownBy { InstanceGuard().check(stateDir) }
                .isInstanceOf(InstanceAlreadyRunningException::class.java)
                .hasMessageContaining("http://localhost:${socket.localPort}/mcp")
                .hasMessageContaining("pid $otherLivePid")
        }
    }

    @Test
    fun `no runtime file lets the server start`() {
        InstanceGuard().check(stateDir)
    }

    @Test
    fun `a runtime file of a process that is gone is stale`() {
        ServerSocket(0).use { socket ->
            record(otherLivePid, socket.localPort)
            assertThat(InstanceGuard(isAlive = { false }).runningInstance(file)).isNull()
        }
    }

    @Test
    fun `a runtime file whose port does not answer is stale`() {
        record(otherLivePid, 8765)
        assertThat(InstanceGuard(portAnswers = { false }).runningInstance(file)).isNull()
    }

    @Test
    fun `a runtime file that names this process is stale`() {
        // A container restarted on the same state volume runs the server under the same pid again.
        ServerSocket(0).use { socket ->
            record(ProcessHandle.current().pid(), socket.localPort)
            assertThat(InstanceGuard().runningInstance(file)).isNull()
        }
    }

    @Test
    fun `the failure analysis names the running server and what to do`() {
        val cause = InstanceAlreadyRunningException("http://localhost:8765/mcp", 42, "/ws", file.path)
        val analysis = InstanceAlreadyRunningFailureAnalyzer().analyze(cause)!!
        assertThat(analysis.description).contains("/ws", "http://localhost:8765/mcp", "42")
        assertThat(analysis.action).contains("http://localhost:8765/mcp", "pid 42")
    }
}
