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

import io.hierograph.graphstore.parquet.HierographArchive
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProjectHelper
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `hierograph:collect` — collects every reactor module's per-module `.hierograph` archive (written by
 * `hierograph:scan` into each module's `target/hierograph`) into a single `<artifactId>-collect.hierograph`
 * and attaches it to the top-level project under type `hierograph`, classifier `collect`, so `mvn deploy`
 * publishes one file a server can consume via `hierograph.graph.parquetDir` (unpacked).
 *
 * The result is a container of the per-module archives, not a content merge. An aggregator goal: runs once
 * on the top-level build. On a duplicate archive (same file name from two modules) it warns and keeps the
 * first.
 */
@Mojo(name = "collect", defaultPhase = LifecyclePhase.VERIFY, aggregator = true, threadSafe = true)
class CollectMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Component
    private lateinit var projectHelper: MavenProjectHelper

    /** Where the combined archive is assembled. */
    @Parameter(property = "hierograph.collectDir", defaultValue = "\${session.topLevelProject.build.directory}/hierograph-collect")
    private lateinit var outputDirectory: File

    /** Attach the combined archive to the top-level project (and thus deploy it). */
    @Parameter(property = "hierograph.attach", defaultValue = "true")
    private var attach: Boolean = true

    @Parameter(property = "hierograph.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("hierograph:collect skipped (hierograph.skip=true)")
            return
        }

        val root = session.topLevelProject
        val outDir = outputDirectory.toPath()
        Files.createDirectories(outDir)

        // Gather every module's .hierograph archives into a staging directory (dedup by file name), then
        // pack that directory into one archive. The per-module archives are already STORED zips of
        // ZSTD-compressed Parquet, so the container is STORED too (no pointless re-compression).
        val staging = Files.createTempDirectory("hierograph-collect-")
        try {
            val seen = HashMap<String, String>() // archive file name -> the module that provided it
            var collected = 0
            for (project in session.allProjects) {
                val moduleDir = File(project.build.directory, "hierograph").toPath()
                if (!Files.isDirectory(moduleDir)) continue
                Files.list(moduleDir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(HierographArchive.EXTENSION) }.sorted().forEach { archive ->
                        val name = archive.fileName.toString()
                        val first = seen.putIfAbsent(name, project.artifactId)
                        if (first != null) {
                            log.warn("hierograph:collect: duplicate archive '$name' from ${project.artifactId}; keeping the one from $first")
                        } else {
                            Files.copy(archive, staging.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                            collected++
                        }
                    }
                }
            }

            if (collected == 0) {
                log.warn("hierograph:collect: no .hierograph archives found — did `hierograph:scan` run for the modules?")
                return
            }

            val collectFile = outDir.resolve("${root.artifactId}-collect${HierographArchive.EXTENSION}")
            HierographArchive.zip(staging, collectFile)
            log.info("hierograph:collect: collected $collected archive(s) → $collectFile")

            if (attach) {
                projectHelper.attachArtifact(root, ARTIFACT_TYPE, CLASSIFIER, collectFile.toFile())
                log.info("hierograph:collect: attached $ARTIFACT_TYPE artifact [$CLASSIFIER]")
            }
        } finally {
            deleteRecursively(staging)
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private companion object {
        const val ARTIFACT_TYPE = "hierograph"
        const val CLASSIFIER = "collect"
    }
}
