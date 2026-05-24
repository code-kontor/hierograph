package io.hierograph.mcp.server.rest

import io.hierograph.mcp.server.tools.navigation.ListDescendantsTool
import io.hierograph.mcp.server.tools.detail.FieldDetailsTool
import io.hierograph.mcp.server.tools.detail.MethodDetailsTool
import io.hierograph.mcp.server.tools.detail.TypeDetailsTool
import io.hierograph.mcp.server.tools.navigation.FindNodeTool
import io.hierograph.mcp.server.tools.navigation.GraphOverviewTool
import io.hierograph.mcp.server.tools.navigation.ListChildrenTool
import io.hierograph.mcp.server.tools.dependencyanalysis.AggregatedDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.IncomingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.PairwiseDependenciesTool
import io.hierograph.mcp.server.tools.reachability.AffectedByTool
import io.hierograph.mcp.server.tools.reachability.FindDependencyPathTool
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class GraphController(
    private val findNodeTool: FindNodeTool,
    private val graphOverviewTool: GraphOverviewTool,
    private val listChildrenTool: ListChildrenTool,
    private val listDescendantsTool: ListDescendantsTool,
    private val aggregatedDependenciesTool: AggregatedDependenciesTool,
    private val pairwiseDependenciesTool: PairwiseDependenciesTool,
    private val outgoingDependenciesTool: OutgoingDependenciesTool,
    private val incomingDependenciesTool: IncomingDependenciesTool,
    private val affectedByTool: AffectedByTool,
    private val findDependencyPathTool: FindDependencyPathTool,
    private val typeDetailsTool: TypeDetailsTool,
    private val methodDetailsTool: MethodDetailsTool,
    private val fieldDetailsTool: FieldDetailsTool
) {

    // --- Navigation ---

    @GetMapping("/find-node")
    fun findNode(
        @RequestParam name: String,
        @RequestParam(required = false) kindFilter: List<String>?
    ): Map<String, *> = findNodeTool.findNode(name, kindFilter)

    @GetMapping("/graph-overview")
    fun graphOverview(): Map<String, Any?> = graphOverviewTool.graphOverview()

    @GetMapping("/list-children")
    fun listChildren(
        @RequestParam nodeId: Long,
        @RequestParam(required = false) kindFilter: List<String>?,
        @RequestParam(required = false) namePattern: String?,
        @RequestParam(required = false) modifierFilter: List<String>?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = listChildrenTool.listChildren(nodeId, kindFilter, namePattern, modifierFilter, limit)

    @GetMapping("/list-descendants")
    fun listDescendants(
        @RequestParam nodeId: Long,
        @RequestParam(required = false) kindFilter: List<String>?,
        @RequestParam(required = false) namePattern: String?,
        @RequestParam(required = false) modifierFilter: List<String>?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = listDescendantsTool.listDescendants(nodeId, kindFilter, namePattern, modifierFilter, limit)

    // --- Dependency analysis ---

    @GetMapping("/aggregated-dependencies")
    fun aggregatedDependencies(
        @RequestParam sourceIds: List<Long>,
        @RequestParam targetIds: List<Long>
    ): Map<String, Any?> = aggregatedDependenciesTool.aggregatedDependencies(sourceIds, targetIds)

    @GetMapping("/pairwise-dependencies")
    fun pairwiseDependencies(
        @RequestParam nodeIds: List<Long>,
        @RequestParam(required = false) direction: String?
    ): Map<String, Any?> = pairwiseDependenciesTool.pairwiseDependencies(nodeIds, direction)

    @GetMapping("/outgoing-dependencies")
    fun outgoingDependencies(
        @RequestParam fromId: Long,
        @RequestParam toId: Long,
        @RequestParam(required = false) detailLevel: String?,
        @RequestParam(required = false) relationship: String?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = outgoingDependenciesTool.outgoingDependencies(fromId, toId, detailLevel, relationship, limit)

    @GetMapping("/incoming-dependencies")
    fun incomingDependencies(
        @RequestParam fromId: Long,
        @RequestParam toId: Long,
        @RequestParam(required = false) detailLevel: String?,
        @RequestParam(required = false) relationship: String?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = incomingDependenciesTool.incomingDependencies(fromId, toId, detailLevel, relationship, limit)

    // --- Reachability ---

    @GetMapping("/affected-by")
    fun affectedBy(
        @RequestParam nodeId: Long,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) maxDepth: Int?,
        @RequestParam(required = false) kindFilter: List<String>?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = affectedByTool.affectedBy(nodeId, direction, maxDepth, kindFilter, limit)

    @GetMapping("/find-dependency-path")
    fun findDependencyPath(
        @RequestParam fromId: Long,
        @RequestParam toId: Long,
        @RequestParam(required = false) maxPaths: Int?,
        @RequestParam(required = false) maxLength: Int?
    ): Map<String, Any?> = findDependencyPathTool.findDependencyPath(fromId, toId, maxPaths, maxLength)

    // --- Entity detail ---

    @GetMapping("/type-details")
    fun typeDetails(@RequestParam typeId: Long): Map<String, Any?> =
        typeDetailsTool.typeDetails(typeId)

    @GetMapping("/method-details")
    fun methodDetails(@RequestParam methodId: Long): Map<String, Any?> =
        methodDetailsTool.methodDetails(methodId)

    @GetMapping("/field-details")
    fun fieldDetails(@RequestParam fieldId: Long): Map<String, Any?> =
        fieldDetailsTool.fieldDetails(fieldId)
}
