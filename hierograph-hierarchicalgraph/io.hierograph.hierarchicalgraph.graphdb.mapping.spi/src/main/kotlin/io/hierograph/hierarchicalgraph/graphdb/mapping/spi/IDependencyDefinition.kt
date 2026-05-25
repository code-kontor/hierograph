package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

interface IDependencyDefinition {
    val idStart: Long
    val idTarget: Long
    val idRel: Long
    val type: String
    val weight: Int get() = 1
    val attributesBitmap: Int get() = 0
}

data class DefaultDependencyDefinition(
    override val idStart: Long,
    override val idTarget: Long,
    override val idRel: Long,
    override val type: String,
    override val weight: Int = 1,
    override val attributesBitmap: Int = 0
) : IDependencyDefinition
