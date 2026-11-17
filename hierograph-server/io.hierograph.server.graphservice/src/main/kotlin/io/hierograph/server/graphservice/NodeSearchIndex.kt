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
 * Each loaded graph snapshot builds its own index, and the snapshot's model is never mutated afterwards.
 * A search is an allocation-free scan over a flat [entries] array. The entries refer to the node's own
 * name and fully-qualified name strings, so the index adds no string copies; [NodeSearch] compares them
 * case-insensitively. (Lower-cased copies would cost about a third of a large graph's per-build memory.)
 *
 * `kind` is intentionally not cached — it is a cheap field read on the node and is needed live so
 * the matching predicate keeps exactly the same typing as the rest of the tool surface.
 */
class NodeSearchIndex private constructor(val entries: List<Entry>) {

    /** One searchable node: the node itself plus its name and fqn, the node's own strings. */
    class Entry(
        val node: HGNode,
        val name: String,
        val fqn: String,
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
        nodes.mapTo(combined) { entry(it) }
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
            hierarchy.traverse(hierarchy.rootNode) { node -> entries.add(entry(node)) }
            entries.trimToSize()
            return NodeSearchIndex(entries)
        }

        private fun entry(node: HGNode) = Entry(node, NodeMetadata.getName(node), NodeMetadata.getQualifiedName(node))
    }
}
