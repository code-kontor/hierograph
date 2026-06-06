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
package io.hierograph.hierarchicalgraph.core.algorithms

import io.hierograph.hierarchicalgraph.core.model.AggregatedDependency
import io.hierograph.hierarchicalgraph.core.model.CoreNode
import io.hierograph.hierarchicalgraph.core.model.Hierarchy

interface INodeSorter {
    fun sort(nodes: List<CoreNode>, hierarchy: Hierarchy): SortResult
}

interface SortResult {
    val orderedNodes: List<CoreNode>
    val upwardDependencies: List<AggregatedDependency>
}
