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

import io.hierograph.manifest.ModuleSource
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.descriptor.PluginDescriptor
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.DefaultDependencyResolutionRequest
import org.apache.maven.project.DependencyResolutionException
import org.apache.maven.project.DependencyResolutionResult
import org.apache.maven.project.MavenProject
import org.apache.maven.project.ProjectDependenciesResolver
import org.eclipse.aether.graph.DependencyFilter
import org.eclipse.aether.graph.DependencyNode
import java.io.File

/**
 * `hierograph:manifest` — writes the build manifest for the whole reactor in one step, **without a lifecycle
 * phase, without compiling and without writing into the project**. Only the manifest is written, into
 * `hierograph.manifestDir`.
 *
 * `create-descriptor` needs Maven's own dependency resolution, which under Maven 3 resolves a reactor sibling only
 * from its packaged artifact or, after a lifecycle phase in the same build, from its compiled output. The manifest
 * needs only a sibling's coordinates (its classpath entry is internal, `jar: null`), so this goal resolves itself:
 * per project, it collects the dependency graph — POMs only, siblings' POMs from the reactor — and resolves only the
 * artifacts that are not reactor projects. The classpath is the graph's pre-order, first occurrence wins: the order
 * in which Maven builds `project.artifacts`, so the result equals what `create-descriptor` records.
 *
 * This makes the goal usable where the project must not be touched — e.g. in a container with the workspace
 * mounted read-only — and as a cheap explicit command: no `process-classes`, no installed siblings.
 */
@Mojo(name = "manifest", aggregator = true, requiresDependencyResolution = ResolutionScope.NONE, threadSafe = true)
class ManifestMojo : AbstractMojo() {

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private lateinit var session: MavenSession

    @Parameter(defaultValue = "\${plugin}", readonly = true)
    private lateinit var pluginDescriptor: PluginDescriptor

    @Component
    private lateinit var resolver: ProjectDependenciesResolver

    /** Directory the manifest is written to. */
    @Parameter(property = "hierograph.manifestDir", defaultValue = "\${session.executionRootDirectory}/.hierograph")
    private lateinit var manifestDir: File

    @Parameter(property = "hierograph.skip", defaultValue = "false")
    private var skip: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("hierograph:manifest skipped (hierograph.skip=true)")
            return
        }
        val reactorGavs = session.allProjects.mapTo(HashSet()) { gav(it.groupId, it.artifactId, it.version) }
        // Resolve every node except reactor projects: those stay in the graph as coordinates only.
        val notReactor = DependencyFilter { node, _ -> node.dependency == null || gavOf(node) !in reactorGavs }

        val modules = session.allProjects.filter { it.packaging != "pom" }.map { project ->
            val dependencies = resolve(project, notReactor)
            MavenManifest.describe(project, dependencies, reactorGavs).let { descriptor ->
                ModuleSource(descriptor.gav, project.artifactId, descriptor = descriptor)
            }
        }
        val merged = MavenManifest.merge(
            session, manifestDir, pluginDescriptor.version, log,
            modules, MavenManifest.regenerateManifest(pluginDescriptor.version),
        )
        log.info("hierograph:manifest: ${merged.modules} module(s) → ${merged.file}" + if (merged.written) "" else " (unchanged)")
    }

    /** The project's dependencies in classpath order (all scopes), reactor siblings unresolved. */
    private fun resolve(project: MavenProject, filter: DependencyFilter): List<ResolvedDependency> {
        val request = DefaultDependencyResolutionRequest(project, session.repositorySession).setResolutionFilter(filter)
        val result: DependencyResolutionResult = try {
            resolver.resolve(request)
        } catch (e: DependencyResolutionException) {
            // Only a missing non-reactor artifact gets here; the manifest would be wrong without it.
            throw MojoExecutionException("hierograph:manifest: cannot resolve the dependencies of ${project.artifactId}: ${e.message}", e)
        }
        val dependencies = ArrayList<ResolvedDependency>()
        val seen = HashSet<String>()
        fun walk(nodes: List<DependencyNode>) {
            for (node in nodes) {
                val dependency = node.dependency
                if (dependency != null) {
                    val gav = gavOf(node)
                    if (seen.add(gav)) dependencies += ResolvedDependency(gav, dependency.scope, dependency.artifact.file)
                }
                walk(node.children)
            }
        }
        walk(result.dependencyGraph.children)
        return dependencies
    }

    private fun gavOf(node: DependencyNode): String = node.dependency.artifact.let { gav(it.groupId, it.artifactId, it.baseVersion) }
}
