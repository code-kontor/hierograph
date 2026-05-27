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
package io.hierograph.boltclient.internal

import io.hierograph.boltclient.IBoltClient
import org.neo4j.driver.*
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.Relationship
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.function.Function

class BoltClientImpl(
    private val executorService: ExecutorService,
    override val uri: String,
    override val name: String?,
    override val description: String?
) : IBoltClient {

    private var driver: Driver? = null
    override var isConnected: Boolean = false
        private set

    override fun connect() {
        val config = Config.builder().withoutEncryption().build()
        driver = GraphDatabase.driver(uri, config)
        isConnected = true
    }

    override fun disconnect() {
        driver?.close()
        driver = null
        isConnected = false
    }

    override fun getNode(nodeId: Long): Node {
        assertConnected()
        driver!!.session().use { session ->
            val result = session.run("MATCH (n) WHERE id(n) = $nodeId RETURN n")
            return result.single().get("n").asNode()
        }
    }

    override fun getRelationship(relationshipId: Long): Relationship {
        assertConnected()
        driver!!.session().use { session ->
            val result = session.run("MATCH ()-[r]->() WHERE id(r) = $relationshipId RETURN r")
            return result.single().get("r").asRelationship()
        }
    }

    override fun getNodeLabels(): List<String> {
        return syncExecCypherQuery("CALL db.labels").records().map { it.get("label").asString() }
    }

    override fun getPropertyKeys(): List<String> {
        return syncExecCypherQuery("CALL db.propertyKeys").records().map { it.get("propertyKey").asString() }
    }

    override fun getRelationshipTypes(): List<String> {
        return syncExecCypherQuery("CALL db.relationshipTypes").records().map { it.get("relationshipType").asString() }
    }

    override fun syncExecCypherQuery(cypherQuery: String): EagerResult {
        assertConnected()
        return driver!!.executableQuery(cypherQuery).execute()
    }

    override fun syncExecCypherQuery(cypherQuery: String, params: Map<String, Any>): EagerResult {
        assertConnected()
        return driver!!.executableQuery(cypherQuery).withParameters(params).execute()
    }

    override fun <T> asyncExecCypherQueryAndTransformResult(
        cypherQuery: String,
        transform: Function<Result, T>
    ): Future<T> {
        return asyncExecCypherQueryAndTransformResult(cypherQuery, emptyMap(), transform)
    }

    override fun <T> asyncExecCypherQueryAndTransformResult(
        cypherQuery: String,
        params: Map<String, Any>,
        transform: Function<Result, T>
    ): Future<T> {
        assertConnected()
        val task = FutureTask<T> {
            driver!!.session().use { session ->
                val result = if (params.isEmpty()) {
                    session.run(cypherQuery)
                } else {
                    session.run(cypherQuery, params)
                }
                transform.apply(result)
            }
        }
        executorService.execute(task)
        return task
    }

    private fun assertConnected() {
        check(isConnected) { "BoltClient is not connected." }
    }
}
