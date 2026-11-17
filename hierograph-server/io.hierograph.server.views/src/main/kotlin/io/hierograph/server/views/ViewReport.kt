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

import io.hierograph.hierarchicalgraph.model.HGNode

/**
 * What applying a view found (views specification, §5.3). A view with errors is still applied as far as it
 * can be; the report says what was left out and why.
 */
data class ViewReport(val findings: List<ViewFinding>) {

    val errors: List<ViewFinding> get() = findings.filter { it.severity == ViewFinding.Severity.ERROR }

    val warnings: List<ViewFinding> get() = findings.filter { it.severity == ViewFinding.Severity.WARNING }

    val hasErrors: Boolean get() = findings.any { it.severity == ViewFinding.Severity.ERROR }

    val infos: List<ViewFinding> get() = findings.filter { it.severity == ViewFinding.Severity.INFO }
}

/**
 * One finding of a [ViewReport].
 *
 * [group] is the path of the group it concerns (`core/hierograph-graphstore`), or for a restructuring
 * finding the move (`moves[2]`) or module (`modules[0]`) of the document; [selector] is the selector where
 * one is involved, and [nodes] the graph nodes it is about, in the order the [message] names them.
 */
data class ViewFinding(
    val severity: Severity,
    val code: Code,
    val group: String,
    val message: String,
    val selector: MemberSelector? = null,
    val nodes: List<HGNode> = emptyList(),
) {
    enum class Severity { ERROR, WARNING, INFO }

    enum class Code(val severity: Severity) {
        /** A selector matches no node. The selector is ignored. */
        SELECTOR_MATCHES_NOTHING(Severity.WARNING),

        /** A selector's `within` matches no node, so the selector cannot match anything. It is ignored. */
        WITHIN_MATCHES_NOTHING(Severity.WARNING),

        /** A node is selected by two groups. It stays in the first group in document order. */
        NODE_IN_TWO_GROUPS(Severity.ERROR),

        /** A member lies inside a member of another group. The inner member is dropped. */
        MEMBER_INSIDE_OTHER_GROUPS_MEMBER(Severity.ERROR),

        /** A member lies inside another member of the same group, which already includes it. It is dropped. */
        MEMBER_INSIDE_OWN_MEMBER(Severity.WARNING),

        /** The members of a group do not share one parent. The group is not applied; its valid subgroups are. */
        MEMBERS_WITH_DIFFERENT_PARENTS(Severity.ERROR),

        /** A group has no members after resolution and no applied subgroups. It is not created. */
        EMPTY_GROUP(Severity.WARNING),

        /** A group's handle is already taken by a node of the graph. The whole view is not applied. */
        GROUP_HANDLE_COLLISION(Severity.ERROR),

        // restructuring (views specification, §6.4)

        /** A move's selector selects nothing. The move is ignored. */
        MOVE_SELECTS_NOTHING(Severity.WARNING),

        /** A move's target names no module, or no virtual module. The move is skipped. */
        UNKNOWN_MOVE_TARGET(Severity.ERROR),

        /** A move's target pattern matches more than one module. The move is skipped. */
        AMBIGUOUS_MOVE_TARGET(Severity.ERROR),

        /** A node is selected by two moves. The first move in document order moves it. */
        NODE_MOVED_TWICE(Severity.ERROR),

        /** A package would move into its own subtree. That node's move is skipped. */
        MOVE_INTO_OWN_SUBTREE(Severity.ERROR),

        /** A node that cannot be moved there: a member, external code, or a module into a package. Skipped. */
        CANNOT_MOVE(Severity.ERROR),

        /** A package an earlier move merged into an existing package of the graph. Its move is skipped. */
        MOVE_OF_MERGED_PACKAGE(Severity.ERROR),

        /** A virtual node's handle is already taken by a node of the graph. The whole view is not applied. */
        VIRTUAL_HANDLE_COLLISION(Severity.ERROR),

        /** A virtual module that no move fills. It stays, empty. */
        VIRTUAL_MODULE_EMPTY(Severity.WARNING),

        /** A real module the moves left without children. It stays, empty: it may be meant to go, or a move is missing. */
        REAL_MODULE_EMPTY(Severity.INFO),

        /** A package that now exists, with types, in more modules than before (a split package). */
        SPLIT_PACKAGE(Severity.INFO),
    }
}
