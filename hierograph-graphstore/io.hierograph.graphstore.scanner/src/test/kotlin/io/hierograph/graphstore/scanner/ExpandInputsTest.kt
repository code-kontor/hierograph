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
package io.hierograph.graphstore.scanner

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ExpandInputsTest {

    @Test
    fun `a glob in the last segment expands to the matching entries, sorted`(@TempDir dir: Path) {
        listOf("b-1.0.jar", "a-1.0.jar", "notes.txt").forEach { Files.writeString(dir.resolve(it), "") }
        Files.createDirectories(dir.resolve("classes"))

        assertThat(expandInputs(listOf("$dir/*.jar"))).containsExactly(dir.resolve("a-1.0.jar"), dir.resolve("b-1.0.jar"))
        assertThat(expandInputs(listOf("$dir/*.jar", "$dir/classes")))
            .containsExactly(dir.resolve("a-1.0.jar"), dir.resolve("b-1.0.jar"), dir.resolve("classes"))
    }

    @Test
    fun `a missing input or an empty pattern fails with a message naming it`(@TempDir dir: Path) {
        assertThatThrownBy { expandInputs(listOf("$dir/missing.jar")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("Input not found: $dir/missing.jar")
        assertThatThrownBy { expandInputs(listOf("$dir/*.jar")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage("No input matches $dir/*.jar")
    }
}
