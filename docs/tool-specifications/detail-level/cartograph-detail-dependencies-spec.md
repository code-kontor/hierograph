# Cartograph: `detail_dependencies` Specification

This is the complete specification for `detail_dependencies`, the headline detail-level tool introduced in v0.2. It serves the "what's the method/field-level evidence underneath this aggregated dependency?" question — drilling from a known type-level relationship into its concrete underlying detail-level edges.

This specification builds on conventions established in `mcp-tools.md` (NodeRef, node IDs, the type-level dependency model, limits, JSON precision) and the architectural framing in `detail-level-tools.md` (the hierarchical/detail/code layer model, the aggregated-dependency-driven workflow). The tool is the natural continuation of the type-level evidence tool `outgoing_core_dependencies` — same source/target framing, but returning method/field-level edges instead of type-level edges.

## Purpose

`detail_dependencies` returns the method-level and field-level dependencies between a source subtree and a target subtree, optionally filtered by relationship kind. It is the *drill-down* tool that bridges the hierarchical level and the detail level.

By the time the LLM wants detail-level information, it almost always already has a source and target subtree in mind — the hierarchical tools just told it. This tool takes both endpoints, exactly the same shape as `outgoing_core_dependencies`, and returns the underlying concrete edges at the method/field level.

The aggregated-dependency-driven framing scopes detail queries naturally: instead of "find all methods that throw `IOException`" (which could return thousands), the LLM asks "find methods in subtree A that throw `IOException` from subtree B" (which is bounded by the underlying aggregated edge, typically tens to a few hundred concrete edges).

## Parameters

```
detail_dependencies(
    from_id: long,           // required: source subtree
    to_id: long,             // required: target subtree
    relationship: string?,    // optional filter: e.g. "throws", "calls", "annotated_by"
    limit: int = 50
)
```

### `from_id` (required)

The node ID of the source subtree. Per the established convention, this is interpreted as "this node and all its descendants." A type-kind node is treated as a subtree of size one (the type itself). A package or module is treated as the subtree containing all its types.

Typically obtained from prior hierarchical queries — `find_node`, `aggregated_outgoing`, `outgoing_core_dependencies`, etc. The LLM rarely constructs `from_id` from scratch.

For *global* queries ("find this everywhere in the codebase"), pass the root node ID as `from_id`. The description should explicitly mention this pattern so the LLM knows how to express it.

### `to_id` (required)

The node ID of the target subtree. Same semantics as `from_id`: a subtree, with type-kind nodes being subtrees of size one.

For relationship kinds that target specific entities (e.g., `throws` targets exception types, `annotated_by` targets annotation types), `to_id` is typically the type ID of the specific exception, annotation, etc. The tool returns the methods/fields from `from_id`'s subtree that have that relationship with `to_id` or its descendants.

### `relationship` (optional)

A relationship kind to filter on. If omitted, all relationship kinds are returned and grouped in the response's `by_relationship` summary.

The v0.2 vocabulary, derived from jQAssistant's Java schema. Each entry lists the Cartograph kind, a description, and the underlying jQAssistant edge label(s) the tool maps to.

**Method-originated relationships:**
- `throws` — method declares it throws this exception type — jQAssistant: `THROWS`
- `calls` — method invokes a method — jQAssistant: `INVOKES`, `VIRTUAL_INVOKES`
- `returns` — method's return type — jQAssistant: `RETURNS`
- `parameter_type` — method has a parameter of this type — jQAssistant: `HAS` (to Parameter) + `OF_TYPE` (to Type)
- `reads_field` — method reads a field — jQAssistant: `READS`
- `writes_field` — method writes a field — jQAssistant: `WRITES`
- `overrides` — method overrides another method — jQAssistant: `OVERRIDES`
- `annotated_by` — method has this annotation type — jQAssistant: `ANNOTATED_BY` (to Annotation) + `OF_TYPE` (to Type)
- `parameter_annotated_by` — method has a parameter with this annotation type — jQAssistant: `HAS` (to Parameter) + `ANNOTATED_BY` + `OF_TYPE`

