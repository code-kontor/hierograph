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

/**
 * The packed access-flag layout for [MemberFacts.modifiers], with helpers to build it from the scanner's
 * boolean flags and to decode it back to the canonical modifier-string lists the detail tools emit
 * (visibility first, then storage modifiers). A dedicated bit layout keeps the snapshot self-contained;
 * the scanner packs the bits from bytecode access flags.
 *
 * Package-private visibility is the absence of [PUBLIC] / [PROTECTED] / [PRIVATE].
 */
object MemberModifiers {

    const val PUBLIC = 1 shl 0
    const val PROTECTED = 1 shl 1
    const val PRIVATE = 1 shl 2
    const val STATIC = 1 shl 3
    const val FINAL = 1 shl 4
    const val ABSTRACT = 1 shl 5
    const val SYNCHRONIZED = 1 shl 6
    const val NATIVE = 1 shl 7
    const val TRANSIENT = 1 shl 8
    const val VOLATILE = 1 shl 9
    const val DEFAULT = 1 shl 10

    /**
     * Compiler-generated flags. The hierarchy resolver needs them to get covariant-return overrides right
     * (a covariant override compiles to a [BRIDGE] method forwarding to the real one), and the modifier
     * lists end with them, so a reader can tell compiler-generated members from declared ones.
     */
    const val BRIDGE = 1 shl 11
    const val SYNTHETIC = 1 shl 12

    /**
     * A field that is a compile-time constant: it carries a `ConstantValue` attribute, so the compiler
     * inlines its value at every use. Not a modifier; never listed by [fieldModifiers].
     */
    const val CONSTANT = 1 shl 13

    /** Packs the given flags into a bitmap. [visibility] is one of public/protected/private (else package-private). */
    fun pack(
        visibility: String?,
        static: Boolean = false,
        final: Boolean = false,
        abstract: Boolean = false,
        synchronized: Boolean = false,
        native: Boolean = false,
        default: Boolean = false,
        transient: Boolean = false,
        volatile: Boolean = false,
        bridge: Boolean = false,
        synthetic: Boolean = false,
        constant: Boolean = false,
    ): Int {
        var bits = when (visibility?.lowercase()) {
            "public" -> PUBLIC
            "protected" -> PROTECTED
            "private" -> PRIVATE
            else -> 0
        }
        if (static) bits = bits or STATIC
        if (final) bits = bits or FINAL
        if (abstract) bits = bits or ABSTRACT
        if (synchronized) bits = bits or SYNCHRONIZED
        if (native) bits = bits or NATIVE
        if (default) bits = bits or DEFAULT
        if (transient) bits = bits or TRANSIENT
        if (volatile) bits = bits or VOLATILE
        if (bridge) bits = bits or BRIDGE
        if (synthetic) bits = bits or SYNTHETIC
        if (constant) bits = bits or CONSTANT
        return bits
    }

    fun isBridge(bits: Int): Boolean = bits and BRIDGE != 0
    fun isSynthetic(bits: Int): Boolean = bits and SYNTHETIC != 0
    fun isConstant(bits: Int): Boolean = bits and CONSTANT != 0

    fun visibility(bits: Int): String = when {
        bits and PUBLIC != 0 -> "public"
        bits and PROTECTED != 0 -> "protected"
        bits and PRIVATE != 0 -> "private"
        else -> "package-private"
    }

    /**
     * Method/constructor modifiers in canonical order: visibility, then static/final/abstract/synchronized/
     * native/default, then the compiler-generated `bridge` and `synthetic`.
     */
    fun methodModifiers(bits: Int): List<String> = buildList {
        add(visibility(bits))
        if (bits and STATIC != 0) add("static")
        if (bits and FINAL != 0) add("final")
        if (bits and ABSTRACT != 0) add("abstract")
        if (bits and SYNCHRONIZED != 0) add("synchronized")
        if (bits and NATIVE != 0) add("native")
        if (bits and DEFAULT != 0) add("default")
        if (bits and BRIDGE != 0) add("bridge")
        if (bits and SYNTHETIC != 0) add("synthetic")
    }

    /** Field modifiers in canonical order: visibility, then static/final/transient/volatile, then `synthetic`. */
    fun fieldModifiers(bits: Int): List<String> = buildList {
        add(visibility(bits))
        if (bits and STATIC != 0) add("static")
        if (bits and FINAL != 0) add("final")
        if (bits and TRANSIENT != 0) add("transient")
        if (bits and VOLATILE != 0) add("volatile")
        if (bits and SYNTHETIC != 0) add("synthetic")
    }
}
