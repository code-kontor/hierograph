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

import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGRootNode

/**
 * Projects an [HGRootNode] to a [GraphSnapshot] by one pre-order walk of the
 * tree and one identity-dedup pass over the dependencies reachable through
 * `outgoingCoreDependencies` on every visited node.
 *
 * Derived caches (`accumulatedOutgoing/Incoming`, `predecessors`,
 * `getOutgoingDependenciesTo(...)` etc.) are never accessed.
 */
class GraphWriter(private val codecs: CodecRegistry) {

    fun write(root: HGRootNode): GraphSnapshot {
        val nodes = mutableListOf<NodeRecord>()
        val seenDeps = LinkedHashSet<HGCoreDependency>()

        traverse(root, parent = null) { node, parent ->
            seenDeps.addAll(node.outgoingCoreDependencies)
            if (node !== root) nodes += toRecord(node, parent)
        }

        return GraphSnapshot(
            root = toRecord(root, parent = null),
            nodes = nodes,
            deps = seenDeps.map { toRecord(it) }
        )
    }

    private fun traverse(node: HGNode, parent: HGNode?, visit: (HGNode, HGNode?) -> Unit) {
        visit(node, parent)
        for (child in node.children) {
            traverse(child, node, visit)
        }
    }

    private fun toRecord(node: HGNode, parent: HGNode?): NodeRecord {
        val codec = codecs.nodeCodecFor(node.nodeSource)
        return NodeRecord(
            id = node.identifier.toString(),
            parentId = parent?.identifier?.toString(),
            kind = encodeKind(node.kind),
            source = SourceRef(codec.typeId, codec.write(node.nodeSource))
        )
    }

    private fun toRecord(dep: HGCoreDependency): DepRecord {
        val codec = codecs.depCodecFor(dep.dependencySource)
        return DepRecord(
            id = dep.dependencySource.identifier.toString(),
            fromId = dep.from.identifier.toString(),
            toId = dep.to.identifier.toString(),
            type = dep.type,
            weight = dep.weight,
            attributesBitmap = dep.attributesBitmap,
            source = SourceRef(codec.typeId, codec.write(dep.dependencySource))
        )
    }
}
