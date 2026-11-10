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
 * The kind of a single reference (edge) between a source and a target key.
 *
 * Structural and signature kinds are produced in both scanner modes; body kinds only in FULL mode.
 * [DEPENDS_ON] is MVP-only: a coarse, pre-aggregated type-level dependency carried until the
 * fine-grained kinds fully replace it.
 */
enum class RefKind {
    // structural
    EXTENDS, IMPLEMENTS, ANNOTATED_BY,

    // signature
    FIELD_TYPE, PARAM_TYPE, RETURN_TYPE, THROWS,
    /** An annotation on a member or parameter (a type's own annotations are [ANNOTATED_BY]). */
    MEMBER_ANNOTATED_BY,
    /** A type argument or bound in a generic signature (`List<Foo>` → `Foo`), invisible to erased descriptors. */
    TYPE_ARGUMENT,
    /** A type used in an annotation's value: an enum constant, a class value, or a nested annotation. */
    ANNOTATION_VALUE,

    // body (FULL mode only)
    INVOKES, READS, WRITES, INSTANTIATES, CAST, CATCHES,
    /** A class literal (`Foo.class`, an `ldc` of a type constant). */
    CLASS_LITERAL,

    // MVP only: coarse, pre-aggregated type-level dependency
    DEPENDS_ON;

    companion object {
        /** The structural kinds that drive the extends / implements / annotatedBy dependency flags. */
        val STRUCTURAL: Set<RefKind> = setOf(EXTENDS, IMPLEMENTS, ANNOTATED_BY)
    }
}