**Field-originated relationships:**
- `has_type` — field is of this type — jQAssistant: `OF_TYPE`
- `annotated_by` — field has this annotation type — jQAssistant: `ANNOTATED_BY` (to Annotation) + `OF_TYPE` (to Type)
- `read_by` — field is read by this method — jQAssistant: `READS` (reverse direction)
- `written_by` — field is written by this method — jQAssistant: `WRITES` (reverse direction)

`annotated_by` appears in both groups; the source kind (method or field) makes it unambiguous in context.

The jQAssistant labels are shown here for reviewability of the mapping. The tool itself takes only the Cartograph `relationship` string as input — consumers never pass raw jQAssistant labels.

The list is surfaced through `describe_graph`'s response (per the convention discussed in the v2 detail-tools doc) so the LLM discovers the vocabulary once per session rather than guessing.

### `limit` (default 50)

Maximum number of edges to return. Server-side cap at 500.

Because both source and target subtrees scope the query, result sets are typically bounded — most aggregated edges have tens to low hundreds of underlying detail edges, not thousands. When the cap is reached, the `summary` block still reports the true total so the LLM can either re-issue with a larger limit or apply a tighter filter (most often, narrowing `relationship`).

## Response shape

This tool emits graph-shaped output — many edges, the same nodes appearing as endpoints of multiple edges, plus referenced again in `by_source_type`. To keep the response compact, it uses **slim payload encoding**: a single top-level `nodes` map carries each node's display fields once, and every other reference is an ID.

```json
{
  "nodes": {
    "47291": { "name": "coordination", "qualified_name": "org.elasticsearch.cluster.coordination", "kind": "java.module" },
    "38104": { "name": "transport", "qualified_name": "org.elasticsearch.transport", "kind": "java.module" },
    "47200": { "name": "ClusterCoordinator", "qualified_name": "org.elasticsearch.cluster.coordination.ClusterCoordinator", "kind": "java.class" },
    "47201": { "name": "LeaderElector", "qualified_name": "org.elasticsearch.cluster.coordination.LeaderElector", "kind": "java.class" },
    "47202": { "name": "StateRecoverer", "qualified_name": "org.elasticsearch.cluster.coordination.StateRecoverer", "kind": "java.class" },
    "91204": { "name": "applyState", "qualified_name": "org.elasticsearch.cluster.coordination.ClusterCoordinator.applyState", "kind": "java.method" },
    "91205": { "name": "sendState", "qualified_name": "org.elasticsearch.cluster.coordination.ClusterCoordinator.sendState", "kind": "java.method" },
    "88301": { "name": "TransportException", "qualified_name": "org.elasticsearch.transport.TransportException", "kind": "java.class" },
    "88401": { "name": "send", "qualified_name": "org.elasticsearch.transport.TransportService.send", "kind": "java.method" }
  },
  "from_scope": 47291,
  "to_scope": 38104,
  "edges": [
    {
      "from": 91204,
      "from_parent": 47200,
      "to": 88301,
      "relationship": "throws",
      "location": {
        "absolute_path": "/Users/gerd/elasticsearch/server/src/main/java/org/elasticsearch/cluster/coordination/ClusterCoordinator.java",
        "relative_path": "server/src/main/java/org/elasticsearch/cluster/coordination/ClusterCoordinator.java",
        "workspace_root": "/Users/gerd/elasticsearch",
        "line_number": 247
      }
    },
    {
      "from": 91205,
      "from_parent": 47200,
      "to": 88401,
      "to_parent": 38104,
      "relationship": "calls",
      "location": {
        "absolute_path": "/Users/gerd/elasticsearch/server/src/main/java/org/elasticsearch/cluster/coordination/ClusterCoordinator.java",
        "relative_path": "server/src/main/java/org/elasticsearch/cluster/coordination/ClusterCoordinator.java",
        "workspace_root": "/Users/gerd/elasticsearch",
        "line_number": 312
      }
    }
  ],
  "summary": {
    "total_edges": 47,
    "returned": 47,
    "truncated": false,
    "by_relationship": {
      "throws": 12,
      "calls": 28,
      "annotated_by": 4,
      "reads_field": 3
    },
    "by_source_type": [
      { "type": 47200, "edge_count": 23 },
      { "type": 47201, "edge_count": 18 },
      { "type": 47202, "edge_count": 6 }
    ]
  }
}
```

### Top-level fields

