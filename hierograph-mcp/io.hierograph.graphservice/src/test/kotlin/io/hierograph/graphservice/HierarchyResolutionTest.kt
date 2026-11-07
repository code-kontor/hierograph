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
package io.hierograph.graphservice

import io.hierograph.graphservice.HierarchyResolution.Method
import io.hierograph.graphservice.HierarchyResolution.Type
import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.model.MemberModifiers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The load-time hierarchy resolver: from `Sub extends Base` (both declaring `foo()`) it derives the
 * override edge, and from a direct call to `Base#foo` it derives the virtual dispatch to `Sub#foo`.
 */
class HierarchyResolutionTest {

    private fun method(owner: String, signature: String, modifiers: Int = 0) =
        Method("$owner#$signature", signature, modifiers, lineFrom = 1)

    private val base = Type("com.Base", directSupertypes = emptyList(), methods = listOf(method("com.Base", "void foo()")))
    private val sub = Type("com.Sub", directSupertypes = listOf("com.Base"), methods = listOf(method("com.Sub", "void foo()")))

    @Test
    fun `derives overrides and virtual dispatch`() {
        // caller com.Client#run() calls the static type Base#foo()
        val directCall = DetailRef("com.Client#void run()", "com.Client", "com.Base#void foo()", "com.Base", "calls", 5)

        val derived = HierarchyResolution.resolve(listOf(base, sub), listOf(directCall))

        assertThat(derived).contains(
            DetailRef("com.Sub#void foo()", "com.Sub", "com.Base#void foo()", "com.Base", "overrides", 1),
            DetailRef("com.Client#void run()", "com.Client", "com.Sub#void foo()", "com.Sub", "calls", 5),
        )
        // nothing is derived into or through unscanned types
        assertThat(derived).noneMatch { it.toType == "com.Client" }
    }

    @Test
    fun `a virtual-call target that is a bridge is redirected to the real method`() {
        // com.A#self() returns Object; com.B covariantly narrows it to com.B, so javac emits a real
        // `com.B self()` plus a synthetic bridge `java.lang.Object self()` forwarding to it.
        val a = Type("com.A", emptyList(), listOf(method("com.A", "java.lang.Object self()")))
        val b = Type(
            "com.B", listOf("com.A"),
            listOf(
                method("com.B", "com.B self()"),
                Method("com.B#java.lang.Object self()", "java.lang.Object self()", modifiers = 0, lineFrom = 1, bridgeTarget = "com.B#com.B self()"),
            ),
        )
        val call = DetailRef("com.C#void run()", "com.C", "com.A#java.lang.Object self()", "com.A", "calls", 5)

        val derived = HierarchyResolution.resolve(listOf(a, b), listOf(call))

        // the virtual dispatch resolves to the real method, and no edge points at the synthetic bridge
        assertThat(derived).contains(
            DetailRef("com.C#void run()", "com.C", "com.B#com.B self()", "com.B", "calls", 5),
        )
        assertThat(derived).noneMatch { it.relationship == "calls" && it.toKey == "com.B#java.lang.Object self()" }
    }

    @Test
    fun `static and private methods take no part in overrides`() {
        val baseS = Type("com.B", emptyList(), listOf(method("com.B", "void s()", MemberModifiers.STATIC)))
        val subS = Type("com.S", listOf("com.B"), listOf(method("com.S", "void s()", MemberModifiers.STATIC)))
        assertThat(HierarchyResolution.resolve(listOf(baseS, subS), emptyList())).isEmpty()
    }
}
