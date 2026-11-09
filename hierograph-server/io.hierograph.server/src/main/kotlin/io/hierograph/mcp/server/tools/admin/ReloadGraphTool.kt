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

import io.hierograph.server.graphservice.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * MCP tool: `reload_graph`
 *
 * Rebuilds the in-memory hierarchical graph from the configured source (the build manifest, or a Parquet
 * snapshot) without restarting the server.
 */
@Component
class ReloadGraphTool(
    private val graphService: HierarchicalGraphService
) {

    @Tool(
        name = "reload_graph",
        description = "[Maintenance] Rebuild the hierarchical graph from its source without restarting the " +
                "server: in manifest mode every module is rescanned, in Parquet mode the snapshot is re-read. In manifest mode the graph follows compiled classes " +
                "and manifest changes on its own; call this when it seems out of date, or after changing " +
                "something the watcher does not see. Returns a status ('reloaded' or 'error'), the " +
                "refreshed root-child count, the data-snapshot hash and, in manifest mode, the " +
                "build_state: 'current', or 'stale' with the build files edited since the manifest was " +
                "written (run the build that writes the manifest), 'regenerating', or " +
                "'regeneration-failed' with the cause; build_tool names the build tool. Pagination " +
                "cursors obtained before the reload become stale when the reload changed the graph."
    )
    fun reloadGraph(): Map<String, Any?> = graphService.reload()
}