**`nodes`** — Map from stringified node ID to display fields (`name`, `qualified_name`, `kind`). Every node referenced anywhere in the response (scope endpoints, edge endpoints, declaring types, `by_source_type` entries) has exactly one entry here. Insertion order is meaningful — types appear grouped with their declared methods/fields, and types appear in `by_source_type` order — but consumers must not depend on key iteration order for correctness.

**`from_scope`** / **`to_scope`** — Node IDs of the source and target subtrees, as passed in via `from_id` and `to_id`. The corresponding display fields are in `nodes`.

### Per-edge fields

**`from`** — Node ID of the source method or field. Resolve via `nodes[from]` for display fields.

**`from_parent`** — Node ID of the source method/field's declaring type. Always present on edges. Most edges in a single query share a small number of declaring types; this field plus `by_source_type` lets the LLM see which types are responsible for the coupling without resolving qualified names. The declaring type's display fields are in `nodes`.

**`to`** — Node ID of the target. The target kind depends on the relationship: types for `throws`/`returns`/`parameter_type`/`has_type`/`annotated_by`, methods for `calls`/`overrides`, fields for `reads_field`/`writes_field`, methods for `read_by`/`written_by`. Resolve via `nodes[to]`.

**`to_parent`** — Node ID of the target's declaring type, when the target is a method or field. Omitted when the target is itself a type (`throws`, `returns`, etc.). The declaring type's display fields are in `nodes`.

**`relationship`** — The relationship kind for this edge. One of the kinds in the v0.2 vocabulary. When the `relationship` parameter is supplied to the call, all edges have the same value here; when omitted, edges may carry different values.

**`location`** — File and line number where this dependency is realized in the source code. Per the Cartograph convention, includes both absolute and relative paths plus a `workspace_root` so the LLM can use whichever form its file-reading tools prefer. The `line_number` enables direct navigation to the call site or declaration site for further investigation via Claude's code-reading tools.

`location` may be `null` for some relationships if jQAssistant doesn't capture line-level data for them (e.g., for inherited annotations or type relationships derived from class headers rather than method bodies). The tool returns the field as `null` rather than omitting it, so the response shape is consistent.

### Summary fields

**`total_edges`** — Count of detail-level edges matching the filter set between the two subtrees, regardless of `limit`. The truth-telling field.

**`returned`** — Count of edges actually in the response array.

**`truncated`** — Boolean. `true` if `total_edges > returned`.

**`by_relationship`** — Map from relationship kind to edge count. When the `relationship` parameter is omitted, this gives the LLM a quick picture of *what kinds of coupling* exist between the subtrees — "mostly calls with some throws and a few annotations." When the `relationship` parameter is supplied, this map has a single entry; the structural insight comes from `by_source_type` instead.

**`by_source_type`** — A list of `{type, edge_count}` pairs, grouped by the declaring type of the source method/field. `type` is a node ID; resolve via `nodes[type]`. Lets the LLM see *which types within the source subtree are responsible* for the coupling, ranked by edge count. The list is sorted descending by `edge_count` and capped at 10 entries — for source subtrees with many contributing types, this surfaces the top contributors. If more than 10 types contribute, the cap is signaled by an `others_count` field on the summary (omitted here for clarity; add when relevant).

The summary fields together transform a flat edge list into a *structured* understanding. The LLM can answer "what's the coupling between A and B?" from just the summary, without enumerating individual edges — though the edges are there if needed for line-level investigation.

## Semantics

A few details worth being explicit about:

### Subtree scoping

"Edges from `from_id`'s subtree to `to_id`'s subtree" means: the edge's source method/field is declared on a type that is a descendant of `from_id`, AND the edge's target is a method/field/type that is itself a descendant of `to_id` (or, for cross-type relationships, declared on a type that is a descendant of `to_id`).

For type-targeted relationships (`throws`, `returns`, `parameter_type`, `has_type`, `annotated_by` from methods or fields), the target is the type itself, and "target subtree contains target type" is the condition.

For method-targeted relationships (`calls`, `overrides`), the target is a method, and the condition is "the method's declaring type is in the target subtree."

For field-targeted relationships (`reads_field`, `writes_field`), the target is a field, and the condition is "the field's declaring type is in the target subtree."

