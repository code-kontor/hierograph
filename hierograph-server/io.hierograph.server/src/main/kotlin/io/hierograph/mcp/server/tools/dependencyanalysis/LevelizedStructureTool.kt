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
package io.hierograph.mcp.server.tools.dependencyanalysis

import io.hierograph.hierarchicalgraph.algorithms.GraphAlgorithms
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.mcp.server.tools.CompactTree
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component

/**
 * MCP tool: `levelized_structure`
 *
 * A container's children in rows by level, as a levelized structure map draws them: a child sits one row above the
 * highest child it depends on, the children of a cycle (a tangle) share a row, and the feedback dependencies are
 * the few dependencies inside the tangles that point against the levels. Entirely in-memory, over the children of
 * one node; the type-level evidence of a feedback dependency comes from `outgoing_dependencies`.
 */
@Component
class LevelizedStructureTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "levelized_structure",
        description = "[Dependency analysis] " +
                "How the children of one node (a module's packages, a package's sub-packages and types, a view's " +
                "groups, the modules under the root) are layered: the children in rows by level, bottom first — " +
                "a child sits one row above the highest child it uses, so every ordinary dependency points down — " +
                "the tangles (cycles among the children, each on one row, members ordered so the heaviest " +
                "dependencies point down), and the feedback dependencies: the dependencies inside the tangles that " +
                "point up, heaviest first. They are the cheapest set whose removal untangles the tangles, so they " +
                "are the refactoring candidates. Answers 'how are the packages of X layered?', 'where are the " +
                "cycles in X?', 'which dependencies untangle this cycle?'. Chains of lone packages are folded (see " +
                "compact): a module whose packages start with io › io.acme › io.acme.app levelizes io.acme.app's " +
                "peers directly. One container per call: to go deeper, " +
                "call it again on a child. For the type-level dependencies behind a feedback dependency use " +
                "outgoing_dependencies(sourceIds=[from], targetIds=[to])."
    )
    fun levelizedStructure(
        @ToolParam(description = "The node whose direct children to levelize (a module, package, view group, or the root).")
        nodeId: Long,
        @ToolParam(
            description = "Max feedback dependencies to return, heaviest first (1-500, default 100). " +
                    "The summary counts all of them.",
            required = false
        )
        limit: Int?,
        @ToolParam(description = CompactTree.PARAM_DESCRIPTION, required = false)
        compact: Boolean? = null,
    ): Map<String, Any?> {
        val node = graphService.model.lookupNode(nodeId)
            ?: return errorResponse(
                "NODE_NOT_FOUND",
                "No node with id $nodeId exists in the graph.",
                "Use find_node to look up the correct node ID."
            )
        val hierarchy = graphService.model.hierarchy
        // Folding changes no weight: the levelization of the chain ends is that of the chains.
        val folding = CompactTree.folding(compact, hierarchy)
        val children = folding?.displayChildrenOf(node)?.map { it.node } ?: hierarchy.childrenOf(node).toList()
        val levelization = GraphAlgorithms.levelize(children, hierarchy)
        val effectiveLimit = (limit ?: 100).coerceIn(1, 500)

        // Every child once, by id; the rows and tangles refer to them by id.
        val nodes = LinkedHashMap<String, Any>()
        for (child in children) {
            nodeRefFactory.putSlimNode(nodes, child)
            @Suppress("UNCHECKED_CAST")
            (nodes[child.identifier.toString()] as? MutableMap<String, Any?>)?.let { CompactTree.mark(it, child, folding) }
        }

        val levels = levelization.levels.mapIndexed { index, row ->
            linkedMapOf("level" to index, "node_ids" to row.map { it.identifier })
        }
        val tangles = levelization.tangles.map { tangle ->
            val members = tangle.members.toSet()
            linkedMapOf(
                "level" to tangle.level,
                "member_ids" to tangle.members.map { it.identifier },
                "rows" to tangle.rows.map { row -> row.map { it.identifier } },
                "feedback_weight" to levelization.feedbackDependencies
                    .filter { it.from in members }
                    .sumOf { it.aggregatedWeight },
            )
        }
        val feedback = levelization.feedbackDependencies
        val returned = feedback.take(effectiveLimit).map {
            linkedMapOf(
                "from" to it.from.identifier,
                "to" to it.to.identifier,
                "weight" to it.aggregatedWeight,
                "type_dependency_count" to it.coreDependencies.size,
            )
        }

        return linkedMapOf(
            "parent" to nodeRefFactory.minimalNodeRef(node),
            "summary" to linkedMapOf(
                "children" to children.size,
                "levels" to levels.size,
                "acyclic" to tangles.isEmpty(),
                "tangles" to tangles.size,
                "feedback_dependencies" to feedback.size,
                "feedback_weight" to feedback.sumOf { it.aggregatedWeight },
                "returned" to returned.size,
                "truncated" to (returned.size < feedback.size),
            ),
            "levels" to levels,
            "tangles" to tangles,
            "feedback_dependencies" to returned,
            "nodes" to nodes,
        )
    }

    private fun errorResponse(code: String, message: String, recovery: String): Map<String, Any?> =
        mapOf("error" to linkedMapOf<String, Any?>("code" to code, "message" to message, "recovery" to recovery))
}
