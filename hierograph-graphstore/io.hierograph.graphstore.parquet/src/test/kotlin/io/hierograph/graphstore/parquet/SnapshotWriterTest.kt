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
package io.hierograph.graphstore.parquet

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.MemberFacts
import io.hierograph.graphstore.model.Ref
import io.hierograph.graphstore.model.RefKind
import io.hierograph.graphstore.parquet.SnapshotWriter.ArtifactClass
import io.hierograph.mcp.javaspec.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SnapshotWriterTest {

    private val classes = listOf(
        ArtifactClass(
            "app",
            ClassFacts(
                fqn = "com.foo.A",
                kind = JavaKinds.CLASS,
                modifiers = 0,
                members = listOf(MemberFacts("com.foo.A#a()V", JavaKinds.METHOD, "a()V", lineFrom = 1, lineTo = 2)),
                refs = listOf(
                    Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1),
                    Ref("com.foo.A", "java.util.List", RefKind.DEPENDS_ON, count = 7),
                ),
                classHash = "h1",
                sourceFile = "A.java",
            ),
        ),
        ArtifactClass("app", ClassFacts("com.foo.B", JavaKinds.CLASS, 0, emptyList(), emptyList(), "h2")),
    )

    @Test
    fun `writeClassFacts round-trips through the loader uncompressed`(@TempDir dir: Path) {
        SnapshotWriter.writeClassFacts(dir, classes, CompressionCodec.UNCOMPRESSED)
        assertRoundTrip(dir)
    }

    @Test
    fun `writeClassFacts round-trips through the loader with zstd`(@TempDir dir: Path) {
        SnapshotWriter.writeClassFacts(dir, classes) // default ZSTD
        assertRoundTrip(dir)
    }

    private fun assertRoundTrip(dir: Path) {
        val store = GraphStore()
        ParquetSnapshotLoader().load(dir, store)

        assertThat(store.node("com.foo.A")!!.placeholder).isFalse
        assertThat(store.childKeysOf("com.foo.A")).containsExactly("com.foo.A#a()V")
        assertThat(store.node("java.util.List")!!.placeholder).isTrue

        val deps = store.typeDependencies().associateBy { it.to }
        assertThat(deps.getValue("com.foo.B").extends).isTrue
        assertThat(deps.getValue("java.util.List").weight).isEqualTo(7)
    }
}
