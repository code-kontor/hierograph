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
package io.hierograph.graphstore

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TypeResolverTest {

    @Test
    fun `OWNER_FIRST prefers the owner, else the smallest definer`() {
        assertThat(TypeResolver.OWNER_FIRST.resolve("a", "Foo", setOf("a", "b"))).isEqualTo("a")
        assertThat(TypeResolver.OWNER_FIRST.resolve("z", "Foo", setOf("b", "a"))).isEqualTo("a")
    }

    @Test
    fun `classpathOrder binds to the first definer on the owner's classpath`() {
        // Two owners with opposite dependency order; the same FQN is defined by both libX and libY.
        val resolver = TypeResolver.classpathOrder(
            mapOf(
                "a" to listOf("a", "libX", "libY"),
                "b" to listOf("b", "libY", "libX"),
            ),
        )
        assertThat(resolver.resolve("a", "Foo", setOf("libX", "libY"))).isEqualTo("libX") // a sees libX first
        assertThat(resolver.resolve("b", "Foo", setOf("libX", "libY"))).isEqualTo("libY") // b sees libY first
        // The owner itself defines it → the owner wins (its own output is first on its classpath).
        assertThat(resolver.resolve("a", "Bar", setOf("a", "libX"))).isEqualTo("a")
    }

    @Test
    fun `classpathOrder falls back to OWNER_FIRST for an owner with no recorded classpath`() {
        val resolver = TypeResolver.classpathOrder(mapOf("a" to listOf("a", "libX")))
        // Unknown owner → OWNER_FIRST (smallest definer, since the owner is not among them).
        assertThat(resolver.resolve("unknown", "Foo", setOf("libY", "libX"))).isEqualTo("libX")
        // Known owner, but none of its classpath entries define the FQN → OWNER_FIRST fallback.
        assertThat(resolver.resolve("a", "Foo", setOf("libZ"))).isEqualTo("libZ")
    }
}
