/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.core.model.HierarchicalGraphFactory
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.MappingProvider
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource
import io.hierograph.boltclient.IBoltClient
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.IBoltClientAware

open class DefaultMappingService : IMappingService {

    override fun convert(mappingProvider: MappingProvider, boltClient: IBoltClient): HGRootNode {

        val hierarchyDefinitionProvider = mappingProvider.hierarchyDefinitionProvider

        // 1. Create root node
        val rootNodeSource = GraphDbRootNodeSource(identifier = -1L)
        rootNodeSource.boltClient = boltClient
        val rootNode = HierarchicalGraphFactory.createRootNode { rootNodeSource }
        rootNode.registerExtension(IBoltClient::class.java, boltClient)

        // Node lookup map (neo4j id -> HGNode)
        val idToNodeMap = mutableMapOf<Long, HGNode>()

        // 2. Initialize hierarchy provider
        if (hierarchyDefinitionProvider is IBoltClientAware) {
            hierarchyDefinitionProvider.boltClient = boltClient
        }
        hierarchyDefinitionProvider.initialize()

        // 3. Build root-level nodes
        val rootNodes = hierarchyDefinitionProvider.toplevelNodeIds
        for (rn in rootNodes) {
            val node = createNodeIfAbsent(rn.id, rootNode, rootNode, idToNodeMap, hierarchyDefinitionProvider)
            setKindIfNull(node, rn.kind)
        }

        // 4. Build hierarchy
        val parentChildNodes = hierarchyDefinitionProvider.parentChildNodeIds
        for (pcn in parentChildNodes) {
            val parentNode = createNodeIfAbsent(pcn.parentId, rootNode, null, idToNodeMap, hierarchyDefinitionProvider)
            val childNode = createNodeIfAbsent(pcn.childId, rootNode, parentNode, idToNodeMap, hierarchyDefinitionProvider)
            setKindIfNull(childNode, pcn.childKind)
        }

        // 5. Remove dangling nodes
        val danglingKeys = idToNodeMap.entries
            .filter { (_, node) -> node.parent == null }
            .map { it.key }
        danglingKeys.forEach { idToNodeMap.remove(it) }

        // 6. Initialize dependency provider
        val dependencyDefinitionProvider = mappingProvider.dependencyDefinitionProvider
        if (dependencyDefinitionProvider is IBoltClientAware) {
            dependencyDefinitionProvider.boltClient = boltClient
        }
        dependencyDefinitionProvider.initialize()

        // 7. Build dependencies
        val dependencies = dependencyDefinitionProvider.dependencies
        for (depDef in dependencies) {
            createDependency(depDef, idToNodeMap, dependencyDefinitionProvider)
        }

        // 8. Register extensions
        rootNode.registerExtension(MappingProvider::class.java, mappingProvider)

        // 9. dispose
        hierarchyDefinitionProvider.dispose()
        dependencyDefinitionProvider.dispose()

        // 10. Return
        return rootNode
    }

    private fun createNodeIfAbsent(
        id: Long,
        rootNode: HGRootNode,
        parent: HGNode?,
        idToNodeMap: MutableMap<Long, HGNode>,
        hierarchyDefinitionProvider: IHierarchyDefinitionProvider
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
        val nodeSource = hierarchyDefinitionProvider.createNodeSource(id)
        val node = if (parent != null) {
            HierarchicalGraphFactory.createNode(rootNode, parent) { nodeSource }
        } else {
            HierarchicalGraphFactory.createOrphanNode(rootNode) { nodeSource }
        }
        idToNodeMap[id] = node
        return node
    }

    private fun setKindIfNull(node: HGNode, kind: Any) {
        if (node.kind == null) {
            node.kind = kind
        }
    }

    private fun createDependency(
        depDef: DependencyDefinition,
        idToNodeMap: Map<Long, HGNode>,
        dependencyDefinitionProvider: IDependencyDefinitionProvider
    ) {
        val fromNode = idToNodeMap[depDef.idStart] ?: return
        val toNode = idToNodeMap[depDef.idTarget] ?: return

        val dep = HierarchicalGraphFactory.createCoreDependency(
            fromNode, toNode, depDef.type,
            { dependencyDefinitionProvider.createDependencySource(depDef) }
        )
        dep.weight = depDef.weight
        dep.attributesBitmap = depDef.attributesBitmap
    }
}
