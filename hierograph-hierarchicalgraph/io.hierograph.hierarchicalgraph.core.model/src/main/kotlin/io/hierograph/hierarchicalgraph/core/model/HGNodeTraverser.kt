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

object HGNodeTraverser {

    fun traverse(node: HGNode, action: (HGNode) -> Unit) {
        action(node)
        for (child in node.children) {
            traverse(child, action)
        }
    }

    fun traverse(node: HGNode, action: (HGNode) -> Unit, filter: (HGNode) -> Boolean) {
        if (filter(node)) {
            action(node)
        }
        for (child in node.children) {
            traverse(child, action, filter)
        }
    }

    fun traverseWithPruning(node: HGNode, action: (HGNode) -> Unit, descendInto: (HGNode) -> Boolean) {
        action(node)
        if (descendInto(node)) {
            for (child in node.children) {
                traverseWithPruning(child, action, descendInto)
            }
        }
    }
}
