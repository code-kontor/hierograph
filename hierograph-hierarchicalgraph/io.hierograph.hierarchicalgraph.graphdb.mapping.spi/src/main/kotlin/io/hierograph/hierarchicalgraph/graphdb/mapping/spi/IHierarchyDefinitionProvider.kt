package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

interface IHierarchyDefinitionProvider {
    fun getToplevelNodeIds(): List<RootNode>
    fun getParentChildNodeIds(): List<ParentChildNode>
}

data class RootNode(val id: Long, val kind: Any)

data class ParentChildNode(val parentId: Long, val childId: Long, val childKind: Any)
