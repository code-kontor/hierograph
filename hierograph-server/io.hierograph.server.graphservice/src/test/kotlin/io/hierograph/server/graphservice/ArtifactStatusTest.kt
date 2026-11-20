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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The artifact status in manifest mode: an output the build has no sources for is never written, so it isn't
 * reported as not compiled, nor counted as declared (#46).
 */
class ArtifactStatusTest {

    @Test
    fun `an output without sources is neither declared nor not compiled, until sources are added`(@TempDir tmp: Path) {
        val classes = Path.of("target/classes").toAbsolutePath()
        assertThat(classes).`as`("module is compiled before the test runs").isDirectory()
        // `app`: compiled main classes, tests written but not compiled yet.
        val appMain = sourceRoot(tmp, "app/src/main/java", "App.java")
        val appTest = sourceRoot(tmp, "app/src/test/java", "AppTest.java")
        // `lib`: compiled, no tests at all (a test root the build names that doesn't exist).
        val libTest = tmp.resolve("lib/src/test/java")
        // `itest`: tests only; its main root holds nothing but a hidden placeholder.
        val itestMain = sourceRoot(tmp, "itest/src/main/java", ".gitkeep")
        val itestTest = sourceRoot(tmp, "itest/src/test/java", "ItTest.java")
        val service = service(
            tmp,
            module(tmp, "app", classes, appMain, appTest),
            module(tmp, "lib", classes, appMain, libTest),
            module(tmp, "itest", tmp.resolve("itest/target/classes"), itestMain, itestTest),
        )
        try {
            assertThat(service.reload()).containsEntry("status", "reloaded")

            val status = service.artifacts()!!
            assertThat(status.notCompiled.map { it.artifact })
                .containsExactly("io.hierograph:app:0:test", "io.hierograph:itest:0:test")
            assertThat(status.scanned).isEqualTo(2)
            assertThat(status.declared).isEqualTo(4)

            // A first test in `lib`: its tests are expected now.
            sourceRoot(tmp, "lib/src/test/java", "LibTest.java")
            val added = service.artifacts()!!
            assertThat(added.notCompiled.map { it.artifact }).contains("io.hierograph:lib:0:test")
            assertThat(added.declared).isEqualTo(5)
        } finally {
            service.shutdown()
        }
    }

    private fun sourceRoot(tmp: Path, root: String, file: String): Path {
        val dir = Files.createDirectories(tmp.resolve(root))
        Files.writeString(dir.resolve(file), "")
        return dir
    }

    private fun module(tmp: Path, name: String, main: Path, mainSources: Path, testSources: Path): String {
        val test = tmp.resolve("$name/target/test-classes")
        return """
            { "gav": "io.hierograph:$name:0", "name": "$name",
              "mainOutputDirs": ["${main.toUri().path}"], "testOutputDirs": ["${test.toUri().path}"],
              "sourceRoots": ["${mainSources.toUri().path}"], "testSourceRoots": ["${testSources.toUri().path}"],
              "classpath": [], "testClasspath": [] }
        """.trimIndent()
    }

    /** A manifest-mode service with tests included, over the given modules, built without Spring. */
    private fun service(tmp: Path, vararg modules: String): HierarchicalGraphService {
        // At <reactorRoot>/.hierograph/, so the recorded reactor root is the local one.
        val manifest = Files.writeString(
            Files.createDirectories(tmp.resolve(".hierograph")).resolve("manifest.json"),
            """
            {
              "schemaVersion": 2, "generator": "test", "generatedAt": "2026-01-01T00:00:00Z",
              "reactorRoot": "${tmp.toUri().path}",
              "modules": [${modules.joinToString(",")}]
            }
            """.trimIndent(),
        )
        return HierarchicalGraphService().apply {
            set("graphSource", "manifest")
            set("manifestFile", manifest.toString())
            set("includeTests", true)
            set("stateDirSetting", tmp.resolve("state").toString())
            set("watchMode", "off")
            set("buildFilesMode", "off")
            set("graphDumpFile", tmp.resolve("dump.txt").toString())
        }
    }

    private fun HierarchicalGraphService.set(field: String, value: Any) {
        HierarchicalGraphService::class.java.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
    }
}
