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
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File

/**
 * `hierograph:register` — records this module in the build manifest. It does **not** scan bytecode: it
 * writes a small `module.json` fragment to the module's `target/hierograph/` describing the GAV, the
 * compiled-output directories (main/test) and the resolved compile+runtime classpath — each entry flagged
 * internal (a reactor sibling) or external, with its resolved jar path. `hierograph:manifest` later merges
 * every module's fragment into one top-level manifest that the local MCP server scans and observes.
 *
 * Only the build tool knows the module layout and the already-resolved classpath, so the plugin reports
 * them; scanning and fact storage move to the server. Bound to `process-classes`.
 */
@Mojo(
    name = "register",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = true,
)
class RegisterMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(property = "hierograph.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("hierograph:register skipped (hierograph.skip=true)")
            return
        }
        if (project.packaging == "pom") {
            log.info("hierograph:register skips aggregator module ${project.artifactId} (packaging=pom)")
            return
        }

        val mainDir = File(project.build.outputDirectory).takeIf { it.isDirectory }
        val testDir = File(project.build.testOutputDirectory).takeIf { it.isDirectory }
        if (mainDir == null && testDir == null) {
            log.info("hierograph:register: no compiled classes for ${project.artifactId}; nothing to register")
            return
        }

        val reactorGavs = session.allProjects.mapTo(HashSet()) { gav(it.groupId, it.artifactId, it.version) }
        val classpath = project.artifacts
            .filter { it.scope == "compile" || it.scope == "runtime" }
            .map {
                val g = gav(it.groupId, it.artifactId, it.version)
                ClasspathEntryManifest(gav = g, internal = g in reactorGavs, jar = it.file?.absolutePath)
            }

        val module = ModuleManifest(
            gav = gav(project.groupId, project.artifactId, project.version),
            name = project.artifactId,
            mainOutputDir = mainDir?.absolutePath,
            testOutputDir = testDir?.absolutePath,
            sourceRoots = project.compileSourceRoots.toList(),
            classpath = classpath,
        )

        val fragmentDir = File(project.build.directory, FRAGMENT_SUBDIR).apply { mkdirs() }
        val fragment = File(fragmentDir, FRAGMENT_FILE)
        fragment.writeText(GSON.toJson(module) + "\n")
        log.info("hierograph:register ${project.artifactId}: ${classpath.size} classpath entries → $fragment")
    }

    private fun gav(groupId: String, artifactId: String, version: String) = "$groupId:$artifactId:$version"

    private companion object {
        val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    }
}
