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

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * `hierographClasspaths` — resolves the classpaths of one project for [HierographManifestTask], which depends on
 * it in every module project.
 *
 * A configuration may only be resolved while its project's lock is held, which a parallel build enforces; a task
 * holds the lock of its own project only. So each project resolves its own configurations here and hands the
 * result to the shared [ManifestPlan]; the root task merges. Like the root task it builds nothing and writes
 * nothing.
 */
@DisableCachingByDefault(because = "Has no outputs; hands the resolved classpaths to hierographManifest in memory")
abstract class HierographClasspathsTask : DefaultTask() {

    private var plan: ManifestPlan? = null
    private val projectPath: String = project.path

    init {
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache("Hands the resolved classpaths to hierographManifest in memory")
    }

    internal fun plan(plan: ManifestPlan) {
        this.plan = plan
    }

    @TaskAction
    fun resolve() {
        val plan = checkNotNull(plan) { "$NAME: the build model was not collected (projectsEvaluated did not run)" }
        if (plan.options.skip) return
        val module = plan.modules.find { it.path == projectPath } ?: return // e.g. Kotlin Multiplatform
        plan.classpaths[module.path] = GradleManifest.classpaths(plan, module)
    }

    companion object {
        const val NAME = "hierographClasspaths"
    }
}
