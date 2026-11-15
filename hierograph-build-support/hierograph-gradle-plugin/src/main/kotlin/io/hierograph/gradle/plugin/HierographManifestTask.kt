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

import io.hierograph.manifest.ManifestWriter
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * `hierographManifest` — writes the build manifest for the whole build, **without compiling and without writing
 * into the project**: only `manifest.json` and `manifest.lock` in the manifest directory.
 *
 * It depends only on the [HierographClasspathsTask] of every module project, which resolves that project's
 * classpaths. Project dependencies are recorded by coordinates (internal entries, `jar: null`); only the artifacts
 * of external components are resolved, which downloads them but builds nothing. It is never up to date:
 * [ManifestWriter] decides whether the file changes, and a regeneration must always re-check.
 *
 * It reads the model of every project, so it is not compatible with the configuration cache; Gradle disables the
 * cache for an invocation that runs it.
 */
@DisableCachingByDefault(because = "Writes a file outside the build's outputs; ManifestWriter decides whether it changes")
abstract class HierographManifestTask : DefaultTask() {

    private var plan: ManifestPlan? = null

    init {
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache("Reads the dependency model of every project of the build")
    }

    internal fun plan(plan: ManifestPlan) {
        this.plan = plan
    }

    @TaskAction
    fun write() {
        val plan = checkNotNull(plan) { "$NAME: the build model was not collected (projectsEvaluated did not run)" }
        if (plan.options.skip) {
            logger.lifecycle("$NAME skipped (hierograph.skip=true)")
            return
        }
        plan.skipped.forEach { logger.info("$NAME: skipping ${it.first} (${it.second})") }
        val merged = ManifestWriter.merge(GradleManifest.mergeRequest(plan)) { logger.warn("hierograph: $it") }
        logger.lifecycle("$NAME: ${merged.modules} module(s) → ${merged.file}" + if (merged.written) "" else " (unchanged)")
    }

    companion object {
        const val NAME = "hierographManifest"
    }
}
