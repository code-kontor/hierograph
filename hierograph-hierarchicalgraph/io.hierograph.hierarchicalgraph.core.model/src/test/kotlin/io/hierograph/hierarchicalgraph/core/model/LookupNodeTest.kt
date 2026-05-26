/*
 * Copyright 2024 Gerd Wuetherich
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
package io.hierograph.hierarchicalgraph.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LookupNodeTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `lookupNode returns correct node`() {
        val found = g.root.lookupNode(g.a1.identifier)
        assertThat(found).isSameAs(g.a1)
    }

    @Test
    fun `lookupNode returns correct leaf node`() {
        val found = g.root.lookupNode(g.b3.identifier)
        assertThat(found).isSameAs(g.b3)
    }

    @Test
    fun `lookupNode returns null for unknown identifier`() {
        val found = g.root.lookupNode(-1L)
        assertThat(found).isNull()
    }
}
