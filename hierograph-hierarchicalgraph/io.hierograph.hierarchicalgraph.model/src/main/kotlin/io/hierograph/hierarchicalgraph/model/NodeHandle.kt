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

import java.security.MessageDigest

/**
 * Derives a node's opaque, compact protocol handle from its stable semantic identity. The handle is what
 * the agent round-trips across MCP tools; the identity is the node itself. Keeping them separate — the
 * identity lives on the node source, the handle is this projection of it — is what lets the handle be both
 * cheap on the wire and durable across rescans.
 *
 * Because the handle is a pure function of the identity (no allocation counter, no backend id, no
 * persisted state), the same node yields the same handle across rescans, restarts, fresh clones, and even
 * across different graph backends — provided the node's semantic coordinates are
 * unchanged. A rename or package move changes the identity, hence the handle, which is correct: that is a
 * different node.
 *
 * A node's identity is its [nodeKey] — the GraphStore key, which is already **artifact-scoped**
 * (`type:<artifact>:<fqn>`, `pkg:<artifact>:<fqn>`, or the artifact key itself). Because the artifact is
 * baked into the key, the same fully-qualified type name defined in two artifacts has two distinct keys
 * and therefore two distinct handles — no separate artifact component is needed here.
 *
 * The value is a SHA-256 of the key truncated to 53 bits. 53 bits is the largest integer that survives a
 * round-trip through a JSON/JavaScript `number` (IEEE-754 double) intact — MCP payloads are JSON, so a
 * wider handle could be silently corrupted by a JS client. Collisions are a birthday event in a 2^53
 * space (well under 1-in-10,000 for graphs up to ~10^6 nodes); callers that build a whole node set at
 * once should nonetheless check for them (see [assertNoCollision]) and fail fast rather than serve an
 * ambiguous handle.
 */
object NodeHandle {

    /** 2^53 - 1: the largest exactly-representable integer in an IEEE-754 double (JSON-safe). */
    private const val JSON_SAFE_MASK = 0x1FFFFFFFFFFFFFL

    /**
     * The stable 53-bit handle for the node identified by [nodeKey]. Pure and deterministic across
     * processes, platforms, and graph backends.
     */
    fun of(nodeKey: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(nodeKey.toByteArray(Charsets.UTF_8))
        var acc = 0L
        for (i in 0 until 8) {
            acc = (acc shl 8) or (digest[i].toLong() and 0xFF)
        }
        return acc and JSON_SAFE_MASK
    }

    /**
     * Records [identity]'s handle in [seen] and fails fast if a different identity already produced the
     * same handle — the only way a content-addressed handle can be wrong. Intended for one-shot use while
     * assigning handles to a full node set.
     */
    fun assertNoCollision(identity: String, handle: Long, seen: MutableMap<Long, String>) {
        val previous = seen.put(handle, identity)
        check(previous == null || previous == identity) {
            "Node handle collision at $handle for '$previous' and '$identity' — widen the handle or disambiguate the keys."
        }
    }
}
