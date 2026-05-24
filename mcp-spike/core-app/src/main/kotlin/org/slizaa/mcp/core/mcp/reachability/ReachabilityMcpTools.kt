package org.slizaa.mcp.core.mcp.reachability

import org.slizaa.hierarchicalgraph.core.model.HGAggregatedDependency
import org.slizaa.hierarchicalgraph.core.model.HGCoreDependency
import org.slizaa.hierarchicalgraph.core.model.HGNode
import org.slizaa.hierarchicalgraph.core.algorithms.GraphUtils
import org.slizaa.hierarchicalgraph.core.algorithms.IDependencyStructureMatrix
import org.slizaa.mcp.core.mcp.AbstractGraphMcpTools
import org.slizaa.mcp.core.HierarchicalGraphService
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.util.ArrayDeque
import java.util.TreeSet

@Component
class ReachabilityMcpTools(graphService: HierarchicalGraphService) : AbstractGraphMcpTools(graphService) {

    @Tool(
        name = "find_dependency_path",
        description = "[Hierarchical reachability] Determine whether a transitive dependency path exists from one node to another, " +
                "and if so, return the shortest path. This is the right tool for transitive reachability " +
                "questions — it answers 'can A indirectly affect B?' over the in-memory dependency graph. " +
                "An 'exists: false' result is definitive — there is no dependency chain of any length " +
                "between the two nodes (up to max_length). Computed via BFS over accumulated core " +
                "dependencies; cheap given the in-memory graph."
    )
    fun findDependencyPath(
        @ToolParam(description = "Source node ID — starting point of the path") fromId: Long,
        @ToolParam(description = "Target node ID — destination of the path") toId: Long,
        @ToolParam(
            description = "Maximum path length to consider (1-20, default 10)",
            required = false
        ) maxLength: Int?
    ): Map<String, Any> {

        val fromNode = graphService.rootNode.lookupNode(fromId)
            ?: return mapOf("error" to "Source node not found: $fromId")

        val toNode = graphService.rootNode.lookupNode(toId)
            ?: return mapOf("error" to "Target node not found: $toId")

        val effectiveMaxLength = if (maxLength != null) maxLength.coerceIn(1, 20) else 10

        // Determine traversal strategy
        val useAggregatedTraversal = fromNode.children.isNotEmpty() && toNode.children.isNotEmpty()

        // BFS to find shortest path
        val predecessors = linkedMapOf<HGNode, HGNode?>()
        val queue = ArrayDeque<HGNode>()
        queue.add(fromNode)
        predecessors[fromNode] = null

        // For aggregated traversal, collect all sibling nodes at the same level as candidates
        var candidateNodes: MutableSet<HGNode>? = null
        if (useAggregatedTraversal) {
            candidateNodes = linkedSetOf()
            collectAllNodesAtLevel(fromNode, candidateNodes)
        }

        var found = false
        while (queue.isNotEmpty() && !found) {
            val current = queue.poll()

            // Check path length
            var depth = 0
            var trace: HGNode? = current
            while (predecessors[trace] != null) {
                depth++
                trace = predecessors[trace]
            }
            if (depth >= effectiveMaxLength) continue

            // Collect neighbors based on traversal strategy
            val neighbors = linkedSetOf<HGNode>()
            if (useAggregatedTraversal) {
                for (candidate in candidateNodes!!) {
                    if (candidate.identifier == current.identifier) continue
                    val aggDep = current.getOutgoingDependenciesTo(candidate)
                    if (aggDep != null && aggDep.aggregatedWeight > 0) {
                        neighbors.add(candidate)
                    }
                }
            } else {
                for (dep in current.accumulatedOutgoingCoreDependencies) {
                    neighbors.add(dep.to)
                }
            }

            for (neighbor in neighbors) {
                if (neighbor in predecessors) continue // already visited
                predecessors[neighbor] = current
                if (neighbor.identifier == toNode.identifier) {
                    found = true
                    break
                }
                queue.add(neighbor)
            }
        }

        val result = linkedMapOf<String, Any>(
            "from" to toNodeRefShort(fromNode),
            "to" to toNodeRefShort(toNode)
        )

        if (found) {
            val pathNodes = mutableListOf<HGNode>()
            var step: HGNode? = toNode
            while (step != null) {
                pathNodes.add(step)
                step = predecessors[step]
            }
            pathNodes.reverse()

            result["exists"] = true
            result["length"] = pathNodes.size - 1
            result["path"] = pathNodes.map { toNodeRefShort(it) }
        } else {
            result["exists"] = false
            result["max_length_searched"] = effectiveMaxLength
        }

        return result
    }

