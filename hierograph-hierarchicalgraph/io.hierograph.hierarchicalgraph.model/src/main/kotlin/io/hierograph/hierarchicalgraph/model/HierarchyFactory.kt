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
package io.hierograph.hierarchicalgraph.model

import io.hierograph.hierarchicalgraph.model.internal.HierarchyImpl

object HierarchyFactory {

    fun createHierarchy(coreGraph: HGGraph, rootNode: HGNode): HierarchyImpl {
        return HierarchyImpl(
            coreGraph = coreGraph,
            rootNode = rootNode,
            parentMap = mutableMapOf(),
            childrenMap = mutableMapOf(),
        )
    }

    /**
     * Places [child] under [parent] without clearing caches: the fast path for building a hierarchy before
     * its first query. Checks, like [Hierarchy.addChild], that [child] is not placed yet, is not the root
     * and does not contain [parent].
     */
    fun addChild(hierarchy: Hierarchy, parent: HGNode, child: HGNode) {
        (hierarchy as HierarchyImpl).placeChild(parent.identifier, child.identifier)
    }
}
