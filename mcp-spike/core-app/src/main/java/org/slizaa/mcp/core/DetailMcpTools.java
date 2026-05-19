package org.slizaa.mcp.core;

import org.neo4j.driver.Record;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DetailMcpTools extends AbstractGraphMcpTools {

    public DetailMcpTools(HierarchicalGraphService graphService) {
        super(graphService);
    }

    @Tool(name = "list_methods",
            description = "[Detail-level] Return the methods declared on a type, with lightweight metadata for each. " +
                    "Use this when you have identified a type and want to understand its method-level composition — " +
                    "for example, 'what does ClusterService contain?' or 'list the public methods of this class.' " +
                    "Returns each method as a NodeRef plus counts: parameter count, throws count, annotation count, " +
                    "plus modifier flags. The counts let you decide which methods are worth investigating further " +
                    "(high annotation_count suggests framework wiring; high throws_count suggests error-handling complexity). " +
                    "The summary block gives a structural overview (visibility distribution, constructor count, " +
                    "declared vs. inherited) that's often more useful than enumerating every method. " +
                    "Common parameter patterns: " +
                    "Just type_id: enumerate all declared methods. " +
                    "type_id + modifier_filter: ['public']: list the public API. " +
                    "type_id + name_pattern: 'init': find initialization-style methods. " +
                    "type_id + include_inherited: true: see the full callable surface, including methods from ancestors. " +
                    "Important: include_inherited only shows methods from ancestor types that were part of the scan. " +
                    "Methods from external libraries (e.g. java.lang.Object, framework base classes) are only visible " +
                    "if those libraries were included in the jQAssistant scan. If inherited_count is 0, it may mean " +
                    "the superclass is outside the scanned codebase, not that there are no inherited methods. " +
                    "For deep information about one specific method (parameters, return type as a NodeRef, throws, " +
                    "annotations, location), use method_details. " +
                    "For 'which methods call this one?' or dependency-driven views, use detail_dependencies.")
    public Map<String, Object> listMethods(
            @ToolParam(description = "The node ID of the type whose methods should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record).") long typeId,
            @ToolParam(description = "Optional case-insensitive substring match against the method name.",
                    required = false) String namePattern,
            @ToolParam(description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, abstract, synchronized, native, default.",
                    required = false) List<String> modifierFilter,
            @ToolParam(description = "Whether to include inherited methods from superclasses and interfaces. Default false.",
                    required = false) Boolean includeInherited,
            @ToolParam(description = "Max methods to return (1-500, default 50).", required = false) Integer limit) {

        // Validate modifier_filter values
        Set<String> allowedModifiers = Set.of("public", "protected", "private", "package-private",
                "static", "final", "abstract", "synchronized", "native", "default");
        if (modifierFilter != null) {
            for (String mod : modifierFilter) {
                if (!allowedModifiers.contains(mod)) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "INVALID_MODIFIER");
                    error.put("message", "Invalid modifier: '" + mod + "'. Allowed values: " + allowedModifiers);
                    error.put("invalid_value", mod);
                    return error;
                }
            }
        }

        // Validate type_id exists in HG model
        HGNode typeNode = graphService.getRootNode().lookupNode(typeId);
        if (typeNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + typeId + ". Re-resolve via find_node.");
            return error;
        }

        // Validate it's a type kind
        INodeMetadataProvider mp = getMetadataProvider();
        String kind = mp.getKind(typeNode);
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        if (!typeKinds.contains(kind)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("message", "Node " + typeId + " is a '" + kind + "', not a type. " +
                    "list_methods requires a Class, Interface, Enum, Annotation, or Record.");
            error.put("actual_kind", kind);
            return error;
        }

        boolean inherited = includeInherited != null && includeInherited;
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;

        // Build Cypher query
        String cypher = buildListMethodsCypher(inherited);

        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("typeId", typeId));

        // Process results and apply filters
        List<Map<String, Object>> allMethods = new ArrayList<>();
        int totalPublic = 0, totalProtected = 0, totalPrivate = 0, totalPackagePrivate = 0;
        int totalConstructors = 0, totalAbstract = 0;
        int totalDeclared = 0, totalInherited = 0;

        for (Record record : queryResult.records()) {
            long methodId = record.get("methodId").asLong();
            String methodName = record.get("methodName").asString("");
            String methodFqn = record.get("methodFqn").asString("");
            boolean isConstructor = record.get("isConstructor").asBoolean(false);
            long declaringTypeId = record.get("declaringTypeId").asLong();
            String declaringTypeName = record.get("declaringTypeName").asString("");
            String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
            List<String> declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString);
            String returnTypeName = record.get("returnTypeName").isNull() ? "void" : record.get("returnTypeName").asString("void");
            long paramCount = record.get("paramCount").asLong(0);
            long throwsCount = record.get("throwsCount").asLong(0);
            long annotationCount = record.get("annotationCount").asLong(0);

            // Extract modifiers from method properties
            List<String> modifiers = extractModifiers(record);

            // Determine visibility
            String visibility = getVisibility(modifiers);

            // Apply name_pattern filter
            if (namePattern != null && !namePattern.isBlank()) {
                if (!methodName.toLowerCase().contains(namePattern.toLowerCase())) {
                    continue;
                }
            }

            // Apply modifier_filter
            if (modifierFilter != null && !modifierFilter.isEmpty()) {
                boolean allMatch = true;
                for (String requiredMod : modifierFilter) {
                    if (requiredMod.equals("package-private")) {
                        if (!visibility.equals("package-private")) {
                            allMatch = false;
                            break;
                        }
                    } else if (!modifiers.contains(requiredMod)) {
                        allMatch = false;
                        break;
                    }
                }
                if (!allMatch) continue;
            }

            // Count for summary
            boolean isInherited = declaringTypeId != typeId;
            if (isInherited) totalInherited++; else totalDeclared++;
            switch (visibility) {
                case "public" -> totalPublic++;
                case "protected" -> totalProtected++;
                case "private" -> totalPrivate++;
                case "package-private" -> totalPackagePrivate++;
            }
            if (isConstructor) totalConstructors++;
            if (modifiers.contains("abstract")) totalAbstract++;

            // Build method entry
            Map<String, Object> methodEntry = new LinkedHashMap<>();

            // NodeRef for method
            Map<String, Object> nodeRef = new LinkedHashMap<>();
            nodeRef.put("id", methodId);
            nodeRef.put("name", methodName);
            nodeRef.put("qualified_name", methodFqn);
            nodeRef.put("kind", isConstructor ? "java.constructor" : "java.method");
            nodeRef.put("parent_id", declaringTypeId);
            nodeRef.put("parent_kind", mp.getKindFromLabels(declaringTypeLabels));
            methodEntry.put("node", nodeRef);

            methodEntry.put("modifiers", modifiers);
            methodEntry.put("return_type_name", returnTypeName);
            methodEntry.put("parameter_count", paramCount);
            methodEntry.put("throws_count", throwsCount);
            methodEntry.put("annotation_count", annotationCount);
            methodEntry.put("is_constructor", isConstructor);
            methodEntry.put("is_inherited", isInherited);

            if (isInherited) {
                Map<String, Object> declaredBy = new LinkedHashMap<>();
                declaredBy.put("id", declaringTypeId);
                declaredBy.put("name", declaringTypeName);
                declaredBy.put("qualified_name", declaringTypeFqn);
                declaredBy.put("kind", mp.getKindFromLabels(declaringTypeLabels));
                methodEntry.put("declared_by", declaredBy);
            } else {
                methodEntry.put("declared_by", null);
            }

            allMethods.add(methodEntry);
        }

        int totalMatching = allMethods.size();
        boolean truncated = totalMatching > effectiveLimit;
        List<Map<String, Object>> returnedMethods = allMethods.stream()
                .limit(effectiveLimit)
                .toList();

        // Build type ref
        Map<String, Object> typeRef = new LinkedHashMap<>();
        typeRef.put("id", typeId);
        typeRef.put("name", mp.getName(typeNode));
        typeRef.put("qualified_name", mp.getQualifiedName(typeNode));
        typeRef.put("kind", kind);

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_matching", totalMatching);
        summary.put("returned", returnedMethods.size());
        summary.put("truncated", truncated);
        summary.put("declared_count", totalDeclared);
        summary.put("inherited_count", totalInherited);
        Map<String, Object> byVisibility = new LinkedHashMap<>();
        byVisibility.put("public", totalPublic);
        byVisibility.put("protected", totalProtected);
        byVisibility.put("private", totalPrivate);
        byVisibility.put("package-private", totalPackagePrivate);
        summary.put("by_visibility", byVisibility);
        summary.put("constructors", totalConstructors);
        summary.put("abstract_methods", totalAbstract);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", typeRef);
        result.put("methods", returnedMethods);
        result.put("summary", summary);

        return result;
    }

    @Tool(name = "detail_dependencies",
            description = "[Detail-level] Return the method-level and field-level dependencies between a source subtree " +
                    "and a target subtree. This is the drill-down tool that bridges the hierarchical level and the " +
                    "detail level — given an aggregated dependency you've identified (typically via aggregated_outgoing, " +
                    "aggregated_incoming, or outgoing_core_dependencies), this returns the underlying concrete " +
                    "method/field edges that explain it. " +
                    "Returns each edge with full NodeRefs for source and target (including parent_id), the " +
                    "relationship kind, and source location when available. The summary block groups edges by " +
                    "relationship kind (by_relationship) and by source type (by_source_type) — these are often " +
                    "more useful than enumerating individual edges. " +
                    "Common parameter patterns: " +
                    "from_id + to_id (no relationship): see the full detail-level coupling between two subtrees. " +
                    "from_id + to_id + relationship 'throws': drill into one specific kind of coupling. " +
                    "from_id = root_id + to_id = some_annotation_type: global query — find every method with this annotation. " +
                    "from_id = root_id + to_id = some_type + relationship 'has_type': find every field of this type. " +
                    "from_id = to_id: internal coupling within a subtree at the method/field level. " +
                    "Important: the graph only contains detail-level edges for code that was part of the scan. " +
                    "Dependencies to external library types may not have method-level detail. " +
                    "Relationship kinds: throws, calls, returns, parameter_type, reads_field, writes_field, " +
                    "overrides, annotated_by, parameter_annotated_by, has_type, read_by, written_by.")
    public Map<String, Object> detailDependencies(
            @ToolParam(description = "Source subtree root node ID. All types under this node are included as sources. " +
                    "Pass the root node ID for global queries.") long fromId,
            @ToolParam(description = "Target subtree root node ID. All types under this node are included as targets.") long toId,
            @ToolParam(description = "Optional relationship kind filter. One of: throws, calls, returns, parameter_type, " +
                    "reads_field, writes_field, overrides, annotated_by, parameter_annotated_by, has_type, read_by, written_by.",
                    required = false) String relationship,
            @ToolParam(description = "Max edges to return (1-500, default 50).", required = false) Integer limit) {

        // Allowed relationship kinds
        Set<String> allowedRelationships = Set.of("throws", "calls", "returns",
                "parameter_type", "reads_field", "writes_field", "overrides",
                "annotated_by", "parameter_annotated_by", "has_type", "read_by", "written_by");

        if (relationship != null && !relationship.isBlank() && !allowedRelationships.contains(relationship)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "INVALID_RELATIONSHIP");
            error.put("message", "Invalid relationship: '" + relationship + "'. Allowed values: " + allowedRelationships);
            error.put("invalid_value", relationship);
            return error;
        }

        // Resolve nodes (handle root node specially)
        HGNode fromNode = resolveNodeOrRoot(fromId);
        if (fromNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Source node not found: " + fromId + ". Re-resolve via find_node.");
            return error;
        }

        HGNode toNode = resolveNodeOrRoot(toId);
        if (toNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Target node not found: " + toId + ". Re-resolve via find_node.");
            return error;
        }

        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;
        INodeMetadataProvider mp = getMetadataProvider();

        // Resolve subtrees to type IDs
        List<Long> fromTypeIds = collectSubtreeTypeIds(fromNode, mp);
        List<Long> toTypeIds = collectSubtreeTypeIds(toNode, mp);

        if (fromTypeIds.isEmpty() || toTypeIds.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("from_scope", toNodeRefShort(fromNode));
            result.put("to_scope", toNodeRefShort(toNode));
            result.put("edges", List.of());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total_edges", 0);
            summary.put("returned", 0);
            summary.put("truncated", false);
            summary.put("by_relationship", relationship != null ? Map.of(relationship, 0) : Map.of());
            summary.put("by_source_type", List.of());
            result.put("summary", summary);
            return result;
        }

        // Determine effective relationship filter
        String effectiveRel = (relationship != null && !relationship.isBlank()) ? relationship : null;

        // Build and execute Cypher query
        String cypher = buildDetailDependenciesCypher(effectiveRel);
        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("fromTypes", fromTypeIds, "toTypes", toTypeIds));

        // Process results
        List<Map<String, Object>> allEdges = new ArrayList<>();
        Map<String, Integer> byRelationship = new TreeMap<>();
        Map<Long, Integer> sourceTypeCounts = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> sourceTypeRefs = new LinkedHashMap<>();

        for (Record record : queryResult.records()) {
            String relName = record.get("relName").asString();

            // Build source NodeRef
            long srcId = record.get("srcId").asLong();
            String srcName = record.get("srcName").asString("");
            String srcFqn = record.get("srcFqn").asString("");
            List<String> srcLabels = record.get("srcLabels").asList(org.neo4j.driver.Value::asString);
            long srcTypeId = record.get("srcTypeId").asLong();
            String srcTypeName = record.get("srcTypeName").asString("");
            String srcTypeFqn = record.get("srcTypeFqn").asString("");
            List<String> srcTypeLabels = record.get("srcTypeLabels").asList(org.neo4j.driver.Value::asString);

            // Build target NodeRef
            long tgtId = record.get("tgtId").asLong();
            String tgtName = record.get("tgtName").asString("");
            String tgtFqn = record.get("tgtFqn").asString("");
            List<String> tgtLabels = record.get("tgtLabels").asList(org.neo4j.driver.Value::asString);
            long tgtTypeId = record.get("tgtTypeId").asLong();
            String tgtTypeName = record.get("tgtTypeName").asString("");
            String tgtTypeFqn = record.get("tgtTypeFqn").asString("");
            List<String> tgtTypeLabels = record.get("tgtTypeLabels").asList(org.neo4j.driver.Value::asString);

            // Build edge
            Map<String, Object> edge = new LinkedHashMap<>();

            Map<String, Object> fromRef = new LinkedHashMap<>();
            fromRef.put("id", srcId);
            fromRef.put("name", srcName);
            fromRef.put("qualified_name", srcFqn);
            fromRef.put("kind", deriveDetailKind(srcLabels));
            fromRef.put("parent_id", srcTypeId);
            fromRef.put("parent_kind", mp.getKindFromLabels(srcTypeLabels));
            edge.put("from", fromRef);

            Map<String, Object> toRef = new LinkedHashMap<>();
            toRef.put("id", tgtId);
            toRef.put("name", tgtName);
            toRef.put("qualified_name", tgtFqn);
            toRef.put("kind", deriveDetailKind(tgtLabels));
            if (tgtTypeId != tgtId) {
                toRef.put("parent_id", tgtTypeId);
                toRef.put("parent_kind", mp.getKindFromLabels(tgtTypeLabels));
            }
            edge.put("to", toRef);

            edge.put("relationship", relName);

            // Location (line number if available)
            long lineNumber = record.get("lineNumber").asLong(-1);
            if (lineNumber > 0) {
                Map<String, Object> location = new LinkedHashMap<>();
                location.put("line_number", lineNumber);
                edge.put("location", location);
            } else {
                edge.put("location", null);
            }

            allEdges.add(edge);

            // Summary counts
            byRelationship.merge(relName, 1, Integer::sum);
            sourceTypeCounts.merge(srcTypeId, 1, Integer::sum);
            if (!sourceTypeRefs.containsKey(srcTypeId)) {
                Map<String, Object> typeRef = new LinkedHashMap<>();
                typeRef.put("id", srcTypeId);
                typeRef.put("name", srcTypeName);
                typeRef.put("qualified_name", srcTypeFqn);
                typeRef.put("kind", mp.getKindFromLabels(srcTypeLabels));
                sourceTypeRefs.put(srcTypeId, typeRef);
            }
        }

        // Sort edges: by relationship, then source type FQN, then source name, then line number
        allEdges.sort((a, b) -> {
            int cmp = ((String) a.get("relationship")).compareTo((String) b.get("relationship"));
            if (cmp != 0) return cmp;
            @SuppressWarnings("unchecked")
            Map<String, Object> aFrom = (Map<String, Object>) a.get("from");
            @SuppressWarnings("unchecked")
            Map<String, Object> bFrom = (Map<String, Object>) b.get("from");
            cmp = String.valueOf(aFrom.get("qualified_name")).compareTo(String.valueOf(bFrom.get("qualified_name")));
            if (cmp != 0) return cmp;
            cmp = String.valueOf(aFrom.get("name")).compareTo(String.valueOf(bFrom.get("name")));
            if (cmp != 0) return cmp;
            @SuppressWarnings("unchecked")
            Map<String, Object> aLoc = (Map<String, Object>) a.get("location");
            @SuppressWarnings("unchecked")
            Map<String, Object> bLoc = (Map<String, Object>) b.get("location");
            long aLine = aLoc != null ? (long) aLoc.get("line_number") : 0;
            long bLine = bLoc != null ? (long) bLoc.get("line_number") : 0;
            return Long.compare(aLine, bLine);
        });

        // Truncate
        int totalEdges = allEdges.size();
        boolean truncated = totalEdges > effectiveLimit;
        List<Map<String, Object>> returnedEdges = allEdges.stream()
                .limit(effectiveLimit)
                .toList();

        // Build by_source_type (top 10, sorted by count descending)
        List<Map<String, Object>> bySourceType = sourceTypeCounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", sourceTypeRefs.get(e.getKey()));
                    entry.put("edge_count", e.getValue());
                    return entry;
                })
                .toList();

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_edges", totalEdges);
        summary.put("returned", returnedEdges.size());
        summary.put("truncated", truncated);
        if (effectiveRel != null && !byRelationship.containsKey(effectiveRel)) {
            byRelationship.put(effectiveRel, 0);
        }
        summary.put("by_relationship", byRelationship);
        summary.put("by_source_type", bySourceType);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from_scope", toNodeRefShort(fromNode));
        result.put("to_scope", toNodeRefShort(toNode));
        result.put("edges", returnedEdges);
        result.put("summary", summary);

        return result;
    }

    @Tool(name = "method_details",
            description = "[Detail-level] Return the full structural details of a single method, in one call. " +
                    "Use this when you've identified a method of interest (via list_methods, detail_dependencies, " +
                    "or another tool that surfaces method IDs) and need the complete picture: modifiers, return type, " +
                    "parameters with names and types, declared exceptions, annotations, the method it overrides " +
                    "(if any), and source location. " +
                    "The response includes the declaring type as a NodeRef, so you can navigate back to the " +
                    "hierarchical model. Parameter types and return type are also NodeRefs — you can feed these " +
                    "into other tools to investigate, e.g., find_node on the qualified name or aggregated_incoming " +
                    "to see what depends on a particular parameter type. " +
                    "Primitive types (void, int, boolean, etc.) appear with id: null and kind: 'java.primitive' — " +
                    "these are not first-class entities in the graph and cannot be used as input to other tools. " +
                    "For generic types like List<String>, the erased type (java.util.List) is reported; type " +
                    "parameters are not surfaced in v0.2. " +
                    "Use the location field together with your file-reading tools when you need to inspect the " +
                    "actual method implementation (the line number points to the method declaration; the body " +
                    "follows from there). " +
                    "When to use this vs. neighboring tools: " +
                    "For the methods declared on a type (composition, not single-method detail), use list_methods. " +
                    "For 'which methods call this one?' or 'which methods throw this exception?', use " +
                    "detail_dependencies — that's the dependency-driven view rather than the entity-detail view. " +
                    "For fields rather than methods, use field_details (parallel tool, parallel shape).")
    public Map<String, Object> methodDetails(
            @ToolParam(description = "The node ID of the method to inspect. Must be a method-kind node " +
                    "(java.method or java.constructor). Typically obtained from list_methods or detail_dependencies.")
            long methodId) {

        INodeMetadataProvider mp = getMetadataProvider();

        // Run the main details query — relaxed match so we can distinguish NODE_NOT_FOUND from WRONG_NODE_KIND
        String cypher = buildMethodDetailsCypher();
        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("methodId", methodId));

        var records = queryResult.records();
        if (records.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("code", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + methodId + ". Re-resolve via find_node or list_methods.");
            return error;
        }

        Record record = records.get(0);
        List<String> methodLabels = record.get("methodLabels").asList(org.neo4j.driver.Value::asString);
        if (!methodLabels.contains("Method")) {
            String actualKind = mp.getKindFromLabels(methodLabels);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("code", "WRONG_NODE_KIND");
            error.put("message", "Node " + methodId + " is a '" + actualKind + "', not a method. " +
                    "method_details requires a method-kind node.");
            error.put("actual_kind", actualKind);
            return error;
        }

        boolean isConstructor = methodLabels.contains("Constructor");
        String methodName = record.get("methodName").asString("");
        String methodFqn = record.get("methodFqn").asString("");
        long lineNumber = record.get("lineNumber").asLong(-1);

        // Declaring type
        long declaringTypeId = record.get("declaringTypeId").asLong(-1);
        String declaringTypeName = record.get("declaringTypeName").asString("");
        String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
        List<String> declaringTypeLabels = record.get("declaringTypeLabels").isNull()
                ? List.of()
                : record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString);
        String declaringTypeKind = mp.getKindFromLabels(declaringTypeLabels);

        // Modifiers
        List<String> modifiers = extractModifiers(record);

        // Method NodeRef
        Map<String, Object> methodRef = new LinkedHashMap<>();
        methodRef.put("id", methodId);
        methodRef.put("name", methodName);
        methodRef.put("qualified_name", methodFqn);
        methodRef.put("kind", isConstructor ? "java.constructor" : "java.method");
        methodRef.put("parent_id", declaringTypeId);
        methodRef.put("parent_kind", declaringTypeKind);

        // Declaring type NodeRef
        Map<String, Object> declaringTypeRef = new LinkedHashMap<>();
        declaringTypeRef.put("id", declaringTypeId);
        declaringTypeRef.put("name", declaringTypeName);
        declaringTypeRef.put("qualified_name", declaringTypeFqn);
        declaringTypeRef.put("kind", declaringTypeKind);

        // Return type — for constructors, fall back to the declaring type if not explicitly captured
        Map<String, Object> returnTypeRef;
        if (record.get("returnTypeId").isNull()) {
            if (isConstructor) {
                returnTypeRef = declaringTypeRef;
            } else {
                // No return type captured (rare); treat as void primitive
                returnTypeRef = primitiveRef("void");
            }
        } else {
            long rtId = record.get("returnTypeId").asLong();
            String rtName = record.get("returnTypeName").asString("");
            String rtFqn = record.get("returnTypeFqn").asString("");
            List<String> rtLabels = record.get("returnTypeLabels").asList(org.neo4j.driver.Value::asString);
            returnTypeRef = toTypeRef(rtId, rtName, rtFqn, rtLabels, mp);
        }

        // Parameters
        List<Map<String, Object>> parameters = buildParameters(record, mp);

        // Throws
        List<Map<String, Object>> throwsList = buildTypeRefList(record.get("throwsList"), mp);

        // Annotations on method
        List<Map<String, Object>> methodAnnotations = buildAnnotationList(record.get("methodAnnotations"), mp);

        // Overrides
        Map<String, Object> overridesRef = null;
        if (!record.get("overrideId").isNull()) {
            long ovId = record.get("overrideId").asLong();
            String ovName = record.get("overrideName").asString("");
            String ovFqn = record.get("overrideFqn").asString("");
            List<String> ovLabels = record.get("overrideLabels").asList(org.neo4j.driver.Value::asString);
            boolean ovIsCtor = ovLabels.contains("Constructor");
            long ovDtId = record.get("overrideDeclTypeId").asLong(-1);
            List<String> ovDtLabels = record.get("overrideDeclTypeLabels").isNull()
                    ? List.of()
                    : record.get("overrideDeclTypeLabels").asList(org.neo4j.driver.Value::asString);

            overridesRef = new LinkedHashMap<>();
            overridesRef.put("id", ovId);
            overridesRef.put("name", ovName);
            overridesRef.put("qualified_name", ovFqn);
            overridesRef.put("kind", ovIsCtor ? "java.constructor" : "java.method");
            overridesRef.put("parent_id", ovDtId);
            overridesRef.put("parent_kind", mp.getKindFromLabels(ovDtLabels));
        }

        // Location
        Map<String, Object> location = null;
        if (lineNumber > 0) {
            location = new LinkedHashMap<>();
            location.put("line_number", lineNumber);
        }

        // Assemble result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", methodRef);
        result.put("declaring_type", declaringTypeRef);
        result.put("modifiers", modifiers);
        result.put("is_constructor", isConstructor);
        result.put("return_type", returnTypeRef);
        result.put("parameters", parameters);
        result.put("throws", throwsList);
        result.put("annotations", methodAnnotations);
        result.put("overrides", overridesRef);
        result.put("location", location);

        return result;
    }

    @Tool(name = "list_fields",
            description = "[Detail-level] Return the fields declared on a type, with lightweight metadata for each. " +
                    "Use this when you have identified a type and want to understand its data members — " +
                    "for example, 'what fields does UserEntity have?' or 'list the autowired dependencies of this Spring component.' " +
                    "Returns each field as a NodeRef plus metadata: modifiers, field type name, annotation count, and flags " +
                    "like is_constant. The annotation_count is particularly valuable for framework-wiring questions — " +
                    "fields with annotations are often where Spring injection, JPA mappings, or validation rules live. " +
                    "The summary block surfaces aggregate signals like annotated_count, constant_count, and visibility " +
                    "distribution, which often tell the framework story before you even look at individual fields. " +
                    "Common parameter patterns: " +
                    "Just type_id: enumerate all declared fields. " +
                    "type_id + modifier_filter: ['private', 'final']: list constructor-injected dependencies. " +
                    "type_id + modifier_filter: ['static', 'final']: list the constants this type defines. " +
                    "type_id + name_pattern: 'id': find ID-like fields. " +
                    "type_id + include_inherited: true: see all fields, including inherited ones. " +
                    "Important: include_inherited only shows fields from ancestor types that were part of the scan. " +
                    "Fields from external libraries (e.g. framework base classes) are only visible if those libraries " +
                    "were included in the jQAssistant scan. If inherited_count is 0, it may mean the superclass is " +
                    "outside the scanned codebase, not that there are no inherited fields. " +
                    "For deep information about one specific field (full type as a NodeRef, list of annotations, " +
                    "methods that read or write it), use field_details. " +
                    "For 'which methods read this field?' or dependency-driven views, use detail_dependencies. " +
                    "For methods rather than fields, use list_methods (same shape, different entity).")
    public Map<String, Object> listFields(
            @ToolParam(description = "The node ID of the type whose fields should be enumerated. " +
                    "Must be a type-kind node (Class, Interface, Enum, Annotation, Record).") long typeId,
            @ToolParam(description = "Optional case-insensitive substring match against the field name.",
                    required = false) String namePattern,
            @ToolParam(description = "Optional list of Java modifiers, ANDed together. " +
                    "Allowed values: public, protected, private, package-private, static, final, transient, volatile.",
                    required = false) List<String> modifierFilter,
            @ToolParam(description = "Whether to include inherited fields from superclasses. Default false.",
                    required = false) Boolean includeInherited,
            @ToolParam(description = "Max fields to return (1-500, default 50).", required = false) Integer limit) {

        // Validate modifier_filter values
        Set<String> allowedModifiers = Set.of("public", "protected", "private", "package-private",
                "static", "final", "transient", "volatile");
        if (modifierFilter != null) {
            for (String mod : modifierFilter) {
                if (!allowedModifiers.contains(mod)) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("error", "INVALID_MODIFIER");
                    error.put("message", "Invalid modifier: '" + mod + "'. Allowed values for fields: " + allowedModifiers);
                    error.put("invalid_value", mod);
                    return error;
                }
            }
        }

        // Validate type_id exists in HG model
        HGNode typeNode = graphService.getRootNode().lookupNode(typeId);
        if (typeNode == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "NODE_NOT_FOUND");
            error.put("message", "Node not found: " + typeId + ". Re-resolve via find_node.");
            return error;
        }

        // Validate it's a type kind
        INodeMetadataProvider mp = getMetadataProvider();
        String kind = mp.getKind(typeNode);
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        if (!typeKinds.contains(kind)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "WRONG_NODE_KIND");
            error.put("message", "Node " + typeId + " is a '" + kind + "', not a type. " +
                    "list_fields requires a Class, Interface, Enum, Annotation, or Record.");
            error.put("actual_kind", kind);
            return error;
        }

        boolean inherited = includeInherited != null && includeInherited;
        int effectiveLimit = limit != null ? Math.min(Math.max(limit, 1), 500) : 50;

        // Build Cypher query
        String cypher = buildListFieldsCypher(inherited);

        var queryResult = graphService.getBoltClient().syncExecCypherQuery(
                cypher, Map.of("typeId", typeId));

        // Process results and apply filters
        List<Map<String, Object>> allFields = new ArrayList<>();
        int totalPublic = 0, totalProtected = 0, totalPrivate = 0, totalPackagePrivate = 0;
        int totalAnnotated = 0, totalStatic = 0, totalFinal = 0, totalConstant = 0;
        int totalDeclared = 0, totalInherited = 0;

        for (Record record : queryResult.records()) {
            long fieldId = record.get("fieldId").asLong();
            String fieldName = record.get("fieldName").asString("");
            String fieldFqn = record.get("fieldFqn").asString("");
            long declaringTypeId = record.get("declaringTypeId").asLong();
            String declaringTypeName = record.get("declaringTypeName").asString("");
            String declaringTypeFqn = record.get("declaringTypeFqn").asString("");
            List<String> declaringTypeLabels = record.get("declaringTypeLabels").asList(org.neo4j.driver.Value::asString);
            String fieldTypeName = record.get("fieldTypeName").isNull() ? "unknown" : record.get("fieldTypeName").asString("unknown");
            long annotationCount = record.get("annotationCount").asLong(0);

            // Extract modifiers
            List<String> modifiers = extractFieldModifiers(record);

            // Determine visibility
            String visibility = getVisibility(modifiers);

            // Apply name_pattern filter
            if (namePattern != null && !namePattern.isBlank()) {
                if (!fieldName.toLowerCase().contains(namePattern.toLowerCase())) {
                    continue;
                }
            }

            // Apply modifier_filter
            if (modifierFilter != null && !modifierFilter.isEmpty()) {
                boolean allMatch = true;
                for (String requiredMod : modifierFilter) {
                    if (requiredMod.equals("package-private")) {
                        if (!visibility.equals("package-private")) {
                            allMatch = false;
                            break;
                        }
                    } else if (!modifiers.contains(requiredMod)) {
                        allMatch = false;
                        break;
                    }
                }
                if (!allMatch) continue;
            }

            // Compute is_constant
            boolean isConstant = modifiers.contains("static") && modifiers.contains("final");

            // Count for summary
            boolean isInherited = declaringTypeId != typeId;
            if (isInherited) totalInherited++; else totalDeclared++;
            switch (visibility) {
                case "public" -> totalPublic++;
                case "protected" -> totalProtected++;
                case "private" -> totalPrivate++;
                case "package-private" -> totalPackagePrivate++;
            }
            if (annotationCount > 0) totalAnnotated++;
            if (modifiers.contains("static")) totalStatic++;
            if (modifiers.contains("final")) totalFinal++;
            if (isConstant) totalConstant++;

            // Build field entry
            Map<String, Object> fieldEntry = new LinkedHashMap<>();

            // NodeRef for field
            Map<String, Object> nodeRef = new LinkedHashMap<>();
            nodeRef.put("id", fieldId);
            nodeRef.put("name", fieldName);
            nodeRef.put("qualified_name", fieldFqn);
            nodeRef.put("kind", "java.field");
            nodeRef.put("parent_id", declaringTypeId);
            nodeRef.put("parent_kind", mp.getKindFromLabels(declaringTypeLabels));
            fieldEntry.put("node", nodeRef);

            fieldEntry.put("modifiers", modifiers);
            fieldEntry.put("field_type_name", fieldTypeName);
            fieldEntry.put("annotation_count", annotationCount);
            fieldEntry.put("is_constant", isConstant);
            fieldEntry.put("is_inherited", isInherited);

            if (isInherited) {
                Map<String, Object> declaredBy = new LinkedHashMap<>();
                declaredBy.put("id", declaringTypeId);
                declaredBy.put("name", declaringTypeName);
                declaredBy.put("qualified_name", declaringTypeFqn);
                declaredBy.put("kind", mp.getKindFromLabels(declaringTypeLabels));
                fieldEntry.put("declared_by", declaredBy);
            } else {
                fieldEntry.put("declared_by", null);
            }

            allFields.add(fieldEntry);
        }

        int totalMatching = allFields.size();
        boolean truncated = totalMatching > effectiveLimit;
        List<Map<String, Object>> returnedFields = allFields.stream()
                .limit(effectiveLimit)
                .toList();

        // Build type ref
        Map<String, Object> typeRef = new LinkedHashMap<>();
        typeRef.put("id", typeId);
        typeRef.put("name", mp.getName(typeNode));
        typeRef.put("qualified_name", mp.getQualifiedName(typeNode));
        typeRef.put("kind", kind);

        // Build summary
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_matching", totalMatching);
        summary.put("returned", returnedFields.size());
        summary.put("truncated", truncated);
        summary.put("declared_count", totalDeclared);
        summary.put("inherited_count", totalInherited);
        Map<String, Object> byVisibility = new LinkedHashMap<>();
        byVisibility.put("public", totalPublic);
        byVisibility.put("protected", totalProtected);
        byVisibility.put("private", totalPrivate);
        byVisibility.put("package-private", totalPackagePrivate);
        summary.put("by_visibility", byVisibility);
        summary.put("annotated_count", totalAnnotated);
        summary.put("static_count", totalStatic);
        summary.put("final_count", totalFinal);
        summary.put("constant_count", totalConstant);

        // Build result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", typeRef);
        result.put("fields", returnedFields);
        result.put("summary", summary);

        return result;
    }

    // --- private helpers ---

    private HGNode resolveNodeOrRoot(long nodeId) {
        HGNode node = graphService.getRootNode().lookupNode(nodeId);
        if (node == null) {
            // Check if it's the root node itself
            Object rootId = graphService.getRootNode().getIdentifier();
            if (rootId instanceof Long && (Long) rootId == nodeId) {
                return graphService.getRootNode();
            }
        }
        return node;
    }

    private List<Long> collectSubtreeTypeIds(HGNode node, INodeMetadataProvider mp) {
        Set<String> typeKinds = Set.of("Class", "Interface", "Enum", "Annotation", "Record");
        List<Long> typeIds = new ArrayList<>();
        collectSubtreeTypeIdsRecursive(node, typeKinds, mp, typeIds);
        return typeIds;
    }

    private void collectSubtreeTypeIdsRecursive(HGNode node, Set<String> typeKinds,
                                                 INodeMetadataProvider mp, List<Long> result) {
        String kind = mp.getKind(node);
        if (typeKinds.contains(kind)) {
            result.add((Long) node.getIdentifier());
        }
        for (HGNode child : node.getChildren()) {
            collectSubtreeTypeIdsRecursive(child, typeKinds, mp, result);
        }
    }

    private String deriveDetailKind(List<String> labels) {
        if (labels.contains("Constructor")) return "java.constructor";
        if (labels.contains("Method")) return "java.method";
        if (labels.contains("Field")) return "java.field";
        if (labels.contains("Interface")) return "java.interface";
        if (labels.contains("Enum")) return "java.enum";
        if (labels.contains("Annotation")) return "java.annotation";
        if (labels.contains("Class")) return "java.class";
        if (labels.contains("Type")) return "java.class";
        return "unknown";
    }

    private String buildDetailDependenciesCypher(String relationship) {
        List<String> branches = new ArrayList<>();

        // Determine which groups to query
        Set<String> rels = relationship != null ? Set.of(relationship) :
                Set.of("throws", "calls", "returns", "parameter_type", "reads_field",
                        "writes_field", "overrides", "annotated_by", "parameter_annotated_by",
                        "has_type", "read_by", "written_by");

        // Group A: Method -> Type (throws, returns)
        if (rels.contains("throws")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:THROWS]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'throws' AS relName, src.firstLineNumber AS lineNumber""");
        }

        if (rels.contains("returns")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:RETURNS]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'returns' AS relName, src.firstLineNumber AS lineNumber""");
        }

        // Group B: Method -> Method (calls, overrides)
        if (rels.contains("calls")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:INVOKES]->(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'calls' AS relName, r.lineNumber AS lineNumber""");
        }

        if (rels.contains("overrides")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:OVERRIDES]->(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'overrides' AS relName, src.firstLineNumber AS lineNumber""");
        }

        // Group C: Method -> Field (reads_field, writes_field)
        if (rels.contains("reads_field")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:READS]->(tgt:Field)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'reads_field' AS relName, r.lineNumber AS lineNumber""");
        }

        if (rels.contains("writes_field")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[r:WRITES]->(tgt:Field)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'writes_field' AS relName, r.lineNumber AS lineNumber""");
        }

        // Group D: Field <- Method (read_by, written_by) — reversed direction
        if (rels.contains("read_by")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)<-[r:READS]-(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'read_by' AS relName, r.lineNumber AS lineNumber""");
        }

        if (rels.contains("written_by")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)<-[r:WRITES]-(tgt:Method)<-[:DECLARES]-(tt:Type)
                WHERE id(st) IN $fromTypes AND id(tt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tt) AS tgtTypeId, tt.name AS tgtTypeName, tt.fqn AS tgtTypeFqn, labels(tt) AS tgtTypeLabels,
                       'written_by' AS relName, r.lineNumber AS lineNumber""");
        }

        // Group E: Field -> Type (has_type via OF_TYPE)
        if (rels.contains("has_type")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)-[r:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'has_type' AS relName, null AS lineNumber""");
        }

        // Group F: Method/Field -> Annotation -> Type (annotated_by)
        if (rels.contains("annotated_by")) {
            // Method annotated by (through intermediate annotation node)
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'annotated_by' AS relName, src.firstLineNumber AS lineNumber""");
            // Field annotated by
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Field)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'annotated_by' AS relName, null AS lineNumber""");
        }

        // Group G: Method -> Parameter -> Type (parameter_type)
        if (rels.contains("parameter_type")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[:HAS]->(p:Parameter)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'parameter_type' AS relName, src.firstLineNumber AS lineNumber""");
        }

        // Group H: Method -> Parameter -> Annotation -> Type (parameter_annotated_by)
        if (rels.contains("parameter_annotated_by")) {
            branches.add("""
                MATCH (st:Type)-[:DECLARES]->(src:Method)-[:HAS]->(p:Parameter)-[:ANNOTATED_BY]->(a)-[:OF_TYPE]->(tgt:Type)
                WHERE id(st) IN $fromTypes AND id(tgt) IN $toTypes
                RETURN id(src) AS srcId, src.name AS srcName, src.fqn AS srcFqn, labels(src) AS srcLabels,
                       id(st) AS srcTypeId, st.name AS srcTypeName, st.fqn AS srcTypeFqn, labels(st) AS srcTypeLabels,
                       id(tgt) AS tgtId, tgt.name AS tgtName, tgt.fqn AS tgtFqn, labels(tgt) AS tgtLabels,
                       id(tgt) AS tgtTypeId, tgt.name AS tgtTypeName, tgt.fqn AS tgtTypeFqn, labels(tgt) AS tgtTypeLabels,
                       'parameter_annotated_by' AS relName, src.firstLineNumber AS lineNumber""");
        }

        if (branches.isEmpty()) {
            // Shouldn't happen, but handle gracefully
            return "RETURN null AS srcId, null AS srcName, null AS srcFqn, null AS srcLabels, " +
                    "null AS srcTypeId, null AS srcTypeName, null AS srcTypeFqn, null AS srcTypeLabels, " +
                    "null AS tgtId, null AS tgtName, null AS tgtFqn, null AS tgtLabels, " +
                    "null AS tgtTypeId, null AS tgtTypeName, null AS tgtTypeFqn, null AS tgtTypeLabels, " +
                    "null AS relName, null AS lineNumber LIMIT 0";
        }

        return String.join(" UNION ALL ", branches);
    }

    private String buildListFieldsCypher(boolean includeInherited) {
        if (includeInherited) {
            return """
                MATCH (t:Type) WHERE id(t) = $typeId
                CALL {
                    WITH t
                    MATCH (t)-[:DECLARES]->(f:Field)
                    MATCH (dt:Type)-[:DECLARES]->(f)
                    OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                    OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                    RETURN f, dt, ft,
                           count(DISTINCT a) AS annotationCount
                    UNION
                    WITH t
                    MATCH (t)-[:EXTENDS*1..]->(ancestor:Type)-[:DECLARES]->(f:Field)
                    MATCH (dt:Type)-[:DECLARES]->(f)
                    OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                    OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                    RETURN f, dt, ft,
                           count(DISTINCT a) AS annotationCount
                }
                RETURN id(f) AS fieldId,
                       f.name AS fieldName,
                       f.fqn AS fieldFqn,
                       f.visibility AS visibility,
                       f.static AS isStatic,
                       f.final AS isFinal,
                       f.transient AS isTransient,
                       f.volatile AS isVolatile,
                       id(dt) AS declaringTypeId,
                       dt.name AS declaringTypeName,
                       dt.fqn AS declaringTypeFqn,
                       labels(dt) AS declaringTypeLabels,
                       ft.fqn AS fieldTypeName,
                       annotationCount
                """;
        } else {
            return """
                MATCH (t:Type)-[:DECLARES]->(f:Field) WHERE id(t) = $typeId
                OPTIONAL MATCH (f)-[:OF_TYPE]->(ft:Type)
                OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(a)
                RETURN id(f) AS fieldId,
                       f.name AS fieldName,
                       f.fqn AS fieldFqn,
                       f.visibility AS visibility,
                       f.static AS isStatic,
                       f.final AS isFinal,
                       f.transient AS isTransient,
                       f.volatile AS isVolatile,
                       id(t) AS declaringTypeId,
                       t.name AS declaringTypeName,
                       t.fqn AS declaringTypeFqn,
                       labels(t) AS declaringTypeLabels,
                       ft.fqn AS fieldTypeName,
                       count(DISTINCT a) AS annotationCount
                """;
        }
    }

    private List<String> extractFieldModifiers(Record record) {
        List<String> modifiers = new ArrayList<>();

        // Visibility first (canonical order)
        String visibility = record.get("visibility").asString(null);
        if (visibility != null) {
            modifiers.add(visibility.toLowerCase());
        } else {
            modifiers.add("package-private");
        }

        // Storage modifiers in canonical order
        if (record.get("isStatic").asBoolean(false)) modifiers.add("static");
        if (record.get("isFinal").asBoolean(false)) modifiers.add("final");
        if (record.get("isTransient").asBoolean(false)) modifiers.add("transient");
        if (record.get("isVolatile").asBoolean(false)) modifiers.add("volatile");

        return modifiers;
    }

    private String buildListMethodsCypher(boolean includeInherited) {
        if (includeInherited) {
            return """
                MATCH (t:Type) WHERE id(t) = $typeId
                CALL {
                    WITH t
                    MATCH (t)-[:DECLARES]->(m:Method)
                    MATCH (dt:Type)-[:DECLARES]->(m)
                    OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                    OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                    OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                    OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                    RETURN m, dt, rt,
                           count(DISTINCT p) AS paramCount,
                           count(DISTINCT ex) AS throwsCount,
                           count(DISTINCT a) AS annotationCount
                    UNION
                    WITH t
                    MATCH (t)-[:EXTENDS|IMPLEMENTS*1..]->(ancestor:Type)-[:DECLARES]->(m:Method)
                    MATCH (dt:Type)-[:DECLARES]->(m)
                    OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                    OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                    OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                    OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                    RETURN m, dt, rt,
                           count(DISTINCT p) AS paramCount,
                           count(DISTINCT ex) AS throwsCount,
                           count(DISTINCT a) AS annotationCount
                }
                RETURN id(m) AS methodId,
                       m.name AS methodName,
                       m.fqn AS methodFqn,
                       (m:Constructor) AS isConstructor,
                       m.visibility AS visibility,
                       m.static AS isStatic,
                       m.final AS isFinal,
                       m.abstract AS isAbstract,
                       m.synchronized AS isSynchronized,
                       m.native AS isNative,
                       m.default AS isDefault,
                       id(dt) AS declaringTypeId,
                       dt.name AS declaringTypeName,
                       dt.fqn AS declaringTypeFqn,
                       labels(dt) AS declaringTypeLabels,
                       rt.fqn AS returnTypeName,
                       paramCount, throwsCount, annotationCount
                """;
        } else {
            return """
                MATCH (t:Type)-[:DECLARES]->(m:Method) WHERE id(t) = $typeId
                OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(a)
                RETURN id(m) AS methodId,
                       m.name AS methodName,
                       m.fqn AS methodFqn,
                       (m:Constructor) AS isConstructor,
                       m.visibility AS visibility,
                       m.static AS isStatic,
                       m.final AS isFinal,
                       m.abstract AS isAbstract,
                       m.synchronized AS isSynchronized,
                       m.native AS isNative,
                       m.default AS isDefault,
                       id(t) AS declaringTypeId,
                       t.name AS declaringTypeName,
                       t.fqn AS declaringTypeFqn,
                       labels(t) AS declaringTypeLabels,
                       rt.fqn AS returnTypeName,
                       count(DISTINCT p) AS paramCount,
                       count(DISTINCT ex) AS throwsCount,
                       count(DISTINCT a) AS annotationCount
                """;
        }
    }

    private List<String> extractModifiers(Record record) {
        List<String> modifiers = new ArrayList<>();

        // Visibility first (canonical order)
        String visibility = record.get("visibility").asString(null);
        if (visibility != null) {
            modifiers.add(visibility.toLowerCase());
        } else {
            modifiers.add("package-private");
        }

        // Other modifiers in canonical order
        if (record.get("isStatic").asBoolean(false)) modifiers.add("static");
        if (record.get("isFinal").asBoolean(false)) modifiers.add("final");
        if (record.get("isAbstract").asBoolean(false)) modifiers.add("abstract");
        if (record.get("isSynchronized").asBoolean(false)) modifiers.add("synchronized");
        if (record.get("isNative").asBoolean(false)) modifiers.add("native");
        if (record.get("isDefault").asBoolean(false)) modifiers.add("default");

        return modifiers;
    }

    private String getVisibility(List<String> modifiers) {
        if (modifiers.contains("public")) return "public";
        if (modifiers.contains("protected")) return "protected";
        if (modifiers.contains("private")) return "private";
        return "package-private";
    }

    // --- method_details helpers ---

    private static final Set<String> JAVA_PRIMITIVES = Set.of(
            "void", "boolean", "byte", "char", "short", "int", "long", "float", "double");

    private String buildMethodDetailsCypher() {
        return """
                MATCH (m) WHERE id(m) = $methodId
                OPTIONAL MATCH (dt:Type)-[:DECLARES]->(m)
                OPTIONAL MATCH (m)-[:RETURNS]->(rt:Type)
                OPTIONAL MATCH (m)-[:OVERRIDES]->(ov:Method)
                OPTIONAL MATCH (odt:Type)-[:DECLARES]->(ov)
                CALL {
                    WITH m
                    OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)
                    WITH p WHERE p IS NOT NULL
                    OPTIONAL MATCH (p)-[:OF_TYPE]->(pt:Type)
                    CALL {
                        WITH p
                        OPTIONAL MATCH (p)-[:ANNOTATED_BY]->(pa)-[:OF_TYPE]->(pat:Type)
                        RETURN collect(DISTINCT {id: id(pat), name: pat.name, fqn: pat.fqn, labels: labels(pat)}) AS pAnns
                    }
                    RETURN collect({
                        index: p.index,
                        name: p.name,
                        paramTypeId: id(pt),
                        paramTypeName: pt.name,
                        paramTypeFqn: pt.fqn,
                        paramTypeLabels: labels(pt),
                        annotations: pAnns
                    }) AS parameters
                }
                CALL {
                    WITH m
                    OPTIONAL MATCH (m)-[:THROWS]->(ex:Type)
                    RETURN collect(DISTINCT {id: id(ex), name: ex.name, fqn: ex.fqn, labels: labels(ex)}) AS throwsList
                }
                CALL {
                    WITH m
                    OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(ma)-[:OF_TYPE]->(at:Type)
                    RETURN collect(DISTINCT {id: id(at), name: at.name, fqn: at.fqn, labels: labels(at)}) AS methodAnnotations
                }
                RETURN labels(m) AS methodLabels,
                       m.name AS methodName,
                       m.fqn AS methodFqn,
                       m.firstLineNumber AS lineNumber,
                       m.visibility AS visibility,
                       m.static AS isStatic,
                       m.final AS isFinal,
                       m.abstract AS isAbstract,
                       m.synchronized AS isSynchronized,
                       m.native AS isNative,
                       m.default AS isDefault,
                       id(dt) AS declaringTypeId,
                       dt.name AS declaringTypeName,
                       dt.fqn AS declaringTypeFqn,
                       labels(dt) AS declaringTypeLabels,
                       id(rt) AS returnTypeId,
                       rt.name AS returnTypeName,
                       rt.fqn AS returnTypeFqn,
                       labels(rt) AS returnTypeLabels,
                       id(ov) AS overrideId,
                       ov.name AS overrideName,
                       ov.fqn AS overrideFqn,
                       labels(ov) AS overrideLabels,
                       id(odt) AS overrideDeclTypeId,
                       labels(odt) AS overrideDeclTypeLabels,
                       parameters,
                       throwsList,
                       methodAnnotations
                """;
    }

    private List<Map<String, Object>> buildParameters(Record record, INodeMetadataProvider mp) {
        var paramsValue = record.get("parameters");
        if (paramsValue.isNull()) return List.of();

        List<Map<String, Object>> raw = new ArrayList<>();
        for (var v : paramsValue.values()) {
            raw.add(v.asMap());
        }

        // Sort by index ascending (defensive — Cypher subquery order may not be guaranteed)
        raw.sort(Comparator.comparingLong(m -> {
            Object idx = m.get("index");
            return idx instanceof Number ? ((Number) idx).longValue() : 0L;
        }));

        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> p = raw.get(i);
            Map<String, Object> paramEntry = new LinkedHashMap<>();
            Object idx = p.get("index");
            long position = idx instanceof Number ? ((Number) idx).longValue() : i;
            paramEntry.put("position", position);
            paramEntry.put("name", p.get("name"));

            Long ptId = p.get("paramTypeId") instanceof Number ? ((Number) p.get("paramTypeId")).longValue() : null;
            String ptName = (String) p.get("paramTypeName");
            String ptFqn = (String) p.get("paramTypeFqn");
            @SuppressWarnings("unchecked")
            List<String> ptLabels = (List<String>) p.get("paramTypeLabels");
            paramEntry.put("type", toTypeRefFromMap(ptId, ptName, ptFqn, ptLabels, mp));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> annsRaw = (List<Map<String, Object>>) p.get("annotations");
            paramEntry.put("annotations", buildAnnotationListFromMaps(annsRaw, mp));

            out.add(paramEntry);
        }
        return out;
    }

    private List<Map<String, Object>> buildTypeRefList(org.neo4j.driver.Value value, INodeMetadataProvider mp) {
        if (value.isNull()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (var v : value.values()) {
            Map<String, Object> m = v.asMap();
            Long id = m.get("id") instanceof Number ? ((Number) m.get("id")).longValue() : null;
            if (id == null) continue;
            String name = (String) m.get("name");
            String fqn = (String) m.get("fqn");
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) m.get("labels");
            out.add(toTypeRefFromMap(id, name, fqn, labels, mp));
        }
        return out;
    }

    private List<Map<String, Object>> buildAnnotationList(org.neo4j.driver.Value value, INodeMetadataProvider mp) {
        if (value.isNull()) return List.of();
        List<Map<String, Object>> raw = new ArrayList<>();
        for (var v : value.values()) {
            raw.add(v.asMap());
        }
        return buildAnnotationListFromMaps(raw, mp);
    }

    private List<Map<String, Object>> buildAnnotationListFromMaps(List<Map<String, Object>> raw, INodeMetadataProvider mp) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            Long id = m.get("id") instanceof Number ? ((Number) m.get("id")).longValue() : null;
            if (id == null) continue;
            String name = (String) m.get("name");
            String fqn = (String) m.get("fqn");
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) m.get("labels");
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", toTypeRefFromMap(id, name, fqn, labels, mp));
            out.add(wrapper);
        }
        return out;
    }

    private Map<String, Object> toTypeRef(long id, String name, String fqn, List<String> labels, INodeMetadataProvider mp) {
        return toTypeRefFromMap(id, name, fqn, labels, mp);
    }

    private Map<String, Object> toTypeRefFromMap(Long id, String name, String fqn, List<String> labels, INodeMetadataProvider mp) {
        // Primitive detection: jQAssistant represents primitives as Type nodes with fqn like "void", "int", etc.
        if (fqn != null && JAVA_PRIMITIVES.contains(fqn)) {
            return primitiveRef(fqn);
        }
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", id);
        ref.put("name", name != null ? name : "");
        ref.put("qualified_name", fqn != null ? fqn : "");
        ref.put("kind", mp.getKindFromLabels(labels != null ? labels : List.of()));
        return ref;
    }

    private Map<String, Object> primitiveRef(String name) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", null);
        ref.put("name", name);
        ref.put("qualified_name", name);
        ref.put("kind", "java.primitive");
        return ref;
    }
}
