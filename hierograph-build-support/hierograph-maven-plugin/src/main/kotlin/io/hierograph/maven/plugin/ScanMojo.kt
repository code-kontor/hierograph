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

import io.hierograph.graphstore.jvm.model.ArtifactClasspath
import io.hierograph.graphstore.jvm.model.ClasspathEntry
import io.hierograph.graphstore.jvm.parquet.HierographArchive
import io.hierograph.graphstore.jvm.scanner.BytecodeScanner
import io.hierograph.graphstore.jvm.scanner.ScanArtifact
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.apache.maven.project.MavenProjectHelper
import java.io.File

/**
 * `hierograph:scan` — scans this module's compiled classes into a per-module `.hierograph` archive keyed
 * by its GAV, capturing the module's dependency classpath (in resolution order) into the archive's
 * `classpath.parquet` manifest so load-time type resolution follows the real classpath.
 *
 * The archive is attached to the project as a secondary artifact of type `hierograph` (no classifier), so
 * `mvn install` / `mvn deploy` publishes it as `…-<version>.hierograph` beside the module's own jar and a
 * consumer can build a graph from the repository without the source. With [includeTestClasses] the test
 * output is scanned into a second archive keyed `<gav>:test` and attached under the `tests` classifier.
 * Bound to `package`, where the module's classes are compiled and its primary artifact exists.
 */
@Mojo(
    name = "scan",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true,
)
class ScanMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Component
    private lateinit var projectHelper: MavenProjectHelper

    @Parameter(defaultValue = "\${project.build.outputDirectory}", readonly = true)
    private lateinit var outputDirectory: File

    @Parameter(defaultValue = "\${project.build.testOutputDirectory}", readonly = true)
    private lateinit var testOutputDirectory: File

    /** Where the archive is written before it is attached. */
    @Parameter(property = "hierograph.archiveDir", defaultValue = "\${project.build.directory}/hierograph")
    private lateinit var archiveDirectory: File

    /** Attach the archive (and thus deploy it). When false, the archive is written but not published. */
    @Parameter(property = "hierograph.attach", defaultValue = "true")
    private var attach: Boolean = true

    /** Also scan the test output into a second archive keyed `<gav>:test`, attached under the `tests` classifier. */
    @Parameter(property = "hierograph.includeTestClasses", defaultValue = "false")
    private var includeTestClasses: Boolean = false

    /** Scanner parallelism; 0 (the default) uses the number of available processors. */
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
        fun entries(include: (String) -> Boolean) = project.artifacts
            .filter { include(it.scope) }
            .map { val g = gav(it.groupId, it.artifactId, it.version); ClasspathEntry(g, internal = g in reactorGavs) }

        // Main output: the module itself, classpath = its non-test dependencies (compile/runtime/provided/system).
        scanAndAttach(key, project.artifactId, outputDirectory, ArtifactClasspath(key, project.artifactId, entries { it != "test" }), classifier = null)

        if (includeTestClasses) {
            if (testOutputDirectory.isDirectory) {
                // Test output keyed `<gav>:test`; it depends on the main module, then the full test classpath
                // (all scopes) — the same shape the server's manifest scan uses.
                val testKey = "$key:test"
                val testName = "${project.artifactId} (test)"
                val testClasspath = ArtifactClasspath(testKey, testName, listOf(ClasspathEntry(key, internal = true)) + entries { true })
                scanAndAttach(testKey, testName, testOutputDirectory, testClasspath, classifier = "tests")
            } else {
                log.info("hierograph:scan: no compiled test classes at $testOutputDirectory; skipping test archive")
            }
        }
    }

    /** Scans [dir] as artifact [key] into a `.hierograph` archive under [archiveDirectory], and attaches it. */
    private fun scanAndAttach(key: String, name: String, dir: File, classpath: ArtifactClasspath, classifier: String?) {
        val parallelism = if (threads > 0) threads else Runtime.getRuntime().availableProcessors()
        val result = BytecodeScanner(parallelism).scan(listOf(ScanArtifact(key, dir.toPath())))
        result.writeTo(archiveDirectory.toPath(), classpaths = mapOf(key to classpath))
        val archive = archiveDirectory.toPath().resolve(HierographArchive.fileName(key)).toFile()

        log.info(
            "hierograph:scan $name: ${result.classes.size} types, " +
                "${result.classes.sumOf { it.facts.members.size }} members, ${result.dependencies.size} detail refs, " +
                "${classpath.entries.size} classpath entries → $archive",
        )

        if (attach) {
            if (classifier == null) projectHelper.attachArtifact(project, ARTIFACT_TYPE, archive)
            else projectHelper.attachArtifact(project, ARTIFACT_TYPE, classifier, archive)
            log.info("hierograph:scan: attached $ARTIFACT_TYPE artifact ($key)${classifier?.let { " [$it]" } ?: ""}")
        }
    }


    private companion object {
        /** The dedicated Maven artifact type / extension the archive is published under. */
        const val ARTIFACT_TYPE = "hierograph"
    }
}
