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

/**
 * The Maven side of a manifest merge: what only Maven knows, handed to the build-tool-independent
 * [ManifestWriter]. Shared by `hierograph:create-descriptor` and `hierograph:merge-descriptors`.
 *
 * - **Modules** are every project of `session.allProjects`, which is the full reactor even when the build is
 *   narrowed with `-pl`.
 * - **Build files** are every project's POM (aggregators included), each project's parent chain where the parent
 *   was read from a local file rather than the local repository, and `.mvn/maven.config` and
 *   `.mvn/extensions.xml` of the multi-module project directory.
 * - **Regeneration command** is `./mvnw -q -B process-classes` when the execution root holds a Maven wrapper
 *   (`./mvnw.cmd` on Windows), otherwise `mvn` from the `PATH`. `process-classes` rather than a bare
 *   `hierograph:create-descriptor`, because without a lifecycle phase Maven 3 does not resolve reactor siblings
 *   from their output directories.
 */
internal object MavenManifest {

    fun merge(session: MavenSession, manifestDir: File, pluginVersion: String, log: Log): MergeOutcome {
        val root = File(session.executionRootDirectory)
        val request = MergeRequest(
            manifestDir = manifestDir.toPath(),
            generator = "io.hierograph.maven.plugin/$pluginVersion",
            reactorRoot = session.executionRootDirectory,
            buildTool = BuildTool("maven", session.systemProperties.getProperty("maven.version").orEmpty()),
            regenerate = regenerateCommand(root),
            modules = session.allProjects.map { ModuleSource(gav(it.groupId, it.artifactId, it.version), it.artifactId, descriptorFile(it).toPath()) },
            buildFiles = buildFiles(session),
        )
        return ManifestWriter.merge(request) { log.warn("hierograph: $it") }
    }

    private fun regenerateCommand(root: File): List<String> {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val wrapper = if (windows) "mvnw.cmd" else "mvnw"
        val maven = if (File(root, wrapper).isFile) "./$wrapper" else if (windows) "mvn.cmd" else "mvn"
        return listOf(maven, "-q", "-B", "process-classes")
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
