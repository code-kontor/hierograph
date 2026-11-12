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
package io.hierograph.server.views

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.mapping.GraphStoreToHGModel

/**
 * A model built the way the server builds one — from a [GraphStore], with one package node per segment and
 * nested types beside their enclosing type — shaped like the extraction examples of the views design:
 *
 * ```
 * acme:store:1.0     com.acme.store.Store              → model.Node (1), org.slf4j.Logger (1)
 *                    com.acme.store.model.{Node, Edge}
 *                    com.acme.store.parquet.Reader     → model.Node (2), Store (1)
 *                    com.acme.store.parquet.Reader$Page → model.Node (1)
 *                    com.acme.store.parquet.Writer     → model.Edge (1)
 *                    com.acme.store.detail.Query       → parquet.Reader (3), model.Node (1)
 * acme:server:1.0    com.acme.server.Service           → Store (1), detail.Query (2)
 *                    com.acme.server.Helper
 *                    com.acme.server.watch.Watcher     → Helper (1)
 * acme:mapping:1.0   com.acme.mapping.Mapper           → Store (1)
 * External Types     org.slf4j.Logger
 * ```
 */
class StoreReactor {

    val store = GraphStore().apply {
        for (name in listOf("store", "server", "mapping")) {
            registerArtifact(ArtifactInfo("acme:$name:1.0", name, "acme:$name:1.0"))
        }
        type("acme:store:1.0", "com.acme.store.Store", "com.acme.store.model.Node" to 1, "org.slf4j.Logger" to 1)
        type("acme:store:1.0", "com.acme.store.model.Node")
        type("acme:store:1.0", "com.acme.store.model.Edge")
        type("acme:store:1.0", "com.acme.store.parquet.Reader", "com.acme.store.model.Node" to 2, "com.acme.store.Store" to 1)
        type("acme:store:1.0", "com.acme.store.parquet.Reader\$Page", "com.acme.store.model.Node" to 1)
        type("acme:store:1.0", "com.acme.store.parquet.Writer", "com.acme.store.model.Edge" to 1)
        type("acme:store:1.0", "com.acme.store.detail.Query", "com.acme.store.parquet.Reader" to 3, "com.acme.store.model.Node" to 1)
        type("acme:server:1.0", "com.acme.server.Service", "com.acme.store.Store" to 1, "com.acme.store.detail.Query" to 2)
        type("acme:server:1.0", "com.acme.server.Helper")
        type("acme:server:1.0", "com.acme.server.watch.Watcher", "com.acme.server.Helper" to 1)
        type("acme:mapping:1.0", "com.acme.mapping.Mapper", "com.acme.store.Store" to 1)
    }

    private fun GraphStore.type(artifact: String, fqn: String, vararg refs: Pair<String, Int>) =
        applyClass(
            artifact,
            ClassFacts(fqn, JavaKinds.CLASS, 0, emptyList(), refs.map { (to, n) -> Ref(to, RefKind.DEPENDS_ON, count = n) }, fqn),
        )

    val model: HGModel = GraphStoreToHGModel.build(store)
    val hierarchy: Hierarchy get() = model.hierarchy

    /** The node with qualified name [qualifiedName] below the module [module] (by qualified name), in [h]. */
    fun node(qualifiedName: String, module: String? = null, h: Hierarchy = hierarchy): HGNode {
        val scope = module?.let { m -> h.childrenOf(h.rootNode).single { NodeMetadata.getQualifiedName(it) == m } }
        val candidates = if (scope == null) h.descendantsOf(h.rootNode) else listOf(scope) + h.descendantsOf(scope)
        return candidates.single { NodeMetadata.getQualifiedName(it) == qualifiedName }
    }

    fun module(qualifiedName: String, h: Hierarchy = hierarchy): HGNode =
        h.childrenOf(h.rootNode).single { NodeMetadata.getQualifiedName(it) == qualifiedName }

    /** The qualified names of [node]'s children in [h], sorted. */
    fun children(node: HGNode, h: Hierarchy = hierarchy): List<String> =
        h.childrenOf(node).map { NodeMetadata.getQualifiedName(it) }.sorted()

    /** The chain of qualified names from [node] up to, without, the root. */
    fun path(node: HGNode, h: Hierarchy): List<String> =
        generateSequence(node) { h.parentOf(it) }.takeWhile { it !== h.rootNode }.map { NodeMetadata.getQualifiedName(it) }.toList().reversed()
}
