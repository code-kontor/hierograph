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

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class PollingGraphWatcherTest {

    private val gav = "com.example:app:1.0"

    @Test
    fun `reports a module once its output settles, and not before start`(@TempDir tmp: Path) {
        val moduleDir = Files.createDirectories(tmp.resolve("app/target/classes/com/example"))
        Files.write(moduleDir.resolve("Existing.class"), byteArrayOf(1, 2, 3))
        val manifest = Files.writeString(tmp.resolve("manifest.json"), "{}")

        val changed = ConcurrentHashMap.newKeySet<String>()
        val manifestChanges = AtomicInteger()
        val watcher = PollingGraphWatcher(intervalMs = 50, settleMs = 60)
        try {
            watcher.start(
                WatchTargets(manifest, mapOf(gav to tmp.resolve("app/target/classes"))),
                onChange = { changed += it },
                onManifestChange = { manifestChanges.incrementAndGet() },
            )

            // Nothing is reported for the pre-existing baseline.
            Thread.sleep(200)
            assertThat(changed).isEmpty()

            // A new class file → the module is reported once it settles.
            Files.write(moduleDir.resolve("New.class"), byteArrayOf(4, 5, 6, 7))
            await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(changed).containsExactly(gav) }

            // Touching the manifest fires the manifest-change signal.
            Files.writeString(manifest, """{"schemaVersion":1}""")
            await().atMost(Duration.ofSeconds(5)).untilAsserted { assertThat(manifestChanges.get()).isGreaterThan(0) }
        } finally {
            watcher.close()
        }
    }
}
