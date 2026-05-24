package org.slizaa.mcp.core.rest

import org.slizaa.mcp.core.mcp.detail.DetailDependenciesMcpTool
import org.slizaa.mcp.core.mcp.discovery.DiscoveryMcpTools
import org.slizaa.mcp.core.mcp.detail.FieldDetailsMcpTool
import org.slizaa.mcp.core.mcp.detail.ListFieldsMcpTool
import org.slizaa.mcp.core.mcp.detail.ListMethodsMcpTool
import org.slizaa.mcp.core.mcp.detail.MethodDetailsMcpTool
import org.slizaa.mcp.core.mcp.navigation.FindNodeTool
import org.slizaa.mcp.core.mcp.pairwisedependency.PairwiseDependencyMcpTools
import org.slizaa.mcp.core.mcp.reachability.ReachabilityMcpTools
import org.slizaa.mcp.core.mcp.scopedependency.ScopeDependencyMcpTools
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class GraphController(
    private val findNodeTool: FindNodeTool,
    private val discoveryTools: DiscoveryMcpTools,
    private val pairwiseTools: PairwiseDependencyMcpTools,
    private val scopeTools: ScopeDependencyMcpTools,
    private val reachabilityTools: ReachabilityMcpTools,
    private val listMethodsTool: ListMethodsMcpTool,
    private val listFieldsTool: ListFieldsMcpTool,
    private val detailDependenciesTool: DetailDependenciesMcpTool,
    private val methodDetailsTool: MethodDetailsMcpTool,
    private val fieldDetailsTool: FieldDetailsMcpTool
) {

    @GetMapping("/find-node")
    fun findNode(
        @RequestParam name: String,
        @RequestParam(required = false) kindFilter: List<String>?
    ): Map<String, *> = findNodeTool.findNode(name, kindFilter)

    @GetMapping("/list-children")
    fun listChildren(
        @RequestParam(required = false) nodeId: Long?,
        @RequestParam(required = false) limit: Int?
    ): List<Map<String, Any?>> = discoveryTools.listChildren(nodeId, limit)

    @GetMapping("/list-descendants")
    fun listDescendants(
        @RequestParam rootId: Long,
        @RequestParam(required = false) kindFilter: List<String>?,
        @RequestParam(required = false) excludeKindFilter: List<String>?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = discoveryTools.listDescendants(rootId, kindFilter, excludeKindFilter, limit)

    @GetMapping("/dependency-between")
    fun dependencyBetween(
        @RequestParam fromId: Long,
        @RequestParam toId: Long
    ): Map<String, Any?> = pairwiseTools.dependencyBetween(fromId, toId)

    @GetMapping("/aggregated-outgoing")
    fun aggregatedOutgoing(
        @RequestParam sourceId: Long,
        @RequestParam(required = false) targetScopeId: Long?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = scopeTools.aggregatedOutgoing(sourceId, targetScopeId, limit)

    @GetMapping("/aggregated-incoming")
    fun aggregatedIncoming(
        @RequestParam targetId: Long,
        @RequestParam(required = false) sourceScopeId: Long?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = scopeTools.aggregatedIncoming(targetId, sourceScopeId, limit)

    @GetMapping("/outgoing-core-dependencies")
    fun outgoingCoreDependencies(
        @RequestParam fromId: Long,
        @RequestParam toId: Long,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = scopeTools.outgoingCoreDependencies(fromId, toId, limit)

    @GetMapping("/incoming-core-dependencies")
    fun incomingCoreDependencies(
        @RequestParam toId: Long,
        @RequestParam fromId: Long,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = scopeTools.incomingCoreDependencies(toId, fromId, limit)

    @GetMapping("/describe-graph")
    fun describeGraph(
        @RequestParam(required = false) scopeId: Long?
    ): Map<String, Any?> = discoveryTools.describeGraph(scopeId)

    @GetMapping("/find-dependency-path")
    fun findDependencyPath(
        @RequestParam fromId: Long,
        @RequestParam toId: Long,
        @RequestParam(required = false) maxLength: Int?
    ): Map<String, Any?> = reachabilityTools.findDependencyPath(fromId, toId, maxLength)

    @GetMapping("/pairwise-dependencies")
    fun pairwiseDependencies(
        @RequestParam nodeIds: List<Long>,
        @RequestParam(required = false) includeSelfLoops: Boolean?
    ): Map<String, Any?> = reachabilityTools.pairwiseDependencies(nodeIds, includeSelfLoops)

    @GetMapping("/affected-by")
    fun affectedBy(
        @RequestParam sourceId: Long,
        @RequestParam(required = false) maxDepth: Int?,
        @RequestParam(required = false) groupingScopeId: Long?,
        @RequestParam(required = false) topN: Int?
    ): Map<String, Any?> = reachabilityTools.affectedBy(sourceId, maxDepth, groupingScopeId, topN)

    @GetMapping("/outgoing-to")
    fun outgoingTo(
        @RequestParam sourceId: Long,
        @RequestParam targetIds: List<Long>,
        @RequestParam(required = false) includeMissing: Boolean?
    ): Map<String, Any?> = pairwiseTools.outgoingTo(sourceId, targetIds, includeMissing)

    @GetMapping("/incoming-from")
    fun incomingFrom(
        @RequestParam targetId: Long,
        @RequestParam sourceIds: List<Long>,
        @RequestParam(required = false) includeMissing: Boolean?
    ): Map<String, Any?> = pairwiseTools.incomingFrom(targetId, sourceIds, includeMissing)

    // --- Detail-level tools ---

    @GetMapping("/list-methods")
    fun listMethods(
        @RequestParam typeId: Long,
        @RequestParam(required = false) namePattern: String?,
        @RequestParam(required = false) modifierFilter: List<String>?,
        @RequestParam(required = false) includeInherited: Boolean?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = listMethodsTool.listMethods(typeId, namePattern, modifierFilter, includeInherited, limit)

    @GetMapping("/list-fields")
    fun listFields(
        @RequestParam typeId: Long,
        @RequestParam(required = false) namePattern: String?,
        @RequestParam(required = false) modifierFilter: List<String>?,
        @RequestParam(required = false) includeInherited: Boolean?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = listFieldsTool.listFields(typeId, namePattern, modifierFilter, includeInherited, limit)

    @GetMapping("/detail-dependencies")
    fun detailDependencies(
        @RequestParam fromId: Long,
        @RequestParam toId: Long,
        @RequestParam(required = false) relationship: String?,
        @RequestParam(required = false) limit: Int?
    ): Map<String, Any?> = detailDependenciesTool.detailDependencies(fromId, toId, relationship, limit)

    @GetMapping("/method-details")
    fun methodDetails(@RequestParam methodId: Long): Map<String, Any?> =
        methodDetailsTool.methodDetails(methodId)

    @GetMapping("/field-details")
    fun fieldDetails(@RequestParam fieldId: Long): Map<String, Any?> =
        fieldDetailsTool.fieldDetails(fieldId)
}
