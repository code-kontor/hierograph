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
package io.hierograph.hierarchicalgraph.graphdb.model

import io.hierograph.boltclient.IBoltClient
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.INodeSource

open class GraphDbNodeSource(
    override val identifier: Any
) : INodeSource {

    override var node: HGNode? = null

    var boltClient: IBoltClient? = null

    /** Labels and properties are fetched from Neo4j once, on first access, and then cached. */
    private val nodeData: NodeData by lazy { loadNodeData() }

    val labels: List<String> get() = nodeData.labels

    val properties: Map<String, String> get() = nodeData.properties

    private fun loadNodeData(): NodeData {
        val client = checkNotNull(boltClient) {
            "No bolt client set on GraphDbNodeSource for node $identifier."
        }
        val neo4jNode = client.getNode(identifier as Long)
        return NodeData(
            labels = neo4jNode.labels().toList(),
            properties = neo4jNode.asMap().mapValues { (_, value) -> value.toString() }
        )
    }

    private data class NodeData(
        val labels: List<String>,
        val properties: Map<String, String>
    )
}
