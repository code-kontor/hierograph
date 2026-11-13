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

/**
 * The packed access flags of a type ([ClassFacts.modifiers]). Uses the same bit layout as
 * [MemberModifiers] (visibility, [MemberModifiers.STATIC], [MemberModifiers.FINAL],
 * [MemberModifiers.ABSTRACT]); only the flags meaningful for a type are packed and decoded.
 * Package-private visibility is the absence of a visibility bit. [GENERIC] is a type-only bit, above the
 * member bits: it is not a modifier, so [modifiers] never lists it.
 */
object TypeModifiers {

    /** The type declares type parameters (`class Box<T>`): its class signature starts with `<`. */
    const val GENERIC = 1 shl 13

    /** Packs a type's flags. [visibility] is one of public/protected/private (else package-private). */
    fun pack(
        visibility: String?,
        abstract: Boolean = false,
        final: Boolean = false,
        static: Boolean = false,
        generic: Boolean = false,
    ): Int =
        MemberModifiers.pack(visibility, static = static, final = final, abstract = abstract) or
            (if (generic) GENERIC else 0)

    /** Type modifiers in canonical order — visibility, then abstract/final/static — as `type_details` emits them. */
    fun modifiers(bits: Int): List<String> = buildList {
        add(MemberModifiers.visibility(bits))
        if (bits and MemberModifiers.ABSTRACT != 0) add("abstract")
        if (bits and MemberModifiers.FINAL != 0) add("final")
        if (bits and MemberModifiers.STATIC != 0) add("static")
    }

    fun isAbstract(bits: Int): Boolean = bits and MemberModifiers.ABSTRACT != 0
    fun isGeneric(bits: Int): Boolean = bits and GENERIC != 0
    fun isFinal(bits: Int): Boolean = bits and MemberModifiers.FINAL != 0
    fun isStatic(bits: Int): Boolean = bits and MemberModifiers.STATIC != 0
}
