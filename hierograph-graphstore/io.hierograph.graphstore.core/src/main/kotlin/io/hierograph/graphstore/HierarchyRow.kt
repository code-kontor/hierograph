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
package io.hierograph.graphstore

/**
 * One row of the hierarchy projection: a node's [kind] and [fqn], plus the [parentFqn] of its parent
 * (`null` for top-level modules). This is the FQN-keyed shape used to diff the GraphStore against the
 * jQAssistant `GraphDbMapping`, which has no equivalent of Neo4j's internal ids.
 */
data class HierarchyRow(
    val kind: Any,
    val fqn: String,
    val parentFqn: String?,
)
