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
package io.hierograph.mcp.server.core.pagination

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import io.hierograph.hierarchicalgraph.core.model.HGModel
import java.security.MessageDigest
import java.util.Base64

/**
 * The two hashes a cursor carries: the query hash (`qh`) and the data hash (`dh`).
 *
 * Both are produced in the same compact shape — the first 12 bytes of a SHA-256 digest, base64-URL
 * encoded without padding (a 16-character string) — so cursors stay small. Twelve bytes is ample
 * collision resistance for distinguishing one query or data snapshot from another.
 */

/** SHA-256 of [input], truncated to its first 12 bytes and base64-URL encoded without padding. */
private fun shortHash(input: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(12))
}

/**
 * Computes the query hash (`qh`) — a fingerprint of a request's query parameters.
 *
 * Lets the server detect when the caller changes parameters between pages, which would make a resumed
 * offset incoherent. The hash must be stable: the same query produces the same hash every time, so the
 * parameters are serialized canonically — map keys sorted ([SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS]),
 * and `null`-valued entries dropped so an unsupplied parameter and an explicit `null` hash alike.
 *
 * Callers pass the parameters that *define* the query — and must exclude `limit` and `cursor`, which
 * are deliberately not part of the query identity (the page size may legitimately vary between pages).
 * Where a parameter is a set-like filter whose element order is insignificant, the caller should
 * normalize (e.g. sort) it before hashing so a reordering isn't seen as a different query.
 */
object QueryHash {

    private val mapper = ObjectMapper().apply {
        configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
    }

    /** Returns the stable query hash for [params]. */
    fun of(params: Map<String, Any?>): String {
        val canonical = params.filterValues { it != null }
        return shortHash(mapper.writeValueAsBytes(canonical))
    }
}

/**
 * Computes the data hash (`dh`) — an identifier for a snapshot of the underlying graph.
 *
 * This is what makes cursors stateless across restarts: if the server reloads and the data is
 * unchanged, the fingerprint is identical, so cursors from before the restart still resume correctly;
 * if a rescan changed the graph, the fingerprint differs and stale cursors are rejected.
 *
 * jQAssistant exposes no scan id or timestamp here, so this is the spec's computed-fingerprint
 * fallback: it combines the total node count, the total leaf-level edge count, and an
 * order-independent accumulation of per-node identity (identifier + kind). Using order-independent
 * aggregation means the fingerprint doesn't depend on traversal order — only on *what* is in the
 * graph, not the order it happens to be visited. It is a fingerprint, not a cryptographic guarantee:
 * two genuinely different scans could in principle collide, but in practice this reliably distinguishes
 * one loaded graph from another.
 */
object DataHash {

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
            val kindHash = (node.kind?.hashCode() ?: 0).toLong()
            // Addition is commutative, so the total is independent of visitation order.
            identityAccumulator += idHash * 31 + kindHash
        }

        val canonical = "$nodeCount:$edgeCount:$identityAccumulator"
        return shortHash(canonical.toByteArray(Charsets.UTF_8))
    }
}
