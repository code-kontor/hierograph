package io.hierograph.hierarchicalgraph.graphdb.model

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.INodeSource
import org.slizaa.core.boltclient.IBoltClient

class GraphDbRootNodeSource(
    override val identifier: Any
) : INodeSource {

    override var node: HGNode? = null

    var boltClient: IBoltClient? = null

    val properties: Map<String, String> = emptyMap()

    val labels: List<String> = emptyList()
}
