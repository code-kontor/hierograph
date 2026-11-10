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
package io.hierograph.server.graphservice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OutputSignatureTest {

    private fun moduleDir(root: Path, name: String, classes: Int): Path = root.resolve(name).also { dir ->
        repeat(classes) { i ->
            val file = dir.resolve("p/C$i.class")
            Files.createDirectories(file.parent)
            Files.write(file, ByteArray(i + 1))
        }
    }

    @Test
    fun `concurrent walks give each module's signature, in input order`(@TempDir tmp: Path) {
        val modules = listOf(
            listOf(moduleDir(tmp, "a", 3)),
            listOf(moduleDir(tmp, "b-java", 2), moduleDir(tmp, "b-kotlin", 4)),
            listOf(tmp.resolve("missing")),
            listOf(moduleDir(tmp, "c", 5)),
        )
        val expected = modules.map { dirs -> dirs.sumOf { outputSignature(it) } }

        assertThat(outputSignatures(modules)).isEqualTo(expected)
        val pool = signaturePool(3)
        try {
            assertThat(outputSignatures(modules, pool)).isEqualTo(expected)
        } finally {
            pool.shutdownNow()
        }
        assertThat(expected[2]).isZero()
        assertThat(expected.distinct()).hasSize(4)
    }
}
