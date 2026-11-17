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

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind

/**
 * The group aliases a kind filter accepts besides the node kinds themselves: `types`, `members` and
 * `packages`. They are query syntax of the server's tool surface (`kindFilter` parameters, view selectors,
 * node search), not part of the graph model.
 */
object KindAliases {

    const val TYPES = "types"
    const val MEMBERS = "members"
    const val PACKAGES = "packages"

    @JvmField
    val ALL: Set<String> = setOf(TYPES, MEMBERS, PACKAGES)

    /** The node kinds [alias] stands for, or `null` when it is not an alias. */
    @JvmStatic
    fun expand(alias: String): List<JavaNodeKind>? = when (alias) {
        TYPES -> JavaNodeKind.TYPES.toList()
        MEMBERS -> JavaNodeKind.MEMBERS.toList()
        PACKAGES -> listOf(JavaNodeKind.PACKAGE)
        else -> null
    }
}