    @Tool(
        name = "pairwise_dependencies",
        description = "[Hierarchical pairwise] **Use this for: dependency structure matrix (DSM), module coupling matrix, " +
                "cycle detection across a module set, layering analysis, all-pairs coupling within a group of modules.** " +
                "Given a set of nodes (typically siblings, layers, or a user-specified group), return all pairwise " +
                "aggregated dependencies among them as an edge list, plus server-computed structural insights: density, " +
                "cycle presence, strongly connected components, and topological order. One call instead of N\u00B2 calls to " +
                "dependency_between or aggregated_outgoing. " +
                "Example: pairwise_dependencies(nodeIds=[ids of top-level projects from describe_graph or list_children of root]) " +
                "→ returns the DSM edge list, plus density, cycle status, SCCs, and topological order. " +
                "Returns nodes once in a top-level 'nodes' map keyed by ID; 'edges' reference nodes by ID, not embedded copies. " +
                "The 'summary' block carries the structural digest — for many architectural questions (cycle check, density, " +
                "layering) the summary alone is the answer: has_cycles==false confirms proper layering (and " +
                "strongly_connected_components names the nodes to untangle when true), density characterizes how tightly " +
                "coupled the set is, max_edge_weight flags the heaviest pair, topological_order (when acyclic) gives the " +
                "natural reading order. " +
                "Do NOT loop aggregated_outgoing or dependency_between over a node set to build a matrix — use this tool " +
                "instead. For a single (source, target) check use dependency_between; for one source against many targets " +
                "use outgoing_to; for many sources against one target use incoming_from; for fan-out use aggregated_outgoing; " +
                "for blast radius use aggregated_incoming; for method/field-level evidence underneath an aggregated edge " +
                "use detail_dependencies."
    )
    fun pairwiseDependencies(
        @ToolParam(
            description = "List of node IDs to analyze pairwise (the analysis set; typically siblings, layers, " +
                    "or a user-specified group of modules)"
        ) nodeIds: List<Long>,
        @ToolParam(
            description = "Whether to include self-loops (internal coupling within each node's subtree). " +
                    "Default false — the headline use cases (layering check, cycle detection, DSM rendering) are about " +
                    "between-node coupling. Set true when ranking modules by internal coupling.",
            required = false
        ) includeSelfLoops: Boolean?
    ): Map<String, Any> {

        if (nodeIds.isEmpty()) {
            return mapOf("error" to "node_ids must contain at least one node ID", "code" to "EMPTY_NODE_SET")
        }

        val selfLoops = includeSelfLoops == true

        // Resolve nodes
        val nodes = mutableListOf<HGNode>()
        for (id in nodeIds) {
            val node = graphService.rootNode.lookupNode(id)
                ?: return mapOf("error" to "Node not found: $id", "code" to "NODE_NOT_FOUND")
            nodes.add(node)
        }

        // Build DSM using the existing algorithms library
        val dsm: IDependencyStructureMatrix = GraphUtils.createDependencyStructureMatrix(nodes)
        val orderedNodes = dsm.orderedNodes
        val size = orderedNodes.size

        // Slim-encoded nodes map
        val nodesMap = linkedMapOf<String, Any>()
        for (node in orderedNodes) {
            nodesMap[node.identifier.toString()] = linkedMapOf(
                "name" to getMetadataProvider().getName(node),
                "qualified_name" to getMetadataProvider().getQualifiedName(node),
                "kind" to getMetadataProvider().getKind(node)
            )
        }

        // Edge list
        val edges = mutableListOf<Map<String, Any>>()
        var totalWeight = 0
        var edgeCount = 0
        var maxWeight = 0
        var nonSelfEdges = 0

        for (i in 0 until size) {
            val source = orderedNodes[i]
            for (j in 0 until size) {
                if (!selfLoops && i == j) continue
                val target = orderedNodes[j]
                val aggDep = source.getOutgoingDependenciesTo(target) ?: continue
                if (aggDep.aggregatedWeight <= 0) continue

                val weight = aggDep.aggregatedWeight
                val kinds = TreeSet<String>()
                for (coreDep in aggDep.coreDependencies) {
                    coreDep.type?.let { kinds.add(it) }
                }

                edges.add(
                    linkedMapOf(
                        "from" to source.identifier,
                        "to" to target.identifier,
                        "weight" to weight,
                        "kinds" to kinds.toList()
                    )
                )

                totalWeight += weight
                edgeCount++
                maxWeight = maxOf(maxWeight, weight)
                if (i != j) nonSelfEdges++
            }
        }

        // Density: off-diagonal only
        val possibleEdges = size * (size - 1)
        val density = if (possibleEdges > 0) Math.round(nonSelfEdges * 1000.0 / possibleEdges) / 1000.0 else 0.0

        // SCCs; topological_order present only when acyclic
        val cycles = dsm.cycles
        val hasCycles = cycles.isNotEmpty()
        val sccs = cycles.map { cycle -> cycle.map { it.identifier } }

        val summary = linkedMapOf<String, Any>(
            "node_count" to size,
            "edge_count" to edgeCount,
            "total_weight" to totalWeight,
            "max_edge_weight" to maxWeight,
            "density" to density,
            "has_cycles" to hasCycles,
            "strongly_connected_components" to sccs
        )
        if (!hasCycles) {
            summary["topological_order"] = orderedNodes.map { it.identifier }
        }

        return linkedMapOf(
            "nodes" to nodesMap,
            "edges" to edges,
            "summary" to summary
        )
    }

