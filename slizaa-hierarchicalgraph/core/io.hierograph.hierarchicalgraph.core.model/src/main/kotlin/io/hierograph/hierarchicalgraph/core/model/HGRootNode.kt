package io.hierograph.hierarchicalgraph.core.model

interface HGRootNode : HGNode {
    var name: String?

    fun <T : Any> registerExtension(clazz: Class<T>, extension: T)
    fun registerExtension(key: String, extension: Any)
    fun <T : Any> getExtension(clazz: Class<T>): T?
    fun <T : Any> getExtension(key: String, clazz: Class<T>): T?
    fun <T : Any> hasExtension(clazz: Class<T>): Boolean
    fun <T : Any> hasExtension(key: String, clazz: Class<T>): Boolean

    fun invalidateAllCaches()
    fun invalidateCaches(nodes: List<HGNode>)
    fun initializeCaches(nodes: List<HGNode>)
    fun lookupNode(identifier: Any): HGNode?
}
