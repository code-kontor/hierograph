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

import io.hierograph.manifest.BuildTool
import io.hierograph.manifest.ClasspathEntry
import io.hierograph.manifest.ModuleDescriptor
import io.hierograph.manifest.ManifestWriter
import io.hierograph.manifest.MergeOutcome
import io.hierograph.manifest.MergeRequest
import io.hierograph.manifest.ModuleSource
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import java.io.File
import java.nio.file.Path

/** Per-module descriptor location, relative to a module's `target` directory (`hierograph:create-descriptor` writes it). */
internal const val DESCRIPTOR_SUBDIR = "hierograph"
internal const val DESCRIPTOR_FILE = "module.json"

internal fun descriptorFile(project: MavenProject): File = File(File(project.build.directory, DESCRIPTOR_SUBDIR), DESCRIPTOR_FILE)

internal fun gav(groupId: String, artifactId: String, version: String) = "$groupId:$artifactId:$version"

/** One resolved dependency of a project, in classpath order: its GAV, its (mediated) scope and its file, if resolved. */
internal data class ResolvedDependency(val gav: String, val scope: String, val file: File?)

/**
 * The Maven side of a manifest merge: what only Maven knows, handed to the build-tool-independent
 * [ManifestWriter]. Shared by `hierograph:create-descriptor`, `hierograph:merge-descriptors` and
 * `hierograph:manifest`.
 *
 * - **Modules** are every project of `session.allProjects`, which is the full reactor even when the build is
 *   narrowed with `-pl`. [describe] turns a project and its resolved dependencies into a descriptor.
 * - **Build files** are every project's POM (aggregators included), each project's parent chain where the parent
 *   was read from a local file rather than the local repository, and `.mvn/maven.config` and
 *   `.mvn/extensions.xml` of the multi-module project directory.
 * - **Regeneration command** reruns the goal that wrote the manifest: `./mvnw` when the execution root holds a
 *   Maven wrapper (`./mvnw.cmd` on Windows), otherwise `mvn` from the `PATH`, with `-q -B`, the build's
 *   explicitly active profiles, and the goal's own arguments ([REGENERATE_DESCRIPTORS] or [regenerateManifest]).
 */
internal object MavenManifest {

    /**
     * Regeneration arguments for a manifest merged from descriptor files: `process-classes`, because
     * `create-descriptor` needs a lifecycle phase for Maven 3 to resolve reactor siblings from their output.
     */
    val REGENERATE_DESCRIPTORS = listOf("process-classes")

    /** Regeneration arguments for a manifest written by `hierograph:manifest`: the goal itself, no lifecycle. */
    fun regenerateManifest(pluginVersion: String) = listOf("io.hierograph:hierograph-maven-plugin:$pluginVersion:manifest")

    /** Modules read from the descriptor files `create-descriptor` wrote into each project's `target/hierograph/`. */
    fun descriptorFileModules(session: MavenSession): List<ModuleSource> =
        session.allProjects.map { ModuleSource(gav(it.groupId, it.artifactId, it.version), it.artifactId, descriptorFile(it).toPath()) }

    /**
     * The descriptor of [project] from its resolved [dependencies] (classpath order). A dependency whose GAV is in
     * [reactorGavs] is internal: it is scanned from its own output, so its file is irrelevant and recorded as `null`.
     * The main classpath holds every non-`test` scope, the test classpath every scope. Output directories are the
     * configured ones and need not exist yet.
     */
    fun describe(project: MavenProject, dependencies: List<ResolvedDependency>, reactorGavs: Set<String>): ModuleDescriptor {
        fun entries(include: (String) -> Boolean) = dependencies.filter { include(it.scope) }.map {
            val internal = it.gav in reactorGavs
            ClasspathEntry(gav = it.gav, internal = internal, jar = if (internal) null else it.file?.absolutePath)
        }
        return ModuleDescriptor(
            gav = gav(project.groupId, project.artifactId, project.version),
            name = project.artifactId,
            mainOutputDirs = listOf(File(project.build.outputDirectory).absolutePath),
            testOutputDirs = listOf(File(project.build.testOutputDirectory).absolutePath),
            sourceRoots = project.compileSourceRoots.toList(),
            testSourceRoots = project.testCompileSourceRoots.toList(),
            classpath = entries { it != "test" },
            testClasspath = entries { true },
        )
    }

    fun merge(
        session: MavenSession,
        manifestDir: File,
        pluginVersion: String,
        log: Log,
        modules: List<ModuleSource>,
        regenerateArgs: List<String>,
    ): MergeOutcome {
        val request = MergeRequest(
            manifestDir = manifestDir.toPath(),
            generator = "io.hierograph.maven.plugin/$pluginVersion",
            reactorRoot = session.executionRootDirectory,
            buildTool = BuildTool("maven", session.systemProperties.getProperty("maven.version").orEmpty()),
            regenerate = regenerateCommand(session, regenerateArgs),
            modules = modules,
            buildFiles = buildFiles(session),
        )
        return ManifestWriter.merge(request) { log.warn("hierograph: $it") }
    }

    private fun regenerateCommand(session: MavenSession, args: List<String>): List<String> {
        val root = File(session.executionRootDirectory)
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val wrapper = if (windows) "mvnw.cmd" else "mvnw"
        val maven = if (File(root, wrapper).isFile) "./$wrapper" else if (windows) "mvn.cmd" else "mvn"
        val profiles = session.request.activeProfiles.orEmpty()
        return listOf(maven, "-q", "-B") + (if (profiles.isEmpty()) emptyList() else listOf("-P" + profiles.joinToString(","))) + args
    }

    private fun buildFiles(session: MavenSession): List<Path> {
        val localRepo = session.repositorySession?.localRepository?.basedir?.toPath()?.toAbsolutePath()?.normalize()
        val files = ArrayList<Path>()
        fun add(file: File?) {
            val path = file?.toPath()?.toAbsolutePath()?.normalize() ?: return
            if (localRepo == null || !path.startsWith(localRepo)) files.add(path)
        }
        for (project in session.allProjects) {
            add(project.file)
            var parent = project.parent
            while (parent != null) {
                add(parent.file)
                parent = parent.parent
            }
        }
        session.request.multiModuleProjectDirectory?.let { root ->
            add(File(root, ".mvn/maven.config"))
            add(File(root, ".mvn/extensions.xml"))
        }
        return files
    }
}