    @Tool(
        name = "affected_by",
        description = "[Hierarchical reachability] Return the transitive blast radius of a proposed change to a node — everything that " +
                "depends on it, directly or indirectly, up to a specified depth — as a structured summary. " +
                "This is the tool for the full ripple-effect question, distinct from aggregated_incoming " +
                "(which shows only direct dependents). Traversal walks backwards through core (leaf-level) " +
                "dependencies from the source's boundary. The source's own subtree is excluded. " +
                "The response is a structured summary (depth distribution, by-scope breakdown, top affected " +
                "nodes) rather than an enumeration of every affected node. Depth is measured in core " +
                "dependency hops. Cycles are handled via visited-set deduplication; each affected node " +
                "appears once at its shortest reachable depth."
    )
    fun affectedBy(
        @ToolParam(description = "The node being changed — starting point for blast radius analysis") sourceId: Long,
        @ToolParam(
            description = "Maximum traversal depth in core dependency hops (1-20, default 5)",
            required = false
        ) maxDepth: Int?,
        @ToolParam(
            description = "Aggregate the by_scope result at the level of children of this scope. " +
                    "Defaults to root (top-level modules).",
            required = false
        ) groupingScopeId: Long?,
        @ToolParam(
            description = "Size of the top_affected_nodes list (1-50, default 10)",
            required = false
        ) topN: Int?
    ): Map<String, Any> {

        val sourceNode = graphService.rootNode.lookupNode(sourceId)
            ?: return mapOf("error" to "Source node not found: $sourceId")

        val effectiveMaxDepth = if (maxDepth != null) maxDepth.coerceIn(1, 20) else 5
        val effectiveTopN = if (topN != null) topN.coerceIn(1, 50) else 10

        val groupingScope: HGNode = if (groupingScopeId == null) {
            graphService.rootNode
        } else {
            graphService.rootNode.lookupNode(groupingScopeId)
                ?: return mapOf("error" to "Grouping scope node not found: $groupingScopeId")
        }

        // Collect the source's own leaf descendants (to exclude from results)
        val sourceSubtreeIds = mutableSetOf<Any>()
        collectDescendantIds(sourceNode, sourceSubtreeIds)

        // BFS backwards through incoming core dependencies
        val affectedWithDepth = linkedMapOf<HGNode, Int>()
        val affectedWeight = linkedMapOf<HGNode, Int>()

        val queue = ArrayDeque<HGNode>()

        // Get all leaf nodes in the source subtree and their incoming edges from outside
        val boundaryIncoming = mutableListOf<HGCoreDependency>()
        collectBoundaryIncomingDeps(sourceNode, sourceSubtreeIds, boundaryIncoming)

        for (dep in boundaryIncoming) {
            val from = dep.from
            if (from.identifier !in sourceSubtreeIds && from !in affectedWithDepth) {
                affectedWithDepth[from] = 1
                affectedWeight[from] = dep.weight
                queue.add(from)
            } else if (from in affectedWeight) {
                affectedWeight.merge(from, dep.weight, Integer::sum)
            }
        }

        // BFS expansion
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            val currentDepth = affectedWithDepth[current]!!
            if (currentDepth >= effectiveMaxDepth) continue

            for (dep in current.incomingCoreDependencies) {
                val from = dep.from
                if (from.identifier in sourceSubtreeIds) continue
                if (from in affectedWithDepth) continue

                affectedWithDepth[from] = currentDepth + 1
                affectedWeight[from] = dep.weight
                queue.add(from)
            }
        }

