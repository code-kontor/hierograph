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
package io.hierograph.graphstore.jqa

import io.hierograph.graphstore.HierarchyRow
import io.hierograph.graphstore.TypeDependency
import io.hierograph.mcp.javaspec.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SnapshotDiffTest {

    private fun hrow(fqn: String, parent: String?) = HierarchyRow(JavaKinds.CLASS, fqn, parent)

    private fun dep(
        from: String, to: String, weight: Int = 1,
        extends: Boolean = false, implements: Boolean = false, annotatedBy: Boolean = false, other: Boolean = false,
    ) = TypeDependency(from, to, weight, extends, implements, annotatedBy, other)

    @Test
    fun `identical projections produce an empty diff`() {
        val h = listOf(hrow("com.foo.A", "com.foo"), hrow("com.foo.B", "com.foo"))
        val d = listOf(dep("com.foo.A", "com.foo.B", extends = true))

        val result = SnapshotDiff.diff(h, d, h, d)

        assertThat(result.isEmpty).isTrue
        assertThat(result.render()).isEqualTo("No differences.\n")
    }

    @Test
    fun `hierarchy rows present on only one side are reported`() {
        val gs = listOf(hrow("com.foo.A", "com.foo"), hrow("com.foo.Extra", "com.foo"))
        val ref = listOf(hrow("com.foo.A", "com.foo"), hrow("com.foo.Missing", "com.foo"))

        val result = SnapshotDiff.diff(gs, emptyList(), ref, emptyList())

        assertThat(result.hierarchy.onlyInGraphStore).containsExactly(hrow("com.foo.Extra", "com.foo"))
        assertThat(result.hierarchy.onlyInReference).containsExactly(hrow("com.foo.Missing", "com.foo"))
        assertThat(result.isEmpty).isFalse
    }

    @Test
    fun `the other and weight divergences can be ignored to reach equivalence`() {
        // Same edge, but GraphStore adds the structural count to the weight and flips 'other'.
        val gs = listOf(dep("com.foo.A", "com.foo.B", weight = 6, extends = true, other = true))
        val ref = listOf(dep("com.foo.A", "com.foo.B", weight = 5, extends = true, other = false))

        assertThat(SnapshotDiff.diff(emptyList(), gs, emptyList(), ref).dependencies.isEmpty).isFalse

        val lenient = SnapshotDiff.diff(
            emptyList(), gs, emptyList(), ref,
            DiffOptions(ignoreDependencyOther = true, ignoreDependencyWeight = true),
        )
        assertThat(lenient.dependencies.isEmpty).isTrue
        assertThat(lenient.isEmpty).isTrue
    }
}
