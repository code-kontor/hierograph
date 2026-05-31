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
package io.hierograph.hierarchicalgraph.serialization.internal

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.core.model.HierarchicalGraphFactory

/**
 * Rebuilds an [HGRootNode] from a [GraphSnapshot]. Two linear passes:
 * nodes first (parents before children — relies on the writer's pre-order
 * layout), then core dependencies.
 *
 * The derived caches on `HGNodeImpl` stay cold; they rebuild lazily on
 * first access after the read.
 */
class GraphReader(private val codecs: CodecRegistry) {

    fun read(snapshot: GraphSnapshot): HGRootNode {
        require(snapshot.schemaVersion == GraphSnapshot.SCHEMA_VERSION) {
            "Unsupported schemaVersion ${snapshot.schemaVersion}; expected ${GraphSnapshot.SCHEMA_VERSION}"
        }

        val byId = HashMap<String, HGNode>(snapshot.nodes.size + 1)

        val rootCodec = codecs.nodeCodecFor(snapshot.root.source.type)
        val root = HierarchicalGraphFactory.createRootNode {
            rootCodec.read(snapshot.root.id, snapshot.root.source.payload)
        }
        root.kind = decodeKind(snapshot.root.kind)
        byId[snapshot.root.id] = root

        for (rec in snapshot.nodes) {
            val parentId = rec.parentId
                ?: throw IllegalArgumentException("Non-root node ${rec.id} has null parentId")
            val parent = byId[parentId]
                ?: throw IllegalArgumentException("Node ${rec.id} references unknown parent $parentId; nodes must be ordered parents-before-children")
            val codec = codecs.nodeCodecFor(rec.source.type)
            val node = HierarchicalGraphFactory.createNode(root, parent) {
                codec.read(rec.id, rec.source.payload)
            }
            node.kind = decodeKind(rec.kind)
            byId[rec.id] = node
        }

        for (rec in snapshot.deps) {
            val from = byId[rec.fromId]
                ?: throw IllegalArgumentException("Dependency ${rec.id} references unknown from-node ${rec.fromId}")
            val to = byId[rec.toId]
                ?: throw IllegalArgumentException("Dependency ${rec.id} references unknown to-node ${rec.toId}")
            val depCodec = codecs.depCodecFor(rec.source.type)
            val dep = HierarchicalGraphFactory.createCoreDependency(from, to, rec.type) {
                depCodec.read(rec.id, rec.source.payload)
            }
            dep.weight = rec.weight
            dep.attributesBitmap = rec.attributesBitmap
        }

        return root
    }
}
