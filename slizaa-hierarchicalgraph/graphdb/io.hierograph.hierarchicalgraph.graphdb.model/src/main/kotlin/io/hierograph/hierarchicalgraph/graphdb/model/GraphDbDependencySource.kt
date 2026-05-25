package io.hierograph.hierarchicalgraph.graphdb.model

import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.IDependencySource
import org.slizaa.core.boltclient.IBoltClient

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
