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
package io.hierograph.hierarchicalgraph.core.model

import io.hierograph.hierarchicalgraph.core.model.internal.HierarchyImpl

object HierarchyFactory {

    fun createHierarchy(coreGraph: CoreGraph, rootNode: CoreNode): HierarchyImpl {
        return HierarchyImpl(
            coreGraph = coreGraph,
            rootNode = rootNode,
            parentMap = mutableMapOf(),
            childrenMap = mutableMapOf(),
        )
    }

    fun addChild(hierarchy: Hierarchy, parent: CoreNode, child: CoreNode) {
        val impl = hierarchy as HierarchyImpl
        impl.parentMap[child.identifier] = parent.identifier
        impl.childrenMap
            .getOrPut(parent.identifier) { mutableListOf() }
            .add(child.identifier)
    }
}
