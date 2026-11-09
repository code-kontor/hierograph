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
package io.hierograph.server.graphservice

import java.nio.file.Path

/**
 * What a [GraphWatcher] observes: each module's compiled-output directories (one or more per module), the
 * manifest file, and the build files the manifest lists.
 */
data class WatchTargets(val manifest: Path, val modules: Map<String, List<Path>>, val buildFiles: Set<Path> = emptySet())

/**
 * Observes a build's compiled output and reports *which modules changed* once their files have settled,
 * so [HierarchicalGraphService] can rescan only those modules and swap a fresh graph in. Pluggable because
 * the right mechanism depends on where the server runs: polling works everywhere (including a mounted
 * volume under Docker Desktop, where native file events do not cross the boundary), while a native,
 * event-driven watcher is better on a bare JVM.
 *
 * Callbacks are expected to return quickly (the implementation may run them on its own watch thread); the
 * service hands off the actual rebuild to its own executor.
 */
interface GraphWatcher : AutoCloseable {

    /**
     * Begin watching [targets]. [onChange] is invoked with the GAVs whose compiled output changed and then
     * settled; [onManifestChange] when the manifest file itself changes (module set / classpath);
     * [onBuildFilesChange] once per settled burst of build-file edits, with the files that changed. Only
     * changes *after* `start` are reported — the current state is taken as the baseline.
     */
    fun start(
        targets: WatchTargets,
        onChange: (changedGavs: Set<String>) -> Unit,
        onManifestChange: () -> Unit,
        onBuildFilesChange: (changedFiles: Set<Path>) -> Unit = {},
    )

    override fun close()
}
