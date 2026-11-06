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
package io.hierograph.graphstore.jqa.detail

import io.hierograph.graphstore.GraphStore
import io.hierograph.mcp.javaspec.JavaKinds

/**
 * Resolves a detail-query scope against the **type-level** GraphStore — the in-memory half of the
 * detail query. A scope node (module / package / type key) expands to the set of type FQNs whose member
 * edges are in scope: every type in the node's subtree **plus their transitive supertypes**, because an
 * inherited member is declared by a supertype (the detail tool's `EXTENDS|IMPLEMENTS*0..` expansion).
 * The member-edge lookup then filters `from_type` (or `to_type`) against this set.
 */
object DetailScope {

    fun expand(store: GraphStore, rootKey: String): Set<String> {
        val subtree = linkedSetOf<String>()
        collectTypes(store, rootKey, subtree)
        return withSupertypes(store, subtree)
    }

    private fun collectTypes(store: GraphStore, key: String, out: MutableSet<String>) {
        val node = store.node(key) ?: return
        if (JavaKinds.isTypeKind(node.kind)) out += node.fqn
        for (child in store.childKeysOf(key)) collectTypes(store, child, out)
    }

    /** Transitively adds supertypes, following the type graph's EXTENDS / IMPLEMENTS dependencies. */
    private fun withSupertypes(store: GraphStore, seed: Set<String>): Set<String> {
        val superEdges = HashMap<String, MutableList<String>>()
        for (dep in store.typeDependencies()) {
            if (dep.extends || dep.implements) superEdges.getOrPut(dep.from) { mutableListOf() } += dep.to
        }
        val result = linkedSetOf<String>()
        val queue = ArrayDeque(seed)
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (result.add(t)) superEdges[t]?.forEach(queue::add)
        }
        return result
    }
}
