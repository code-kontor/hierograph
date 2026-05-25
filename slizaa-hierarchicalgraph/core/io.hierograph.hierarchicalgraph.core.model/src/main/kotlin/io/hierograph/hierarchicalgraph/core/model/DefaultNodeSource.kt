package io.hierograph.hierarchicalgraph.core.model

class DefaultNodeSource(
    override val identifier: Any,
    val properties: MutableMap<String, String> = mutableMapOf()
) : INodeSource {
    override var node: HGNode? = null
}
