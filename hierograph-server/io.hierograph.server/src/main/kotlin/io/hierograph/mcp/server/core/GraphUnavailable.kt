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

/**
 * The single, modular source of the response every MCP tool returns while no hierarchical graph is
 * loaded.
 *
 * The server starts "empty" when its graph source cannot be built (for example, the project has not
 * been compiled yet), in which case no in-memory hierarchical graph exists. The analysis tools then
 * have nothing to operate on and answer with the description below instead of failing. `reload_graph`
 * is exempt and loads the graph once its source is available.
 */
object GraphUnavailable {

    /** Stable machine-readable marker so callers can branch on "no graph" without parsing prose. */
    const val STATUS = "no_graph_available"

    /** The description returned to every tool call while no hierarchical graph is available. */
    fun response(): Map<String, Any?> = linkedMapOf(
        "status" to STATUS,
        "message" to "No hierarchical graph is available. The Hierograph MCP server could not build " +
                "a graph from its source, so the analysis tools cannot answer. Make the source available " +
                "(from a build manifest: build the project so its classes are compiled; from a Parquet " +
                "snapshot: provide the snapshot directory), then call reload_graph to load the graph."
    )
}