        // Compute depth distribution
        val depthDistribution = java.util.TreeMap<Int, Int>()
        var maxDepthReached = 0
        for (depth in affectedWithDepth.values) {
            depthDistribution.merge(depth, 1, Integer::sum)
            maxDepthReached = maxOf(maxDepthReached, depth)
        }

        // Compute by_scope
        val byScope = mutableListOf<Map<String, Any>>()
        for (scopeChild in groupingScope.children) {
            val scopeDescIds = mutableSetOf<Any>()
            collectDescendantIds(scopeChild, scopeDescIds)

            var affectedCount = 0
            var minDepthInScope = Int.MAX_VALUE
            var maxDepthInScope = 0

            for ((node, depth) in affectedWithDepth) {
                if (node.identifier in scopeDescIds) {
                    affectedCount++
                    minDepthInScope = minOf(minDepthInScope, depth)
                    maxDepthInScope = maxOf(maxDepthInScope, depth)
                }
            }

            if (affectedCount > 0) {
                val aggDep = scopeChild.getOutgoingDependenciesTo(sourceNode)
                val weightToSource = aggDep?.aggregatedWeight ?: 0

                byScope.add(
                    linkedMapOf(
                        "scope" to toNodeRefShort(scopeChild),
                        "affected_descendant_count" to affectedCount,
                        "min_depth" to minDepthInScope,
                        "max_depth" to maxDepthInScope,
                        "weight_to_source" to weightToSource
                    )
                )
            }
        }
        byScope.sortByDescending { it["affected_descendant_count"] as Int }

        // Top affected nodes: ranked by weight descending
        val topAffected = affectedWithDepth.entries
            .sortedByDescending { affectedWeight.getOrDefault(it.key, 0) }
            .take(effectiveTopN)
            .map { (node, depth) ->
                linkedMapOf<String, Any>(
                    "node" to toNodeRefShort(node),
                    "depth" to depth,
                    "weight_from_predecessor" to (affectedWeight[node] ?: 0)
                )
            }

        // Build result
        return linkedMapOf(
            "source" to toNodeRefShort(sourceNode),
            "max_depth_requested" to effectiveMaxDepth,
            "summary" to linkedMapOf(
                "total_affected_leaves" to affectedWithDepth.size,
                "max_depth_reached" to maxDepthReached,
                "depth_distribution" to depthDistribution
            ),
            "by_scope" to byScope,
            "top_affected_nodes" to topAffected
        )
    }

    // --- helpers ---

    private fun collectAllNodesAtLevel(referenceNode: HGNode, result: MutableSet<HGNode>) {
        var depth = 0
        var walk: HGNode? = referenceNode
        while (walk?.parent != null) {
            depth++
            walk = walk.parent
        }
        // walk is now root; collect all nodes at that depth
        collectNodesAtDepth(walk!!, depth, 0, result)
    }

    private fun collectNodesAtDepth(node: HGNode, targetDepth: Int, currentDepth: Int, result: MutableSet<HGNode>) {
        if (currentDepth == targetDepth) {
            result.add(node)
            return
        }
        for (child in node.children) {
            collectNodesAtDepth(child, targetDepth, currentDepth + 1, result)
        }
    }

    private fun collectDescendantIds(node: HGNode, ids: MutableSet<Any>) {
        ids.add(node.identifier)
        for (child in node.children) {
            collectDescendantIds(child, ids)
        }
    }

    private fun collectBoundaryIncomingDeps(
        node: HGNode, sourceSubtreeIds: Set<Any>,
        result: MutableList<HGCoreDependency>
    ) {
        if (node.children.isEmpty()) {
            for (dep in node.incomingCoreDependencies) {
                if (dep.from.identifier !in sourceSubtreeIds) {
                    result.add(dep)
                }
            }
        } else {
            for (child in node.children) {
                collectBoundaryIncomingDeps(child, sourceSubtreeIds, result)
            }
        }
    }
}
