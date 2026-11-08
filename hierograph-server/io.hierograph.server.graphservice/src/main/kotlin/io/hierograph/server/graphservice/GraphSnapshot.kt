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

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.hierarchicalgraph.model.HGModel

/**
 * An immutable bundle of the loaded graph and everything derived from it.
 *
 * The model, its search index, and its data-snapshot hash always belong together — they describe one
 * scan of the codebase. Holding them in a single immutable value lets [HierarchicalGraphService] swap
 * the whole set atomically on a reload, so a concurrent tool call sees a consistent old-or-new
 * snapshot and never a torn mix of a new model with a stale index or hash.
 *
 * [graphStore] is the type-level GraphStore the model was bridged from; the Parquet-backed detail path
 * reads it for scope expansion and type display so it stays in lock-step with the served model.
 *
 * [generation] is a monotonically increasing build id. The Parquet detail source
 * ([HierarchicalGraphService] couples a dir + derived edges to a generation) is only served when its
 * generation matches the live snapshot's — so during a `manifest`-mode rescan, while the new graph is
 * live but its Parquet is still being written, detail queries report "rebuilding" rather than returning
 * data from the previous scan.
 */
data class GraphSnapshot(
    val model: HGModel,
    val searchIndex: NodeSearchIndex,
    val dataHash: String,
    val graphStore: GraphStore? = null,
    /**
     * Reverse index `node handle → member key` for the Parquet detail tools. Members are not nodes in the
     * bridged model, so their content-addressed handles cannot be looked up via the model; this maps a
     * handle back to the `<owner fqn>#<signature>` key to read from Parquet.
     */
    val memberHandles: Map<Long, String> = emptyMap(),
    val generation: Long = 0,
)
