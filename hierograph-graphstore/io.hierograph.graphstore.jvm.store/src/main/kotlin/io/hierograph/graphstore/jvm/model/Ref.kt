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

import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind

/**
 * An aggregated reference from its owning type (the [ClassFacts] that holds it) to the type [toKey]: the
 * rollup of the owner's detail [Dependency] facts of one [kind] to that type (see [DependencyRollup]).
 * [count] is the number of facts behind it.
 *
 * [typeLevel] tells the facts whose source is the type itself (`extends`, `implements`, the type's own
 * annotations) from those whose source is one of its members. It matters for [DependencyKind.ANNOTATED_BY],
 * the one kind with facts of both, where only a type-level ref is an annotation of the type.
 *
 * The target is a **key string** (a type FQN), never a node object; internal and external targets look
 * identical.
 */
data class Ref(
    val toKey: String,
    val kind: DependencyKind,
    val count: Int? = null,
    val typeLevel: Boolean = false,
)
