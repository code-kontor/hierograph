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

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.views.ViewFinding.Code

/**
 * A view resolved against a hierarchy: the groups that can be applied, with their member nodes, and the
 * [report] of everything else (views specification, §5).
 *
 * [groups] are the groups to place directly below their [ResolvedGroup.parent]. That is every applied
 * top-level group, and also every applied group whose enclosing group could not be applied.
 */
data class ResolvedView(
    val document: ViewDocument,
    val groups: List<ResolvedGroup>,
    val report: ViewReport,
)

/**
 * An applicable group: its [members] all have [parent] as their parent in the base hierarchy, and so do the
 * [groups] nested in it. [members] are in base-hierarchy order, [groups] in document order.
 */
data class ResolvedGroup(
    val definition: GroupDefinition,
    /** The group's path in its view: the names from the top-level group down, joined by `/`. */
    val path: String,
    val parent: HGNode,
    val members: List<HGNode>,
    val groups: List<ResolvedGroup>,
) {
    val name: String get() = definition.name
}

/**
 * Resolves [ViewDocument]s against one [Hierarchy] (views specification, §5). It only reads the
 * hierarchy; placing the groups is materialization's job.
 *
 * The resolver indexes the hierarchy's qualified names on first use, so one instance should serve every view
 * resolved against the same hierarchy. Concurrent [resolve] calls are safe as long as the hierarchy is not
 * changed meanwhile: the index is built once under a lock, and the hierarchy's reads are thread-safe.
 */
class ViewResolver(private val hierarchy: Hierarchy) {

    /** Every node except the root, in pre-order, with its qualified name. */
    private val nodes: List<Pair<String, HGNode>> by lazy {
        hierarchy.descendantsOf(hierarchy.rootNode).map { NodeMetadata.getQualifiedName(it) to it }
    }

    private val byQualifiedName: Map<String, List<HGNode>> by lazy {
        nodes.groupBy({ it.first }, { it.second })
    }

    fun resolve(document: ViewDocument): ResolvedView = Resolution(document).run()

    /** The nodes [selector] selects, in pre-order. */
    fun select(selector: MemberSelector): List<HGNode> = matching(selector).nodes

    internal class Selected(val nodes: List<HGNode>, val withinMatchedNothing: Boolean)

    /** The nodes [selector] selects, in pre-order, and whether its `within` matched nothing. */
    internal fun matching(selector: MemberSelector): Selected {
        val kinds = selector.kind?.let { kind -> JavaKinds.expandAlias(kind)?.map { it.value }?.toSet() ?: setOf(kind) }
        val within = selector.within?.let { nodesMatching(NamePattern.compile(it)).toSet() }
        if (within != null && within.isEmpty()) return Selected(emptyList(), withinMatchedNothing = true)
        val selected = nodesMatching(NamePattern.compile(selector.match)).filter { node ->
            (kinds == null || node.kind?.toString() in kinds) &&
                (within == null || hierarchy.predecessorsOf(node).any { it in within })
        }
        return Selected(selected, withinMatchedNothing = false)
    }

    private fun nodesMatching(pattern: NamePattern): List<HGNode> =
        if (pattern.isExact) byQualifiedName[pattern.source].orEmpty()
        else nodes.mapNotNull { (qualifiedName, node) -> node.takeIf { pattern.matches(qualifiedName) } }

