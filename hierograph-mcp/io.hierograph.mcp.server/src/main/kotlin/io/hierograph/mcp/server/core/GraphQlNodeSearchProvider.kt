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
package io.hierograph.mcp.server.core

import io.hierograph.graphql.NodeSearchProvider
import io.hierograph.graphql.controller.InvalidArgumentException
import io.hierograph.hierarchicalgraph.core.model.HGNode
import org.springframework.stereotype.Component

/** Serves the GraphQL `findNodes` field from [NodeSearch], so it matches and ranks exactly like `find_node`. */
@Component
class GraphQlNodeSearchProvider(private val nodeSearch: NodeSearch) : NodeSearchProvider {

    override fun findNodes(name: String, kinds: List<String>?, limit: Int): List<HGNode> {
        val invalid = nodeSearch.invalidKinds(kinds)
        if (invalid.isNotEmpty()) {
            throw InvalidArgumentException(
                "Unknown kind${if (invalid.size > 1) "s" else ""}: ${invalid.joinToString(", ") { "'$it'" }}. " +
                    "Valid kinds: ${nodeSearch.validKindValues.joinToString(", ")}. " +
                    "Group aliases: ${nodeSearch.validAliases.joinToString(", ")}."
            )
        }
        return nodeSearch.search(name, kinds, limit)
    }
}
