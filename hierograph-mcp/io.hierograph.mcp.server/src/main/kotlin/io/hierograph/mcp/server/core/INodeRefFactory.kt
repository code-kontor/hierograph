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

import io.hierograph.hierarchicalgraph.core.model.HGNode

/**
 * Defines the API for converting [io.hierograph.hierarchicalgraph.core.model.HGNode] instances into the map-based
 * representations used in MCP tool responses.
 *
 * Three flavours of node representation:
 * - **Minimal NodeRef** – identity fields only; used inside larger structures (edge endpoints, path steps).
 * - **Enriched NodeRef** – identity plus kind-appropriate metadata; used as primary browse results.
 * - **Slim node entry** – display fields registered into a shared `nodes` map for deduplication.
 */
interface INodeRefFactory {

    // ── NodeRef creation ───────────────────────────────────────────────

    /**
     * Identity-only NodeRef: `id`, `name`, `qualified_name`, `kind`, `parent_id`, `parent_kind`.
     */
    fun minimalNodeRef(node: HGNode): LinkedHashMap<String, Any?>

    /**
     * Identity plus kind-appropriate metadata (counts, modifiers, flags).
     * The metadata varies by node kind (module, package, type, method, field).
     */
    fun enrichedNodeRef(node: HGNode): LinkedHashMap<String, Any?>

    /**
     * NodeRef for a Java primitive type (`void`, `int`, `boolean`, etc.).
     * Returned with `id: null` and `kind: "java.primitive"`.
     */
    fun primitiveRef(name: String): LinkedHashMap<String, Any?>

    // ── slim payload encoding ───────────────────────────────

    /**
     * Registers a node's display fields into a per-response [nodes] map keyed by
     * stringified node ID. First registration wins.
     */
    fun putSlimNode(nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?)

    /**
     * Convenience overload that extracts display fields from an [HGNode].
     */
    fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode)

    // ── utility ────────────────────────────────────────────────────────

    /**
     * Counts all descendants of [node] whose kind is in [kinds].
     */
    fun countDescendantsByKind(node: HGNode, kinds: Set<*>): Int

    /**
     * Counts all descendants of [node] (regardless of kind).
     */
    fun countDescendants(node: HGNode): Long
}