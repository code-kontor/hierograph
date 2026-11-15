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
package io.hierograph.itest.gradle

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyScope
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * End to end: the server in manifest mode loads a manifest written by the Gradle plugin, and an edited build
 * script makes it run the manifest's own `regenerate` command (`./gradlew -q --init-script … hierographManifest`).
 */
@EnabledIfSystemProperty(named = GradleFixture.ENABLED_PROPERTY, matches = "true")
@SpringBootTest(classes = [HierarchicalGraphService::class])
class GradleManifestServerIT {

    companion object {
        private val fixture = GradleFixture(Path.of("target/gradle-fixtures/server").toAbsolutePath())

        private val manifest: Path get() = fixture.root.resolve(".hierograph/manifest.json")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("hierograph.graph.source") { "manifest" }
            registry.add("hierograph.graph.manifest") {
                // Compile the build once (the server scans class files), writing the manifest in the same run.
                fixture.create().apply { gradle("classes") }
                manifest.toString()
            }
            registry.add("hierograph.graph.stateDir") { fixture.root.resolveSibling("server-state").toString() }
            registry.add("hierograph.graph.watch.mode") { "polling" }
            registry.add("hierograph.graph.watch.interval") { "200" }
            registry.add("hierograph.graph.watch.build-files") { "regenerate" }
            registry.add("hierograph.graph.dumpFile") { "target/gradle-fixtures/hierarchical-graph.txt" }
        }
    }

    @Autowired
    private lateinit var graphService: HierarchicalGraphService

    @Test
    fun `loads a gradle manifest and regenerates it when a build script changes`() {
        val regenerate = GradleFixture.readJson(manifest)["regenerate"].map { it.asText() }
        assertThat(regenerate).containsExactly("./gradlew", "-q", "--init-script", fixture.initScript.toString(), "hierographManifest")

        val types = with(HierarchyScope(graphService.model.hierarchy)) {
            fun HGNode.all(): List<HGNode> = listOf(this) + children.flatMap { it.all() }
            hierarchy.rootNode.all().map { NodeMetadata.getQualifiedName(it) }
        }
        assertThat(types).contains("com.example.app.App", "com.example.lib.Lib")
        assertThat(graphService.buildState()).containsEntry("status", "current")

        // Edit a build script: the server reruns the manifest's command, which records the new content.
        val script = fixture.write("app/build.gradle.kts", Files.readString(fixture.root.resolve("app/build.gradle.kts")) + "// edited\n")
        val expected = sha256(script)
        val deadline = System.currentTimeMillis() + 180_000
        while (recordedSha256(script) != expected || graphService.buildState()?.get("status") != "current") {
            check(System.currentTimeMillis() < deadline) { "not regenerated in time; build state ${graphService.buildState()}" }
            Thread.sleep(250)
        }
    }

    private fun recordedSha256(file: Path): String? = GradleFixture.readJson(manifest)["buildFiles"]
        .firstOrNull { Path.of(it["path"].asText()) == file.toRealPath() || Path.of(it["path"].asText()) == file }?.get("sha256")?.asText()

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
