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
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `hierograph:aggregate` — collects every reactor module's per-module `.hierograph` archive (written by
 * `hierograph:scan` into each module's `target/hierograph`) into a single snapshot directory that the MCP
 * server consumes via `hierograph.graph.parquetDir`. An aggregator goal: runs once on the top-level build.
 * On a duplicate archive (same GAV emitted by two modules) it warns and keeps the first.
 */
@Mojo(name = "aggregate", defaultPhase = LifecyclePhase.VERIFY, aggregator = true, threadSafe = true)
class AggregateMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(property = "hierograph.outputDir", defaultValue = "\${session.topLevelProject.build.directory}/hierograph-snapshot")
    private lateinit var outputDir: File

    @Parameter(property = "hierograph.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("hierograph:aggregate skipped (hierograph.skip=true)")
            return
        }
        val out = outputDir.toPath()
        Files.createDirectories(out)
        val seen = HashMap<String, String>() // archive file name -> the module that provided it
        var copied = 0
        for (project in session.allProjects) {
            val moduleSnapshot = File(project.build.directory, "hierograph").toPath()
            if (!Files.isDirectory(moduleSnapshot)) continue
            Files.list(moduleSnapshot).use { stream ->
                stream.filter { it.fileName.toString().endsWith(HierographArchive.EXTENSION) }.sorted().forEach { archive ->
                    val name = archive.fileName.toString()
                    val first = seen.putIfAbsent(name, project.artifactId)
                    if (first != null) {
                        log.warn("hierograph:aggregate: duplicate archive '$name' from ${project.artifactId}; keeping the one from $first")
                    } else {
                        Files.copy(archive, out.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                        copied++
                    }
                }
            }
        }
        log.info("hierograph:aggregate: collected $copied artifact archive(s) → $out")
        if (copied == 0) {
            log.warn("hierograph:aggregate: no .hierograph archives found — did `hierograph:scan` run for the modules?")
        }
    }
}
