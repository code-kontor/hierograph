/*
 * Copyright 2024 Gerd Wuetherich
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
package io.hierograph.mcp.javaspec

/**
 * Canonical constants for Java-specific node kinds, relationship kinds,
 * and group aliases used throughout Hierograph's tool surface.
 *
 * Node kinds are defined as [JavaNodeKind] enum values. The string constants
 * (`MODULE`, `CLASS`, etc.) are kept as aliases for the enum's [value][JavaNodeKind.value]
 * property for use in contexts that need plain strings (Cypher queries, JSON keys).
 */
object JavaKinds {

    // ── node kinds (enum values) ───────────────────────────────────────

    @JvmField val MODULE = JavaNodeKind.MODULE
    @JvmField val PACKAGE = JavaNodeKind.PACKAGE
    @JvmField val CLASS = JavaNodeKind.CLASS
    @JvmField val INTERFACE = JavaNodeKind.INTERFACE
    @JvmField val ENUM = JavaNodeKind.ENUM
    @JvmField val RECORD = JavaNodeKind.RECORD
    @JvmField val ANNOTATION = JavaNodeKind.ANNOTATION
    @JvmField val METHOD = JavaNodeKind.METHOD
    @JvmField val FIELD = JavaNodeKind.FIELD
    @JvmField val PRIMITIVE = JavaNodeKind.PRIMITIVE
    @JvmField val CONSTRUCTOR = JavaNodeKind.CONSTRUCTOR

    // ── kind groups ────────────────────────────────────────────────────

    /** All type-level node kinds. */
    @JvmField
    val TYPE_KINDS: Set<JavaNodeKind> = setOf(CLASS, INTERFACE, ENUM, RECORD, ANNOTATION)

    /** All member-level node kinds. */
    @JvmField
    val MEMBER_KINDS: Set<JavaNodeKind> = setOf(METHOD, FIELD)

    /** All valid node kinds (excluding pseudo-kinds like primitive and constructor). */
    @JvmField
    val ALL_KINDS: Set<JavaNodeKind> = setOf(MODULE, PACKAGE, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD)

    // ── group aliases (accepted by kind_filter parameters) ─────────────

    const val ALIAS_TYPES = "types"
    const val ALIAS_MEMBERS = "members"
    const val ALIAS_PACKAGES = "packages"

    @JvmField
    val ALL_ALIASES: Set<String> = setOf(ALIAS_TYPES, ALIAS_MEMBERS, ALIAS_PACKAGES)

    /**
     * Expands a group alias to its constituent kinds.
     * Returns `null` if the input is not a recognized alias.
     */
    @JvmStatic
    fun expandAlias(alias: String): List<JavaNodeKind>? = when (alias) {
        ALIAS_TYPES -> listOf(CLASS, INTERFACE, ENUM, RECORD, ANNOTATION)
        ALIAS_MEMBERS -> listOf(METHOD, FIELD)
        ALIAS_PACKAGES -> listOf(PACKAGE)
        else -> null
    }

    // ── Java primitives ────────────────────────────────────────────────

    /** The set of Java primitive type names. */
    @JvmField
    val JAVA_PRIMITIVES: Set<String> = setOf(
        "void", "boolean", "byte", "char", "short", "int", "long", "float", "double"
    )
}
