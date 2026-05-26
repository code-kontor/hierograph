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
