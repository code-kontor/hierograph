package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.core.model.HierarchicalGraphFactory
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNode
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.RootNode
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbDependencySource
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource
import org.slizaa.core.boltclient.IBoltClient

class DefaultMappingService : IMappingService {

    override fun convert(mappingProvider: IMappingProvider, boltClient: IBoltClient): HGRootNode {
        // 1. Create root node
        val rootNodeSource = GraphDbRootNodeSource(identifier = -1L)
        rootNodeSource.boltClient = boltClient
        val rootNode = HierarchicalGraphFactory.createRootNode { rootNodeSource }
        rootNode.registerExtension(IBoltClient::class.java, boltClient)

        // Node lookup map (neo4j id -> HGNode)
        val idToNodeMap = mutableMapOf<Long, HGNode>()

        // 2. Initialize hierarchy provider
        val hierarchyProvider = mappingProvider.hierarchyDefinitionProvider
        if (hierarchyProvider is IBoltClientAware) {
            hierarchyProvider.initialize(boltClient)
        }

        // 3. Build root-level nodes
        val rootNodes = hierarchyProvider.getToplevelNodeIds()
        for (rn in rootNodes) {
            val node = createNodeIfAbsent(rn.id, rootNode, rootNode, idToNodeMap)
            setKindIfNull(node, rn.kind)
        }

        // 4. Build hierarchy
        val parentChildNodes = hierarchyProvider.getParentChildNodeIds()
        for (pcn in parentChildNodes) {
            val parentNode = createNodeIfAbsent(pcn.parentId, rootNode, null, idToNodeMap)
            val childNode = createNodeIfAbsent(pcn.childId, rootNode, parentNode, idToNodeMap)
            setKindIfNull(childNode, pcn.childKind)
        }

        // 5. Remove dangling nodes
        val danglingKeys = idToNodeMap.entries
            .filter { (_, node) -> node.parent == null }
            .map { it.key }
        danglingKeys.forEach { idToNodeMap.remove(it) }

        // 6. Initialize dependency provider
        val dependencyProvider = mappingProvider.dependencyDefinitionProvider
        if (dependencyProvider is IBoltClientAware) {
            dependencyProvider.initialize(boltClient)
        }

        // 7. Build dependencies
        val dependencies = dependencyProvider.getDependencies()
        for (depDef in dependencies) {
            createDependency(depDef, rootNode, idToNodeMap)
        }

        // 8. Register extensions
        rootNode.registerExtension(IMappingProvider::class.java, mappingProvider)
        rootNode.registerExtension(INodeMetadataProvider::class.java, mappingProvider.nodeMetadataProvider)

        // 9. Return
        return rootNode
    }

    private fun createNodeIfAbsent(
        id: Long,
        rootNode: HGRootNode,
        parent: HGNode?,
        idToNodeMap: MutableMap<Long, HGNode>
    ): HGNode {
        val existing = idToNodeMap[id]
        if (existing != null) {
            // Node exists but may need parent set
            if (existing.parent == null && parent != null) {
                HierarchicalGraphFactory.setParent(existing, parent)
            }
            return existing
        }

        // Create new node
        val nodeSource = GraphDbNodeSource(identifier = id)
        val node = HierarchicalGraphFactory.createNode(rootNode, parent ?: rootNode) { nodeSource }
        idToNodeMap[id] = node
        return node
    }

    private fun setKindIfNull(node: HGNode, kind: Any) {
        if (node.kind == null) {
            node.kind = kind
        }
    }

    private fun createDependency(
        depDef: IDependencyDefinition,
        rootNode: HGRootNode,
        idToNodeMap: Map<Long, HGNode>
    ) {
        val fromNode = idToNodeMap[depDef.idStart] ?: return
        val toNode = idToNodeMap[depDef.idTarget] ?: return

        val dep = HierarchicalGraphFactory.createCoreDependency(
            fromNode, toNode, depDef.type,
            { GraphDbDependencySource(identifier = depDef.idRel, type = depDef.type) }
        )
        dep.weight = depDef.weight
        dep.attributesBitmap = depDef.attributesBitmap
    }
}
