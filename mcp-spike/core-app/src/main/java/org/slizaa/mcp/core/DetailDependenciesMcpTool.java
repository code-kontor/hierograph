package org.slizaa.mcp.core;

import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Implementation of the {@code detail_dependencies} MCP tool. See the spec at
 * {@code docs/tool-specifications/detail-level/cartograph-detail-dependencies-spec.md}.
 *
 * <p>Returns method/field-level edges between a source subtree and a target subtree, with
 * optional filtering by Cartograph relationship kind. The Cartograph vocabulary is mapped
 * to jQAssistant edge labels by the {@link #BRANCHES} table — the single place to look
 * when adding, renaming, or remapping a relationship kind.</p>
 */
@Component
public class DetailDependenciesMcpTool extends AbstractDetailMcpTool {

    private static final Logger log = LoggerFactory.getLogger(DetailDependenciesMcpTool.class);

    /**
     * When true, the Cypher statement built for each invocation is logged at INFO together
     * with the effective {@code relationship} / {@code include_inherited} flags and the
     * resolved {@code fromTypes} / {@code toTypes} parameters. Set via
     * {@code slizaa.mcp.tools.detail-dependencies.log-cypher=true} in
     * {@code application.properties}; defaults to {@code false}. Each detail tool gets its
     * own flag as the need arises.
     */
    @Value("${slizaa.mcp.tools.detail-dependencies.log-cypher:false}")
    private boolean logCypher;

    public DetailDependenciesMcpTool(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "detail_dependencies",
            description = "Return the method-level and field-level dependencies between a source subtree and a target " +
                    "subtree. This is the drill-down tool that bridges the hierarchical level and the detail level — " +
                    "given an aggregated dependency you've identified (typically via aggregated_outgoing, " +
                    "aggregated_incoming, or outgoing_core_dependencies), this returns the underlying concrete " +
                    "method/field edges that explain it. " +
                    "Returns a top-level 'nodes' map (each referenced node listed once with name, qualified_name, kind) " +
                    "plus an 'edges' list whose entries reference nodes by ID. Each edge carries 'from' and 'to' " +
                    "(node IDs), 'from_parent' and optionally 'to_parent' (declaring-type IDs for navigation back to " +
                    "the hierarchical model), the relationship kind, and the source location (file path and line " +
                    "number). The 'summary' block groups edges by relationship kind (by_relationship) and by source " +
                    "type (by_source_type) — these are often more useful than enumerating individual edges, because " +
                    "they tell you what kind of coupling exists and which types in the source subtree are responsible. " +
                    "Common parameter patterns: " +
                    "from_id + to_id (no relationship): see the full structural picture of detail-level coupling " +
                    "between two subtrees. Returns all relationship kinds; the by_relationship summary tells you the " +
                    "distribution. " +
                    "from_id + to_id + relationship 'throws': drill into one specific kind of coupling (here, " +
                    "exception throws). " +
                    "from_id = root_id + to_id = some_annotation_type: global query — find every method anywhere " +
                    "with this annotation. " +
                    "from_id = root_id + to_id = some_field with relationship 'writes_field': global query — find " +
                    "every method that writes this field. " +
                    "include_inherited=true: also include edges where the source method/field is inherited from an " +
                    "ancestor of a type in the source subtree (and symmetrically for the target subtree, when the " +
                    "target is a method or field). When the source method is inherited, 'from_parent' is the actual " +
                    "declaring ancestor (not the subtree-anchor type); same for 'to_parent'. Default false. " +
                    "When to use this vs. neighboring tools: " +
                    "For the type-level evidence (which type-pairs are coupled), use outgoing_core_dependencies or " +
                    "incoming_core_dependencies. This tool drills one level deeper, into the methods and fields that " +
                    "realize those type-level edges. " +
                    "For the methods declared on a single type (composition rather than dependency), use list_methods. " +
                    "For everything about one specific method or field, use method_details or field_details. " +
                    "Relationship kinds available: throws, calls, returns, parameter_type, reads_field, writes_field, " +
                    "overrides, annotated_by, parameter_annotated_by, has_type, read_by, written_by.")
    public Map<String, Object> detailDependencies(
            @ToolParam(description = "Source subtree root node ID. All types under this node are included as sources. " +
                    "Pass the root node ID for global queries.")
            long fromId,
            @ToolParam(description = "Target subtree root node ID. All types under this node are included as targets.")
            long toId,
            @ToolParam(description = "Optional relationship kind filter. One of: throws, calls, returns, " +
                    "parameter_type, reads_field, writes_field, overrides, annotated_by, parameter_annotated_by, " +
                    "has_type, read_by, written_by.",
                    required = false)
            String relationship,
            @ToolParam(description = "Whether to also include edges where the source method/field is inherited from " +
                    "an ancestor of a type in the source subtree (and symmetrically for the target subtree, when the " +
                    "target is a method or field). Default false.",
                    required = false)
            Boolean includeInherited,
            @ToolParam(description = "Max edges to return (1-500, default 50).", required = false)
            Integer limit) {

        // --- Validate relationship --------------------------------------------------------
        if (relationship != null && !relationship.isBlank() && !RELATIONSHIP_KINDS.contains(relationship)) {
            return error("INVALID_RELATIONSHIP",
                    "Invalid relationship: '" + relationship + "'. Allowed values: " + RELATIONSHIP_KINDS,
                    Map.of("invalid_value", relationship));
        }

        // --- Resolve scope nodes ----------------------------------------------------------
        HGNode fromNode = resolveNodeOrRoot(fromId);
        if (fromNode == null) {
            return error("NODE_NOT_FOUND",
                    "Source node not found: " + fromId + ". Re-resolve via find_node.", Map.of());
        }
        HGNode toNode = resolveNodeOrRoot(toId);
        if (toNode == null) {
            return error("NODE_NOT_FOUND",
                    "Target node not found: " + toId + ". Re-resolve via find_node.", Map.of());
        }

        // --- Parameters & subtree expansion -----------------------------------------------
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;
        boolean inherited = includeInherited != null && includeInherited;
        String effectiveRel = (relationship != null && !relationship.isBlank()) ? relationship : null;
        INodeMetadataProvider mp = getMetadataProvider();

        List<Long> fromTypeIds = collectSubtreeTypeIds(fromNode, mp);
        List<Long> toTypeIds = collectSubtreeTypeIds(toNode, mp);

        // Empty subtree on either side → no edges, but still return a well-formed response.
        if (fromTypeIds.isEmpty() || toTypeIds.isEmpty()) {
            return emptyResult(fromNode, toNode, effectiveRel);
        }

        // --- Build & (optionally) log the Cypher ------------------------------------------
        String cypher = buildCypher(effectiveRel, inherited);
        if (logCypher) {
            log.info("detail_dependencies cypher (relationship={}, include_inherited={}, fromTypes={}, toTypes={}):\n{}",
                    effectiveRel, inherited, fromTypeIds, toTypeIds, cypher);
        }
        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("fromTypes", fromTypeIds, "toTypes", toTypeIds));

        // --- Aggregate result rows --------------------------------------------------------
        List<SortableEdge> allEdges = new ArrayList<>();
        Map<String, Integer> byRelationship = new TreeMap<>();
        Map<Long, Integer> sourceTypeCounts = new LinkedHashMap<>();
        Map<Long, String[]> nodeDisplay = new LinkedHashMap<>();  // id → [name, fqn, kind]

        for (Record record : queryResult.records()) {
            String relName = record.get("relName").asString();

            long srcId = record.get("srcId").asLong();
            long srcTypeId = record.get("srcTypeId").asLong();
            long tgtId = record.get("tgtId").asLong();
            long tgtTypeId = record.get("tgtTypeId").asLong();
            long lineNumber = record.get("lineNumber").asLong(-1);

            // Stash display fields for the nodes map (first-write-wins via putIfAbsent).
            nodeDisplay.putIfAbsent(srcId, new String[]{
                    record.get("srcName").asString(""),
                    record.get("srcFqn").asString(""),
                    deriveDetailKind(record.get("srcLabels").asList(org.neo4j.driver.Value::asString))});
            nodeDisplay.putIfAbsent(srcTypeId, new String[]{
                    record.get("srcTypeName").asString(""),
                    record.get("srcTypeFqn").asString(""),
                    mp.getKindFromLabels(record.get("srcTypeLabels").asList(org.neo4j.driver.Value::asString))});
            nodeDisplay.putIfAbsent(tgtId, new String[]{
                    record.get("tgtName").asString(""),
                    record.get("tgtFqn").asString(""),
                    deriveDetailKind(record.get("tgtLabels").asList(org.neo4j.driver.Value::asString))});
            if (tgtTypeId != tgtId) {
                nodeDisplay.putIfAbsent(tgtTypeId, new String[]{
                        record.get("tgtTypeName").asString(""),
                        record.get("tgtTypeFqn").asString(""),
                        mp.getKindFromLabels(record.get("tgtTypeLabels").asList(org.neo4j.driver.Value::asString))});
            }

            // Build the edge (slim: IDs only; no embedded NodeRefs).
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", srcId);
            edge.put("from_parent", srcTypeId);
            edge.put("to", tgtId);
            if (tgtTypeId != tgtId) {
                edge.put("to_parent", tgtTypeId);
            }
            edge.put("relationship", relName);
            edge.put("location", lineNumber > 0 ? Map.of("line_number", lineNumber) : null);

            allEdges.add(new SortableEdge(edge, relName,
                    record.get("srcTypeFqn").asString(""),
                    record.get("srcName").asString(""),
                    lineNumber > 0 ? lineNumber : 0L));

            byRelationship.merge(relName, 1, Integer::sum);
            sourceTypeCounts.merge(srcTypeId, 1, Integer::sum);
        }

        // --- Sort, truncate ---------------------------------------------------------------
        // Order: relationship (alphabetical) → source type FQN → source name → line number.
        // Stable under truncation: the cut returns a predictable prefix.
        allEdges.sort(Comparator
                .comparing(SortableEdge::relationship)
                .thenComparing(SortableEdge::srcTypeFqn)
                .thenComparing(SortableEdge::srcName)
                .thenComparingLong(SortableEdge::lineNumber));

        int totalEdges = allEdges.size();
        boolean truncated = totalEdges > effectiveLimit;
        List<Map<String, Object>> returnedEdges = allEdges.stream()
                .limit(effectiveLimit)
                .map(SortableEdge::edge)
                .toList();

        // --- by_source_type (top 10, with others_count when capped) -----------------------
        int totalSourceTypes = sourceTypeCounts.size();
        List<Map<String, Object>> bySourceType = sourceTypeCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", e.getKey());
                    entry.put("edge_count", e.getValue());
                    return entry;
                })
                .toList();

        // --- Assemble slim nodes map: only what the response actually references ---------
        Map<String, Object> nodes = buildNodesMap(fromNode, toNode, bySourceType, returnedEdges, nodeDisplay);

        // --- Summary ----------------------------------------------------------------------
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_edges", totalEdges);
        summary.put("returned", returnedEdges.size());
        summary.put("truncated", truncated);
        // When the caller filtered to a relationship that produced no edges, the by_relationship
        // map should still surface that kind (with count 0) so the absence is explicit.
        if (effectiveRel != null && !byRelationship.containsKey(effectiveRel)) {
            byRelationship.put(effectiveRel, 0);
        }
        summary.put("by_relationship", byRelationship);
        summary.put("by_source_type", bySourceType);
        if (totalSourceTypes > bySourceType.size()) {
            summary.put("others_count", totalSourceTypes - bySourceType.size());
        }

        // --- Result -----------------------------------------------------------------------
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("from_scope", fromNode.getIdentifier());
        result.put("to_scope", toNode.getIdentifier());
        result.put("edges", returnedEdges);
        result.put("summary", summary);
        return result;
    }

    // =====================================================================================
    // Relationship → Cypher mapping
    // =====================================================================================

    /**
     * Shape of a relationship branch. Determines how the target side of the MATCH pattern is
     * written and how the target's declaring-type fields are projected.
     */
    private enum BranchShape {
        /** {@code (src)<middle>(tgt:Type)} — target IS a Type; no DECLARES join. */
        TO_TYPE,
        /** {@code (src)<middle>(tgt:Entity)<-[:DECLARES]-(tt:Type)} — target is a Method/Field. */
        TO_ENTITY,
        /** {@code (src)<middle>(tgt:Method)<-[:DECLARES]-(tt:Type)} — reverse direction (read_by/written_by). */
        REVERSE_FROM_ENTITY
    }

    /**
     * Declarative description of one Cartograph relationship and how it maps to a Cypher
     * branch. This table is the single source of truth for the Cartograph ↔ jQAssistant
     * vocabulary mapping — adding or remapping a relationship kind only requires editing
     * one row here.
     *
     * @param cartographKind  the relationship string in the tool's response and parameter
     * @param srcLabel        jQAssistant label on the source entity (Method or Field)
     * @param shape           how the target side is matched
     * @param tgtLabel        jQAssistant label on the target entity
     * @param middle          Cypher fragment between source declarer and target — includes the
     *                        edge pattern and any intermediate nodes (Parameter, Annotation, ...).
     *                        For REVERSE_FROM_ENTITY the arrows in {@code middle} point left.
     * @param lineNumberExpr  Cypher expression yielding the source line number for this edge,
     *                        e.g. {@code r.lineNumber} (per-call) or {@code src.firstLineNumber}
     *                        (declaration-line fallback), or {@code null}.
     */
    private record BranchSpec(
            String cartographKind,
            String srcLabel,
            BranchShape shape,
            String tgtLabel,
            String middle,
            String lineNumberExpr) {}

    /**
     * The full Cartograph relationship vocabulary mapped to jQAssistant edge patterns.
     * Order here is irrelevant — branches are emitted as a Cypher {@code UNION ALL} and
     * results are sorted in Java afterwards.
     */
    private static final List<BranchSpec> BRANCHES = List.of(
            // Method → Type (direct edge)
            new BranchSpec("throws", "Method", BranchShape.TO_TYPE, "Type",
                    "-[r:THROWS]->", "src.firstLineNumber"),
            new BranchSpec("returns", "Method", BranchShape.TO_TYPE, "Type",
                    "-[r:RETURNS]->", "src.firstLineNumber"),
            // Field → Type
            new BranchSpec("has_type", "Field", BranchShape.TO_TYPE, "Type",
                    "-[r:OF_TYPE]->", "null"),
            // Method → Method / Field (with declarer join on target)
            new BranchSpec("calls", "Method", BranchShape.TO_ENTITY, "Method",
                    "-[r:INVOKES|VIRTUAL_INVOKES]->", "r.lineNumber"),
            new BranchSpec("overrides", "Method", BranchShape.TO_ENTITY, "Method",
                    "-[r:OVERRIDES]->", "src.firstLineNumber"),
            new BranchSpec("reads_field", "Method", BranchShape.TO_ENTITY, "Field",
                    "-[r:READS]->", "r.lineNumber"),
            new BranchSpec("writes_field", "Method", BranchShape.TO_ENTITY, "Field",
                    "-[r:WRITES]->", "r.lineNumber"),
            // Field ← Method (reverse direction)
            new BranchSpec("read_by", "Field", BranchShape.REVERSE_FROM_ENTITY, "Method",
                    "<-[r:READS]-", "r.lineNumber"),
            new BranchSpec("written_by", "Field", BranchShape.REVERSE_FROM_ENTITY, "Method",
                    "<-[r:WRITES]-", "r.lineNumber"),
            // Indirect target via intermediate node(s) — all collapse onto TO_TYPE because the
            // final pattern still ends at a Type and no declarer join is needed.
            new BranchSpec("annotated_by", "Method", BranchShape.TO_TYPE, "Type",
                    "-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->", "src.firstLineNumber"),
            new BranchSpec("annotated_by", "Field", BranchShape.TO_TYPE, "Type",
                    "-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->", "null"),
            new BranchSpec("parameter_type", "Method", BranchShape.TO_TYPE, "Type",
                    "-[:HAS]->(p:Parameter)-[:OF_TYPE]->", "src.firstLineNumber"),
            new BranchSpec("parameter_annotated_by", "Method", BranchShape.TO_TYPE, "Type",
                    "-[:HAS]->(p:Parameter)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->", "src.firstLineNumber")
    );

    /** Distinct Cartograph relationship kinds — derived from {@link #BRANCHES} for validation. */
    private static final Set<String> RELATIONSHIP_KINDS = BRANCHES.stream()
            .map(BranchSpec::cartographKind)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Assembles the full Cypher statement as a {@code UNION ALL} of one branch per matching
     * {@link BranchSpec}. Each branch carries the source/target declarer joins (with optional
     * inheritance traversal) and projects a uniform tuple of columns.
     */
    private String buildCypher(String relationship, boolean inherited) {
        List<String> branches = BRANCHES.stream()
                .filter(b -> relationship == null || b.cartographKind().equals(relationship))
                .map(b -> renderBranch(b, inherited))
                .toList();

        if (branches.isEmpty()) {
            // Should not happen — RELATIONSHIP_KINDS is derived from BRANCHES — but be safe.
            return EMPTY_CYPHER;
        }
        return String.join(" UNION ALL ", branches);
    }

    /** Renders one branch from a {@link BranchSpec}. */
    private String renderBranch(BranchSpec b, boolean inherited) {
        String srcDeclarer = inherited
                ? "(st:Type)-[:EXTENDS|IMPLEMENTS*0..]->(so:Type)-[:DECLARES]->(src:" + b.srcLabel() + ")"
                : "(st:Type)-[:DECLARES]->(src:" + b.srcLabel() + ")";

        String srcProj = inherited
                ? "id(so) AS srcTypeId, so.name AS srcTypeName, so.fqn AS srcTypeFqn, labels(so) AS srcTypeLabels"
                : "id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels";

        // Target side: differs between TO_TYPE (direct Type target) and the two entity-shaped
        // branches (which add the declarer join on the target so inheritance can be applied).
        String tgtMatch;
        String tgtProj;
        String whereTgt;
        if (b.shape() == BranchShape.TO_TYPE) {
            tgtMatch = "(tgt:" + b.tgtLabel() + ")";
            tgtProj = "id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels";
            whereTgt = "id(tgt) IN $toTypes";
        } else {
            tgtMatch = inherited
                    ? "(tgt:" + b.tgtLabel() + ")<-[:DECLARES]-(to:Type)<-[:EXTENDS|IMPLEMENTS*0..]-(tt:Type)"
                    : "(tgt:" + b.tgtLabel() + ")<-[:DECLARES]-(tt:Type)";
            tgtProj = inherited
                    ? "id(to) AS tgtTypeId, to.name AS tgtTypeName, to.fqn AS tgtTypeFqn, labels(to) AS tgtTypeLabels"
                    : "id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels";
            whereTgt = "id(tt) IN $toTypes";
        }

        return "MATCH " + srcDeclarer + b.middle() + tgtMatch + " " +
                "WHERE id(st) IN $fromTypes AND " + whereTgt + " " +
                "RETURN DISTINCT " +
                "id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels, " +
                srcProj + ", " +
                "id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels, " +
                tgtProj + ", " +
                "'" + b.cartographKind() + "' AS relName, " + b.lineNumberExpr() + " AS lineNumber";
    }

    /** Schema-matching no-op Cypher — used only when {@link #BRANCHES} would yield no branch. */
    private static final String EMPTY_CYPHER =
            "RETURN null AS srcId, null AS srcName, null AS srcFqn, null AS srcLabels, " +
            "null AS srcTypeId, null AS srcTypeName, null AS srcTypeFqn, null AS srcTypeLabels, " +
            "null AS tgtId, null AS tgtName, null AS tgtFqn, null AS tgtLabels, " +
            "null AS tgtTypeId, null AS tgtTypeName, null AS tgtTypeFqn, null AS tgtTypeLabels, " +
            "null AS relName, null AS lineNumber LIMIT 0";

    // =====================================================================================
    // Subtree resolution
    // =====================================================================================

    private HGNode resolveNodeOrRoot(long nodeId) {
        HGNode node = graphService.getRootNode().lookupNode(nodeId);
        if (node != null) return node;
        Object rootId = graphService.getRootNode().getIdentifier();
        if (rootId instanceof Long rootLong && rootLong == nodeId) {
            return graphService.getRootNode();
        }
        return null;
    }

    private List<Long> collectSubtreeTypeIds(HGNode node, INodeMetadataProvider mp) {
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        List<Long> result = new ArrayList<>();
        collectSubtreeTypeIdsRecursive(node, typeKinds, mp, result);
        return result;
    }

    private void collectSubtreeTypeIdsRecursive(HGNode node, Set<String> typeKinds,
                                                INodeMetadataProvider mp, List<Long> result) {
        if (typeKinds.contains(mp.getKind(node))) {
            result.add((Long) node.getIdentifier());
        }
        for (HGNode child : node.getChildren()) {
            collectSubtreeTypeIdsRecursive(child, typeKinds, mp, result);
        }
    }

    // =====================================================================================
    // Response assembly helpers
    // =====================================================================================

    /**
     * Builds the slim {@code nodes} map containing exactly the nodes referenced by the
     * truncated edge set, {@code by_source_type} entries, and the two scope endpoints. The
     * scope endpoints are emitted first so the LLM can locate them at the top; the rest
     * follow in iteration order over the collected reference set.
     */
    private Map<String, Object> buildNodesMap(HGNode fromNode, HGNode toNode,
                                              List<Map<String, Object>> bySourceType,
                                              List<Map<String, Object>> returnedEdges,
                                              Map<Long, String[]> nodeDisplay) {
        Set<Long> referenced = new LinkedHashSet<>();
        referenced.add((Long) fromNode.getIdentifier());
        referenced.add((Long) toNode.getIdentifier());
        for (Map<String, Object> e : bySourceType) {
            referenced.add((Long) e.get("type"));
        }
        for (Map<String, Object> edge : returnedEdges) {
            referenced.add((Long) edge.get("from"));
            referenced.add((Long) edge.get("from_parent"));
            referenced.add((Long) edge.get("to"));
            Object toParent = edge.get("to_parent");
            if (toParent instanceof Long tp) referenced.add(tp);
        }

        Map<String, Object> nodes = new LinkedHashMap<>();
        // Scope endpoints first — their display fields come from the HG model (the root node
        // is not in Neo4j, so nodeDisplay may not have it).
        putSlimNode(nodes, fromNode);
        putSlimNode(nodes, toNode);
        for (Long id : referenced) {
            String[] disp = nodeDisplay.get(id);
            if (disp != null) {
                putSlimNode(nodes, id, disp[0], disp[1], disp[2]);
            }
        }
        return nodes;
    }

    /** Build the empty-edges result for the early-return path. */
    private Map<String, Object> emptyResult(HGNode fromNode, HGNode toNode, String effectiveRel) {
        Map<String, Object> nodes = new LinkedHashMap<>();
        putSlimNode(nodes, fromNode);
        putSlimNode(nodes, toNode);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_edges", 0);
        summary.put("returned", 0);
        summary.put("truncated", false);
        summary.put("by_relationship", effectiveRel != null ? Map.of(effectiveRel, 0) : Map.of());
        summary.put("by_source_type", List.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("from_scope", fromNode.getIdentifier());
        result.put("to_scope", toNode.getIdentifier());
        result.put("edges", List.of());
        result.put("summary", summary);
        return result;
    }

    private Map<String, Object> error(String code, String message, Map<String, Object> extra) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", code);
        err.put("message", message);
        err.putAll(extra);
        return err;
    }

    // =====================================================================================
    // Internal sort wrapper
    // =====================================================================================

    /**
     * Pairs an edge map with its sort keys so the keys don't leak into the serialized
     * response. Sort key precedence is: relationship, source-type FQN, source name, line.
     */
    private record SortableEdge(
            Map<String, Object> edge,
            String relationship,
            String srcTypeFqn,
            String srcName,
            long lineNumber) {}
}
