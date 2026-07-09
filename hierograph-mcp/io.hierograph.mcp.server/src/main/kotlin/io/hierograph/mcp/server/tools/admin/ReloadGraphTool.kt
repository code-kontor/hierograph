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
package io.hierograph.mcp.server.tools.admin

import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * MCP tool: `reload_graph`
 *
 * Rebuilds the in-memory hierarchical graph from the jQAssistant Bolt store without restarting the
 * server, so the analysis tools reflect the current code after a re-scan.
 */
@Component
class ReloadGraphTool(
    private val graphService: HierarchicalGraphService
) {

    @Tool(
        name = "reload_graph",
        description = "[Maintenance] Reload the hierarchical graph from the jQAssistant Bolt store " +
                "without restarting the server. Call this after re-running a jQAssistant scan (for " +
                "example via the Hierograph 'rescan' command) so every other tool reflects the " +
                "current code. Reuses the existing Bolt connection. Returns a status ('reloaded' or " +
                "'error'), the refreshed root-child count, and a new data-snapshot hash. Any " +
                "pagination cursors obtained before the reload become stale and must be re-requested."
    )
    fun reloadGraph(): Map<String, Any?> = graphService.reload()
}