This matches how `outgoing_core_dependencies` handles type-level edges, and gives the LLM a consistent mental model: subtree scoping always means "the entity lives somewhere under this subtree, transitively."

### Multi-relationship default

When `relationship` is omitted, the response returns edges of *all* kinds. This is the typical first call — the LLM gets the full picture of what's happening between the two subtrees in one query.

The `by_relationship` summary gives a one-glance distribution, and the LLM can re-issue with a specific `relationship` filter if it wants to drill into one kind.

### Edge ordering

Edges are returned in a stable, predictable order: first by `relationship` (alphabetical), then by source type's qualified name, then by source method/field name, then by location line number. This puts edges of the same relationship together, edges from the same source type together within that, and source-file order within a single source.

This ordering helps the LLM read the response coherently — "all the throws first, all the calls next, grouped by which class they're from." It also means truncation at the limit returns a predictable prefix rather than an arbitrary slice.

### Global queries via the root

When `from_id = root_id` (or `to_id = root_id`), the corresponding side is effectively "the whole codebase." This handles questions like:

- "Find every method anywhere that throws `IOException`": `detail_dependencies(from=root, to=ioexception_id, relationship="throws")`
- "Find every method anywhere annotated with `@Deprecated`": `detail_dependencies(from=root, to=deprecated_annotation_id, relationship="annotated_by")`
- "Find every field of type `Connection` across the codebase": `detail_dependencies(from=root, to=connection_type_id, relationship="has_type")`

Global queries can return large result sets. The standard summary fields (`by_relationship`, `by_source_type`) give the LLM structural digest even when the raw count is high, and `truncated: true` warns when more data exists.

The tool description should explicitly mention this pattern so the LLM knows global queries are expressible.

### Self-loops

When `from_id == to_id` (or one is a descendant of the other), the query returns intra-subtree edges. For example, `detail_dependencies(from=some_module, to=some_module)` returns method/field-level coupling among the types within that module — the *internal* coupling.

This is valid and sometimes useful — "how internally coupled is this module at the method level?" — but the LLM should be aware that the result set can be larger than expected (intra-subtree edges are usually denser than inter-subtree ones). The tool description doesn't need to forbid this pattern but should mention that it captures internal coupling.

## Error cases

The tool returns a structured error in these cases:

**`NODE_NOT_FOUND`** — `from_id` or `to_id` doesn't exist in the graph. The error indicates which.

**`INVALID_RELATIONSHIP`** — `relationship` is not a recognized kind. The error includes the offending value and the allowed list.

Empty result sets are *not* errors. When the source and target subtrees genuinely have no detail-level edges between them, the response returns `edges: []` and `summary.total_edges: 0`. This is a meaningful answer — "there's no coupling here" — distinct from a missing-node error.

## Performance characteristics

`detail_dependencies` queries Neo4j directly. Expected behavior:

- **Typical case** (single class to single class, or small module to small module): tens of edges, response under 100ms.
- **Common case** (module to module): a few hundred edges, response under 500ms.
- **Heavy case** (root to a widely-used type, e.g., everywhere `String` is used): thousands of edges, response approaches 1–2 seconds and truncates at the limit.
- **Pathological case** (root to root, all relationships): would return millions of edges. Should never happen in practice — the LLM has no reason to ask this — but the limit caps it.

The Cypher query is more complex than the list_methods/list_fields queries because it traverses subtree containment on both sides. Pre-compile and parameterize. Some specific optimizations:

- Use the in-memory model's descendant resolution to expand `from_id` and `to_id` to type-ID lists *before* querying Neo4j. This converts subtree-traversal into a parameterized "source type IN (...) AND target type IN (...)" query, which Neo4j handles efficiently.
- The `by_source_type` summary can be computed from the same query results with a GROUP BY, avoiding a second query.

For global queries (root as source or target), the descendant expansion produces a list of all type IDs, which can be large. This is fine for the query — Neo4j handles large IN lists — but worth verifying performance on the Spring Framework or Elasticsearch test graph.

## Description for the tool registration

This is the text exposed to the LLM via MCP.

