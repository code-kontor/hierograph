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
package io.hierograph.graphql

import io.hierograph.hierarchicalgraph.core.model.HGNode

/**
 * Backs the GraphQL `HierarchicalGraph.findNodes` field: name search over the served graph, ranked best
 * match first. The host supplies the index (the MCP server shares it with `find_node`). Implementations
 * reject invalid [kinds] with [io.hierograph.graphql.controller.InvalidArgumentException], so the client
 * gets a `BAD_REQUEST` naming the valid values.
 */
fun interface NodeSearchProvider {
    fun findNodes(name: String, kinds: List<String>?, limit: Int): List<HGNode>
}
