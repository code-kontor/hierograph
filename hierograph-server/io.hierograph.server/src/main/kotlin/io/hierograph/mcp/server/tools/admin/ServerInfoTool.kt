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

import io.hierograph.mcp.server.instance.ServerInstance
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * MCP tool: `server_info`
 *
 * Describes the running server itself, where `graph_overview` describes the graph it serves.
 */
@Component
class ServerInfoTool(
    private val instance: ServerInstance,
) {

    @Tool(
        name = "server_info",
        description = "[Maintenance] Describe the running server itself: which workspace it serves, how it " +
                "was started and what it has loaded. Call it first to check that this server serves the " +
                "project you are working in: compare 'workspace' with the project's build root. Returns " +
                "url, ui_url (the web UI, to hand to the user for a visual view), port, pid, version, commit {id, dirty, time}, " +
                "build_time, started_at, workspace, " +
                "state_dir, source (kind 'manifest' with manifest, build_root, include_tests and watch " +
                "{mode, build_files}, or kind 'parquet' with parquet_dir), artifacts (manifest mode: " +
                "declared, scanned, and not_compiled with each output that is watched but not scanned yet), " +
                "snapshot (generation, data_hash, total_nodes, and detail_dir, null while the detail " +
                "snapshot is being written; null without a graph), build_state and graph_update in manifest " +
                "mode (graph_update: 'idle', 'pending', 'rescanning', 'applying-manifest' or 'reloading'), and " +
                "runtime_file (the server.json this server wrote, or null). Answers also while no graph " +
                "is loaded. No parameters."
    )
    fun serverInfo(): Map<String, Any?> = instance.info()
}
