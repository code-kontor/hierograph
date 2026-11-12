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
package io.hierograph.server.mapping

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.model.JavaEdgeAttributes

/**
 * Builds Hierograph's hierarchical graph ([HGModel]) from a [GraphStore]: the bridge from the ingest model
 * to the analysis model. See `spec/hierograph-mapping.md`.
 *
 * The model has one node per GraphStore node under a synthetic root (top-level modules hang under the
 * root, every other node under its GraphStore parent) and one `DEPENDS_ON` core dependency per
 * [GraphStore.typeDependencies] entry whose endpoints both exist. Each node's identifier is the
 * content-addressed [NodeHandle] of its GraphStore key, so handles survive rescans and restarts; its
 * [GraphStoreNodeSource] carries the display name, the FQN, the key and the modifier bits. The
 * type-dependency flags are encoded into the [JavaEdgeAttributes] bitmap.
 *
 * Reads the store without modifying it; the caller must not modify the store while [build] runs.
 */
object GraphStoreToHGModel {

    private const val ROOT_ID = "<root>"
    private const val DEPENDS_ON = "DEPENDS_ON"

    fun build(store: GraphStore): HGModel {
        // Content-addressed handles: a node's identifier is a pure function of its stable GraphStore key
        // (see NodeHandle), which is already artifact-scoped — so it survives rescans/restarts, is
        // identical across backends, and two same-FQN types in different artifacts get distinct handles.
        // We assign the handles here and check for the (vanishingly rare) hash collision, failing fast.
        val handles = HashMap<Long, String>()
        fun handle(key: String): Long =
            NodeHandle.of(key).also { NodeHandle.assertNoCollision(key, it, handles) }

        val coreGraph = HGGraphFactory.createHGGraph()
        val rootNode = HGGraphFactory.createNode(coreGraph) { DefaultNodeSource(handle(ROOT_ID)) }
        val hierarchy = HierarchyFactory.createHierarchy(coreGraph, rootNode)

        // One HGNode per GraphStore node; identifier = the content-addressed handle. The node source carries
        // name, FQN, key and modifiers so the metadata provider and detail lookups can read them.
        val byKey = HashMap<String, HGNode>()
        for (n in store.nodes()) {
            val hg = HGGraphFactory.createNode(coreGraph) {
                GraphStoreNodeSource(handle(n.key), n.name, n.fqn, n.key, n.modifiers)
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

        // Type-to-type dependencies, keyed on resolved artifact-scoped node keys (so edges connect the
        // correct distinct nodes when an FQN is defined in more than one artifact).
        for (dep in store.typeDependencies()) {
            val from = byKey[dep.fromKey] ?: continue
            val to = byKey[dep.toKey] ?: continue
            val coreDep = HGGraphFactory.createCoreDependency(from, to, DEPENDS_ON) {
                DefaultDependencySource("${dep.fromKey} -> ${dep.toKey}")
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
