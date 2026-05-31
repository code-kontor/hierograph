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

/**
 * Wire-format root for a serialized [io.hierograph.hierarchicalgraph.core.model.HGRootNode].
 *
 * The shape is intentionally flat — nodes refer to one another by `id`
 * strings — so the snapshot has no object cycles regardless of how
 * interconnected the original graph is.
 *
 * [nodes] is the set of non-root nodes in pre-order (parents before
 * children, in `HGNode.children` iteration order). [deps] is every
 * `HGCoreDependency`, deduplicated by identity.
 */
data class GraphSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val root: NodeRecord,
    val nodes: List<NodeRecord>,
    val deps: List<DepRecord>
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Flat record for a single `HGNode`. [parentId] is `null` only for the root.
 * [kind] is `null` exactly when `HGNode.kind` is `null` on the source node.
 */
data class NodeRecord(
    val id: String,
    val parentId: String?,
    val kind: KindRef?,
    val source: SourceRef
)

/**
 * Flat record for a single `HGCoreDependency`. [id] is the string form of
 * the underlying `IDependencySource.identifier`.
 */
data class DepRecord(
    val id: String,
    val fromId: String,
    val toId: String,
    val type: String,
    val weight: Int,
    val attributesBitmap: Int,
    val source: SourceRef
)

/**
 * Carries enough information to reconstruct `HGNode.kind`. [type] is the FQCN
 * of the kind class; [value] its string form (`Enum.name` for enums, the
 * string itself for `String` kinds).
 */
data class KindRef(val type: String, val value: String)

/**
 * Discriminated payload for an `INodeSource` or `IDependencySource`. [type]
 * matches a `NodeSourceCodec.typeId` / `DepSourceCodec.typeId` registered in
 * the [CodecRegistry] used at read time. [payload] is opaque to the
 * serializer and meaningful only to the codec.
 */
data class SourceRef(val type: String, val payload: Map<String, String> = emptyMap())
