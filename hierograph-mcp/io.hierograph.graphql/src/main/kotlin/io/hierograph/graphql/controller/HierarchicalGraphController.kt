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
package io.hierograph.graphql.controller

import io.hierograph.graphql.model.DependencySetModel
import io.hierograph.graphql.model.NodeSetModel
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

@Controller
class HierarchicalGraphController {

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun identifier(rootNode: HGRootNode): String = rootNode.identifier.toString()

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun globalIdentifier(rootNode: HGRootNode): String =
        rootNode.name ?: rootNode.identifier.toString()

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun rootNode(rootNode: HGRootNode): HGNode = rootNode

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun node(rootNode: HGRootNode, @Argument id: String): HGNode? =
        rootNode.lookupNode(id.toLong())

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun nodes(rootNode: HGRootNode, @Argument ids: List<String>): NodeSetModel {
        val resolved = ids.mapNotNull { rootNode.lookupNode(it.toLong()) }
        return NodeSetModel(resolved)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependency(rootNode: HGRootNode, @Argument id: String): HGCoreDependency? {
        return findCoreDependencyById(rootNode, id)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependencies(rootNode: HGRootNode, @Argument ids: List<String>): DependencySetModel? {
        val idSet = ids.toSet()
        val deps = collectAllCoreDependencies(rootNode).filter { coreDependencyId(it) in idSet }
        return DependencySetModel(deps)
    }

    @SchemaMapping(typeName = "HierarchicalGraph")
    fun dependencySetForAggregatedDependency(
        rootNode: HGRootNode,
        @Argument sourceNodeId: String,
        @Argument targetNodeId: String
    ): DependencySetModel? {
        val sourceNode = rootNode.lookupNode(sourceNodeId.toLong()) ?: return null
        val targetNode = rootNode.lookupNode(targetNodeId.toLong()) ?: return null
        val aggregated = sourceNode.getOutgoingDependenciesTo(targetNode) ?: return null
        return DependencySetModel(aggregated.coreDependencies)
    }

    companion object {
        fun coreDependencyId(dep: HGCoreDependency): String =
            "${dep.from.identifier}_${dep.to.identifier}_${dep.type}"

        fun findCoreDependencyById(rootNode: HGRootNode, id: String): HGCoreDependency? {
            return collectAllCoreDependencies(rootNode).firstOrNull { coreDependencyId(it) == id }
        }

        fun collectAllCoreDependencies(rootNode: HGRootNode): List<HGCoreDependency> {
            val deps = mutableListOf<HGCoreDependency>()
            fun walk(node: HGNode) {
                deps.addAll(node.outgoingCoreDependencies)
                for (child in node.children) walk(child)
            }
            walk(rootNode)
            return deps
        }
    }
}
