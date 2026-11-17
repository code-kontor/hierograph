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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `reload_graph` runs on the graph updater, after the update it is running, and a reload arriving while another
 * one is queued or running joins it instead of scanning again (#37).
 */
class HierarchicalGraphServiceUpdateTest {

    /** A manifest-mode service over this module's own classes, built without Spring. */
    private fun service(tmp: Path): HierarchicalGraphService {
        val classes = Path.of("target/classes").toAbsolutePath()
        assertThat(classes).`as`("module is compiled before the test runs").isDirectory()
        val manifest = Files.writeString(
            tmp.resolve("manifest.json"),
            """
            {
              "schemaVersion": 2, "generator": "test", "generatedAt": "2026-01-01T00:00:00Z",
              "reactorRoot": "${tmp.toUri().path}",
              "modules": [
                { "gav": "io.hierograph:test-graphservice:0", "name": "graphservice",
                  "mainOutputDirs": ["${classes.toUri().path}"], "testOutputDirs": [],
                  "sourceRoots": [], "testSourceRoots": [], "classpath": [], "testClasspath": [] }
              ]
            }
            """.trimIndent(),
        )
        return HierarchicalGraphService().apply {
            set("graphSource", "manifest")
            set("manifestFile", manifest.toString())
            set("stateDirSetting", tmp.resolve("state").toString())
            set("watchMode", "off")
            set("buildFilesMode", "off")
            set("graphDumpFile", tmp.resolve("dump.txt").toString())
        }
    }

    private fun HierarchicalGraphService.set(field: String, value: Any) {
        HierarchicalGraphService::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
    }

    private val HierarchicalGraphService.updater: ScheduledExecutorService
        get() = HierarchicalGraphService::class.java.getDeclaredField("updateExecutor")
            .apply { isAccessible = true }.get(this) as ScheduledExecutorService

    @Test
    fun `a reload waits for the updater, and a second one joins it instead of scanning again`(@TempDir tmp: Path) {
        val service = service(tmp)
        val logger = LoggerFactory.getLogger(HierarchicalGraphService::class.java) as Logger
        val log = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(log)
        try {
            assertThat(service.updateState()).isEqualTo(HierarchicalGraphService.IDLE)

            // The updater is busy, as with a manifest apply: both reloads have to wait for it.
            val release = CountDownLatch(1)
            service.updater.submit { release.await(10, TimeUnit.SECONDS) }
            val first = CompletableFuture.supplyAsync { service.reload() }
            await().atMost(Duration.ofSeconds(5)).until { service.updateState() == HierarchicalGraphService.PENDING }
            val second = CompletableFuture.supplyAsync { service.reload() }
            await().atMost(Duration.ofSeconds(5)).until { log.list.any { "joins the reload" in it.formattedMessage } }
            assertThat(first).isNotDone()
            release.countDown()

            val a = first.get(30, TimeUnit.SECONDS)
            val b = second.get(30, TimeUnit.SECONDS)
            assertThat(a).containsEntry("status", "reloaded").doesNotContainKey("joined")
            assertThat(b).containsEntry("status", "reloaded").containsEntry("joined", HierarchicalGraphService.RELOAD)
            assertThat(b["data_hash"]).isEqualTo(a["data_hash"])
            assertThat(log.list.count { it.formattedMessage.startsWith("Reloading hierarchical graph") }).isEqualTo(1)
            assertThat(service.updateState()).isEqualTo(HierarchicalGraphService.IDLE)

            // Once it is done, the next reload scans again.
            assertThat(service.reload()).doesNotContainKey("joined")
            assertThat(log.list.count { it.formattedMessage.startsWith("Reloading hierarchical graph") }).isEqualTo(2)
        } finally {
            logger.detachAppender(log)
            service.shutdown()
        }
    }
}
