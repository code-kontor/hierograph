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

import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.hierarchicalgraph.model.SyntheticNodeSource
import io.hierograph.server.views.ViewFinding.Code

/**
 * A view applied to a model (views specification, §6): a fork of the base model with a local node per
 * applied group, and the report of the resolution and the materialization.
 *
 * [model] is the base model itself when no group was created, and a fork otherwise; the base model is never
 * changed. [groupNodes] maps each created group's path to its node.
 */
class MaterializedView(
    val document: ViewDocument,
    val model: HGModel,
    val groupNodes: Map<String, HGNode>,
    val report: ViewReport,
)

/** The identities of group nodes (views specification, §6.2). */
object ViewGroups {

    /** The kind of every group node. */
    const val KIND = "view.group"

    /** A group's qualified name: `<view>:<path>`, e.g. `multi-modules:core/hierograph-graphstore`. */
    fun qualifiedName(view: String, path: String): String = "$view:$path"

    /** The stable key a group's handle derives from: `view:<view>:<path>`. */
    fun handleKey(view: String, path: String): String = "view:$view:$path"

    /** A group's handle: a pure function of the view name and the group's path, distinct per view. */
    fun handle(view: String, path: String): Long = NodeHandle.of(handleKey(view, path))

    fun isGroup(node: HGNode): Boolean = node.kind == KIND
}

/**
 * Applies a [ResolvedView] to a fork of a model (views specification, §6.1): creates a local node per
 * applied group below the group's parent, or below its enclosing group, and moves the members underneath.
 *
 * Groups are created in document order and members moved in base-hierarchy order, so the resulting child
 * order is the same for the same document over the same graph. Materializing only reads the base model; it
 * is safe to run while other threads read it, but the returned model must be published safely before other
 * threads read it.
 */
object ViewMaterializer {

    /** Resolves [document] against [base] and materializes it. */
    fun materialize(base: HGModel, document: ViewDocument): MaterializedView =
        materialize(base, ViewResolver(base.hierarchy).resolve(document))

    /** Materializes [resolved], which must have been resolved against [base]'s hierarchy. */
    fun materialize(base: HGModel, resolved: ResolvedView): MaterializedView {
        val document = resolved.document
        if (resolved.groups.isEmpty()) return MaterializedView(document, base, emptyMap(), resolved.report)

        val collisions = collisions(base.hierarchy, document.name, resolved.groups)
        if (collisions.isNotEmpty()) {
            return MaterializedView(document, base, emptyMap(), ViewReport(resolved.report.findings + collisions))
        }

        val fork = base.fork()
        val nodes = LinkedHashMap<String, HGNode>()
        for (group in resolved.groups) place(fork.hierarchy, document.name, group, group.parent, nodes)
        return MaterializedView(document, fork, nodes, resolved.report)
    }

    private fun place(hierarchy: Hierarchy, view: String, group: ResolvedGroup, parent: HGNode, nodes: MutableMap<String, HGNode>) {
        val node = hierarchy.createLocalNode(ViewGroups.KIND) {
            SyntheticNodeSource(ViewGroups.handle(view, group.path), group.name, ViewGroups.qualifiedName(view, group.path))
        }
        hierarchy.addChild(parent, node)
        nodes[group.path] = node
        for (member in group.members) hierarchy.move(member, node)
        for (subgroup in group.groups) place(hierarchy, view, subgroup, node, nodes)
    }

    /**
     * Group handles that are already taken by a node of the base hierarchy. A view with such a group is not
     * applied at all: the model would reject the group node, and a partial view would mislead.
     */
    private fun collisions(hierarchy: Hierarchy, view: String, groups: List<ResolvedGroup>): List<ViewFinding> =
        groups.flatMap { flatten(it) }.mapNotNull { group ->
            val taken = hierarchy.lookupNode(ViewGroups.handle(view, group.path)) ?: return@mapNotNull null
            ViewFinding(
                Code.GROUP_HANDLE_COLLISION.severity, Code.GROUP_HANDLE_COLLISION, group.path,
                "the handle of '${group.path}' is already taken by ${taken.nodeSource.qualifiedName}; the view is not applied",
                nodes = listOf(taken),
            )
        }

    private fun flatten(group: ResolvedGroup): List<ResolvedGroup> = listOf(group) + group.groups.flatMap { flatten(it) }
}
