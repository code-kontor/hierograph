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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExtensionRegistryTest {

    private lateinit var g: SimpleTestGraph

    @BeforeEach
    fun setup() { g = SimpleTestGraph() }

    interface MyExtension {
        fun hello(): String
    }

    class MyExtensionImpl : MyExtension {
        override fun hello() = "world"
    }

    @Test
    fun `register and get typed extension`() {
        val ext = MyExtensionImpl()
        g.coreGraph.registerExtension(MyExtension::class.java, ext)

        assertThat(g.coreGraph.hasExtension(MyExtension::class.java)).isTrue()
        assertThat(g.coreGraph.getExtension(MyExtension::class.java)).isSameAs(ext)
    }

    @Test
    fun `get unregistered extension returns null`() {
        assertThat(g.coreGraph.hasExtension(MyExtension::class.java)).isFalse()
        assertThat(g.coreGraph.getExtension(MyExtension::class.java)).isNull()
    }

    @Test
    fun `register and get string-keyed extension`() {
        val ext = MyExtensionImpl()
        g.coreGraph.registerExtension("myKey", ext)

        assertThat(g.coreGraph.getExtension("myKey", MyExtension::class.java)).isSameAs(ext)
    }

    @Test
    fun `getExtension with wrong type throws`() {
        g.coreGraph.registerExtension("myKey", "a string")

        assertThatThrownBy {
            g.coreGraph.getExtension("myKey", MyExtension::class.java)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `getExtension with wrong type returns null for unregistered key`() {
        assertThat(g.coreGraph.getExtension("myKey", MyExtension::class.java)).isNull()
    }

    @Test
    fun `registerExtension replaces existing`() {
        val ext1 = MyExtensionImpl()
        val ext2 = MyExtensionImpl()
        g.coreGraph.registerExtension(MyExtension::class.java, ext1)
        g.coreGraph.registerExtension(MyExtension::class.java, ext2)

        assertThat(g.coreGraph.getExtension(MyExtension::class.java)).isSameAs(ext2)
    }
}
