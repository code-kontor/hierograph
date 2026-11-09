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

import io.hierograph.server.graphservice.BuildFileMonitor.Inspection
import io.hierograph.server.graphservice.BuildFileMonitor.Mode
import io.hierograph.server.graphservice.BuildFileMonitor.Status
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class BuildFileMonitorTest {

    private val pom: Path = Path.of("/ws/billing/billing-core/pom.xml")

    @Test
    fun `notify reports stale build files until the manifest catches up`(@TempDir tmp: Path) {
        var stale = emptyList<Path>()
        BuildFileMonitor(Mode.NOTIFY, commandOverride = null, timeoutMs = 1_000, inspect = { Inspection(stale, tmp, relocated = false) }).use { monitor ->
            monitor.refresh()
            assertThat(monitor.state.status).isEqualTo(Status.CURRENT)

            stale = listOf(pom)
            monitor.refresh()
            assertThat(monitor.state.status).isEqualTo(Status.STALE)
            assertThat(monitor.state.toMap()).containsEntry("changed_files", listOf(pom.toString()))

            stale = emptyList() // a build rewrote the manifest
            monitor.refresh()
            assertThat(monitor.state.status).isEqualTo(Status.CURRENT)
        }
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `regenerate runs the command, and a request during a run schedules exactly one follow-up`(@TempDir tmp: Path) {
        var stale = listOf(pom)
        val command = listOf("sh", "-c", "echo run >> runs.txt; sleep 0.5")
        BuildFileMonitor(Mode.REGENERATE, command, timeoutMs = 10_000, inspect = { Inspection(stale, tmp, relocated = false) }).use { monitor ->
            monitor.refresh()
            assertThat(monitor.state.status).isEqualTo(Status.REGENERATING)
            await().atMost(Duration.ofSeconds(10)).until { Files.exists(tmp.resolve("runs.txt")) }
            repeat(3) { monitor.refresh() } // more edits while the build runs

            await().atMost(Duration.ofSeconds(10)).until { monitor.state.status != Status.REGENERATING }
            assertThat(Files.readAllLines(tmp.resolve("runs.txt"))).hasSize(2)
            // The command did not rewrite the manifest here, so the build files are still stale.
            assertThat(monitor.state.status).isEqualTo(Status.STALE)

            stale = emptyList()
            monitor.refresh()
            assertThat(monitor.state.status).isEqualTo(Status.CURRENT)
        }
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `a failed regeneration is reported with the end of its output`(@TempDir tmp: Path) {
        val command = listOf("sh", "-c", "echo 'Non-parseable POM billing-core/pom.xml'; exit 1")
        BuildFileMonitor(Mode.REGENERATE, command, timeoutMs = 10_000, inspect = { Inspection(listOf(pom), tmp, relocated = false) }).use { monitor ->
            monitor.refresh()
            await().atMost(Duration.ofSeconds(10)).until { monitor.state.status == Status.REGENERATION_FAILED }
            assertThat(monitor.state.message).contains("exit code 1").contains("Non-parseable POM")
        }
    }

    @Test
    fun `a relocated manifest is reported, never regenerated`(@TempDir tmp: Path) {
        val command = listOf("sh", "-c", "touch ran.txt")
        BuildFileMonitor(Mode.REGENERATE, command, timeoutMs = 10_000, inspect = { Inspection(listOf(pom), tmp, relocated = true) }).use { monitor ->
            monitor.refresh()
            assertThat(monitor.state.status).isEqualTo(Status.STALE)
            Thread.sleep(300)
            assertThat(tmp.resolve("ran.txt")).doesNotExist()
        }
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `the manifest's command runs, with relative arguments resolved against the build root`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("regen.sh"), "touch ran-by-manifest.txt\n")
        val inspection = Inspection(listOf(pom), tmp, relocated = false, buildTool = "gradle", command = listOf("sh", "./regen.sh"))
        BuildFileMonitor(Mode.REGENERATE, commandOverride = null, timeoutMs = 10_000, inspect = { inspection }).use { monitor ->
            monitor.refresh()
            assertThat(monitor.state.toMap()).containsEntry("build_tool", "gradle")
            await().atMost(Duration.ofSeconds(10)).until { monitor.state.status != Status.REGENERATING }
            assertThat(tmp.resolve("ran-by-manifest.txt")).exists()
        }
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `a configured command overrides the manifest's`(@TempDir tmp: Path) {
        val inspection = Inspection(listOf(pom), tmp, relocated = false, command = listOf("sh", "-c", "touch ran-by-manifest.txt"))
        BuildFileMonitor(Mode.REGENERATE, listOf("sh", "-c", "touch ran-by-override.txt"), timeoutMs = 10_000, inspect = { inspection }).use { monitor ->
            monitor.refresh()
            await().atMost(Duration.ofSeconds(10)).until { monitor.state.status != Status.REGENERATING }
            assertThat(tmp.resolve("ran-by-override.txt")).exists()
            assertThat(tmp.resolve("ran-by-manifest.txt")).doesNotExist()
        }
    }

    @Test
    fun `without any command, regenerate behaves as notify`(@TempDir tmp: Path) {
        BuildFileMonitor(Mode.REGENERATE, commandOverride = null, timeoutMs = 10_000, inspect = { Inspection(listOf(pom), tmp, relocated = false) }).use { monitor ->
            monitor.refresh()
            assertThat(monitor.state.status).isEqualTo(Status.STALE)
        }
    }
}
