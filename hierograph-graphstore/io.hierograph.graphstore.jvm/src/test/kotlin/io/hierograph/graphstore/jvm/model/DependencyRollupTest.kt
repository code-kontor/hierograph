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
package io.hierograph.graphstore.jvm.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DependencyRollupTest {

    private val owner = "com.foo.A"
    private val method = "com.foo.A#void m()"

    private fun fact(from: String, toType: String, kind: String, toKey: String = toType) = Dependency(from, owner, toKey, toType, DependencyKind.fromValue(kind)!!)

    @Test
    fun `counted facts roll up per target type and kind, in order of first appearance`() {
        val refs = DependencyRollup.refsOf(
            owner,
            listOf(
                fact(method, "com.foo.B", "calls", "com.foo.B#void x()"),
                fact(owner, "com.foo.Base", "extends"),
                fact(method, "com.foo.B", "calls", "com.foo.B#void y()"),
                fact(method, "com.foo.B", "casts"),
            ),
        )
        assertThat(refs).containsExactly(
            Ref("com.foo.B", RefKind.INVOKES, 2),
            Ref("com.foo.Base", RefKind.EXTENDS, 1),
            Ref("com.foo.B", RefKind.CAST, 1),
        )
    }

    @Test
    fun `annotated_by on the type is structural, on members and parameters it is not`() {
        val refs = DependencyRollup.refsOf(
            owner,
            listOf(
                fact(owner, "com.foo.Ann", "annotated_by"),
                fact(method, "com.foo.Ann", "annotated_by"),
                fact(method, "com.foo.Ann", "parameter_annotated_by"),
            ),
        )
        assertThat(refs).containsExactly(
            Ref("com.foo.Ann", RefKind.ANNOTATED_BY, 1),
            Ref("com.foo.Ann", RefKind.MEMBER_ANNOTATED_BY, 2),
        )
    }

    @Test
    fun `self, primitive, raises and derived facts carry no weight`() {
        val refs = DependencyRollup.refsOf(
            owner,
            listOf(
                fact(method, owner, "calls", "com.foo.A#void n()"),           // self
                fact(method, "int", "parameter_type"),                         // primitive
                fact(method, "void", "returns"),                               // void
                fact(method, "java.lang.IllegalStateException", "raises"),     // not counted
                fact(method, "com.foo.B", "overrides", "com.foo.B#void m()"),  // derived: never counted
            ),
        )
        assertThat(refs).isEmpty()
    }

    @Test
    fun `every counted kind maps onto a stored kind, raises onto none`() {
        for (kind in DependencyKind.entries) {
            assertThat(DependencyRollup.refKindOf(kind, sourceIsType = false) != null).describedAs(kind.value).isEqualTo(kind.counted)
            assertThat(DependencyKind.fromValue(kind.value)).isEqualTo(kind)
        }
    }
}
