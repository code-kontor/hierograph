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
 * The set of Java-specific node kinds used throughout Hierograph's tool surface, with the groups of them
 * ([TYPES], [MEMBERS], [NODE_KINDS]) and the [EXTERNAL_TYPE] pseudo-kind of unscanned types.
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

        /** The type-level kinds. */
        @JvmField
        val TYPES: Set<JavaNodeKind> = setOf(CLASS, INTERFACE, ENUM, RECORD, ANNOTATION)

        /** The member-level kinds. */
        @JvmField
        val MEMBERS: Set<JavaNodeKind> = setOf(METHOD, FIELD)

        /** The kinds a graph node can have: all but the pseudo-kinds [PRIMITIVE] and [CONSTRUCTOR]. */
        @JvmField
        val NODE_KINDS: Set<JavaNodeKind> = setOf(MODULE, PACKAGE, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, METHOD, FIELD)

        /**
         * The kind of an external type node: a type referenced but not part of the scanned code (e.g. a
         * library annotation or class), placed under the synthetic `External Types` module. Unlike [TYPES] it
         * is a plain string, not a [JavaNodeKind] value, because external types carry no parsed metadata.
         */
        const val EXTERNAL_TYPE = "external.type"

        /**
         * True if [kind] denotes a type-level node for dependency-analysis purposes: a scanned Java type
         * ([TYPES]) **or** an [EXTERNAL_TYPE] stub. External types are first-class dependency endpoints (a
         * class can depend on / be annotated by a library type), so the subtree type-collection that anchors
         * type-level and detail-level dependency queries must include them — otherwise edges to/from external
         * types are silently dropped.
         */
        @JvmStatic
        fun isTypeKind(kind: Any?): Boolean = kind in TYPES || kind == EXTERNAL_TYPE
    }
}
