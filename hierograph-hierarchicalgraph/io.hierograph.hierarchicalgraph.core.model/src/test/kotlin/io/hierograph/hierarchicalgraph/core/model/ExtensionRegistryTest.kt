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
        g.root.registerExtension(MyExtension::class.java, ext)

        assertThat(g.root.hasExtension(MyExtension::class.java)).isTrue()
        assertThat(g.root.getExtension(MyExtension::class.java)).isSameAs(ext)
    }

    @Test
    fun `get unregistered extension returns null`() {
        assertThat(g.root.hasExtension(MyExtension::class.java)).isFalse()
        assertThat(g.root.getExtension(MyExtension::class.java)).isNull()
    }

    @Test
    fun `register and get string-keyed extension`() {
        val ext = MyExtensionImpl()
        g.root.registerExtension("myKey", ext)

        assertThat(g.root.hasExtension("myKey", MyExtension::class.java)).isTrue()
        assertThat(g.root.getExtension("myKey", MyExtension::class.java)).isSameAs(ext)
    }

    @Test
    fun `getExtension with wrong type throws`() {
        g.root.registerExtension("myKey", "a string")

        assertThatThrownBy {
            g.root.getExtension("myKey", MyExtension::class.java)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `hasExtension with wrong type returns false`() {
        g.root.registerExtension("myKey", "a string")
        assertThat(g.root.hasExtension("myKey", MyExtension::class.java)).isFalse()
    }

    @Test
    fun `registerExtension replaces existing`() {
        val ext1 = MyExtensionImpl()
        val ext2 = MyExtensionImpl()
        g.root.registerExtension(MyExtension::class.java, ext1)
        g.root.registerExtension(MyExtension::class.java, ext2)

        assertThat(g.root.getExtension(MyExtension::class.java)).isSameAs(ext2)
    }
}
