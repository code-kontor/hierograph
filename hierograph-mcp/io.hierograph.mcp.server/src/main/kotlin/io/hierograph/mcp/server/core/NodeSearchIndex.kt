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
package io.hierograph.mcp.server.core

import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider

/**
 * Immutable, precomputed search index over the hierarchical model, used by `find_node`.
 *
 * The model is built once at startup and never mutated afterwards, so each node's name and
 * fully-qualified name are lower-cased a single time here rather than on every search. A search is
 * then an allocation-free scan over a flat [entries] array (the substring `CONTAINS` semantics are
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

    companion object {

        /** Builds an index over every node in [model] except the synthetic root. */
        fun build(model: HGModel): NodeSearchIndex {
            val hierarchy = model.hierarchy
            val entries = ArrayList<Entry>()
            for (topLevel in hierarchy.childrenOf(hierarchy.rootNode)) {
                hierarchy.traverse(topLevel) { node ->
                    entries.add(
                        Entry(
                            node = node,
                            nameLower = JQAssistantNodeMetadataProvider.getName(node).lowercase(),
                            fqnLower = JQAssistantNodeMetadataProvider.getQualifiedName(node).lowercase(),
                        )
                    )
                }
            }
            entries.trimToSize()
            return NodeSearchIndex(entries)
        }
    }
}
