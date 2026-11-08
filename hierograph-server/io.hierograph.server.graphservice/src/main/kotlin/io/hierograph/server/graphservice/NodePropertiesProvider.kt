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
package io.hierograph.server.graphservice

import io.hierograph.hierarchicalgraph.model.HGNode

/**
 * Supplies `Node.properties` for nodes that carry no store-backed properties of their own (i.e. not a
 * Neo4j `GraphDbNodeSource`), such as the Parquet-backed graph. Invoked on demand — only when a query
 * selects `properties` — and once per response with every such node, so implementations can batch their
 * lookups. Nodes missing from the result get an empty property list.
 */
fun interface NodePropertiesProvider {
    fun propertiesOf(nodes: List<HGNode>): Map<HGNode, Map<String, String?>>
}
