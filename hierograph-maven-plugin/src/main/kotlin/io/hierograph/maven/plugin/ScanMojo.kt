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

import io.hierograph.graphstore.model.ArtifactClasspath
import io.hierograph.graphstore.model.ClasspathEntry
import io.hierograph.graphstore.parquet.HierographArchive
import io.hierograph.graphstore.scanner.BytecodeScanner
import io.hierograph.graphstore.scanner.ScanArtifact
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File

/**
 * `hierograph:scan` — scans this module's compiled classes into a per-module `.hierograph` archive keyed
 * by its GAV, capturing the module's dependency classpath (in resolution order) into the archive's
 * `classpath.parquet` manifest so load-time type resolution follows the real classpath. Bound to
 * `process-classes`; one archive per module under [snapshotDirectory].
 */
@Mojo(
    name = "scan",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = true,
)
class ScanMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(defaultValue = "\${project.build.outputDirectory}", readonly = true)
    private lateinit var outputDirectory: File

    @Parameter(property = "hierograph.snapshotDir", defaultValue = "\${project.build.directory}/hierograph")
    private lateinit var snapshotDirectory: File

    @Parameter(property = "hierograph.threads", defaultValue = "0")
    private var threads: Int = 0

    @Parameter(property = "hierograph.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("hierograph:scan skipped (hierograph.skip=true)")
            return
        }
        if (project.packaging == "pom") {
            log.info("hierograph:scan skips aggregator module ${project.artifactId} (packaging=pom)")
            return
        }
        if (!outputDirectory.isDirectory) {
            log.info("hierograph:scan: no compiled classes at $outputDirectory; nothing to scan")
            return
        }

        val key = gav(project.groupId, project.artifactId, project.version)
        val reactorGavs = session.allProjects.mapTo(HashSet()) { gav(it.groupId, it.artifactId, it.version) }
        val classpathEntries = project.artifacts
            .filter { it.scope == "compile" || it.scope == "runtime" }
            .map { val g = gav(it.groupId, it.artifactId, it.version); ClasspathEntry(g, internal = g in reactorGavs) }
        val classpath = ArtifactClasspath(key, project.artifactId, classpathEntries)

        val parallelism = if (threads > 0) threads else Runtime.getRuntime().availableProcessors()
        val result = BytecodeScanner(parallelism).scan(listOf(ScanArtifact(key, outputDirectory.toPath())))
        result.writeTo(snapshotDirectory.toPath(), classpaths = mapOf(key to classpath))

        log.info(
            "hierograph:scan ${project.artifactId}: ${result.classes.size} types, " +
                "${result.classes.sumOf { it.facts.members.size }} members, ${result.detailRefs.size} detail refs, " +
                "${classpathEntries.size} classpath entries → " +
                snapshotDirectory.toPath().resolve(HierographArchive.fileName(key)),
        )
    }

    private fun gav(groupId: String, artifactId: String, version: String) = "$groupId:$artifactId:$version"
}
