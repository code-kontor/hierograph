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
package io.hierograph.gradle.plugin

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings

/**
 * The settings plugin `io.hierograph`: registers the root task [HierographManifestTask.NAME], which writes
 * `.hierograph/manifest.json` for the whole build without building it.
 *
 * Applied from `settings.gradle(.kts)` or, without changing the project, from an init script (the jar ships one,
 * [INIT_SCRIPT_RESOURCE]). Applied twice — settings and init script load the class through different class loaders,
 * so Gradle does not see one plugin — the second application is ignored.
 *
 * The task is not wired into the build: the server's `regenerate` mode reruns it when a build file changes. Its
 * model is collected once all projects are evaluated, and only when the task is about to run (it is realized).
 * Every project with the `java` plugin gets a [HierographClasspathsTask], which the root task depends on.
 */
class HierographSettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        val extra = settings.gradle.extensions.extraProperties
        if (extra.has(APPLIED_MARKER)) return
        extra.set(APPLIED_MARKER, true)

        val options = Options.read(settings)
        settings.gradle.rootProject { root ->
            val task = root.tasks.register(HierographManifestTask.NAME, HierographManifestTask::class.java) {
                it.group = "hierograph"
                it.description = "Writes the Hierograph build manifest (.hierograph/manifest.json) without building the project."
            }
            root.gradle.projectsEvaluated { gradle ->
                val plan by lazy { GradleManifest.plan(settings, gradle, options) }
                // Each module project resolves its own classpaths (its lock); see HierographClasspathsTask.
                val classpathTasks = gradle.rootProject.allprojects.filter { it.plugins.hasPlugin("java") }.associate { project ->
                    project.path to project.tasks.register(HierographClasspathsTask.NAME, HierographClasspathsTask::class.java) {
                        it.description = "Resolves this project's classpaths for ${HierographManifestTask.NAME}."
                        it.plan(plan)
                    }
                }
                task.configure { manifest ->
                    manifest.plan(plan)
                    manifest.dependsOn(plan.modules.mapNotNull { classpathTasks[it.path] })
                }
            }
        }
    }

    companion object {
        /** Init script that applies this plugin without changing the project, in the plugin jar. */
        const val INIT_SCRIPT_RESOURCE = "io/hierograph/gradle/plugin/hierograph.init.gradle"

        private const val APPLIED_MARKER = "io.hierograph.gradle.plugin.applied"
    }
}
