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
}

/**
 * One finding of a [ViewReport].
 *
 * [group] is the path of the group it concerns (`core/hierograph-graphstore`), [selector] the selector
 * where one is involved, and [nodes] the graph nodes it is about, in the order the [message] names them.
 */
data class ViewFinding(
    val severity: Severity,
    val code: Code,
    val group: String,
    val message: String,
    val selector: MemberSelector? = null,
    val nodes: List<HGNode> = emptyList(),
) {
    enum class Severity { ERROR, WARNING }

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
    }
}