    /** The state of one [resolve] call. */
    private inner class Resolution(private val document: ViewDocument) {

        private val findings = mutableListOf<ViewFinding>()

        /** A group of the document while it is being resolved. */
        private inner class Work(val definition: GroupDefinition, val path: String) {
            val groups: List<Work> = definition.groups.map { Work(it, "$path${GroupDefinition.PATH_SEPARATOR}${it.name}") }
            val members = LinkedHashSet<HGNode>()
            fun preorder(): Sequence<Work> = sequenceOf(this) + groups.asSequence().flatMap { it.preorder() }
        }

        private val roots = document.groups.map { Work(it, it.name) }

        fun run(): ResolvedView {
            val all = roots.asSequence().flatMap { it.preorder() }.toList()
            val owner = assignMembers(all)
            dropNestedMembers(owner)
            val groups = roots.flatMap { place(it).let { (applied, lifted) -> listOfNotNull(applied) + lifted } }
            return ResolvedView(document, groups, ViewReport(findings.toList()))
        }

        private fun finding(code: Code, work: Work, message: String, selector: MemberSelector? = null, nodes: List<HGNode> = emptyList()) {
            findings += ViewFinding(code.severity, code, work.path, message, selector, nodes)
        }

        /** Resolves every selector and gives each node to the first group, in document order, that selects it. */
        private fun assignMembers(all: List<Work>): Map<HGNode, Work> {
            val owner = LinkedHashMap<HGNode, Work>()
            for (work in all) {
                for (selector in work.definition.members) {
                    val selected = matching(selector)
                    when {
                        selected.withinMatchedNothing -> finding(
                            Code.WITHIN_MATCHES_NOTHING, work,
                            "'within' of selector $selector matches no node; the selector is ignored", selector,
                        )
                        selected.nodes.isEmpty() -> finding(
                            Code.SELECTOR_MATCHES_NOTHING, work, "selector $selector matches no node; it is ignored", selector,
                        )
                    }
                    for (node in selected.nodes) {
                        val first = owner.putIfAbsent(node, work)
                        if (first == null || first === work) {
                            work.members += node
                        } else {
                            finding(
                                Code.NODE_IN_TWO_GROUPS, work,
                                "${qualifiedName(node)} is selected by '${first.path}' and by '${work.path}'; it stays in '${first.path}'",
                                selector, listOf(node),
                            )
                        }
                    }
                }
            }
            return owner
        }

        /** Drops every member that lies inside another member, of the same group or another one. */
        private fun dropNestedMembers(owner: Map<HGNode, Work>) {
            val dropped = owner.mapNotNull { (node, work) ->
                val outer = hierarchy.predecessorsOf(node).firstOrNull { it in owner } ?: return@mapNotNull null
                val outerWork = owner.getValue(outer)
                if (outerWork === work) {
                    finding(
                        Code.MEMBER_INSIDE_OWN_MEMBER, work,
                        "${qualifiedName(node)} lies inside ${qualifiedName(outer)}, which '${work.path}' already includes; it is dropped",
                        nodes = listOf(node, outer),
                    )
                } else {
                    finding(
                        Code.MEMBER_INSIDE_OTHER_GROUPS_MEMBER, work,
                        "${qualifiedName(node)} lies inside ${qualifiedName(outer)}, a member of '${outerWork.path}'; it is dropped from '${work.path}'",
                        nodes = listOf(node, outer),
                    )
                }
                node to work
            }
            for ((node, work) in dropped) work.members -= node
        }

        /**
         * Resolves [work] bottom-up. Returns the group if it can be applied, and the applied groups below it
         * that must be placed on their own because [work] cannot be.
         */
        private fun place(work: Work): Pair<ResolvedGroup?, List<ResolvedGroup>> {
            val nested = work.groups.map { place(it) }
            val subgroups = nested.mapNotNull { it.first }
            val lifted = nested.flatMap { it.second }

            if (work.members.isEmpty() && subgroups.isEmpty()) {
                finding(Code.EMPTY_GROUP, work, "'${work.path}' has no members and no applied subgroups; it is not created")
                return null to lifted
            }
            val parents = (work.members.map { hierarchy.parentOf(it)!! } + subgroups.map { it.parent }).distinct()
            if (parents.size > 1) {
                finding(
                    Code.MEMBERS_WITH_DIFFERENT_PARENTS, work,
                    "the members of '${work.path}' have different parents (${parents.joinToString { qualifiedName(it) }}); " +
                        "the group is not applied",
                    nodes = parents,
                )
                return null to (subgroups + lifted)
            }
            val parent = parents.single()
            val order = hierarchy.childrenOf(parent).withIndex().associate { (i, child) -> child to i }
            val members = work.members.sortedBy { order.getValue(it) }
            return ResolvedGroup(work.definition, work.path, parent, members, subgroups) to lifted
        }

        private fun qualifiedName(node: HGNode): String =
            if (node === hierarchy.rootNode) "(root)" else NodeMetadata.getQualifiedName(node)
    }
}
