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
 * A type-level reference from its owning type (the [ClassFacts] that holds it) to [toKey].
 *
 * The target is a **key string** (a type FQN or a member key `<owner fqn>#<signature>`), never a node
 * object; internal and external targets look identical. [count] carries pre-aggregated duplicates.
 */
data class Ref(
    val toKey: String,
    val kind: RefKind,
    val count: Int? = null,
)
