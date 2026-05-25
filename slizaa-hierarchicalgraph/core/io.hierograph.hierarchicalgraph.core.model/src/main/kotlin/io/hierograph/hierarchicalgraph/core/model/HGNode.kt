package io.hierograph.hierarchicalgraph.core.model

interface HGNode {
    var kind: Any?
    val parent: HGNode?
    val children: List<HGNode>
    val nodeSource: INodeSource

    val identifier: Any
    val rootNode: HGRootNode
    val predecessors: List<HGNode>

    val outgoingCoreDependencies: List<HGCoreDependency>
    val incomingCoreDependencies: List<HGCoreDependency>
    val accumulatedOutgoingCoreDependencies: List<HGCoreDependency>
    val accumulatedIncomingCoreDependencies: List<HGCoreDependency>

    fun isPredecessorOf(node: HGNode?): Boolean
    fun isSuccessorOf(node: HGNode?): Boolean

    fun getOutgoingDependenciesTo(target: HGNode): HGAggregatedDependency?
    fun getOutgoingDependenciesTo(targets: List<HGNode>): List<HGAggregatedDependency>
    fun getIncomingDependenciesFrom(source: HGNode): HGAggregatedDependency?
    fun getIncomingDependenciesFrom(sources: List<HGNode>): List<HGAggregatedDependency>

    fun <T : Any> getNodeSource(clazz: Class<T>): T?
}
