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
package io.hierograph.graphstore.hgmodel

import io.hierograph.graphstore.GraphStore
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import io.hierograph.mcp.javaspec.JavaEdgeAttributes

/**
 * Builds the production hierarchical graph ([HGModel]) directly from a [GraphStore] — the bridge that
 * lets the existing Hierograph API run off a Parquet-loaded graph instead of Neo4j.
 *
 * It mirrors what `DefaultMappingService.convert` does from the Neo4j side (root → top-level modules →
 * parent/child → type dependencies, using the same `HGGraphFactory` / `HierarchyFactory`), but reads
 * everything from the in-memory GraphStore and needs no bolt client. GraphStore **keys** become the
 * `HGNode` identifiers (`HGModel` keys on `Any`, so no synthetic longs are required); each node's `fqn`
 * and `name` are stashed in the [DefaultNodeSource] properties for a metadata provider to read, and the
 * type-dependency flags are encoded into the [JavaEdgeAttributes] bitmap exactly as the Java provider expects.
 */
object GraphStoreToHGModel {

    private const val ROOT_ID = "<root>"
    private const val DEPENDS_ON = "DEPENDS_ON"

    fun build(store: GraphStore): HGModel {
        val coreGraph = HGGraphFactory.createHGGraph()
        val rootNode = HGGraphFactory.createNode(coreGraph) { DefaultNodeSource(ROOT_ID) }
        val hierarchy = HierarchyFactory.createHierarchy(coreGraph, rootNode)

        // One HGNode per GraphStore node; identifier = the GraphStore key.
        val byKey = HashMap<String, HGNode>()
        for (n in store.nodes()) {
            val hg = HGGraphFactory.createNode(coreGraph) {
                DefaultNodeSource(n.key, mutableMapOf("fqn" to n.fqn, "name" to n.name))
            }
            hg.kind = n.kind
            byKey[n.key] = hg
        }

        // Hierarchy: each node under its parent; top-level modules (no parent) under the root.
        for (n in store.nodes()) {
            val child = byKey.getValue(n.key)
            val parent = n.parent?.let { byKey[it] } ?: rootNode
            HierarchyFactory.addChild(hierarchy, parent, child)
        }

        // Type-to-type dependencies, with the attribute bitmap the Java edge model expects.
        for (dep in store.typeDependencies()) {
            val from = byKey[dep.from] ?: continue
            val to = byKey[dep.to] ?: continue
            val coreDep = HGGraphFactory.createCoreDependency(from, to, DEPENDS_ON) {
                DefaultDependencySource("${dep.from} -> ${dep.to}")
            }
            coreDep.weight = dep.weight
            coreDep.attributesBitmap = bitmapOf(dep.extends, dep.implements, dep.annotatedBy, dep.other)
        }

        return HGModel(coreGraph, hierarchy)
    }

    private fun bitmapOf(extends: Boolean, implements: Boolean, annotatedBy: Boolean, other: Boolean): Int {
        var bm = 0
        bm = JavaEdgeAttributes.set(bm, JavaEdgeAttributes.IS_EXTENDS, extends)
        bm = JavaEdgeAttributes.set(bm, JavaEdgeAttributes.IS_IMPLEMENTS, implements)
        bm = JavaEdgeAttributes.set(bm, JavaEdgeAttributes.IS_ANNOTATED_BY, annotatedBy)
        bm = JavaEdgeAttributes.set(bm, JavaEdgeAttributes.IS_DEPENDS_ON_OTHER, other)
        return bm
    }
}
