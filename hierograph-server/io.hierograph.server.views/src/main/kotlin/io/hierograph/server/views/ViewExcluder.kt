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
package io.hierograph.server.views

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.views.ViewFinding.Code

/**
 * The nodes a view leaves out (views specification, §5.4), removed from a fork of a model.
 *
 * [model] is the base model itself when nothing is excluded, and a fork otherwise. [excluded] are the nodes
 * the exclusions select, the outermost ones only: in the order of the exclusions, and each one's nodes in
 * pre-order. [excludedCount] counts them and every node inside them, and the external types that only they
 * referenced, swept out with them. [findings] say what each exclusion left out, or that it selected nothing,
 * and what the sweep took.
 */
class Excluded(
    val model: HGModel,
    val excluded: List<HGNode>,
    val excludedCount: Int,
    val findings: List<ViewFinding>,
)

/**
 * Applies a view's exclusions (views specification, §5.4): resolves each selector against the base
 * hierarchy and removes the selected nodes, with everything inside them, from a fork. Removing a node takes
 * its dependencies, to and from it, out of the fork too (hierarchical-graph model specification, §7.2), so
 * every aggregation, level and cycle in the view is computed without them. External types that only the
 * removed nodes referenced then go too.
 */
object ViewExcluder {

    private val TYPE_KINDS: Set<String> = JavaNodeKind.TYPES.map { it.value }.toSet()

    fun exclude(base: HGModel, document: ViewDocument): Excluded {
        if (document.exclude.isEmpty()) return Excluded(base, emptyList(), 0, emptyList())
        val hierarchy = base.hierarchy
        val resolver = ViewResolver(hierarchy)
        val findings = mutableListOf<ViewFinding>()

        // Each node goes to the first exclusion, in document order, that selects it.
        val selectedBy = LinkedHashMap<HGNode, Int>()
        document.exclude.forEachIndexed { i, selector ->
            val selected = resolver.matching(selector)
            when {
                selected.withinMatchedNothing -> findings += finding(
                    Code.WITHIN_MATCHES_NOTHING, i, "'within' of selector $selector matches no node; the exclusion is ignored", selector,
                )
                selected.nodes.isEmpty() -> findings += finding(
                    Code.EXCLUDE_SELECTS_NOTHING, i, "selector $selector matches no node; the exclusion is ignored", selector,
                )
            }
            for (node in selected.nodes) selectedBy.putIfAbsent(node, i)
        }
        // A node inside another excluded node goes with it.
        val outermost = selectedBy.keys.filter { node -> hierarchy.predecessorsOf(node).none { it in selectedBy } }
        if (outermost.isEmpty()) return Excluded(base, emptyList(), 0, findings)

        val fork = base.fork()
        val tallies = document.exclude.indices.associateWith { Tally() }
        for (node in outermost) {
            val tally = tallies.getValue(selectedBy.getValue(node))
            tally.selected += node
            removeSubtree(fork.hierarchy, node, tally)
        }
        for ((i, tally) in tallies) {
            if (tally.selected.isEmpty()) continue
            findings += finding(Code.NODES_EXCLUDED, i, tally.message(document.exclude[i]), document.exclude[i], tally.selected)
        }
        val swept = sweepUnreferencedExternals(hierarchy, fork.hierarchy)
        if (swept.types > 0) {
            findings += ViewFinding(
                Code.EXTERNAL_TYPES_UNREFERENCED.severity, Code.EXTERNAL_TYPES_UNREFERENCED, "exclude",
                "${swept.types} external ${noun(swept.types, "type")} only the excluded nodes referenced " +
                    "${if (swept.types == 1) "leaves" else "leave"} the view: ${swept.nodes} ${noun(swept.nodes, "node")} in all",
                null, emptyList(),
            )
        }
        return Excluded(fork, outermost, tallies.values.sumOf { it.nodes } + swept.nodes, findings)
    }

    /** What the sweep of [GraphStore.EXTERNAL_ARTIFACT_NAME] took out of the view. */
    private class Swept {
        var nodes = 0
        var types = 0
    }

    /**
     * Removes from [fork] the external types (in [GraphStore.EXTERNAL_ARTIFACT_NAME]) that only excluded nodes
     * referenced: referenced in [base], but by nothing left in [fork]. A package, and the module itself, that
     * the sweep empties goes too. An external type only exists because scanned code refers to it, so one the
     * view no longer refers to would be a node without a reason in it.
     */
    private fun sweepUnreferencedExternals(base: Hierarchy, fork: Hierarchy): Swept {
        val swept = Swept()
        val external = fork.childrenOf(fork.rootNode).firstOrNull {
            it.kind == JavaNodeKind.MODULE && NodeMetadata.getQualifiedName(it) == GraphStore.EXTERNAL_ARTIFACT_NAME
        } ?: return swept

        // Whether [node] was removed: an unreferenced external type, or a node the sweep emptied.
        fun sweep(node: HGNode): Boolean {
            if (node.kind == JavaNodeKind.EXTERNAL_TYPE) {
                if (fork.accumulatedIncoming(node).isNotEmpty() || base.accumulatedIncoming(node).isEmpty()) return false
                swept.types++
                removeAll(fork, node, swept)
                return true
            }
            val children = fork.childrenOf(node).toList()
            if (children.isEmpty()) return false
            val removed = children.map { sweep(it) }
            if (!removed.all { it }) return false
            fork.remove(node)
            swept.nodes++
            return true
        }
        sweep(external)
        return swept
    }

    /** Removes [node] and its subtree from [hierarchy], children first, counting the nodes. */
    private fun removeAll(hierarchy: Hierarchy, node: HGNode, swept: Swept) {
        for (child in hierarchy.childrenOf(node).toList()) removeAll(hierarchy, child, swept)
        hierarchy.remove(node)
        swept.nodes++
    }

    private fun noun(count: Int, word: String) = if (count == 1) word else "${word}s"

    /** Removes [node] and its subtree from [hierarchy], children first, counting what goes. */
    private fun removeSubtree(hierarchy: Hierarchy, node: HGNode, tally: Tally) {
        for (child in hierarchy.childrenOf(node).toList()) removeSubtree(hierarchy, child, tally)
        hierarchy.remove(node)
        tally.nodes++
        if (node.kind?.toString() in TYPE_KINDS) tally.types++
    }

    /** What one exclusion left out. */
    private class Tally {
        val selected = mutableListOf<HGNode>()
        var nodes = 0
        var types = 0

        fun message(selector: MemberSelector): String {
            val what = if (selected.size == 1) NodeMetadata.getQualifiedName(selected.single()) else "${selected.size} nodes"
            return "selector $selector leaves out $what: $nodes ${noun(nodes, "node")} in all, $types ${noun(types, "type")}"
        }

        private fun noun(count: Int, word: String) = if (count == 1) word else "${word}s"
    }

    private fun finding(code: Code, index: Int, message: String, selector: MemberSelector, nodes: List<HGNode> = emptyList()) =
        ViewFinding(code.severity, code, "exclude[$index]", message, selector, nodes)
}
