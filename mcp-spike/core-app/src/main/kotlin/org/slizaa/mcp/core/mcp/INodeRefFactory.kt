package org.slizaa.mcp.core.mcp

import org.slizaa.hierarchicalgraph.core.model.HGNode

/**
 * Defines the API for converting [HGNode] instances into the map-based
 * representations used in MCP tool responses.
 *
 * Three flavours of node representation:
 * - **Minimal NodeRef** – identity fields only; used inside larger structures (edge endpoints, path steps).
 * - **Enriched NodeRef** – identity plus kind-appropriate metadata; used as primary browse results.
 * - **Slim node entry** – display fields registered into a shared `nodes` map for deduplication (ADR-0001).
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

    // ── slim payload encoding (ADR-0001) ───────────────────────────────

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
