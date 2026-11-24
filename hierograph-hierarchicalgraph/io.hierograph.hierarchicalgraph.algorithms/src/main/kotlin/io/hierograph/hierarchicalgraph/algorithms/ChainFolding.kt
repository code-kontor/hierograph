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
package io.hierograph.hierarchicalgraph.algorithms

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy

/**
 * A display child (specification, §8): [node] is shown in place of the [skipped] nodes above it, outermost first.
 * [skipped] is empty for a child that isn't folded.
 */
data class DisplayChild(val node: HGNode, val skipped: List<HGNode>)

/**
 * Chain folding (specification, §8): a presentation of a hierarchy in which a chain of lone wrappers, such as a
 * package that holds nothing but one package, is shown as the chain's last node. It changes no dependency and no
 * aggregation (a lone wrapper's subtree is its child's plus itself, which has no dependencies of its own beyond
 * what it holds), so it is computed on demand over any [Hierarchy] and stored nowhere.
 *
 * [foldable] says which nodes may form a chain (for Java, the packages). A node is **skipped** when it is
 * foldable, has exactly one child, and that child is foldable too. Skipped nodes stay real nodes of the hierarchy:
 * they can be looked up, named and passed to every other algorithm.
 */
class ChainFolding(private val hierarchy: Hierarchy, private val foldable: (HGNode) -> Boolean) {

    /** Whether [node] is skipped: a foldable node whose only child is foldable. */
    fun isSkipped(node: HGNode): Boolean {
        if (!foldable(node)) return false
        val children = hierarchy.childrenOf(node)
        return children.size == 1 && foldable(children[0])
    }

    /**
     * The display children of [node]: its children in hierarchy order, each chain replaced by its end, with the
     * skipped nodes it stands for.
     */
    fun displayChildrenOf(node: HGNode): List<DisplayChild> = hierarchy.childrenOf(node).map(::chainFrom)

    /** The chain starting at [start]: its end, and the skipped nodes from [start] down to the end. */
    fun chainFrom(start: HGNode): DisplayChild {
        val skipped = mutableListOf<HGNode>()
        var current = start
        while (isSkipped(current)) {
            skipped += current
            current = hierarchy.childrenOf(current)[0]
        }
        return DisplayChild(current, skipped)
    }

    /**
     * Where [node] is shown: itself, or for a skipped node the end of its chain. A search hit or link that names
     * a skipped node is revealed there.
     */
    fun displayNodeOf(node: HGNode): HGNode = chainFrom(node).node

    /**
     * The skipped nodes [node] stands for, outermost first: the run of skipped ancestors directly above it. Empty
     * when [node] isn't folded, and for a skipped node, which isn't shown itself.
     */
    fun skippedAbove(node: HGNode): List<HGNode> {
        if (isSkipped(node)) return emptyList()
        val skipped = ArrayDeque<HGNode>()
        var parent = hierarchy.parentOf(node)
        while (parent != null && isSkipped(parent)) {
            skipped.addFirst(parent)
            parent = hierarchy.parentOf(parent)
        }
        return skipped.toList()
    }

    /** The node [node] is displayed under: its nearest ancestor that isn't skipped; `null` for the root. */
    fun displayParentOf(node: HGNode): HGNode? {
        var parent = hierarchy.parentOf(node)
        while (parent != null && isSkipped(parent)) parent = hierarchy.parentOf(parent)
        return parent
    }

    /** [node]'s ancestors as displayed, parent first like [Hierarchy.predecessorsOf]: without the skipped ones. */
    fun displayPredecessorsOf(node: HGNode): List<HGNode> =
        hierarchy.predecessorsOf(node).filterNot(::isSkipped)
}
