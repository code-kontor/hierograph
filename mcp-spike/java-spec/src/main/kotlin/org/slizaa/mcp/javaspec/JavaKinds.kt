package org.slizaa.mcp.javaspec

/**
 * Canonical constants for Java-specific node kinds, relationship kinds,
 * and group aliases used throughout Hierograph's tool surface.
 *
 * All string constants match the namespaced vocabulary defined in the
 * tool surface proposal (`java.class`, `java.method`, etc.).
 * Use these constants instead of string literals to avoid typos and keep
 * the vocabulary in one place.
 */
object JavaKinds {

    // ── node kinds ─────────────────────────────────────────────────────

    const val MODULE = "java.module"
    const val PACKAGE = "java.package"
    const val CLASS = "java.class"
    const val INTERFACE = "java.interface"
    const val ENUM = "java.enum"
    const val RECORD = "java.record"
    const val ANNOTATION = "java.annotation"
    const val METHOD = "java.method"
    const val FIELD = "java.field"
    const val PRIMITIVE = "java.primitive"
    const val CONSTRUCTOR = "java.constructor"

    // ── kind groups ────────────────────────────────────────────────────

    /** All type-level node kinds. */
    @JvmField
    val TYPE_KINDS: Set<String> = setOf(CLASS, INTERFACE, ENUM, RECORD, ANNOTATION)

    /** All member-level node kinds. */
    @JvmField
    val MEMBER_KINDS: Set<String> = setOf(METHOD, FIELD)

    /** All valid node kinds (excluding pseudo-kinds like primitive and constructor). */
    @JvmField
    val ALL_KINDS: Set<String> = setOf(MODULE, PACKAGE, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD)

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
    fun expandAlias(alias: String): List<String>? = when (alias) {
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
