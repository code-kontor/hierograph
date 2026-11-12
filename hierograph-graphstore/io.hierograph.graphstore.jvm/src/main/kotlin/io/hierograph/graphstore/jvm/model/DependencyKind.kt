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
 * The kind of a detail dependency: one fact in a class file from a source element (a type or a member)
 * to a target element. This is the one vocabulary for type-level facts (`extends`, a type's own
 * annotations, …) and member-level facts (`calls`, `casts`, …). [value] is the wire name, stored in the
 * `relationship` column of `dependencies.parquet`.
 *
 * Every kind except [RAISES] is [counted]: it adds 1 to the weight of the aggregated dependency between
 * the source's and the target's owner types (see [DependencyRollup]). [RAISES] is not counted because
 * it refines facts that are counted already: `throw new X(…)` is an [INSTANTIATES] of `X`, a [CALLS] of
 * its constructor, and then the throw.
 */
enum class DependencyKind(val value: String, val counted: Boolean = true) {
    // ── type-level: the source is the type itself ──
    /** The source type has the target as its superclass. */
    EXTENDS("extends"),
    /** The source type implements (or, as an interface, extends) the target interface. */
    IMPLEMENTS("implements"),

    // ── declarations: the source is the type, a field or a method ──
    /** The source (type, field or method) carries the target annotation. */
    ANNOTATED_BY("annotated_by"),
    /** A parameter of the source method carries the target annotation; one fact per annotated parameter. */
    PARAMETER_ANNOTATED_BY("parameter_annotated_by"),
    /** The target is used in an annotation value on the source: an enum constant, a class value or a nested annotation. */
    ANNOTATION_VALUE("annotation_value"),
    /** The target appears inside a type argument or bound of the source's generic signature (`List<Money>` → `Money`). */
    TYPE_ARGUMENT("type_argument"),
    /** The source field is declared with the target type. */
    HAS_TYPE("has_type"),
    /** The source method takes a parameter of the target type; one fact per parameter. */
    PARAMETER_TYPE("parameter_type"),
    /** The source method returns the target type. */
    RETURNS("returns"),
    /** The source method declares the target in its `throws` clause. */
    THROWS("throws"),

    // ── method bodies: the source is a method ──
    /** The source method invokes the target method or constructor, or links a lambda to its implementation. */
    CALLS("calls"),
    /** The source method reads the target field. */
    READS_FIELD("reads_field"),
    /** The source method assigns the target field. */
    WRITES_FIELD("writes_field"),
    /** The source method creates an instance (`new`) or an array (`anewarray`, `multianewarray`) of the target. */
    INSTANTIATES("instantiates"),
    /** The source method casts to (`checkcast`) or tests against (`instanceof`) the target. */
    CASTS("casts"),
    /** The source method catches the target exception type. */
    CATCHES("catches"),
    /** The source method uses the target as a class literal (`Money.class`). */
    CLASS_LITERAL("class_literal"),
    /** The source method executes `throw new <target>(…)`. Not counted, see the class comment. */
    RAISES("raises", counted = false);

    companion object {
        private val byValue = entries.associateBy { it.value }

        /** The kind with this wire name, or `null` for a name outside the vocabulary (e.g. a derived relationship). */
        fun fromValue(value: String): DependencyKind? = byValue[value]
    }
}
