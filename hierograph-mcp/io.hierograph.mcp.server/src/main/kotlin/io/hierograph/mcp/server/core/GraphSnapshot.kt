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
package io.hierograph.mcp.server.core

import io.hierograph.hierarchicalgraph.core.model.HGModel

/**
 * An immutable bundle of the loaded graph and everything derived from it.
 *
 * The model, its search index, and its data-snapshot hash always belong together — they describe one
 * scan of the codebase. Holding them in a single immutable value lets [HierarchicalGraphService] swap
 * the whole set atomically on a reload, so a concurrent tool call sees a consistent old-or-new
 * snapshot and never a torn mix of a new model with a stale index or hash.
 */
data class GraphSnapshot(
    val model: HGModel,
    val searchIndex: NodeSearchIndex,
    val dataHash: String
)
