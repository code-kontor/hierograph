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
package io.hierograph.graphql

import io.hierograph.hierarchicalgraph.core.model.Hierarchy

/**
 * Supplies the [Hierarchy] the GraphQL layer operates on. Since the "multiple hierarchies" refactor,
 * structural navigation — parent/children/predecessors, accumulated dependencies, node lookup — lives
 * on the [Hierarchy] rather than on the node, so the GraphQL resolvers go through the hierarchy
 * provided here.
 */
fun interface HierarchicalGraphProvider {
    fun hierarchy(): Hierarchy
}
