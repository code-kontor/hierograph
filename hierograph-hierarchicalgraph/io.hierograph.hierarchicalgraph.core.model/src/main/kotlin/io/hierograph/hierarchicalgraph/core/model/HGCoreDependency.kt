package io.hierograph.hierarchicalgraph.core.model

interface HGCoreDependency {
    val from: HGNode
    val to: HGNode
    val type: String
    var weight: Int
    var attributesBitmap: Int
    val dependencySource: IDependencySource
    val rootNode: HGRootNode

    fun <T : Any> getDependencySource(clazz: Class<T>): T?
}