> Return the method-level and field-level dependencies between a source subtree and a target subtree. This is the drill-down tool that bridges the hierarchical level and the detail level — given an aggregated dependency you've identified (typically via `aggregated_outgoing`, `aggregated_incoming`, or `outgoing_core_dependencies`), this returns the underlying concrete method/field edges that explain it.
>
> Returns a top-level `nodes` map (each referenced node listed once with `name`, `qualified_name`, `kind`) plus an `edges` list whose entries reference nodes by ID. Each edge carries `from` and `to` (node IDs), `from_parent` and optionally `to_parent` (declaring-type IDs for navigation back to the hierarchical model), the relationship kind, and the source location (file path and line number). The `summary` block groups edges by relationship kind (`by_relationship`) and by source type (`by_source_type`) — these are often more useful than enumerating individual edges, because they tell you *what kind of coupling* exists and *which types in the source subtree are responsible*.
>
> Common parameter patterns:
>
> - `from_id` + `to_id` (no relationship): see the full structural picture of detail-level coupling between two subtrees. Returns all relationship kinds; the `by_relationship` summary tells you the distribution.
> - `from_id` + `to_id` + `relationship: "throws"`: drill into one specific kind of coupling (in this case, exception throws).
> - `from_id = root_id` + `to_id = some_annotation_type`: global query — "find every method anywhere with this annotation."
> - `from_id = root_id` + `to_id = some_field` (with `relationship: "writes_field"`): global query — "find every method that writes this field."
>
> When to use this vs. neighboring tools:
>
> - For the type-level evidence (which type-pairs are coupled), use `outgoing_core_dependencies` or `incoming_core_dependencies`. This tool drills one level deeper, into the methods and fields that realize those type-level edges.
> - For the methods declared on a single type (composition rather than dependency), use `list_methods`.
> - For everything about one specific method or field, use `method_details` or `field_details`.
>
> Relationship kinds available: `throws`, `calls`, `returns`, `parameter_type`, `reads_field`, `writes_field`, `overrides`, `annotated_by`, `parameter_annotated_by`, `has_type`, `read_by`, `written_by`. The available set is also surfaced through `describe_graph`'s response.

## Integration with the broader workflow

`detail_dependencies` is the natural continuation of hierarchical investigation:

**Workflow 1: explain an aggregated dependency.**

1. `aggregated_outgoing(some_module)` → see what the module depends on, with weights
2. Notice one heavy edge to another module
3. `outgoing_core_dependencies(some_module, target_module)` → see which type pairs contribute
4. `detail_dependencies(some_module, target_module)` → see method/field-level evidence
5. Notice the coupling is mostly `calls` between two specific classes (from `by_source_type`)
6. `method_details(specific_method_id)` → understand one specific call's context
7. Read the actual code via Claude's file tools

This is the canonical drill-down workflow. Each step adds resolution; the LLM stops when it has enough to answer the user's question.

**Workflow 2: investigate exception flow.**

1. `find_node("TransportException")` → exception type ID
2. `detail_dependencies(from=some_module, to=exception_id, relationship="throws")` → which methods in this module throw this exception
3. `by_source_type` summary shows the throwing types
4. For interesting throws, `method_details(method_id)` to see context

**Workflow 3: framework wiring.**

1. `find_node("@Autowired")` → annotation type ID
2. `detail_dependencies(from=some_module, to=autowired_id, relationship="annotated_by")` → all `@Autowired` methods in the module
3. Plus separately `detail_dependencies(from=some_module, to=autowired_id)` if both methods *and* fields could be annotated
4. For each annotated entity, `field_details` or `method_details` to see what's being injected

**Workflow 4: global search.**

1. `find_node("@Deprecated")` → annotation type ID
2. `detail_dependencies(from=root, to=deprecated_id, relationship="annotated_by")` → every deprecated method/field in the codebase
3. `by_source_type` summary surfaces which classes have the most deprecated members
4. Use the LLM's reasoning to prioritize which to clean up

In each workflow, `detail_dependencies` is doing the same kind of work: turning a structural relationship between subtrees into a list of concrete locations where that relationship is realized, with summary fields that surface structure within the result set.

## Implementation notes

A few specifics worth flagging during implementation:

**Cypher query shape.** The query joins subtree descendant resolution (done in the in-memory model) with the detail-level edges in Neo4j. Pseudocode:

