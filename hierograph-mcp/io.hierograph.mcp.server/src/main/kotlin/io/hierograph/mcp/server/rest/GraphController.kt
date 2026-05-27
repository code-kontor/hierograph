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
        @RequestParam(name = "name") name: String,
        @RequestParam(name = "kindFilter", required = false) kindFilter: List<String>?
    ): Map<String, *> = findNodeTool.findNode(name, kindFilter)

    @GetMapping("/graph-overview")
    fun graphOverview(): Map<String, Any?> = graphOverviewTool.graphOverview()

    @GetMapping("/list-children")
    fun listChildren(
        @RequestParam(name = "nodeId") nodeId: Long,
        @RequestParam(name = "kindFilter", required = false) kindFilter: List<String>?,
        @RequestParam(name = "namePattern", required = false) namePattern: String?,
        @RequestParam(name = "modifierFilter", required = false) modifierFilter: List<String>?,
        @RequestParam(name = "limit", required = false) limit: Int?
    ): Map<String, Any?> = listChildrenTool.listChildren(nodeId, kindFilter, namePattern, modifierFilter, limit)

    @GetMapping("/list-descendants")
    fun listDescendants(
        @RequestParam(name = "nodeId") nodeId: Long,
        @RequestParam(name = "kindFilter", required = false) kindFilter: List<String>?,
        @RequestParam(name = "namePattern", required = false) namePattern: String?,
        @RequestParam(name = "modifierFilter", required = false) modifierFilter: List<String>?,
        @RequestParam(name = "limit", required = false) limit: Int?
    ): Map<String, Any?> = listDescendantsTool.listDescendants(nodeId, kindFilter, namePattern, modifierFilter, limit)

    // --- Dependency analysis ---

    @GetMapping("/aggregated-dependencies")
    fun aggregatedDependencies(
        @RequestParam(name = "sourceIds") sourceIds: List<Long>,
        @RequestParam(name = "targetIds") targetIds: List<Long>
    ): Map<String, Any?> = aggregatedDependenciesTool.aggregatedDependencies(sourceIds, targetIds)

    @GetMapping("/pairwise-dependencies")
    fun pairwiseDependencies(
        @RequestParam(name = "nodeIds") nodeIds: List<Long>,
        @RequestParam(name = "direction", required = false) direction: String?
    ): Map<String, Any?> = pairwiseDependenciesTool.pairwiseDependencies(nodeIds, direction)

    @GetMapping("/outgoing-dependencies")
    fun outgoingDependencies(
        @RequestParam(name = "fromId") fromId: Long,
        @RequestParam(name = "toId") toId: Long,
        @RequestParam(name = "detailLevel", required = false) detailLevel: String?,
        @RequestParam(name = "relationship", required = false) relationship: String?,
        @RequestParam(name = "limit", required = false) limit: Int?
    ): Map<String, Any?> = outgoingDependenciesTool.outgoingDependencies(fromId, toId, detailLevel, relationship, limit)

    @GetMapping("/incoming-dependencies")
    fun incomingDependencies(
        @RequestParam(name = "fromId") fromId: Long,
        @RequestParam(name = "toId") toId: Long,
        @RequestParam(name = "detailLevel", required = false) detailLevel: String?,
        @RequestParam(name = "relationship", required = false) relationship: String?,
        @RequestParam(name = "limit", required = false) limit: Int?
    ): Map<String, Any?> = incomingDependenciesTool.incomingDependencies(fromId, toId, detailLevel, relationship, limit)

    // --- Reachability ---

    @GetMapping("/affected-by")
    fun affectedBy(
        @RequestParam(name = "nodeId") nodeId: Long,
        @RequestParam(name = "direction", required = false) direction: String?,
        @RequestParam(name = "maxDepth", required = false) maxDepth: Int?,
        @RequestParam(name = "kindFilter", required = false) kindFilter: List<String>?,
        @RequestParam(name = "limit", required = false) limit: Int?
    ): Map<String, Any?> = affectedByTool.affectedBy(nodeId, direction, maxDepth, kindFilter, limit)

    @GetMapping("/find-dependency-path")
    fun findDependencyPath(
        @RequestParam(name = "fromId") fromId: Long,
        @RequestParam(name = "toId") toId: Long,
        @RequestParam(name = "maxPaths", required = false) maxPaths: Int?,
        @RequestParam(name = "maxLength", required = false) maxLength: Int?
    ): Map<String, Any?> = findDependencyPathTool.findDependencyPath(fromId, toId, maxPaths, maxLength)

    // --- Entity detail ---

    @GetMapping("/type-details")
    fun typeDetails(@RequestParam(name = "typeId") typeId: Long): Map<String, Any?> =
        typeDetailsTool.typeDetails(typeId)

    @GetMapping("/method-details")
    fun methodDetails(@RequestParam(name = "methodId") methodId: Long): Map<String, Any?> =
        methodDetailsTool.methodDetails(methodId)

    @GetMapping("/field-details")
    fun fieldDetails(@RequestParam(name = "fieldId") fieldId: Long): Map<String, Any?> =
        fieldDetailsTool.fieldDetails(fieldId)
}
