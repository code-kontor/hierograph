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

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.INodeSource
import io.hierograph.boltclient.IBoltClient

open class GraphDbNodeSource(
    override val identifier: Any
) : INodeSource {

    override var node: HGNode? = null

    private var _properties: Map<String, String>? = null
    private var _labels: List<String>? = null

    val properties: Map<String, String>
        get() {
            if (_properties == null) {
                loadNodeData()
            }
            return _properties!!
        }

    val labels: List<String>
        get() {
            if (_labels == null) {
                loadNodeData()
            }
            return _labels!!
        }

    private fun loadNodeData() {
        val boltClient = getBoltClient()
        val neo4jNode = boltClient.getNode(identifier as Long)

        _labels = neo4jNode.labels().toList()
        _properties = neo4jNode.asMap().entries.associate { (k, v) -> k to v.toString() }
    }

    private fun getBoltClient(): IBoltClient {
        val rootSource = node!!.rootNode.nodeSource as GraphDbRootNodeSource
        return checkNotNull(rootSource.boltClient) { "No bolt client set." }
    }
}
