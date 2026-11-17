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
package io.hierograph.server.graphql.controller

import graphql.GraphQLContext
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.server.graphql.GraphSnapshotPinning
import io.hierograph.server.graphql.GraphSnapshotPinning.RequestView
import io.hierograph.server.graphql.HierarchicalGraphProvider
import io.hierograph.server.graphservice.views.ViewService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class QueryController(
    private val provider: HierarchicalGraphProvider,
    private val viewService: ViewService? = null,
) {

    /**
     * The served hierarchy, or the hierarchy of [view]. A request answers in one view: the first
     * `hierarchicalGraph` field selects it for the whole request ([GraphSnapshotPinning]), and a second one
     * that names another view is rejected.
     */
    @QueryMapping
    fun hierarchicalGraph(@Argument view: String?, context: GraphQLContext): Hierarchy {
        val base = provider.hierarchy()
        val name = view?.trim()?.takeIf { it.isNotEmpty() }
        val selected = context.computeIfAbsent(GraphSnapshotPinning.VIEW_KEY) { RequestView(name, name?.let(::resolve)) }
            as RequestView
        if (selected.name != name) {
            throw InvalidArgumentException(
                "A request answers in one view, but it selects hierarchicalGraph ${describe(selected.name)} and ${describe(name)}",
            )
        }
        return selected.snapshot?.model?.hierarchy ?: base
    }

    private fun resolve(name: String) =
        (viewService ?: throw InvalidArgumentException("Views are not available on this server")).view(name).snapshot

    private fun describe(name: String?) = if (name == null) "without a view" else "in view '$name'"
}
