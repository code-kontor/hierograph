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
package io.hierograph.server.graphservice.pagination

import io.hierograph.hierarchicalgraph.model.HGModel
import java.security.MessageDigest
import java.util.Base64

/** SHA-256 of [input], truncated to its first 12 bytes and base64-URL encoded without padding. */
private fun shortHash(input: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(12))
}

/**
 * Computes the data hash (`dh`) — an identifier for a snapshot of the underlying graph.
 *
 * This is what makes cursors stateless across reloads and restarts: if the server reloads and the data
 * is unchanged, the fingerprint is identical, so cursors from before the reload still resume correctly;
 * if a rescan changed the graph, the fingerprint differs and stale cursors are rejected. Every input is
 * hashed from its text, so the fingerprint is also stable across server restarts.
 *
 * The snapshot carries no scan id or timestamp, so the fingerprint is computed: it combines the total
 * node count, the total leaf-level edge count, and an order-independent accumulation of per-node identity (identifier + kind). Using order-independent
 * aggregation means the fingerprint doesn't depend on traversal order — only on *what* is in the
 * graph, not the order it happens to be visited. It is a fingerprint, not a cryptographic guarantee:
 * two genuinely different scans could in principle collide, but in practice this reliably distinguishes
 * one loaded graph from another.
 */
object DataHash {

    /**
     * The data hash of a view: [dataHash] of the base graph combined with a fingerprint of the view
     * document's text, so a cursor issued in a view is rejected when either the graph or the document changes.
     */
    fun combine(dataHash: String, documentText: String): String =
        shortHash("$dataHash\u0000$documentText".toByteArray(Charsets.UTF_8))

    /** Computes the data-snapshot fingerprint for the loaded [model]. */
    fun fingerprint(model: HGModel): String {
        val hierarchy = model.hierarchy
        var nodeCount = 0L
        var edgeCount = 0L
        var identityAccumulator = 0L

        hierarchy.traverse(hierarchy.rootNode) { node ->
            nodeCount++
            edgeCount += node.outgoingCoreDependencies.size
            val idHash = node.identifier.toString().hashCode().toLong()
            // Hash the kind's text, not the kind object: an enum's hashCode() differs between JVM processes.
            val kindHash = (node.kind?.toString()?.hashCode() ?: 0).toLong()
            // Addition is commutative, so the total is independent of visitation order.
            identityAccumulator += idHash * 31 + kindHash
        }

        val canonical = "$nodeCount:$edgeCount:$identityAccumulator"
        return shortHash(canonical.toByteArray(Charsets.UTF_8))
    }
}
