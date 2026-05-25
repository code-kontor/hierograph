package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.*

class HGCoreDependencyImpl(
    override val from: HGNode,
    override val to: HGNode,
    override val type: String,
    override val dependencySource: IDependencySource
) : HGCoreDependency {

    override var weight: Int = 1
    override var attributesBitmap: Int = 0

    override val rootNode: HGRootNode get() = from.rootNode

    override fun <T : Any> getDependencySource(clazz: Class<T>): T? {
        return if (clazz.isInstance(dependencySource)) clazz.cast(dependencySource) else null
    }
}
