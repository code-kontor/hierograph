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
package io.hierograph.manifest

import com.google.gson.JsonParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManifestWriterTest {

    private val api = "com.example:billing-api:1.4.0"
    private val core = "com.example:billing-core:1.4.0"

    private fun descriptor(gav: String, dir: String = "/ws/billing/${gav.split(':')[1]}/target/classes") = ModuleDescriptor(
        gav = gav, name = gav.split(':')[1], mainOutputDirs = listOf(dir), testOutputDirs = emptyList(),
        sourceRoots = emptyList(), testSourceRoots = emptyList(),
        classpath = listOf(ClasspathEntry("org.slf4j:slf4j-api:2.0.16", internal = false, jar = null)), testClasspath = emptyList(),
    )

    private fun request(tmp: Path, modules: List<ModuleSource>, buildFiles: List<Path> = emptyList()) = MergeRequest(
        manifestDir = tmp.resolve(MANIFEST_DIR), generator = "test", reactorRoot = tmp.toString(),
        buildTool = BuildTool("maven", "3.9.6"), regenerate = listOf("./mvnw", "-q", "-B", "process-classes"),
        modules = modules, buildFiles = buildFiles,
    )

    private fun source(tmp: Path, gav: String) = ModuleSource(gav, gav.split(':')[1], tmp.resolve("${gav.split(':')[1]}/target/hierograph/module.json"))

    private fun manifestModules(outcome: MergeOutcome): List<String> =
        JsonParser.parseString(Files.readString(outcome.file)).asJsonObject.getAsJsonArray("modules").map { it.asJsonObject["gav"].asString }

    @Test
    fun `merges descriptors and build files, sorted, with tool and command`(@TempDir tmp: Path) {
        ManifestWriter.writeDescriptor(descriptor(core), source(tmp, core).descriptorFile!!)
        ManifestWriter.writeDescriptor(descriptor(api), source(tmp, api).descriptorFile!!)
        val rootPom = Files.writeString(tmp.resolve("pom.xml"), "<project/>")
        val corePom = Files.writeString(Files.createDirectories(tmp.resolve("billing-core")).resolve("pom.xml"), "<project/>")

        val outcome = ManifestWriter.merge(request(tmp, listOf(source(tmp, core), source(tmp, api)), listOf(rootPom, corePom, rootPom)))

        assertThat(outcome.written).isTrue()
        assertThat(manifestModules(outcome)).containsExactly(api, core)
        val json = JsonParser.parseString(Files.readString(outcome.file)).asJsonObject
        assertThat(json["schemaVersion"].asInt).isEqualTo(SCHEMA_VERSION)
        assertThat(json.getAsJsonObject("buildTool")["name"].asString).isEqualTo("maven")
        assertThat(json.getAsJsonArray("regenerate").map { it.asString }).containsExactly("./mvnw", "-q", "-B", "process-classes")
        val buildFiles = json.getAsJsonArray("buildFiles").map { it.asJsonObject }
        assertThat(buildFiles.map { it["path"].asString }).containsExactly(corePom.toString(), rootPom.toString())
        assertThat(buildFiles.first()["sha256"].asString).hasSize(64)
        assertThat(json.getAsJsonArray("modules").first().asJsonObject.has("mainOutputDirs")).isTrue()
    }

    @Test
    fun `an unchanged merge does not rewrite the manifest`(@TempDir tmp: Path) {
        ManifestWriter.writeDescriptor(descriptor(core), source(tmp, core).descriptorFile!!)
        val first = ManifestWriter.merge(request(tmp, listOf(source(tmp, core))))
        val mtime = Files.getLastModifiedTime(first.file)
        Thread.sleep(20)

        val second = ManifestWriter.merge(request(tmp, listOf(source(tmp, core))))
        assertThat(second.written).isFalse()
        assertThat(Files.getLastModifiedTime(second.file)).isEqualTo(mtime)
        assertThat(ManifestWriter.writeDescriptor(descriptor(core), source(tmp, core).descriptorFile!!)).isFalse()
    }

    @Test
    fun `a missing descriptor keeps its entry, a module no longer in the build drops out`(@TempDir tmp: Path) {
        ManifestWriter.writeDescriptor(descriptor(core), source(tmp, core).descriptorFile!!)
        ManifestWriter.writeDescriptor(descriptor(api), source(tmp, api).descriptorFile!!)
        ManifestWriter.merge(request(tmp, listOf(source(tmp, core), source(tmp, api))))

        Files.delete(source(tmp, api).descriptorFile!!) // a clean, not rebuilt yet
        assertThat(manifestModules(ManifestWriter.merge(request(tmp, listOf(source(tmp, core), source(tmp, api)))))).containsExactly(api, core)

        // billing-api removed from the build: no longer among the sources.
        assertThat(manifestModules(ManifestWriter.merge(request(tmp, listOf(source(tmp, core)))))).containsExactly(core)
    }

    @Test
    fun `a duplicate GAV keeps the first module and warns`(@TempDir tmp: Path) {
        val other = ModuleSource(core, "billing-core-copy", tmp.resolve("copy/target/hierograph/module.json"))
        ManifestWriter.writeDescriptor(descriptor(core), source(tmp, core).descriptorFile!!)
        ManifestWriter.writeDescriptor(descriptor(core, "/ws/billing/copy/target/classes"), other.descriptorFile!!)
        val warnings = ArrayList<String>()

        val outcome = ManifestWriter.merge(request(tmp, listOf(source(tmp, core), other)), warnings::add)

        assertThat(manifestModules(outcome)).containsExactly(core)
        assertThat(Files.readString(outcome.file)).contains("/ws/billing/billing-core/target/classes").doesNotContain("/copy/")
        assertThat(warnings).singleElement().asString().contains("duplicate module").contains("billing-core-copy")
    }

    @Test
    fun `a descriptor in the single-directory shape is read as lists`(@TempDir tmp: Path) {
        val file = Files.createDirectories(tmp.resolve("billing-core/target/hierograph")).resolve("module.json")
        Files.writeString(
            file,
            """{ "gav": "$core", "name": "billing-core", "mainOutputDir": "/ws/billing/billing-core/target/classes",
                 "testOutputDir": null, "classpath": [ { "gav": "$api", "internal": true } ] }""",
        )
        val outcome = ManifestWriter.merge(request(tmp, listOf(ModuleSource(core, "billing-core", file))))
        val module = JsonParser.parseString(Files.readString(outcome.file)).asJsonObject.getAsJsonArray("modules").first().asJsonObject
        assertThat(module.getAsJsonArray("mainOutputDirs").map { it.asString }).containsExactly("/ws/billing/billing-core/target/classes")
        assertThat(module.getAsJsonArray("testOutputDirs")).isEmpty()
        assertThat(module.getAsJsonArray("testClasspath")).isEmpty()
        assertThat(module.has("mainOutputDir")).isFalse()
    }

    @Test
    fun `descriptors given in memory are merged without any descriptor file`(@TempDir tmp: Path) {
        val outcome = ManifestWriter.merge(
            request(tmp, listOf(ModuleSource(core, "billing-core", descriptor = descriptor(core)), ModuleSource(api, "billing-api", descriptor = descriptor(api)))),
        )
        assertThat(manifestModules(outcome)).containsExactly(api, core)
        assertThat(Files.exists(tmp.resolve("billing-core/target"))).isFalse()
    }
}
