package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

import io.hierograph.hierarchicalgraph.core.model.HGNode

interface INodeMetadataProvider {

    // Per-node metadata
    fun getName(node: HGNode): String
    fun getQualifiedName(node: HGNode): String
    fun getKind(node: HGNode): String
    fun getKindFromLabels(labels: List<String>): String
    fun getKnownKinds(): List<String>

    // Cypher query delegation
    fun getFindNodeCypherQuery(kind: String?, limit: Int): String
    fun getNodeCountCypherQuery(scopeId: Long?): String
    fun getDepthStatsCypherQuery(scopeId: Long?): String
    fun getDependencyKindDistributionCypherQuery(scopeId: Long?): String
    fun getScanMetadataCypherQuery(): String
    fun getScannerName(): String
}
