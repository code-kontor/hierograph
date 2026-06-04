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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QueryHashTest {

    @Test
    fun `the same parameters hash identically`() {
        val a = QueryHash.of(mapOf("nodeId" to 42L, "kindFilter" to listOf("types")))
        val b = QueryHash.of(mapOf("nodeId" to 42L, "kindFilter" to listOf("types")))
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `key insertion order does not affect the hash`() {
        val a = QueryHash.of(linkedMapOf("nodeId" to 42L, "namePattern" to "foo"))
        val b = QueryHash.of(linkedMapOf("namePattern" to "foo", "nodeId" to 42L))
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `an unsupplied parameter and an explicit null hash alike`() {
        val withNull = QueryHash.of(mapOf("nodeId" to 42L, "namePattern" to null))
        val without = QueryHash.of(mapOf("nodeId" to 42L))
        assertThat(withNull).isEqualTo(without)
    }

    @Test
    fun `different parameter values produce different hashes`() {
        val a = QueryHash.of(mapOf("nodeId" to 42L))
        val b = QueryHash.of(mapOf("nodeId" to 43L))
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `list element order is significant`() {
        // The hasher does not reorder list elements; normalization is the caller's responsibility.
        val a = QueryHash.of(mapOf("kindFilter" to listOf("types", "members")))
        val b = QueryHash.of(mapOf("kindFilter" to listOf("members", "types")))
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `the hash is a 16-character url-safe string`() {
        val hash = QueryHash.of(mapOf("nodeId" to 42L))
        assertThat(hash).hasSize(16).matches("[A-Za-z0-9_-]+")
    }
}
