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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata

/**
 * Nesting of types, derived from binary names. The hierarchy places a nested type beside its enclosing
 * type in the package (`Invoice$Line` next to `Invoice`), as the class files do, so the enclosing type of
 * `Outer$Inner` is the sibling named `Outer`.
 */
object NestedTypes {

    /**
     * The type [type] is nested in: the sibling whose name is [type]'s name up to the last `$`, or `null`
     * when [type] is top-level or no such sibling exists (a `$` that is part of a generated name).
     */
    fun enclosingType(hierarchy: Hierarchy, type: HGNode): HGNode? {
        val name = NodeMetadata.getName(type)
        if ('$' !in name) return null
        val outer = name.substringBeforeLast('$')
        val parent = hierarchy.parentOf(type) ?: return null
        return hierarchy.childrenOf(parent).firstOrNull { it !== type && isType(it) && NodeMetadata.getName(it) == outer }
    }

    /**
     * The member types declared directly in [type]: the siblings named `<type>$<Name>`, where `<Name>`
     * holds no further `$` and does not start with a digit. Anonymous (`Outer$1`) and local
     * (`Outer$1Local`) classes are not member types and are left out.
     */
    fun memberTypes(hierarchy: Hierarchy, type: HGNode): List<HGNode> {
        val prefix = NodeMetadata.getName(type) + '$'
        val parent = hierarchy.parentOf(type) ?: return emptyList()
        return hierarchy.childrenOf(parent).filter {
            val name = NodeMetadata.getName(it)
            if (!isType(it) || !name.startsWith(prefix)) return@filter false
            val rest = name.substring(prefix.length)
            rest.isNotEmpty() && '$' !in rest && !rest[0].isDigit()
        }
    }

    private fun isType(node: HGNode) = node.kind in JavaKinds.TYPE_KINDS
}
