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
package io.hierograph.server.graphservice

import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.NodeMetadata

/**
 * Immutable, precomputed search index over the hierarchical model, used by `find_node`.
 *
 * Each loaded graph snapshot builds its own index, and the snapshot's model is never mutated afterwards,
 * so each node's name and fully-qualified name are lower-cased a single time here rather than on every
 * search. A search is
 * then an allocation-free scan over a flat [entries] array (substring matching is
 * inherently linear; this just removes the per-query lower-casing cost).
 *
 * `kind` is intentionally not cached — it is a cheap field read on the node and is needed live so
 * the matching predicate keeps exactly the same typing as the rest of the tool surface.
 */
class NodeSearchIndex private constructor(val entries: List<Entry>) {

    /** One searchable node: the node itself plus its pre-lower-cased name and fqn. */
    class Entry(
        val node: HGNode,
        val nameLower: String,
        val fqnLower: String,
    )

    /**
     * This index plus [nodes], for a grouping view whose group nodes are added to an otherwise unchanged graph:
     * the entries of the real nodes are shared, not recomputed. A restructuring view changes the graph's nodes
     * and needs [build] instead.
     */
    fun withAdditional(nodes: Collection<HGNode>): NodeSearchIndex {
        if (nodes.isEmpty()) return this
        val combined = ArrayList<Entry>(entries.size + nodes.size)
        combined.addAll(entries)
        nodes.mapTo(combined) { Entry(it, NodeMetadata.getName(it).lowercase(), NodeMetadata.getQualifiedName(it).lowercase()) }
        return NodeSearchIndex(combined)
    }

    companion object {

        /**
         * Builds an index over every node in [model] except the synthetic root. `traverse` visits the
         * descendants of the node it starts from, so starting at the root covers the modules as well.
         */
        fun build(model: HGModel): NodeSearchIndex {
            val hierarchy = model.hierarchy
            val entries = ArrayList<Entry>()
            hierarchy.traverse(hierarchy.rootNode) { node ->
                entries.add(
                    Entry(
                        node = node,
                        nameLower = NodeMetadata.getName(node).lowercase(),
                        fqnLower = NodeMetadata.getQualifiedName(node).lowercase(),
                    )
                )
            }
            entries.trimToSize()
            return NodeSearchIndex(entries)
        }
    }
}
