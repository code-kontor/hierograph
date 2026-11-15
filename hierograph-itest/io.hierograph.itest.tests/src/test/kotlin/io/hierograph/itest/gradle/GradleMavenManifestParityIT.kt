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

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Path

/**
 * The same build in Maven and Gradle form gives manifests that differ only in output paths, jar locations,
 * `buildTool`, `regenerate`, `generator` and the build files: the same modules, source roots and classpaths in
 * classpath order, with the same internal entries.
 */
@EnabledIfSystemProperty(named = GradleFixture.ENABLED_PROPERTY, matches = "true")
class GradleMavenManifestParityIT {

    @Test
    fun `maven and gradle manifests of the same build agree`() {
        val fixture = GradleFixture(Path.of("target/gradle-fixtures/parity").toAbsolutePath()).create()
        val mavenDir = fixture.root.resolveSibling("parity-maven-manifest")
        val gradleDir = fixture.root.resolveSibling("parity-gradle-manifest")

        fixture.maven("-Dhierograph.manifestDir=$mavenDir")
        fixture.gradle("-Phierograph.manifestDir=$gradleDir")

        val maven = GradleFixture.readJson(mavenDir.resolve("manifest.json"))
        val gradle = GradleFixture.readJson(gradleDir.resolve("manifest.json"))

        assertThat(maven["buildTool"]["name"].asText()).isEqualTo("maven")
        assertThat(gradle["buildTool"]["name"].asText()).isEqualTo("gradle")
        assertThat(gradle["reactorRoot"]).isEqualTo(maven["reactorRoot"])
        assertThat(comparable(gradle)).isEqualTo(comparable(maven))
        assertThat(comparable(gradle).map { it["gav"] }).containsExactly("com.example:app:1.0", "com.example:lib:1.0")

        // One output directory per source set, in each tool's layout.
        for (module in gradle["modules"]) {
            assertThat(module["mainOutputDirs"].map { it.asText() }).singleElement().asString().endsWith("/build/classes/java/main")
        }
        for (module in maven["modules"]) {
            assertThat(module["mainOutputDirs"].map { it.asText() }).singleElement().asString().endsWith("/target/classes")
        }
    }

    /** The modules without what legitimately differs: output directories, and jar locations reduced to file names. */
    private fun comparable(manifest: JsonNode): List<Map<String, Any?>> = manifest["modules"].map { module ->
        fun entries(key: String) = module[key].map { e ->
            listOf(e["gav"].asText(), e["internal"].asBoolean(), e["jar"]?.takeUnless { it.isNull }?.asText()?.substringAfterLast('/'))
        }
        mapOf(
            "gav" to module["gav"].asText(),
            "name" to module["name"].asText(),
            "sourceRoots" to module["sourceRoots"].map { it.asText() },
            "testSourceRoots" to module["testSourceRoots"].map { it.asText() },
            "classpath" to entries("classpath"),
            "testClasspath" to entries("testClasspath"),
        )
    }
}
