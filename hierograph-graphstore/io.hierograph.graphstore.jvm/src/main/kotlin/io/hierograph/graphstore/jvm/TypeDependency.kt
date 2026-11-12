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
package io.hierograph.graphstore.jvm

/**
 * An aggregated type-to-type dependency: all refs from one type to one **resolved** target type collapsed
 * into one row with a summed [weight] and the structural classification flags.
 *
 * Each endpoint is given twice. [fromKey] / [toKey] are the artifact-scoped node keys: the target is
 * resolved (see [TypeResolver]) to a concrete node, so two same-FQN types in different artifacts stay
 * distinct — build a graph from these. [toKey] may name a node that does not exist, because the
 * [ExternalTypeFilter] excluded it; graph builders skip such rows. [fromFqn] / [toFqn] are the same
 * endpoints by name, for display, for comparing stores, and for reasoning that has to include excluded
 * targets (following `extends` reaches `java.lang.Object`).
 *
 * The flags are not mutually exclusive: [extends] / [implements] / [annotatedBy] mark the structural ref
 * kinds, and [other] means the dependency *also* has at least one non-structural (signature or body) ref
 * (`kinds.any { it !in STRUCTURAL }`). A class that extends `Base` and calls one of its methods has both
 * [extends] and [other] set.
 *
 * Example, from Hierograph's own scanner module. `ClassScanner.Visitor` is declared as
 * `private class Visitor(…) : ClassVisitor(Opcodes.ASM9)`. ASM is a library that is not scanned, so
 * `ClassVisitor` is an external placeholder. The class holds two refs to it: the `EXTENDS` ref to its
 * superclass and the call to the superclass constructor `ClassVisitor.<init>(int)`. They aggregate to:
 *
 * ```
 * fromKey     = "type:65:io.hierograph:io.hierograph.graphstore.jvm.scanner:0.3.0-SNAPSHOT:io.hierograph.graphstore.jvm.scanner.ClassScanner$Visitor"
 * toKey       = "type:10:<external>:org.objectweb.asm.ClassVisitor"
 * fromFqn     = "io.hierograph.graphstore.jvm.scanner.ClassScanner$Visitor"
 * toFqn       = "org.objectweb.asm.ClassVisitor"
 * weight      = 2        // one EXTENDS ref + one call
 * extends     = true     // the EXTENDS ref
 * implements  = false
 * annotatedBy = false
 * other       = true     // the constructor call is a body use
 * ```
 *
 * A key is `type:<length of the artifact key>:<artifact key>:<fqn>` (see [GraphStore.typeKey]); external
 * types sit under the artifact key `<external>`. A ref to `java.lang.String` from the same class would
 * have [toKey] `"type:10:<external>:java.lang.String"`, a node that does not exist under
 * [ExternalTypeFilter.EXCLUDE_UBIQUITOUS], the filter the server uses.
 */
data class TypeDependency(
    val fromKey: String,
    val toKey: String,
    val fromFqn: String,
    val toFqn: String,
    val weight: Int,
    val extends: Boolean,
    val implements: Boolean,
    val annotatedBy: Boolean,
    val other: Boolean,
)
