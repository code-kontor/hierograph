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

import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

/**
 * `hierograph:merge-descriptors` — merges every reactor module's `module.json` descriptor (written by
 * `hierograph:create-descriptor` into each module's `target/hierograph/`) into one top-level `manifest.json`.
 * By default it is written to the reactor root's `.hierograph/` directory, which the local MCP server reads
 * to scan each module's compiled output itself and observe the output directories for live updates.
 *
 * `hierograph:create-descriptor` already merges after each module, so this goal is only needed to merge
 * explicitly (e.g. without running the lifecycle). Both share [MavenManifest]. An aggregator goal: runs
 * once on the top-level build. On a duplicate module (same GAV from two descriptors) it warns and keeps
 * the first.
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

        val merged = MavenManifest.merge(
            session, manifestDir, pluginDescriptor.version, log,
            MavenManifest.descriptorFileModules(session), MavenManifest.REGENERATE_DESCRIPTORS,
        )
        log.info("hierograph:merge-descriptors: ${merged.modules} module(s) → ${merged.file}" + if (merged.written) "" else " (unchanged)")
        if (merged.modules == 0) {
            log.warn("hierograph:merge-descriptors: no module descriptors found — did `hierograph:create-descriptor` run for the modules?")
        }
    }
}
