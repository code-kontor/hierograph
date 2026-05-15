package org.slizaa.mcp.core;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GraphController {

    private final GraphMcpTools mcpTools;

    public GraphController(GraphMcpTools mcpTools) {
        this.mcpTools = mcpTools;
    }

    @GetMapping("/find-node")
    public List<Map<String, Object>> findNode(
            @RequestParam String query,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer limit) {
        return mcpTools.findNode(query, kind, limit);
    }

    @GetMapping("/list-children")
    public List<Map<String, Object>> listChildren(
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) Integer limit) {
        return mcpTools.listChildren(nodeId, limit);
    }

    @GetMapping("/list-descendants")
    public Map<String, Object> listDescendants(
            @RequestParam long rootId,
            @RequestParam(required = false) List<String> kindFilter,
            @RequestParam(required = false) List<String> excludeKindFilter,
            @RequestParam(required = false) Integer limit) {
        return mcpTools.listDescendants(rootId, kindFilter, excludeKindFilter, limit);
    }

    @GetMapping("/dependency-between")
    public Map<String, Object> dependencyBetween(
            @RequestParam long fromId,
            @RequestParam long toId) {
        return mcpTools.dependencyBetween(fromId, toId);
    }

    @GetMapping("/aggregated-outgoing")
    public Map<String, Object> aggregatedOutgoing(
            @RequestParam long sourceId,
            @RequestParam(required = false) Long targetScopeId,
            @RequestParam(required = false) Integer limit) {
        return mcpTools.aggregatedOutgoing(sourceId, targetScopeId, limit);
    }

    @GetMapping("/aggregated-incoming")
    public Map<String, Object> aggregatedIncoming(
            @RequestParam long targetId,
            @RequestParam(required = false) Long sourceScopeId,
            @RequestParam(required = false) Integer limit) {
        return mcpTools.aggregatedIncoming(targetId, sourceScopeId, limit);
    }

    @GetMapping("/outgoing-core-dependencies")
    public Map<String, Object> outgoingCoreDependencies(
            @RequestParam long fromId,
            @RequestParam long toId,
            @RequestParam(required = false) Integer limit) {
        return mcpTools.outgoingCoreDependencies(fromId, toId, limit);
    }

    @GetMapping("/incoming-core-dependencies")
    public Map<String, Object> incomingCoreDependencies(
            @RequestParam long toId,
            @RequestParam long fromId,
            @RequestParam(required = false) Integer limit) {
        return mcpTools.incomingCoreDependencies(toId, fromId, limit);
    }

    @GetMapping("/describe-graph")
    public Map<String, Object> describeGraph(
            @RequestParam(required = false) Long scopeId) {
        return mcpTools.describeGraph(scopeId);
    }

    @GetMapping("/find-dependency-path")
    public Map<String, Object> findDependencyPath(
            @RequestParam long fromId,
            @RequestParam long toId,
            @RequestParam(required = false) Integer maxLength) {
        return mcpTools.findDependencyPath(fromId, toId, maxLength);
    }

    @GetMapping("/pairwise-dependencies")
    public Map<String, Object> pairwiseDependencies(
            @RequestParam List<Long> nodeIds,
            @RequestParam(required = false) Boolean includeSelfLoops) {
        return mcpTools.pairwiseDependencies(nodeIds, includeSelfLoops);
    }

    @GetMapping("/affected-by")
    public Map<String, Object> affectedBy(
            @RequestParam long sourceId,
            @RequestParam(required = false) Integer maxDepth,
            @RequestParam(required = false) Long groupingScopeId,
            @RequestParam(required = false) Integer topN) {
        return mcpTools.affectedBy(sourceId, maxDepth, groupingScopeId, topN);
    }

    @GetMapping("/outgoing-to")
    public Map<String, Object> outgoingTo(
            @RequestParam long sourceId,
            @RequestParam List<Long> targetIds,
            @RequestParam(required = false) Boolean includeMissing) {
        return mcpTools.outgoingTo(sourceId, targetIds, includeMissing);
    }

    @GetMapping("/incoming-from")
    public Map<String, Object> incomingFrom(
            @RequestParam long targetId,
            @RequestParam List<Long> sourceIds,
            @RequestParam(required = false) Boolean includeMissing) {
        return mcpTools.incomingFrom(targetId, sourceIds, includeMissing);
    }
}
