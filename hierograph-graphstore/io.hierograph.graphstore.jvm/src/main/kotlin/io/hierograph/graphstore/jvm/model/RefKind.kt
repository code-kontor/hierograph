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
 * The kind of an aggregated reference ([Ref]), the vocabulary of the type-level aggregate the scanner and
 * the snapshot reader roll up from the detail facts. Each kind is the rollup of one [DependencyKind] (see
 * [DependencyRollup]), except [MEMBER_ANNOTATED_BY], which collects the `annotated_by` facts of members and
 * the `parameter_annotated_by` facts.
 *
 * [value] is the name the tools show: the [DependencyKind.value] of the detail kind it rolls up, so a kind in
 * a type-level result leads straight to the detail facts behind it; `member_annotated_by` is the one name
 * of its own.
 */
enum class RefKind(val value: String) {
    // structural
    EXTENDS("extends"), IMPLEMENTS("implements"), ANNOTATED_BY("annotated_by"),

    // signature
    FIELD_TYPE("has_type"), PARAM_TYPE("parameter_type"), RETURN_TYPE("returns"), THROWS("throws"),
    /** An annotation on a member or parameter (a type's own annotations are [ANNOTATED_BY]). */
    MEMBER_ANNOTATED_BY("member_annotated_by"),
    /** A type argument or bound in a generic signature (`List<Foo>` → `Foo`), invisible to erased descriptors. */
    TYPE_ARGUMENT("type_argument"),
    /** A type used in an annotation's value: an enum constant, a class value, or a nested annotation. */
    ANNOTATION_VALUE("annotation_value"),

    // body
    INVOKES("calls"), READS("reads_field"), WRITES("writes_field"), INSTANTIATES("instantiates"), CAST("casts"), CATCHES("catches"),
    /** A class literal (`Foo.class`, an `ldc` of a type constant). */
    CLASS_LITERAL("class_literal");

    companion object {
        /** The structural kinds that drive the extends / implements / annotatedBy dependency flags. */
        val STRUCTURAL: Set<RefKind> = setOf(EXTENDS, IMPLEMENTS, ANNOTATED_BY)

        /** The kinds of a type-level result as the tools show them: by [value], in vocabulary order. */
        fun toMap(kinds: Map<RefKind, Int>): Map<String, Int> =
            entries.filter { (kinds[it] ?: 0) > 0 }.associate { it.value to kinds.getValue(it) }
    }
}
