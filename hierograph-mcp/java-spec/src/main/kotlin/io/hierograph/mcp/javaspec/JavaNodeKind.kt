package io.hierograph.mcp.javaspec

/**
 * The set of Java-specific node kinds used throughout Hierograph's tool surface.
 *
 * Each enum value carries a [value] string that matches the namespaced vocabulary
 * (`java.class`, `java.method`, etc.). [toString] returns the value string so that
 * enum constants can be used directly in string interpolation (e.g. Cypher queries).
 */
enum class JavaNodeKind(val value: String) {
    MODULE("java.module"),
    PACKAGE("java.package"),
    CLASS("java.class"),
    INTERFACE("java.interface"),
    ENUM("java.enum"),
    RECORD("java.record"),
    ANNOTATION("java.annotation"),
    METHOD("java.method"),
    FIELD("java.field"),
    PRIMITIVE("java.primitive"),
    CONSTRUCTOR("java.constructor");

    override fun toString(): String = value

    companion object {
        private val byValue = entries.associateBy { it.value }

        /**
         * Returns the [JavaNodeKind] for the given value string, or `null` if not found.
         */
        @JvmStatic
        fun fromValue(value: String): JavaNodeKind? = byValue[value]
    }
}