```
// Resolve from_id's subtree to type IDs (in-memory)
from_types = descendants_of(from_id).filter(kind == "java.type")

// Resolve to_id's subtree to type IDs (in-memory)
to_types = descendants_of(to_id).filter(kind == "java.type")

// Query Neo4j for detail-level edges
MATCH (source_type:Type)-[:DECLARES]->(source_entity)-[r]->(target)
WHERE id(source_type) IN $from_types
  AND (
    (target:Type AND id(target) IN $to_types)
    OR (target:Method AND id((target)<-[:DECLARES]-(:Type)) IN $to_types)
    OR (target:Field AND id((target)<-[:DECLARES]-(:Type)) IN $to_types)
  )
  AND (type(r) = $relationship OR $relationship IS NULL)
RETURN source_entity, target, type(r), r.location, source_type
ORDER BY type(r), source_type.fqn, source_entity.name, r.location.line
LIMIT $limit
```

The exact patterns depend on jQAssistant's schema; the key idea is parameterized type ID lists from in-memory expansion, plus a single Cypher query that joins everything.

**Relationship kind mapping.** jQAssistant uses specific edge labels (`THROWS`, `INVOKES`, `VIRTUAL_INVOKES`, `READS`, `WRITES`, `OF_TYPE`, etc.). The tool's `relationship` parameter is a Cartograph-normalized vocabulary that maps to these. Maintain the mapping in one place; don't scatter it.

**Location data.** jQAssistant captures source positions on most detail-level edges via `LineNumber` properties or via a `Source` node attached to the edge. The exact mechanism varies by relationship kind; handle each case in the loader/mapper rather than in the query.

**`by_source_type` computation.** Compute this in the same Cypher query via a separate aggregation, not as a post-process in Java. Neo4j is good at this; avoid round-trips.

**Slim payload construction.** Build the `nodes` map manually rather than calling `AbstractGraphMcpTools.toNodeRefShort(HGNode)` for edge endpoints. Insert into `nodes` in a meaningful order — group declaring types with their declared methods/fields, and order types by `by_source_type` ranking. The set of node IDs needed for the map is the union of: `from_scope`, `to_scope`, every `edge.from`, every `edge.to`, every `from_parent` / `to_parent`, and every `by_source_type[].type`. Collect these IDs while assembling the response, then resolve display fields in one pass.

**Truncation honesty.** When the limit is hit, the `total_edges` count should still reflect the true total. This requires a separate `count(*)` query or a Cypher subquery — slightly more expensive than returning `total_edges = returned`, but essential for the LLM to know the truth.

**Edge case: `from_id` equals `to_id`.** This is the self-loop case (internal coupling). The query above handles it correctly — types in both lists, edges retrieved. No special-casing needed.

**Edge case: the `relationship` is supplied but produces no edges.** Return `edges: []` and `summary.total_edges: 0`. The summary's `by_relationship` map still includes the supplied kind (with count 0), so the LLM sees the absence clearly.

**Cypher logging (debug).** The assembled Cypher statement (together with the resolved `fromTypes` / `toTypes` parameters and the effective `relationship` / `include_inherited` flags) is logged at INFO when the property `slizaa.mcp.tools.detail-dependencies.log-cypher` is set to `true`. Defaults to `false`. Useful for debugging query shape and inheritance traversal without attaching a debugger. The property namespace is per-tool; each detail tool gets its own flag as the need arises.

**Testing checklist:**

- Single class to single class with known throws relationship — verify `relationship: "throws"` filter works
- Module to module with known method-level coupling — verify edges are returned with correct relationships
- Subtree containment (parent module to child module) — verify scoping works
- Global query (root to specific annotation) — verify performance on real-sized graph
- Two unrelated subtrees — verify `edges: []` and `total_edges: 0` returned correctly
- `relationship` parameter with method-only kind (e.g., `throws`) when the source subtree has only fields — verify empty result, not error
- `relationship` parameter with field-only kind (e.g., `has_type`) when the source subtree has only methods — verify empty result, not error
- Invalid `relationship` parameter value — verify `INVALID_RELATIONSHIP` error with allowed list
- Truncation case (set `limit=10` on a query that has 100 underlying edges) — verify `total_edges` is correct and `by_source_type` reflects the full set, not just the returned subset
- Self-loop (`from_id == to_id`) on a small module — verify internal coupling is returned correctly
