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
package io.hierograph.hierarchicalgraph.model

/**
 * Reads display metadata (name, qualified name, kind) off an [HGNode] via its [HGNode.nodeSource]. A thin,
 * source-agnostic accessor used across the tool and GraphQL surfaces so every reader agrees on a node's
 * name and kind. */
object NodeMetadata {

    fun getName(node: HGNode): String = node.nodeSource.name

    fun getQualifiedName(node: HGNode): String = node.nodeSource.qualifiedName

    /**
     * The node's semantic kind, from the authoritative [HGNode.kind] assigned during graph construction
     * (e.g. `java.module`, `java.class`), so it agrees with every other kind read across the tool surface.
     */
    fun getKind(node: HGNode): String = node.kind?.toString() ?: "Unknown"
}
