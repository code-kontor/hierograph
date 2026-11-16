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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Hierograph Docker image on a Gradle build: the host writes the manifest, the container reads it from the
 * read-only `/workspace`, maps its host paths there and loads the graph. An edited build script is reported as
 * stale (the container never runs Gradle); the graph follows once the host rewrites the manifest.
 *
 * Opt-in: `-Dhierograph.itest.docker=true` together with `-Dhierograph.itest.gradle=true` (the host side needs the
 * published plugin, see [GradleFixture]), with Docker running and the image built (`-Pdocker package` of the
 * server). `hierograph.itest.image` overrides the image.
 */
@EnabledIfSystemProperty(named = "hierograph.itest.docker", matches = "true")
@EnabledIfSystemProperty(named = GradleFixture.ENABLED_PROPERTY, matches = "true")
class GradleContainerIT {

    private val image = System.getProperty("hierograph.itest.image") ?: "ghcr.io/code-kontor/hierograph-mcp-server:${GradleFixture.version}"

    @Test
    fun `the image serves a manifest the host wrote and follows it when the host rewrites it`() {
        val fixture = GradleFixture(Path.of("target/gradle-fixtures/container").toAbsolutePath()).create()
        // Only the Gradle form, so the entrypoint detects Gradle.
        listOf("pom.xml", "lib/pom.xml", "app/pom.xml").forEach { Files.delete(fixture.root.resolve(it)) }
        fixture.gradleBuild("classes") // the server scans compiled output, built on the host
        fixture.gradle()
        assertThat(GradleFixture.readJson(fixture.root.resolve(".hierograph/manifest.json"))["reactorRoot"].asText())
            .isEqualTo(fixture.root.toString()) // a host path
        val state = fixture.root.resolveSibling("container-state").also { it.toFile().deleteRecursively(); Files.createDirectories(it) }

        val container = "hierograph-itest-gradle-container"
        docker("rm", "-f", container, check = false)
        docker(
            "run", "-d", "--name", container, "--user", "${id("-u")}:${id("-g")}",
            "-v", "${fixture.root}:/workspace:ro", "-v", "$state:/hierograph", image,
        )
        try {
            awaitLog(container, "the graph to load", "Hierarchical graph created")
            // app, lib and the external types: the modules' output was found under /workspace.
            assertThat(logs(container)).contains("Hierarchical graph created with 3 root children")

            // An edited build script: reported, not regenerated.
            fixture.write("app/build.gradle.kts", Files.readString(fixture.root.resolve("app/build.gradle.kts")) + "// edited\n")
            awaitLog(container, "the stale build state", "the graph is stale until the build runs")
            assertThat(logs(container)).doesNotContain("Running ")

            // The host rewrites the manifest; the server applies it.
            fixture.gradle()
            awaitLog(container, "the rewritten manifest to be applied", "Manifest applied")
        } finally {
            docker("rm", "-f", container, check = false)
        }
    }

    @Test
    fun `without a manifest the container exits naming the host command`() {
        val fixture = GradleFixture(Path.of("target/gradle-fixtures/container-no-manifest").toAbsolutePath()).create()
        val output = docker(
            listOf("run", "--rm", "--user", "${id("-u")}:${id("-g")}", "-e", "HIEROGRAPH_BUILD_TOOL=gradle", "-v", "${fixture.root}:/workspace:ro", image),
            check = false,
        )
        assertThat(output).contains("no manifest at /workspace/.hierograph/manifest.json", "hierographManifest")
    }

    private fun awaitLog(container: String, what: String, text: String) {
        val deadline = System.currentTimeMillis() + 120_000
        while (!logs(container).contains(text)) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; container log:\n" + logs(container).takeLast(4000) }
            Thread.sleep(500)
        }
    }

    private fun logs(container: String): String = docker("logs", container, check = false)

    private fun id(flag: String): String = command(listOf("id", flag), check = true).trim()

    private fun docker(vararg args: String, check: Boolean = true): String = docker(args.toList(), check)

    private fun docker(args: List<String>, check: Boolean = true): String = command(listOf("docker") + args, check)

    private fun command(command: List<String>, check: Boolean): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        check(!check || process.exitValue() == 0) { "failed (${process.exitValue()}): $command\n$output" }
        return output
    }
}
