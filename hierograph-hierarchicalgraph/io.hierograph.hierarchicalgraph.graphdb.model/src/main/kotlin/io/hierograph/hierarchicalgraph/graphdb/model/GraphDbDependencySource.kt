/*
 * Copyright 2024 Gerd Wuetherich
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

import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.IDependencySource
import io.hierograph.boltclient.IBoltClient

class GraphDbDependencySource(
    override val identifier: Any,
    val type: String
) : IDependencySource {

    override var dependency: HGCoreDependency? = null

    var userObject: Any? = null

    private var _properties: Map<String, String>? = null

    val properties: Map<String, String>
        get() {
            if (_properties == null) {
                loadRelationshipData()
            }
            return _properties!!
        }

    fun <T : Any> getUserObject(clazz: Class<T>): T? {
        val obj = userObject ?: return null
        return if (clazz.isInstance(obj)) clazz.cast(obj) else null
    }

    private fun loadRelationshipData() {
        val boltClient = getBoltClient()
        val relationship = boltClient.getRelationship(identifier as Long)
        _properties = relationship.asMap().entries.associate { (k, v) -> k to v.toString() }
    }

    private fun getBoltClient(): IBoltClient {
        val rootSource = dependency!!.from.rootNode.nodeSource as GraphDbRootNodeSource
        return checkNotNull(rootSource.boltClient) { "No bolt client set." }
    }
}
