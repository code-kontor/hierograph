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
 * Hierograph can be started "empty" — without a reachable jQAssistant/Neo4j database — in which case no
 * in-memory hierarchical graph is built. The analysis tools then have nothing to operate on and answer
 * with the description below instead of failing. Connecting the server to a database at runtime and
 * loading a graph on demand is planned; this is intentionally the one place to enhance the wording
 * (e.g. to tell callers exactly how to load/connect a graph) as that workflow lands.
 */
object GraphUnavailable {

    /** Stable machine-readable marker so callers can branch on "no graph" without parsing prose. */
    const val STATUS = "no_graph_available"

    /** The description returned to every tool call while no hierarchical graph is available. */
    fun response(): Map<String, Any?> = linkedMapOf(
        "status" to STATUS,
        "message" to "No hierarchical graph is currently available. The Hierograph MCP server was " +
                "started without a reachable jQAssistant/Neo4j database, so no in-memory graph has " +
                "been built. The analysis tools cannot answer until a graph is loaded. Loading a " +
                "graph on demand — by connecting the server to a database once it has been set up — " +
                "is not yet available; for now, start the server with a running database."
    )
}
