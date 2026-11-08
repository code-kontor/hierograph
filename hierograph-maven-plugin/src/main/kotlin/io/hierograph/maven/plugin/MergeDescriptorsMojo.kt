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
package io.hierograph.maven.plugin

import com.google.gson.GsonBuilder
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File
import java.time.Instant

/**
 * `hierograph:merge-descriptors` — merges every reactor module's `module.json` descriptor (written by
 * `hierograph:create-descriptor` into each module's `target/hierograph/`) into one top-level `manifest.json`.
 * By default it is written to the reactor root's `.hierograph/` directory, which the local MCP server reads
 * to scan each module's compiled output itself and observe the output directories for live updates.
 *
 * An aggregator goal: runs once on the top-level build. On a duplicate module (same GAV from two
 * descriptors) it warns and keeps the first.
 */
@Mojo(name = "merge-descriptors", defaultPhase = LifecyclePhase.VERIFY, aggregator = true, threadSafe = true)
class MergeDescriptorsMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(defaultValue = "\${plugin}", readonly = true)
    private lateinit var pluginDescriptor: PluginDescriptor

    /**
     * Directory the merged manifest is written to; defaults to the reactor root's `.hierograph/`. The root
     * is taken from the build's execution root (the directory Maven was launched in), which stays the
     * reactor root even when the build is narrowed with `-pl`.
     */
    @Parameter(property = "hierograph.manifestDir", defaultValue = "\${session.executionRootDirectory}/.hierograph")
    private lateinit var manifestDir: File

    @Parameter(property = "hierograph.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("hierograph:merge-descriptors skipped (hierograph.skip=true)")
            return
        }

        val seen = HashMap<String, String>() // gav -> the module that provided it
        val modules = ArrayList<ModuleDescriptor>()
        for (project in session.allProjects) {
            val descriptorFile = File(File(project.build.directory, DESCRIPTOR_SUBDIR), DESCRIPTOR_FILE)
            if (!descriptorFile.isFile) continue
            val module = try {
                GSON.fromJson(descriptorFile.readText(), ModuleDescriptor::class.java)
            } catch (e: Exception) {
                log.warn("hierograph:merge-descriptors: could not read descriptor $descriptorFile: ${e.message}")
                continue
            } ?: continue
            val first = seen.putIfAbsent(module.gav, project.artifactId)
            if (first != null) {
                log.warn("hierograph:merge-descriptors: duplicate module '${module.gav}' from ${project.artifactId}; keeping the one from $first")
            } else {
                modules += module
            }
        }
        modules.sortBy { it.gav }

        val manifest = Manifest(
            schemaVersion = SCHEMA_VERSION,
            generator = "io.hierograph.maven.plugin/${pluginDescriptor.version}",
            generatedAt = Instant.now().toString(),
            reactorRoot = session.executionRootDirectory,
            modules = modules,
        )

        manifestDir.mkdirs()
        val out = File(manifestDir, MANIFEST_FILE)
        out.writeText(GSON.toJson(manifest) + "\n")
        log.info("hierograph:merge-descriptors: ${modules.size} module(s) → $out")
        if (modules.isEmpty()) {
            log.warn("hierograph:merge-descriptors: no module descriptors found — did `hierograph:create-descriptor` run for the modules?")
        }
    }

    private companion object {
        val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    }
}
