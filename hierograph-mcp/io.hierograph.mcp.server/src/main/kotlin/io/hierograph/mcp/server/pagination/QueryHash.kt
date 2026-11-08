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
package io.hierograph.mcp.server.pagination

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.security.MessageDigest
import java.util.Base64

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
