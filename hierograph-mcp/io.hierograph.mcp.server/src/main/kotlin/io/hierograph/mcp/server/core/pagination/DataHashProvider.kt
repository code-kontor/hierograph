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
package io.hierograph.mcp.server.core.pagination

import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.springframework.stereotype.Component

/**
 * Exposes the data-snapshot hash (`dh`) for the currently-loaded graph.
 *
 * Per the pagination design, the data hash identifies the graph snapshot a cursor was issued against:
 * paginated tools stamp it into the cursors they issue and check it against the cursors they receive.
 *
 * The value is delegated live to [HierarchicalGraphService], which owns the snapshot. Because each
 * snapshot bundles its own hash and [HierarchicalGraphService.reload] swaps the whole snapshot
 * atomically, a reload's new hash is picked up automatically here — and cursors issued against the
 * previous snapshot are correctly recognized as stale.
 */
@Component
class DataHashProvider(
    private val graphService: HierarchicalGraphService
) {

    /** The fingerprint of the currently-loaded graph snapshot. */
    val dataHash: String
        get() = graphService.dataHash
}
