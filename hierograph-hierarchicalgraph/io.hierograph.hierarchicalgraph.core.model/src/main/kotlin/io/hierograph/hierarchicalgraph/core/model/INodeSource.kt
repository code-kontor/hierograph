package io.hierograph.hierarchicalgraph.core.model

interface INodeSource {
    val identifier: Any
    var node: HGNode?
}
