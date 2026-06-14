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
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.IDependencySource

class GraphDbDependencySource(
    override val identifier: Any,
    val type: String
) : IDependencySource {

    override var dependency: HGCoreDependency? = null

    var boltClient: IBoltClient? = null

    var userObject: Any? = null

    /** Relationship properties are fetched from Neo4j once, on first access, and then cached. */
    val properties: Map<String, String> by lazy { loadProperties() }

    fun <T : Any> getUserObject(clazz: Class<T>): T? =
        userObject?.takeIf(clazz::isInstance)?.let(clazz::cast)

    private fun loadProperties(): Map<String, String> {
        val client = checkNotNull(boltClient) {
            "No bolt client set on GraphDbDependencySource for dependency $identifier."
        }
        return client.getRelationship(identifier as Long)
            .asMap()
            .mapValues { (_, value) -> value.toString() }
    }
}
