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
package io.hierograph.graphstore.jvm.archive.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Packing and decoding of the access flags: [MemberModifiers] and [TypeModifiers]. */
class ModifiersTest {

    @Test
    fun `visibility is one of three bits, or package-private without one`() {
        assertThat(MemberModifiers.visibility(MemberModifiers.pack("public"))).isEqualTo("public")
        assertThat(MemberModifiers.visibility(MemberModifiers.pack("PROTECTED"))).isEqualTo("protected")
        assertThat(MemberModifiers.visibility(MemberModifiers.pack("private"))).isEqualTo("private")
        assertThat(MemberModifiers.visibility(MemberModifiers.pack(null))).isEqualTo("package-private")
        assertThat(MemberModifiers.visibility(MemberModifiers.pack("package-private"))).isEqualTo("package-private")
    }

    @Test
    fun `method modifiers decode in canonical order, ending with the compiler-generated ones`() {
        val all = MemberModifiers.pack(
            "public", static = true, final = true, abstract = true, synchronized = true, native = true,
            default = true, bridge = true, synthetic = true,
        )

        assertThat(MemberModifiers.methodModifiers(all)).containsExactly(
            "public", "static", "final", "abstract", "synchronized", "native", "default", "bridge", "synthetic",
        )
        assertThat(MemberModifiers.methodModifiers(MemberModifiers.pack(null))).containsExactly("package-private")
    }

    @Test
    fun `field modifiers decode in canonical order, without the constant flag`() {
        val all = MemberModifiers.pack(
            "private", static = true, final = true, transient = true, volatile = true, synthetic = true, constant = true,
        )

        assertThat(MemberModifiers.fieldModifiers(all)).containsExactly("private", "static", "final", "transient", "volatile", "synthetic")
        assertThat(MemberModifiers.isConstant(all)).isTrue()
        assertThat(MemberModifiers.isConstant(MemberModifiers.pack("private", static = true, final = true))).isFalse()
        assertThat(MemberModifiers.fieldModifiers(MemberModifiers.pack("protected"))).containsExactly("protected")
    }

    @Test
    fun `type modifiers pack and decode their four flags, generic is not a modifier`() {
        val all = TypeModifiers.pack("public", abstract = true, final = true, static = true, generic = true)

        assertThat(TypeModifiers.modifiers(all)).containsExactly("public", "abstract", "final", "static")
        assertThat(listOf(TypeModifiers.isAbstract(all), TypeModifiers.isFinal(all), TypeModifiers.isStatic(all), TypeModifiers.isGeneric(all)))
            .containsOnly(true)

        val none = TypeModifiers.pack(null)
        assertThat(TypeModifiers.modifiers(none)).containsExactly("package-private")
        assertThat(listOf(TypeModifiers.isAbstract(none), TypeModifiers.isFinal(none), TypeModifiers.isStatic(none), TypeModifiers.isGeneric(none)))
            .containsOnly(false)
    }

    @Test
    fun `the generic bit is a bit of its own, no member flag shares it`() {
        val everyMemberBit = MemberModifiers.pack(
            "public", static = true, final = true, abstract = true, synchronized = true, native = true, default = true,
            transient = true, volatile = true, bridge = true, synthetic = true, constant = true,
        ) or MemberModifiers.PROTECTED or MemberModifiers.PRIVATE

        assertThat(TypeModifiers.GENERIC and everyMemberBit).isZero()
        assertThat(TypeModifiers.isGeneric(everyMemberBit)).isFalse()
    }
}
