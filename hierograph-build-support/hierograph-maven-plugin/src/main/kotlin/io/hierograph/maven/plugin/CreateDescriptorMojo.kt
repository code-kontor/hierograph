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

import io.hierograph.manifest.ManifestWriter
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File

/**
 * `hierograph:create-descriptor` — records this module's build facts into a per-module *descriptor*, then
 * merges every reactor module's descriptor into the top-level manifest ([MavenManifest]). It does **not**
 * scan bytecode: it writes a small `module.json` to the module's `target/hierograph/` describing the GAV, the
 * compiled-output directories and source roots (main/test), and the resolved main and test classpaths — each
 * entry flagged internal (a reactor sibling) or external, with its resolved jar path.
 *
 * Every module except `pom` packaging gets a descriptor, compiled or not: the output directories are
 * recorded as configured, so a watching server observes them and picks the classes up once they appear.
 * Because the merge runs here, any build reaching `process-classes` keeps the manifest in step with the
 * module set. Only the build tool knows the module layout and the already-resolved classpath, so the plugin
 * reports them; the server does the scanning. Bound to `process-classes`.
 */
@Mojo(
    name = "create-descriptor",
    defaultPhase = LifecyclePhase.PROCESS_CLASSES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true,
)
class CreateDescriptorMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private lateinit var project: MavenProject

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(defaultValue = "\${plugin}", readonly = true)
    private lateinit var pluginDescriptor: PluginDescriptor

    /** Directory the merged manifest is written to; the same parameter as `hierograph:merge-descriptors`. */
    @Parameter(property = "hierograph.manifestDir", defaultValue = "\${session.executionRootDirectory}/.hierograph")
    private lateinit var manifestDir: File

    @Parameter(property = "hierograph.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("hierograph:create-descriptor skipped (hierograph.skip=true)")
            return
        }
        if (project.packaging == "pom") {
            log.info("hierograph:create-descriptor skips aggregator module ${project.artifactId} (packaging=pom)")
            return
        }

        val reactorGavs = session.allProjects.mapTo(HashSet()) { gav(it.groupId, it.artifactId, it.version) }
        // Maven resolved the classpath for this goal (scope test): every scope, in classpath order.
        val dependencies = project.artifacts.map { ResolvedDependency(gav(it.groupId, it.artifactId, it.version), it.scope, it.file) }
        val descriptor = MavenManifest.describe(project, dependencies, reactorGavs)
        val classpath = descriptor.classpath

        val descriptorFile = descriptorFile(project)
        val descriptorWritten = ManifestWriter.writeDescriptor(descriptor, descriptorFile.toPath())
        log.info(
            "hierograph:create-descriptor ${project.artifactId}: ${classpath.size} classpath entries → $descriptorFile" +
                if (descriptorWritten) "" else " (unchanged)",
        )

        val merged = MavenManifest.merge(
            session, manifestDir, pluginDescriptor.version, log,
            MavenManifest.descriptorFileModules(session), MavenManifest.REGENERATE_DESCRIPTORS,
        )
        log.info(
            "hierograph:create-descriptor: ${merged.modules} module(s) → ${merged.file}" +
                if (merged.written) "" else " (unchanged)",
        )
    }
}
