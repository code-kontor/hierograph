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

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.INodeSource

/**
 * The node source for a GraphStore-backed [io.hierograph.hierarchicalgraph.model.HGModel]. It
 * carries the node's display [name] and [qualifiedName] eagerly (from the GraphStore), so the existing
 * node-metadata provider resolves them off [INodeSource] entirely in memory — the GraphStore node
 * source. [key] is the node's stable
 * GraphStore key, so on-demand lookups (e.g. per-node properties from the snapshot) can map back to it.
 * [modifiers] holds the packed access flags of a scanned type or a member (see
 * [io.hierograph.graphstore.jvm.model.TypeModifiers] and
 * [io.hierograph.graphstore.jvm.model.MemberModifiers]); it is `0` for every other node.
 */
class GraphStoreNodeSource(
    override val identifier: Any,
    override val name: String,
    override val qualifiedName: String,
    val key: String,
    val modifiers: Int = 0,
) : INodeSource {
    override var node: HGNode? = null
}
