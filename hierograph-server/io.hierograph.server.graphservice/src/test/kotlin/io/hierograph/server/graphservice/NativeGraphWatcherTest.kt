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
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real io.methvin backend; gated to OSes where file events are delivered (not Windows CI). */
@EnabledOnOs(OS.MAC, OS.LINUX)
class NativeGraphWatcherTest {

    private val gav = "com.example:app:1.0"

    @Test
    fun `reports a module on a native file event and the manifest via stat`(@TempDir tmp: Path) {
        val moduleDir = Files.createDirectories(tmp.resolve("app/target/classes/com/example"))
        Files.write(moduleDir.resolve("Existing.class"), byteArrayOf(1, 2, 3))
        val manifest = Files.writeString(tmp.resolve("manifest.json"), "{}")

        val changed = ConcurrentHashMap.newKeySet<String>()
        val manifestChanges = AtomicInteger()
        val watcher = NativeGraphWatcher(settleMs = 150)
        try {
            watcher.start(
                WatchTargets(manifest, mapOf(gav to tmp.resolve("app/target/classes"))),
                onChange = { changed += it },
                onManifestChange = { manifestChanges.incrementAndGet() },
            )

            Files.write(moduleDir.resolve("New.class"), byteArrayOf(4, 5, 6, 7))
            await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(changed).contains(gav) }

            Files.writeString(manifest, """{"schemaVersion":1}""")
            await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(manifestChanges.get()).isGreaterThan(0) }
        } finally {
            watcher.close()
        }
    }

    @Test
    fun `reports a module whose output directory appears and disappears`(@TempDir tmp: Path) {
        val moduleDir = tmp.resolve("app/target/classes")
        val manifest = Files.writeString(tmp.resolve("manifest.json"), "{}")

        val changed = ConcurrentHashMap.newKeySet<String>()
        val watcher = NativeGraphWatcher(settleMs = 150)
        try {
            watcher.start(WatchTargets(manifest, mapOf(gav to moduleDir)), onChange = { changed += it }, onManifestChange = {})

            // First compile: the directory appears → reported, and the native watch now covers it.
            Files.createDirectories(moduleDir.resolve("com/example"))
            Files.write(moduleDir.resolve("com/example/New.class"), byteArrayOf(1, 2, 3))
            await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(changed).contains(gav) }

            changed.clear()
            Thread.sleep(300) // let the re-registered watch settle before the next event
            Files.write(moduleDir.resolve("com/example/Other.class"), byteArrayOf(4, 5, 6))
            await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(changed).contains(gav) }

            // `mvn clean`: the directory disappears → reported again.
            changed.clear()
            Files.walk(moduleDir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(changed).contains(gav) }
        } finally {
            watcher.close()
        }
    }
}

