package io.hierograph.hierarchicalgraph.core.model

class DefaultDependencySource(
    override val identifier: Any,
    val properties: MutableMap<String, String> = mutableMapOf()
) : IDependencySource {
    override var dependency: HGCoreDependency? = null
}
