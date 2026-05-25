package io.hierograph.hierarchicalgraph.core.model

interface HGAggregatedDependency {
    val from: HGNode
    val to: HGNode
    val coreDependencies: List<HGCoreDependency>
    val aggregatedWeight: Int
}
