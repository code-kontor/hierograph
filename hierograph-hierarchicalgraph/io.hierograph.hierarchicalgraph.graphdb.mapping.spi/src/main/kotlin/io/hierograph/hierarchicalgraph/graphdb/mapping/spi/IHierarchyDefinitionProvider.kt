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
package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource

interface IHierarchyDefinitionProvider {
    fun initialize()
    fun dispose()
    val toplevelNodeIds: List<ToplevelNodeId>
    val parentChildNodeIds: List<ParentChildNodeId>

    fun createNodeSource(id: Long): GraphDbNodeSource =
        GraphDbNodeSource(identifier = id)
}

data class ToplevelNodeId(val id: Long, val kind: Any)

data class ParentChildNodeId(val parentId: Long, val childId: Long, val childKind: Any)
