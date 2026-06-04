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
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Holds the data-snapshot hash (`dh`) for the currently-loaded graph.
 *
 * Per the pagination design, the data hash is captured once when the graph is loaded and reused for
 * every cursor issued during that period. This bean is that capture point: it computes the fingerprint
 * a single time after startup and exposes it as a stable value that paginated tools stamp into the
 * cursors they issue and check against the cursors they receive.
 *
 * Because the value is constructor-derived from [HierarchicalGraphService], Spring fully initializes
 * the graph (running its `@PostConstruct`) before this bean's [init] runs, so [graphService.rootNode]
 * is populated by the time the fingerprint is computed. If the graph is ever reloaded into a new
 * snapshot, this hash must be recomputed so that cursors from the previous snapshot are recognized as
 * stale.
 */
@Component
class DataHashProvider(
    private val graphService: HierarchicalGraphService
) {

    private lateinit var captured: String

    /** The fingerprint of the loaded graph snapshot; stable for the lifetime of that snapshot. */
    val dataHash: String
        get() = captured

    @PostConstruct
    fun init() {
        captured = DataHash.fingerprint(graphService.rootNode)
        log.info("Captured data-snapshot hash: {}", captured)
    }

    companion object {
        private val log = LoggerFactory.getLogger(DataHashProvider::class.java)
    }
}
