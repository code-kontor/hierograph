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
package io.hierograph.mcp.server.graphql

import io.hierograph.mcp.server.instance.ServerInstance
import io.hierograph.server.graphservice.ArtifactStatus
import io.hierograph.server.graphservice.GraphSourceSettings
import io.hierograph.server.graphservice.GraphState
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.NodeCounts
import io.hierograph.server.graphservice.SnapshotStatus
import io.hierograph.server.graphservice.views.ViewService
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/**
 * `server`: the running server, as the MCP tool `server_info` describes it, from the same values
 * ([ServerInstance] and [HierarchicalGraphService]).
 *
 * It lives here rather than in the GraphQL module because [ServerInstance] belongs to the application. The
 * fields of `ServerInfo` are [ServerInstance]'s properties; the parts that come from the graph service are
 * resolved below. `counts` walks the graph (once per graph, then cached), so it is only resolved when asked
 * for: a client polling `state` and `generation` never triggers it. `jvm` is read from the JVM on each request.
 */
@Controller
class ServerInfoController(
    private val instance: ServerInstance,
    private val graphService: HierarchicalGraphService,
    private val viewService: ViewService? = null,
) {

    @QueryMapping
    fun server(): ServerInstance = instance

    @SchemaMapping(typeName = "ServerInfo")
    fun source(server: ServerInstance): GraphSourceSettings = graphService.graphSourceSettings()

    @SchemaMapping(typeName = "ServerInfo")
    fun artifacts(server: ServerInstance): ArtifactStatus? = graphService.artifacts()

    @SchemaMapping(typeName = "ServerInfo")
    fun graph(server: ServerInstance): GraphStatus =
        GraphStatus(graphService.graphState(), graphService.updateState(), graphService.snapshot())

    @SchemaMapping(typeName = "ServerInfo")
    fun jvm(server: ServerInstance): JvmStatus = JvmStatus.current()

    @SchemaMapping(typeName = "ServerInfo")
    fun viewsVersion(server: ServerInstance): String = viewService?.version() ?: ""

    @SchemaMapping(typeName = "GraphStatus")
    fun counts(status: GraphStatus): NodeCounts? = graphService.nodeCounts()

    /** The `GraphStatus` of one request: the state, the updater's activity and the snapshot in service. */
    class GraphStatus(val state: GraphState, val update: String?, private val snapshot: SnapshotStatus?) {
        val generation: String? get() = snapshot?.generation?.toString()
        val dataHash: String? get() = snapshot?.dataHash
        val loadedAt: String? get() = snapshot?.loadedAt?.toString()
    }
}
