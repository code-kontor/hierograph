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

import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.HierarchyRow
import io.hierograph.graphstore.parquet.ParquetTestWriter.MemberFixture
import io.hierograph.graphstore.parquet.ParquetTestWriter.RefFixture
import io.hierograph.graphstore.parquet.ParquetTestWriter.TypeFixture
import io.hierograph.mcp.javaspec.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ParquetReaderTest {

    @Test
    fun `loads types members and refs into the GraphStore via the applyClass path`(@TempDir dir: Path) {
        ParquetTestWriter.writeTypes(
            dir,
            listOf(
                TypeFixture("app", "com.foo.A", "CLASS", sourceFile = "A.java", classHash = "h1"),
                TypeFixture("app", "com.foo.B", "INTERFACE", classHash = "h2"),
            ),
        )
        ParquetTestWriter.writeMembers(
            dir,
            listOf(
                MemberFixture("com.foo.A", "com.foo.A#a()V", "METHOD", "a", "a()V", lineFrom = 1, lineTo = 3),
                MemberFixture("com.foo.A", "com.foo.A#x", "FIELD", "x", "x"),
            ),
        )
        ParquetTestWriter.writeRefs(
            dir,
            listOf(
                RefFixture("com.foo.A", "com.foo.B", "IMPLEMENTS", count = 1),
                RefFixture("com.foo.A", "java.util.List", "DEPENDS_ON", count = 4),
            ),
        )

        val store = GraphStore()
        ParquetReader().load(dir, store)

        // scanned types
        val a = store.typeByFqn("com.foo.A")!!
        assertThat(a.kind).isEqualTo(JavaKinds.CLASS)
        assertThat(a.placeholder).isFalse
        assertThat(store.typeByFqn("com.foo.B")!!.kind).isEqualTo(JavaKinds.INTERFACE)

        // members are not materialized in the type-level graph (they round-trip through Parquet but
        // stay an on-demand concern)
        assertThat(store.contains("com.foo.A#a()V")).isFalse
        assertThat(store.childKeysOf(store.typeKey("app", "com.foo.A"))).isEmpty()

        // external ref target became a placeholder under the external module
        val list = store.typeByFqn("java.util.List")!!
        assertThat(list.placeholder).isTrue
        assertThat(list.kind).isEqualTo(JavaKinds.EXTERNAL_TYPE)

        // hierarchy projection
        val rows = store.hierarchyRows().toList()
        assertThat(rows).contains(
            HierarchyRow(JavaKinds.MODULE, "app", null),
            HierarchyRow(JavaKinds.CLASS, "com.foo.A", "com.foo"),
            HierarchyRow(JavaKinds.INTERFACE, "com.foo.B", "com.foo"),
            HierarchyRow(JavaKinds.EXTERNAL_TYPE, "java.util.List", "java.util"),
        )

        // dependencies
        val deps = store.typeDependencies().associateBy { it.to }
        assertThat(deps.getValue("com.foo.B").implements).isTrue
        val toList = deps.getValue("java.util.List")
        assertThat(toList.other).isTrue
        assertThat(toList.weight).isEqualTo(4)
    }

    @Test
    fun `missing members and refs tables load the types only`(@TempDir dir: Path) {
        ParquetTestWriter.writeTypes(dir, listOf(TypeFixture("app", "com.foo.A", "CLASS")))

        val store = GraphStore()
        ParquetReader().load(dir, store)

        assertThat(store.typeByFqn("com.foo.A")!!.placeholder).isFalse
        assertThat(store.childKeysOf(store.typeKey("app", "com.foo.A"))).isEmpty()
        assertThat(store.typeDependencies().toList()).isEmpty()
    }
}
