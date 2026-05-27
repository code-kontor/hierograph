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
package io.hierograph.hierarchicalgraph.core.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PredecessorSuccessorTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    @Test
    fun `root has no predecessors`() {
        assertThat(g.root.predecessors).isEmpty()
    }

    @Test
    fun `a1 predecessors are root`() {
        assertThat(g.a1.predecessors).containsExactly(g.root)
    }

    @Test
    fun `a3 predecessors are a2, a1, root`() {
        assertThat(g.a3.predecessors).containsExactly(g.a2, g.a1, g.root)
    }

    @Test
    fun `root isPredecessorOf a3`() {
        assertThat(g.root.isPredecessorOf(g.a3)).isTrue()
    }

    @Test
    fun `a1 isPredecessorOf a3`() {
        assertThat(g.a1.isPredecessorOf(g.a3)).isTrue()
    }

    @Test
    fun `a1 is not predecessorOf b1`() {
        assertThat(g.a1.isPredecessorOf(g.b1)).isFalse()
    }

    @Test
    fun `a3 isSuccessorOf a1`() {
        assertThat(g.a3.isSuccessorOf(g.a1)).isTrue()
    }

    @Test
    fun `a1 is not successorOf a3`() {
        assertThat(g.a1.isSuccessorOf(g.a3)).isFalse()
    }

    @Test
    fun `isPredecessorOf null returns false`() {
        assertThat(g.root.isPredecessorOf(null)).isFalse()
    }

    @Test
    fun `isSuccessorOf null returns false`() {
        assertThat(g.a1.isSuccessorOf(null)).isFalse()
    }
}
