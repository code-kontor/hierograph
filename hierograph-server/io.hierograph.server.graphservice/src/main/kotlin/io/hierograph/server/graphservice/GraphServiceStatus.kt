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

import java.time.Instant

/*
 * What the graph service reports about itself, typed. `server_info` (MCP) renders it with the `toMap()`
 * functions, GraphQL (`server`) reads the properties: both answer from the same values.
 */

/**
 * The graph source and its settings: [kind] `manifest` or `parquet`, `null` for a service filled by `seed`;
 * the manifest, build root, test inclusion and watch settings in `manifest` mode, the snapshot directory in
 * `parquet` mode.
 */
data class GraphSourceSettings(
    val kind: String?,
    val manifest: String? = null,
    val buildRoot: String? = null,
    val includeTests: Boolean? = null,
    val watchMode: String? = null,
    val buildFilesMode: String? = null,
    val parquetDir: String? = null,
) {
    fun toMap(): Map<String, Any?> = when (kind) {
        MANIFEST -> linkedMapOf(
            "kind" to kind,
            "manifest" to manifest,
            "build_root" to buildRoot,
            "include_tests" to includeTests,
            "watch" to linkedMapOf("mode" to watchMode, "build_files" to buildFilesMode),
        )
        null -> linkedMapOf("kind" to null)
        else -> linkedMapOf("kind" to kind, "parquet_dir" to parquetDir)
    }

    companion object {
        const val MANIFEST = "manifest"
    }
}

/** An output the manifest declares that is not compiled yet: watched, and scanned once it appears. */
data class NotCompiledArtifact(val artifact: String, val dirs: List<String>)

/** `manifest` mode: how many outputs the manifest declares, how many are scanned, and the ones not compiled yet. */
data class ArtifactStatus(val declared: Int, val scanned: Int, val notCompiled: List<NotCompiledArtifact>) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "declared" to declared,
        "scanned" to scanned,
        "not_compiled" to notCompiled.map { linkedMapOf("artifact" to it.artifact, "dirs" to it.dirs) },
    )
}

/**
 * The graph snapshot in service, cheap to read: no walk over the graph. [generation] changes with every graph
 * build; [loadedAt] is when the snapshot became live; [detailDir] is `null` while its detail data is being
 * written. The node counts are separate ([NodeCounts]), because they need a walk over the graph.
 */
data class SnapshotStatus(
    val generation: Long,
    val dataHash: String,
    val loadedAt: Instant?,
    val detailDir: String?,
)

/** How many nodes the graph has: modules, packages and scanned types, and all nodes ([total]). */
data class NodeCounts(val modules: Int, val packages: Int, val types: Int, val total: Int)

/** The state of the graph in one word; see [HierarchicalGraphService.graphState]. */
enum class GraphState { LOADED, UPDATING, UNAVAILABLE }
