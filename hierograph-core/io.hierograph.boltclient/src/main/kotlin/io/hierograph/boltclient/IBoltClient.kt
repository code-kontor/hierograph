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
package io.hierograph.boltclient

import org.neo4j.driver.EagerResult
import org.neo4j.driver.Result
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.Relationship
import java.util.concurrent.Future
import java.util.function.Function

interface IBoltClient {
    val name: String?
    val description: String?
    val uri: String
    val isConnected: Boolean

    fun connect()
    fun disconnect()

    fun getNode(nodeId: Long): Node
    fun getRelationship(relationshipId: Long): Relationship

    fun getNodeLabels(): List<String>
    fun getPropertyKeys(): List<String>
    fun getRelationshipTypes(): List<String>

    fun syncExecCypherQuery(cypherQuery: String): EagerResult
    fun syncExecCypherQuery(cypherQuery: String, params: Map<String, Any>): EagerResult

    fun <T> asyncExecCypherQueryAndTransformResult(
        cypherQuery: String,
        transform: Function<Result, T>
    ): Future<T>

    fun <T> asyncExecCypherQueryAndTransformResult(
        cypherQuery: String,
        params: Map<String, Any>,
        transform: Function<Result, T>
    ): Future<T>
}
