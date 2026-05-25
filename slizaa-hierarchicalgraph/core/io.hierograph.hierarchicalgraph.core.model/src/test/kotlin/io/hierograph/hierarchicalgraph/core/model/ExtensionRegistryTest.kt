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
