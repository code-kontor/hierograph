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

import io.hierograph.boltclient.IBoltClient
import io.hierograph.hierarchicalgraph.core.model.*
import io.hierograph.hierarchicalgraph.core.model.internal.HGGraphImpl
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.*
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.IBoltClientAware
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource

open class DefaultMappingService : IMappingService {

    override fun convert(mappingProvider: MappingProvider, boltClient: IBoltClient): HGModel {

        val hierarchyProvider = mappingProvider.hierarchyDefinitionProvider
        val dependencyProvider = mappingProvider.dependencyDefinitionProvider

        // 1. Create core graph
        val coreGraph = HGGraphFactory.createHGGraph()
        coreGraph.registerExtension(IBoltClient::class.java, boltClient)

        // 2. Create root node
        val rootNodeSource = GraphDbRootNodeSource(identifier = -1L)
        rootNodeSource.boltClient = boltClient
        val rootNode = HGGraphFactory.createNode(coreGraph) { rootNodeSource }

        // 3. Create hierarchy
        val hierarchy = HierarchyFactory.createHierarchy(coreGraph, rootNode)

        // 4. Node lookup map (local, for construction only)
        val idToNodeMap = mutableMapOf<Long, HGNode>()

        // 5. Initialize + build hierarchy from provider
        if (hierarchyProvider is IBoltClientAware) {
            hierarchyProvider.boltClient = boltClient
        }
        hierarchyProvider.initialize()

        // 5a. Top-level nodes
        for (rn in hierarchyProvider.toplevelNodeIds) {
            val node = getOrCreateNode(rn.id, coreGraph, boltClient, idToNodeMap, hierarchyProvider)
            // An id must never be added to the hierarchy twice. If a provider returns the same
            // top-level id more than once (e.g. one query row per Main/Test artifact of a module),
            // skip the repeats so the node appears under the root exactly once. Mirrors the guard
            // used for parent-child relationships below.
            if (hierarchy.parentOf(node) == null) {
                HierarchyFactory.addChild(hierarchy, rootNode, node)
            }
            if (node.kind == null) node.kind = rn.kind
        }

        // 5b. Parent-child relationships
        for (pcn in hierarchyProvider.parentChildNodeIds) {
            val parent = getOrCreateNode(pcn.parentId, coreGraph, boltClient, idToNodeMap, hierarchyProvider)
            val child = getOrCreateNode(pcn.childId, coreGraph, boltClient, idToNodeMap, hierarchyProvider)
            // Only add if child doesn't already have a parent in the hierarchy
            if (hierarchy.parentOf(child) == null) {
                HierarchyFactory.addChild(hierarchy, parent, child)
            }
            if (child.kind == null) child.kind = pcn.childKind
        }

        // 5c. Prune orphaned nodes so the model's node set equals the hierarchy tree.
        // getOrCreateNode materializes a core-graph node for every id referenced by a parent-child
        // row, before it is known whether that id ends up attached to the root. Nodes whose subtree
        // never links to a module (e.g. excluded test types reached via a package→type row whose
        // package is never linked to a module) are created but remain unreachable from the root.
        // Removing them here restores the invariant
        //     coreGraph.nodes == { rootNode } ∪ hierarchy.descendantsOf(rootNode)
        // which makes lookupNode an authoritative membership test for every downstream layer and
        // means dangling dependencies cannot form: an orphaned id is simply absent from idToNodeMap
        // when the dependency loop below resolves its endpoints.
        val reachableIds: Set<Any> = buildSet {
            add(rootNode.identifier)
            for (n in hierarchy.descendantsOf(rootNode)) add(n.identifier)
        }
        for (node in coreGraph.nodes.filter { it.identifier !in reachableIds }) {
            HGGraphFactory.removeNode(coreGraph, node)
            idToNodeMap.remove(node.identifier)
        }

        // 6. Build dependencies
        if (dependencyProvider is IBoltClientAware) {
            dependencyProvider.boltClient = boltClient
        }
        dependencyProvider.initialize()

        for (depDef in dependencyProvider.dependencies) {
            val from = idToNodeMap[depDef.idStart] ?: continue
            val to = idToNodeMap[depDef.idTarget] ?: continue
            val dep = HGGraphFactory.createCoreDependency(from, to, depDef.type) {
                val depSource = dependencyProvider.createDependencySource(depDef)
                depSource.boltClient = boltClient
                depSource
            }
            dep.weight = depDef.weight
            dep.attributesBitmap = depDef.attributesBitmap
        }

        // 7. Register mapping provider as extension
        coreGraph.registerExtension(MappingProvider::class.java, mappingProvider)

        // 8. Cleanup
        hierarchyProvider.dispose()
        dependencyProvider.dispose()

        return HGModel(coreGraph, hierarchy)
    }

    private fun getOrCreateNode(
        id: Long,
        coreGraph: HGGraphImpl,
        boltClient: IBoltClient,
        idToNodeMap: MutableMap<Long, HGNode>,
        hierarchyProvider: IHierarchyDefinitionProvider,
    ): HGNode {
        return idToNodeMap.getOrPut(id) {
            val source = hierarchyProvider.createNodeSource(id)
            // Set bolt client for lazy property loading
            source.boltClient = boltClient
            HGGraphFactory.createNode(coreGraph) { source }
        }
    }
}
