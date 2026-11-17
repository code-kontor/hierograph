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
package io.hierograph.server.graphservice.views

import io.hierograph.server.views.ViewDocumentException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

class ViewRegistryTest {

    @TempDir
    lateinit var tmp: Path

    private val projectDir: Path get() = tmp.resolve("project")
    private val stateDir: Path get() = tmp.resolve("state")
    private val registry by lazy { ViewRegistry(projectDir, stateDir) }

    private fun doc(name: String, group: String = "g") = """{ "name": "$name", "groups": [ { "name": "$group", "members": ["a:*"] } ] }"""

    private fun write(dir: Path, file: String, text: String): Path {
        Files.createDirectories(dir)
        return dir.resolve(file).also { it.writeText(text) }
    }

    /** Rewrites [file] and moves its modification time on, so the change is seen even on coarse clocks. */
    private fun rewrite(file: Path, text: String) {
        val before = Files.getLastModifiedTime(file).toMillis()
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + 2_000))
    }

    @Test
    fun `missing directories mean no views`() {
        assertThat(registry.entries()).isEmpty()
        assertThat(registry.find("x")).isNull()
        assertThat(ViewRegistry(null, stateDir).entries()).isEmpty()
    }

    @Test
    fun `a project document is named by its name field, not its file`() {
        write(projectDir, "modules.json", doc("multi-modules"))

        val entry = registry.find("multi-modules")!!
        assertThat(entry.source).isEqualTo(ViewSource.PROJECT)
        assertThat(entry.file.name).isEqualTo("modules.json")
        assertThat(entry.isValid).isTrue()
        assertThat(entry.document!!.groups.single().name).isEqualTo("g")
        assertThat(entry.shadowsProjectView).isFalse()
    }

    @Test
    fun `a state view shadows a project view of the same name`() {
        write(projectDir, "layers.json", doc("layers", group = "from-project"))
        write(stateDir, "layers.json", doc("layers", group = "from-state"))

        val entry = registry.find("layers")!!
        assertThat(entry.source).isEqualTo(ViewSource.STATE)
        assertThat(entry.shadowsProjectView).isTrue()
        assertThat(entry.document!!.groups.single().name).isEqualTo("from-state")
        assertThat(registry.entries()).hasSize(1)
    }

    @Test
    fun `an invalid document is listed under its file name with its problems`() {
        write(projectDir, "broken.json", """{ "name": "broken", "groups": [ { "members": [] } ] }""")
        write(projectDir, "garbage.json", "{ not json")

        val broken = registry.find("broken")!!
        assertThat(broken.isValid).isFalse()
        assertThat(broken.problems).containsExactly("groups[0].name: required")
        assertThat(registry.find("garbage")!!.problems.single()).startsWith("Invalid JSON")
        assertThat(registry.names()).containsExactly("broken", "garbage")
    }

    @Test
    fun `a name defined twice in one directory is taken from the first file`() {
        write(projectDir, "a.json", doc("v", group = "first"))
        write(projectDir, "b.json", doc("v", group = "second"))

        assertThat(registry.find("v")!!.document!!.groups.single().name).isEqualTo("first")
        val duplicate = registry.entries().single { !it.isValid }
        assertThat(duplicate.file.name).isEqualTo("b.json")
        assertThat(duplicate.problems.single()).contains("already defined in a.json")
    }

    @Test
    fun `other files are ignored`() {
        write(projectDir, "README.md", "# views")
        write(projectDir, ".hidden.json", doc("hidden"))
        Files.createDirectories(projectDir.resolve("sub.json"))

        assertThat(registry.entries()).isEmpty()
    }

    @Test
    fun `define stores the document in the state directory`() {
        val entry = registry.define(doc("layers"))

        assertThat(entry.source).isEqualTo(ViewSource.STATE)
        assertThat(entry.file).isEqualTo(stateDir.resolve("layers.json"))
        assertThat(Files.readString(entry.file)).isEqualTo(doc("layers"))
        assertThat(stateDir.listDirectoryEntries()).containsExactly(stateDir.resolve("layers.json"))
    }

    @Test
    fun `define replaces a state view of the same name, also one in a file of another name`() {
        write(stateDir, "old-name.json", doc("layers", group = "old"))

        registry.define(doc("layers", group = "new"))

        assertThat(registry.find("layers")!!.document!!.groups.single().name).isEqualTo("new")
        assertThat(stateDir.resolve("old-name.json").exists()).isFalse()
        assertThat(registry.entries()).hasSize(1)
    }

    @Test
    fun `define shadows a project view without touching it`() {
        val project = write(projectDir, "layers.json", doc("layers", group = "project"))

        val entry = registry.define(doc("layers", group = "state"))

        assertThat(entry.shadowsProjectView).isTrue()
        assertThat(Files.readString(project)).isEqualTo(doc("layers", group = "project"))
    }

    @Test
    fun `an invalid document is not defined`() {
        assertThatThrownBy { registry.define("""{ "name": "Bad Name" }""") }.isInstanceOf(ViewDocumentException::class.java)

        assertThat(stateDir.exists() && stateDir.listDirectoryEntries().isNotEmpty()).isFalse()
        assertThat(registry.entries()).isEmpty()
    }

    @Test
    fun `deleting a state view restores the project view it shadowed`() {
        write(projectDir, "layers.json", doc("layers", group = "project"))
        registry.define(doc("layers", group = "state"))

        val restored = registry.delete("layers")

        assertThat(restored!!.source).isEqualTo(ViewSource.PROJECT)
        assertThat(registry.find("layers")!!.document!!.groups.single().name).isEqualTo("project")
        assertThat(stateDir.resolve("layers.json").exists()).isFalse()
    }

    @Test
    fun `deleting a state view without a project view leaves nothing`() {
        registry.define(doc("layers"))

        assertThat(registry.delete("layers")).isNull()
        assertThat(registry.find("layers")).isNull()
    }

    @Test
    fun `a project view cannot be deleted, an unknown view is reported with the known ones`() {
        val project = write(projectDir, "layers.json", doc("layers"))

        assertThatThrownBy { registry.delete("layers") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("project view")
        assertThat(project.exists()).isTrue()
        assertThatThrownBy { registry.delete("nope") }
            .isInstanceOf(UnknownViewException::class.java)
            .hasMessageContaining("Known views: layers")
    }

    @Test
    fun `edited, added and removed files take effect on the next read`() {
        val file = write(projectDir, "layers.json", doc("layers", group = "before"))
        assertThat(registry.find("layers")!!.document!!.groups.single().name).isEqualTo("before")

        rewrite(file, doc("layers", group = "after"))
        assertThat(registry.find("layers")!!.document!!.groups.single().name).isEqualTo("after")

        write(projectDir, "teams.json", doc("teams"))
        assertThat(registry.names()).containsExactly("layers", "teams")

        Files.delete(file)
        assertThat(registry.names()).containsExactly("teams")
    }

    @Test
    fun `unchanged files are not read again`() {
        write(projectDir, "layers.json", doc("layers"))

        val first = registry.find("layers")
        val second = registry.find("layers")

        assertThat(second).isSameAs(first)
    }

    @Test
    fun `when the project and the state directory are one, its views are state views`() {
        write(stateDir, "layers.json", doc("layers"))
        val same = ViewRegistry(stateDir, stateDir)

        assertThat(same.entries().single().source).isEqualTo(ViewSource.STATE)
        assertThat(same.delete("layers")).isNull()
    }
}
