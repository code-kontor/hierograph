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

/**
 * A view: a named, declarative grouping of graph nodes (views specification, §2), and, with [restructure],
 * a virtual refactoring that moves nodes into other or new modules and packages first (§6.4).
 *
 * Instances come from [ViewDocumentParser], which checks the rules of §3; a document that reaches a
 * [ViewResolver] is therefore well-formed, and everything that can still go wrong is a matter of the graph
 * it is resolved against.
 */
data class ViewDocument(
    /** Unique per server; matches [NAME_PATTERN]. */
    val name: String,
    val description: String? = null,
    /** The top-level groups, in document order. */
    val groups: List<GroupDefinition> = emptyList(),
    /** Whether the view restructures; required for [modules] and [moves], so grouping never moves by accident. */
    val restructure: Boolean = false,
    /** The virtual modules the [moves] can target, in document order. */
    val modules: List<VirtualModuleDefinition> = emptyList(),
    /** The moves, applied in document order before the [groups]. */
    val moves: List<MoveDefinition> = emptyList(),
) {
    companion object {
        /** The allowed form of a view name: lower-case, digits, `.`, `_` and `-`, not starting with a separator. */
        val NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}

/**
 * A group of a [ViewDocument]: a name, the [members] it selects, and nested [groups]. A group has members,
 * subgroups or both.
 */
data class GroupDefinition(
    /** Unique among its siblings; never contains [PATH_SEPARATOR]. */
    val name: String,
    val description: String? = null,
    val members: List<MemberSelector> = emptyList(),
    val groups: List<GroupDefinition> = emptyList(),
) {
    companion object {
        /** Separates the names in a group's path (`core/hierograph-graphstore`). */
        const val PATH_SEPARATOR = '/'
    }
}

/**
 * A module that exists only in a restructuring view. Its [name] is how moves and groups refer to it, and
 * its qualified name in the view.
 */
data class VirtualModuleDefinition(
    /** Unique among the view's modules; matches [NAME_PATTERN]. */
    val name: String,
    val description: String? = null,
) {
    companion object {
        /** Letters, digits, `.`, `_` and `-`; never `:` or `/`, so a name cannot be taken for a GAV or a package target. */
        val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

/**
 * Moves the nodes [select] selects — modules, packages or types — to [to]: an existing module (its
 * qualified name, a pattern that must match exactly one module), a virtual module (its name), or a
 * package inside one of them (`<module>/<package fqn>`). See views specification, §6.4.
 */
data class MoveDefinition(
    val select: MemberSelector,
    val to: String,
) {
    companion object {
        /** Separates the module from the package in a package target. */
        const val PACKAGE_SEPARATOR = '/'
    }
}

/**
 * Selects graph nodes by qualified name (views specification, §4): [match] is a [NamePattern], optionally
 * restricted to one node [kind] (a kind value such as `java.package`, or one of the aliases `types`,
 * `packages`, `members`) and to the strict descendants of the nodes [within] selects.
 *
 * A selector written as a plain string has only [match].
 */
data class MemberSelector(
    val match: String,
    val kind: String? = null,
    val within: String? = null,
) {
    /** The selector as a document would write it, for reports. */
    override fun toString(): String =
        if (kind == null && within == null) match
        else buildList {
            add("match=$match")
            kind?.let { add("kind=$it") }
            within?.let { add("within=$it") }
        }.joinToString(", ", "{", "}")
}
