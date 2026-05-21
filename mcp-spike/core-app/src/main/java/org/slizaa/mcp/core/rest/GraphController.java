package org.slizaa.mcp.core.rest;

import org.slizaa.mcp.core.DetailDependenciesMcpTool;
import org.slizaa.mcp.core.DiscoveryMcpTools;
import org.slizaa.mcp.core.FieldDetailsMcpTool;
import org.slizaa.mcp.core.ListFieldsMcpTool;
import org.slizaa.mcp.core.ListMethodsMcpTool;
import org.slizaa.mcp.core.MethodDetailsMcpTool;
import org.slizaa.mcp.core.PairwiseDependencyMcpTools;
import org.slizaa.mcp.core.ReachabilityMcpTools;
import org.slizaa.mcp.core.ScopeDependencyMcpTools;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GraphController {

    private final DiscoveryMcpTools discoveryTools;
    private final PairwiseDependencyMcpTools pairwiseTools;
    private final ScopeDependencyMcpTools scopeTools;
    private final ReachabilityMcpTools reachabilityTools;
    private final ListMethodsMcpTool listMethodsTool;
    private final ListFieldsMcpTool listFieldsTool;
    private final DetailDependenciesMcpTool detailDependenciesTool;
    private final MethodDetailsMcpTool methodDetailsTool;
    private final FieldDetailsMcpTool fieldDetailsTool;

    public GraphController(DiscoveryMcpTools discoveryTools,
                           PairwiseDependencyMcpTools pairwiseTools,
                           ScopeDependencyMcpTools scopeTools,
                           ReachabilityMcpTools reachabilityTools,
                           ListMethodsMcpTool listMethodsTool,
                           ListFieldsMcpTool listFieldsTool,
                           DetailDependenciesMcpTool detailDependenciesTool,
                           MethodDetailsMcpTool methodDetailsTool,
                           FieldDetailsMcpTool fieldDetailsTool) {
        this.discoveryTools = discoveryTools;
        this.pairwiseTools = pairwiseTools;
        this.scopeTools = scopeTools;
        this.reachabilityTools = reachabilityTools;
        this.listMethodsTool = listMethodsTool;
        this.listFieldsTool = listFieldsTool;
        this.detailDependenciesTool = detailDependenciesTool;
        this.methodDetailsTool = methodDetailsTool;
        this.fieldDetailsTool = fieldDetailsTool;
    }

    @GetMapping("/find-node")
    public List<Map<String, Object>> findNode(
            @RequestParam String query,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer limit) {
        return discoveryTools.findNode(query, kind, limit);
    }

    @GetMapping("/list-children")
    public List<Map<String, Object>> listChildren(
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) Integer limit) {
        return discoveryTools.listChildren(nodeId, limit);
    }

    @GetMapping("/list-descendants")
    public Map<String, Object> listDescendants(
            @RequestParam long rootId,
            @RequestParam(required = false) List<String> kindFilter,
            @RequestParam(required = false) List<String> excludeKindFilter,
            @RequestParam(required = false) Integer limit) {
        return discoveryTools.listDescendants(rootId, kindFilter, excludeKindFilter, limit);
    }

    @GetMapping("/dependency-between")
    public Map<String, Object> dependencyBetween(
            @RequestParam long fromId,
            @RequestParam long toId) {
        return pairwiseTools.dependencyBetween(fromId, toId);
    }

    @GetMapping("/aggregated-outgoing")
    public Map<String, Object> aggregatedOutgoing(
            @RequestParam long sourceId,
            @RequestParam(required = false) Long targetScopeId,
            @RequestParam(required = false) Integer limit) {
        return scopeTools.aggregatedOutgoing(sourceId, targetScopeId, limit);
    }

    @GetMapping("/aggregated-incoming")
    public Map<String, Object> aggregatedIncoming(
            @RequestParam long targetId,
            @RequestParam(required = false) Long sourceScopeId,
            @RequestParam(required = false) Integer limit) {
        return scopeTools.aggregatedIncoming(targetId, sourceScopeId, limit);
    }

    @GetMapping("/outgoing-core-dependencies")
    public Map<String, Object> outgoingCoreDependencies(
            @RequestParam long fromId,
            @RequestParam long toId,
            @RequestParam(required = false) Integer limit) {
        return scopeTools.outgoingCoreDependencies(fromId, toId, limit);
    }

    @GetMapping("/incoming-core-dependencies")
    public Map<String, Object> incomingCoreDependencies(
            @RequestParam long toId,
            @RequestParam long fromId,
            @RequestParam(required = false) Integer limit) {
        return scopeTools.incomingCoreDependencies(toId, fromId, limit);
    }

    @GetMapping("/describe-graph")
    public Map<String, Object> describeGraph(
            @RequestParam(required = false) Long scopeId) {
        return discoveryTools.describeGraph(scopeId);
    }

    @GetMapping("/find-dependency-path")
    public Map<String, Object> findDependencyPath(
            @RequestParam long fromId,
            @RequestParam long toId,
            @RequestParam(required = false) Integer maxLength) {
        return reachabilityTools.findDependencyPath(fromId, toId, maxLength);
    }

    @GetMapping("/pairwise-dependencies")
    public Map<String, Object> pairwiseDependencies(
            @RequestParam List<Long> nodeIds,
            @RequestParam(required = false) Boolean includeSelfLoops) {
        return reachabilityTools.pairwiseDependencies(nodeIds, includeSelfLoops);
    }

    @GetMapping("/affected-by")
    public Map<String, Object> affectedBy(
            @RequestParam long sourceId,
            @RequestParam(required = false) Integer maxDepth,
            @RequestParam(required = false) Long groupingScopeId,
            @RequestParam(required = false) Integer topN) {
        return reachabilityTools.affectedBy(sourceId, maxDepth, groupingScopeId, topN);
    }

    @GetMapping("/outgoing-to")
    public Map<String, Object> outgoingTo(
            @RequestParam long sourceId,
            @RequestParam List<Long> targetIds,
            @RequestParam(required = false) Boolean includeMissing) {
        return pairwiseTools.outgoingTo(sourceId, targetIds, includeMissing);
    }

    @GetMapping("/incoming-from")
    public Map<String, Object> incomingFrom(
            @RequestParam long targetId,
            @RequestParam List<Long> sourceIds,
            @RequestParam(required = false) Boolean includeMissing) {
        return pairwiseTools.incomingFrom(targetId, sourceIds, includeMissing);
    }

    // --- Detail-level tools ---

    @GetMapping("/list-methods")
    public Map<String, Object> listMethods(
            @RequestParam long typeId,
            @RequestParam(required = false) String namePattern,
            @RequestParam(required = false) List<String> modifierFilter,
            @RequestParam(required = false) Boolean includeInherited,
            @RequestParam(required = false) Integer limit) {
        return listMethodsTool.listMethods(typeId, namePattern, modifierFilter, includeInherited, limit);
    }

    @GetMapping("/list-fields")
    public Map<String, Object> listFields(
            @RequestParam long typeId,
            @RequestParam(required = false) String namePattern,
            @RequestParam(required = false) List<String> modifierFilter,
            @RequestParam(required = false) Boolean includeInherited,
            @RequestParam(required = false) Integer limit) {
        return listFieldsTool.listFields(typeId, namePattern, modifierFilter, includeInherited, limit);
    }

    @GetMapping("/detail-dependencies")
    public Map<String, Object> detailDependencies(
            @RequestParam long fromId,
            @RequestParam long toId,
            @RequestParam(required = false) String relationship,
            @RequestParam(required = false) Integer limit) {
        return detailDependenciesTool.detailDependencies(fromId, toId, relationship, limit);
    }

    @GetMapping("/method-details")
    public Map<String, Object> methodDetails(@RequestParam long methodId) {
        return methodDetailsTool.methodDetails(methodId);
    }

    @GetMapping("/field-details")
    public Map<String, Object> fieldDetails(@RequestParam long fieldId) {
        return fieldDetailsTool.fieldDetails(fieldId);
    }
}
