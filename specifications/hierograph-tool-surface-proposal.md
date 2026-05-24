# Hierograph: Tool Surface Proposal

This document proposes a complete tool surface for Hierograph, reflecting the design discussions about zoom levels, the `detail_level` parameter, the lexical-vs-aggregation hierarchy, consistent naming, and consolidating aggregated dependency tools. It is a forward-looking specification, not a description of the current implementation; some tools described here exist today, others are renames, others are consolidations of multiple existing tools.

The goals of this proposal are:

1. Make the user-facing model coherent — one lexical hierarchy from module to method/field, with implementation details (in-memory vs. Neo4j) hidden from the LLM.
2. Reduce confusion between similar tools by introducing a `detail_level` parameter where granularity is the real distinction.
3. Consolidate tools that represent the same underlying operation with different parameter shapes.
4. Keep the API as small as possible without sacrificing the distinct question shapes that the LLM uses.
5. Make each tool's role obvious from its name and parameters, so the LLM picks correctly without extensive description-reading.

### A principle that runs through the API: input-bounded vs. data-bounded

The proposal repeatedly distinguishes two kinds of tools based on what bounds their result size:

**Input-bounded tools** — result size is determined by the LLM's input parameters. For aggregation tools, `|source_ids| × |target_ids|` caps the result count. For path tools, `max_paths` caps it. For entity-detail tools, a single entity bounds it. The LLM controls result size by shaping the input.

**Data-bounded tools** — result size is determined by the underlying graph's complexity, not by input parameters. For navigation tools traversing a subtree, the number of descendants is whatever the codebase has. For dependency tools between two subtrees, the number of edges is determined by how coupled those subtrees are. The LLM can't predict result size from inputs alone.

The two categories need different protection mechanisms:

- Input-bounded tools use **input validation** — defensive caps against degenerate inputs (e.g., the 2500 cross-product cap on `aggregated_dependencies`), with clear errors rather than silent truncation. No limits, no cursors. The LLM already has full control.
- Data-bounded tools use **pagination** — a `limit` parameter for the page size, a `cursor` parameter to retrieve more, and honest reporting of the true total. The LLM gets a manageable page by default and can drill further if needed.

This split shows up consistently throughout the API:

| Category | Tools | Protection |
|---|---|---|
| Input-bounded | `aggregated_dependencies`, `pairwise_dependencies`, `find_dependency_path`, `type_details`, `method_details`, `field_details`, `find_node`, `list_children`, `graph_overview` | Input validation |
| Data-bounded | `list_descendants`, `outgoing_dependencies`, `incoming_dependencies`, `affected_by` | Pagination (cursors) |

The pagination protocol is specified in `hierograph-pagination.md` as a separate document; this proposal references it where relevant.

## The user-facing model

Hierograph exposes one containment hierarchy:

```
module
  └ package
      └ subpackage
          └ type (class, interface, enum, record, annotation)
              └ method
              └ field
```

This is the *lexical* hierarchy as a developer thinks about it. It is the same hierarchy at all levels — modules, packages, types, methods, and fields are all first-class nodes the LLM can address, navigate, and reason about.

Three zoom levels apply to dependency analysis:

- **Aggregated** — pairwise summaries between two subtrees (e.g., "module A depends on module B with weight 247; the dependency includes extends relationships")
- **Type** — type-to-type edges between two subtrees (e.g., "class A.X depends on class B.Y; class A.X extends class B.Z")
- **Detail** — method-to-method, method-to-field, field-to-type edges (e.g., "method A.X.foo calls method B.Y.bar at line 247")

Aggregation is always pairwise — given any (source, target) pair of subtrees, there is exactly one aggregated edge. A "scope" or "subtree-wide aggregation" concept doesn't exist in this model; the LLM provides explicit sets of nodes to aggregate between.

Tools that answer "what's the evidence between A and B?" can operate at type or detail level via a `detail_level` parameter. Tools that answer other question shapes operate at the level the question naturally implies.

## Architecture: what lives where

The split between in-memory and Neo4j is along a meaningful axis — *navigation and aggregation* vs. *detail-level evidence* — not along the type/method boundary.

**The in-memory model contains:**

- **The complete lexical hierarchy.** Every module, package, type, method, and field is represented as a first-class node in memory, with parent references and child references. The full containment tree is navigable from any node without touching Neo4j.
- **Type-level dependency edges.** The aggregated dependency graph between types — with weights and kind attributes — is in memory. Each edge between a (source, target) type pair carries scanner-declared attributes indicating which specific kinds of dependency contribute (extends, implements, annotated_by, and a residual catch-all for the Java provider). This is what makes aggregation, traversal, and reachability fast even on large codebases.

**Neo4j contains (queried on demand):**

- **Detail-level edges.** Method-to-method calls, method-to-type throws, field reads and writes, annotation references, etc. These are queried when the LLM specifically asks for evidence between subtrees at the detail level, or for full per-entity detail.
- **Full per-entity structural detail.** Parameter NodeRefs with their types, full annotation lists, override targets, source locations, read/write digests. These are queried via `method_details` and `field_details` when the LLM has identified one specific entity to investigate.

The architectural principle: **the in-memory model is what the LLM uses to navigate and reason; Neo4j is what the LLM consults for detail-level evidence.** This split tracks the LLM's working pattern — frequent browsing and aggregation happens in memory; occasional drilling into specific evidence touches Neo4j.

### Lazy materialization of node properties

A clean engineering detail worth being explicit about: the in-memory model holds the *structural skeleton* eagerly (node IDs, parent/child relationships, type-level dependency edges) and materializes *node properties* (names, qualified names, modifiers, counts, flags) lazily on first access. When a query needs a node's properties, Hierograph fetches them from Neo4j once and stores them on the in-memory node object; subsequent accesses are served from memory.

This isn't a performance optimization in the usual sense — it's the structurally right way to load this data. The eager alternative is dramatically slower for reasons that have nothing to do with data volume:

- Skeleton only (~110,000 parent-child pairs): **38 ms** to load, **64 MB** memory
- Skeleton plus all properties (same hierarchy): **~118 seconds** to load, **220 MB** memory

The eager-load case is slow because each node's properties require a separate Neo4j point query — an N+1 problem at 110,000 nodes, dominated by per-query overhead. Lazy materialization sidesteps this: the skeleton loads as a few bulk Cypher queries, and properties materialize per-node as the LLM touches them.

A properly batched bulk-load Cypher query could make full materialization fast (seconds rather than minutes) if that ever becomes desirable. For now, the lazy approach is the right default.

**Measurements:**

| Metric | Value |
|---|---|
| Skeleton load time (Spring-sized codebase) | 38 ms |
| Skeleton memory | 64 MB |
| Full-materialization memory (upper bound) | 220 MB |
| Navigation, aggregation, reachability | microseconds |
| Detail-level evidence (Neo4j query) | milliseconds |

Memory scales linearly with hierarchy size; even codebases 10x the size of Spring fit comfortably. Performance has not been a constraint in any tested workflow.

Cached properties are held with strong references for the life of the server process. The cache grows monotonically toward an upper bound of ~220 MB on a Spring-sized codebase, which is the measured full-materialization ceiling. For typical deployments, this is fine. Weak-reference variants and explicit eviction policies are available as future refinements if needed.

## NodeRef shapes

NodeRefs appear throughout the API. To keep response sizes appropriate and the API predictable, NodeRefs come in two shapes:

**Minimal NodeRef** — the identity fields only:

```json
{
  "id": 47291,
  "name": "ClusterService",
  "qualified_name": "org.elasticsearch.cluster.ClusterService",
  "kind": "java.class",
  "parent_id": 12503,
  "parent_kind": "java.package"
}
```

Used when a NodeRef appears *inside a larger structure* — as the endpoint of an edge, as a participant in a path, as a reference inside a per-entity detail response.

**Enriched NodeRef** — identity plus kind-appropriate metadata:

```json
{
  "id": 47291,
  "name": "ClusterService",
  "qualified_name": "org.elasticsearch.cluster.ClusterService",
  "kind": "java.class",
  "parent_id": 12503,
  "parent_kind": "java.package",
  "modifiers": ["public"],
  "member_count": 32,
  "method_count": 28,
  "field_count": 4,
  "annotation_count": 2,
  "interface_count": 1,
  "is_abstract": false,
  "is_generic": false,
  "parent_type": { ... minimal NodeRef of superclass ... }
}
```

Used when the NodeRef *is the result* of a browse-style operation — when the LLM is scanning a set of nodes to decide what to investigate further.

**Which tools return which shape:**

Browse tools (the result *is* the node) return enriched NodeRefs:
- `find_node`
- `list_children`
- `list_descendants`
- `affected_by`

Structural tools (NodeRefs participate in a larger result) return minimal NodeRefs:
- `aggregated_dependencies` (endpoints of edges)
- `pairwise_dependencies` (matrix axes)
- `outgoing_dependencies` / `incoming_dependencies` (endpoints of edges)
- `find_dependency_path` (steps in a path)
- `method_details` / `field_details` (referenced types, annotations, etc.)

This split is architecturally cheap because the full hierarchy with metadata is in memory. Returning enriched NodeRefs costs nothing more than returning minimal ones — the data is already loaded.

**Enriched metadata by kind:**

*Module:*
- `child_count` — immediate children
- `descendant_type_count` — total types underneath
- `descendant_method_count` — total methods underneath

*Package:*
- `child_count`
- `descendant_type_count`
- `direct_type_count` — types directly in this package (excluding sub-packages)

*Type (class, interface, enum, record, annotation):*
- `modifiers` — list of Java modifier keywords
- `member_count` — methods + fields combined
- `method_count`
- `field_count`
- `annotation_count` — annotations on the type itself
- `interface_count` — number of implemented interfaces
- `is_abstract`
- `is_generic`
- `parent_type` — minimal NodeRef of superclass (null for interfaces, root classes, etc.)

*Method:*
- `modifiers`
- `parameter_count`
- `throws_count`
- `annotation_count`
- `is_constructor`

*Field:*
- `modifiers`
- `field_type_name` — human-readable field type as a string (e.g., `"java.util.List"`)
- `annotation_count`
- `is_constant` — true if both static and final

## Input handling: accepted node kinds per tool

Tools have constraints on what kinds of nodes they accept as input, because different tools operate at different levels of the architecture. The policy is consistent across the API:

**Type-level dependency tools** operate on the type-level dependency graph and accept type-kind, package-kind, or module-kind IDs. Subtree inputs (packages, modules) are expanded internally to their contained types. Method and field IDs are rejected with a structured error that includes the declaring type, so the LLM can recover in one step.

Affected tools:
- `outgoing_dependencies` and `incoming_dependencies` at `detail_level: "type"`
- `aggregated_dependencies`
- `pairwise_dependencies`
- `affected_by`
- `find_dependency_path`

**Detail-level dependency tools** accept any node kind that participates in detail-level edges, including methods and fields. No special handling needed; the tool operates at the level where these IDs are first-class endpoints.

Affected tools:
- `outgoing_dependencies` and `incoming_dependencies` at `detail_level: "detail"`

**Entity-detail tools** accept only their target kind. `type_details` accepts type-kind IDs (`java.class`, `java.interface`, `java.enum`, `java.record`, `java.annotation`); `method_details` accepts method IDs only; `field_details` accepts field IDs only. Other kinds return a structured error indicating the expected kind.

**Navigation tools** (`find_node`, `list_children`, `list_descendants`, `graph_overview`) accept any kind, since they operate uniformly across the hierarchy.

### Member ID error response

When a type-level dependency tool receives a method or field ID, it returns a structured error of the form:

```json
{
  "error": {
    "code": "INVALID_NODE_KIND",
    "message": "This tool operates on type-level dependencies. The node 'org.elasticsearch.cluster.ClusterService.applyState' is a method, not a type.",
    "actual_kind": "java.method",
    "declaring_type": {
      "id": 47291,
      "name": "ClusterService",
      "qualified_name": "org.elasticsearch.cluster.ClusterService",
      "kind": "java.class"
    },
    "recovery": "To query dependencies involving this method's declaring type, pass id=47291. To query method-level evidence directly, use outgoing_dependencies or incoming_dependencies with detail_level='detail'."
  }
}
```

The `declaring_type` field is the key ergonomic detail. The LLM can recover in one hop by reading `declaring_type.id` from the error and retrying the call with the right ID, without needing a separate lookup. The `recovery` message also explicitly mentions `detail_level: "detail"` as an alternative path, so the LLM learns the two valid options.

### Input acceptance matrix

A concise reference:

| Tool category | java.module | java.package | java.type kinds | java.method / java.field |
|---|---|---|---|---|
| Type-level dependency tools | ✓ (expand to types) | ✓ (expand to types) | ✓ (direct) | ✗ (error with declaring_type) |
| Detail-level dependency tools | ✓ (expand) | ✓ (expand) | ✓ (expand) | ✓ (direct) |
| Entity-detail tools (`type_details`) | ✗ | ✗ | ✓ (direct) | ✗ |
| Entity-detail tools (`method_details`) | ✗ | ✗ | ✗ | ✓ (method only) |
| Entity-detail tools (`field_details`) | ✗ | ✗ | ✗ | ✓ (field only) |
| Navigation tools | ✓ | ✓ | ✓ | ✓ |

This matrix is invariant across the API. The LLM learns it once via the error responses and `graph_overview`'s description of the model.

## Node kinds and relationship kinds

Several tools take a `kind_filter` parameter (for node kinds) or a `relationship` parameter (for detail-level edge kinds). The accepted values are defined here. The complete current vocabulary is also surfaced by `graph_overview` so the LLM can discover it at session start, but documenting it here makes the proposal self-contained and gives implementers a single reference.

The vocabulary is namespaced (`java.class`, `java.method`, etc.) to allow future extensions to other languages without breaking existing values. When other scanners are added (Python, TypeScript), they introduce their own namespaces.

### Node kinds

These are the values accepted by `kind_filter` parameters and reported as the `kind` field on every NodeRef.

**Structural kinds** (the containment hierarchy):

- `java.module` — a build module (Maven/Gradle module, or whatever the loader configures as the top-level container)
- `java.package` — a Java package; can contain sub-packages and types
- `java.class` — a regular class
- `java.interface` — an interface
- `java.enum` — an enum type
- `java.record` — a record (Java 14+)
- `java.annotation` — an annotation type
- `java.method` — a method declared on a type (includes constructors; constructors are also flagged via `is_constructor` in `method_details`)
- `java.field` — a field declared on a type

**Pseudo-kind for primitives:**

- `java.primitive` — used in NodeRefs for primitive types (`int`, `boolean`, `void`, etc.) in positions like `method_details.return_type` and `field_details.type`. These NodeRefs have `id: null` because primitives aren't first-class nodes in the graph. The LLM should not pass primitive NodeRefs to other tools.

**Group aliases** (accepted by `kind_filter`, expand to multiple specific kinds):

- `"types"` — expands to `["java.class", "java.interface", "java.enum", "java.record", "java.annotation"]`
- `"members"` — expands to `["java.method", "java.field"]`
- `"packages"` — expands to `["java.package"]` (alias kept for parallelism with other groups)

Group aliases are an ergonomic shortcut; specific kinds always work too. Mixing groups and specific kinds in the same filter list is allowed: `kind_filter=["types", "java.method"]` returns all types and all methods.

### Type-level edge attributes

Type-level dependency edges (returned by `aggregated_dependencies`, `pairwise_dependencies`, and `outgoing_dependencies` / `incoming_dependencies` at `detail_level: "type"`) carry attributes that indicate which specific *kinds* of underlying relationships contribute to them. These attributes preserve information that would otherwise be lost if only a generic "depends_on" edge were tracked.

The design principle is **one edge per (source, target) pair, with attributes describing the kinds of dependency that contribute to it.** This is different from a multi-edge design where each kind would be a separate edge — that approach would force every traversal, aggregation, and reachability algorithm to deduplicate, and would inflate the in-memory graph with parallel edges. The attribute-on-edge design keeps the graph topology clean while preserving the kind information.

For the Java provider, the attribute set is:

- **`is_extends`** — at least one type in the source subtree extends a type in the target subtree
- **`is_implements`** — at least one type in the source subtree implements an interface in the target subtree
- **`is_annotated_by`** — at least one type in the source subtree is annotated by an annotation type in the target subtree
- **`is_depends_on_other`** — at least one *other* form of dependency exists (calls, throws, parameter types, field types, return types, field reads/writes, etc. — the residual after the three structural kinds above)

Multiple attributes can be true simultaneously. A class that extends another class *and* uses methods from it would have both `is_extends: true` and `is_depends_on_other: true` on the edge.

**Why this matters for the LLM:**

The attribute-on-edge design lets the LLM answer kind-specific questions at the aggregated and type levels without needing to drill into detail-level evidence:

- *"Does anything in the API layer extend anything in the infrastructure layer?"* → filter aggregated edges by `is_extends: true` between the two scopes
- *"Show me the inheritance structure of this module"* → query type-level edges where `is_extends: true` or `is_implements: true`
- *"Which annotation types are used heavily in this codebase?"* → query edges to annotation types where `is_annotated_by: true`
- *"Is this dependency more than just usage — is there a structural commitment?"* → check whether `is_extends` or `is_implements` is true

Without the attributes, these questions would require descending to `detail_level: "detail"` for the answer.

**Response shape:**

The attributes appear on each type-level edge as a structured set:

```json
{
  "source": { ... NodeRef ... },
  "target": { ... NodeRef ... },
  "weight": 12,
  "type_pair_count": 3,
  "attributes": {
    "is_extends": true,
    "is_implements": false,
    "is_annotated_by": false,
    "is_depends_on_other": true
  }
}
```

The `weight` is the sum of underlying detail-level edge weights (the count of concrete dependencies). The `type_pair_count` is the number of distinct type-to-type edges that contribute (relevant on aggregated edges between subtrees; always 1 on direct type-to-type edges). The `attributes` carry the kind information independently from weight.

**Scanner-driven vocabulary:**

The attribute set is declared by the provider, not hardcoded in Hierograph. The Java provider declares `is_extends`, `is_implements`, `is_annotated_by`, `is_depends_on_other`. A future Python provider would declare its own set — possibly overlapping (Python has `extends` in single inheritance and now also explicit `implements` for protocols) but with language-specific differences (`decorated_by` rather than `annotated_by`, perhaps). The `graph_overview` tool surfaces the active attribute vocabulary so the LLM learns it at session start.

**Why not separate edges per kind:**

An alternative design would represent each kind as a separate edge: `A —[extends]→ B` and `A —[depends_on]→ B` as two parallel edges. This was considered and rejected because:

1. The graph topology becomes a multigraph, forcing every algorithm to deduplicate
2. Aggregation gets harder — combining counts across kinds requires deciding which edges to merge
3. Reachability and pathfinding become ambiguous when multiple edges connect the same pair
4. The natural question "is there *any* dependency between A and B" requires examining multiple edges

The attribute-on-edge design avoids all of these. The graph is simple; the kind information is preserved; questions can be asked at either the "any dependency" level (does the edge exist?) or the "specific kind" level (which attribute is true?).

### Detail-level relationship kinds

These are the values accepted by the `relationship` parameter on `outgoing_dependencies` / `incoming_dependencies` at `detail_level: "detail"`, and reported as the `relationship` field on each detail-level edge.

Unlike the type-level attributes (which are *flags on a single edge*), detail-level relationships *are* the edges — each detail-level edge has exactly one relationship kind. This is correct at the detail level because the source entities are different (a method *calls* another method, but doesn't extend it; that's a different concrete relationship).

**Method-originated relationships** (the source entity is a method):

- `throws` — method declares it throws this exception type
- `calls` — method invokes a method
- `returns` — method's return type
- `parameter_type` — method has a parameter of this type
- `reads_field` — method reads a field
- `writes_field` — method writes a field
- `overrides` — method overrides another method
- `annotated_by` — method has this annotation type
- `parameter_annotated_by` — method has a parameter with this annotation type

**Field-originated relationships** (the source entity is a field):

- `has_type` — field is of this type
- `annotated_by` — field has this annotation type
- `read_by` — field is read by this method
- `written_by` — field is written by this method

Note that `annotated_by` appears in both groups; the source kind (method or field) makes it unambiguous in context.

### Future extensions

When other language scanners are added, they introduce their own namespaces:

- `python.module`, `python.class`, `python.function`, `python.method`, ...
- `typescript.module`, `typescript.class`, `typescript.function`, ...

Each scanner also declares its own type-level edge attribute set and detail-level relationship vocabulary. The framework is uniform; the specifics adapt to what each scanner captures.

The MCP tools accept these uniformly; only the loader (and any per-language metadata in `method_details` / `field_details`) needs to be aware of the language-specific specifics. The `graph_overview` response reports which namespaces are present in the current graph so the LLM knows what to expect.

## The tools

### Discovery and navigation

These tools find IDs and explore the containment hierarchy. They work uniformly across all levels of the hierarchy (module through method/field). They don't take a `detail_level` parameter; the level is determined by the input node and any kind filters supplied.

#### `find_node`

```
find_node(
    name: string,           // required
    kind_filter: string[]?  // optional, restricts results to specific kinds
)
```

Resolves a name into one or more node IDs by searching the whole graph. Matches against simple names, qualified names, and substrings. Returns NodeRefs with enough context for the LLM to disambiguate when multiple matches exist.

Works at any level — finds modules, packages, types, methods, or fields. The LLM can pass `kind_filter` to scope the search if it knows what kind of entity it's looking for.

#### `graph_overview`

```
graph_overview()
```

*(Renamed from `describe_graph` — the new name is more accurate about what the tool returns.)*

Returns a structural overview of the whole codebase: stats (node counts by kind, total edges), the kind taxonomy with brief descriptions, top-level module structure, the relationship vocabulary available for detail-level queries, and the zoom-level model.

This is the orientation tool. The LLM calls it once at the start of a session to learn what's in the codebase and what vocabulary the other tools use.

#### `list_children`

```
list_children(
    node_id: long,                       // required
    kind_filter: string[]?,              // optional
    name_pattern: string?,               // optional substring match on names
    modifier_filter: string[]?,          // optional, only meaningful for methods/fields
    include_inherited: bool = false,     // optional, only meaningful for types
    limit: int = 200
)
```

Returns the direct children of a node, one level deep, as **enriched NodeRefs**. Each child carries kind-appropriate metadata (see *NodeRef shapes* above). The children returned depend on the input node's kind:

- On a module: returns packages (and possibly types declared directly in the module's default package)
- On a package: returns types and sub-packages
- On a type: returns methods and fields

Because every node in the hierarchy is in memory with its metadata, browsing is fast and rich at every level. The LLM can scan a module's packages with descendant counts, a package's types with method counts and annotation counts, a class's methods with parameter counts and annotation counts — all in one in-memory operation.

**Parameters:**

- `kind_filter` — restricts the result to specific kinds. Accepts specific values (`"java.class"`) and group aliases (`"types"`, `"members"`, `"packages"`).
- `name_pattern` — case-insensitive substring match against child names. Useful for finding members by partial name on a type with many methods.
- `modifier_filter` — restricts to children whose modifiers include *all* listed values. Example: `["private", "final"]` returns only effectively-immutable members. Only meaningful when the children are methods or fields; passing it for other input kinds is silently ignored.
- `include_inherited` — when called on a type, includes methods and fields inherited from ancestor types. Default false (only declared members). Only meaningful for type inputs.
- `limit` — caps the number of children returned. Default 200; honest truncation with `total` in the summary if exceeded.

**Use cases:**

- **"What's in this module?"** → `list_children(module_id)` returns packages and types with their counts.
- **"Which classes in this package look framework-managed?"** → `list_children(package_id, kind_filter=["types"])` and inspect `annotation_count` per type.
- **"What does this class declare?"** → `list_children(type_id)` returns methods and fields with metadata.
- **"What constants does this class expose?"** → `list_children(type_id, modifier_filter=["static", "final"])`.

For full detail on one specific method or field (parameter types as NodeRefs, full annotation list, override target, read/write digest), use `method_details` or `field_details` after identifying the entity of interest from `list_children`.

#### `list_descendants`

```
list_descendants(
    node_id: long,                       // required
    kind_filter: string[]?,              // optional
    name_pattern: string?,               // optional substring filter on name
    modifier_filter: string[]?,          // optional, only meaningful for methods/fields
    include_inherited: bool = false,     // optional, only meaningful when types appear in traversal
    limit: int = 200,
    cursor: string?                      // for pagination on large results
)
```

Returns all descendants of a node (multi-level traversal). Each descendant is an enriched NodeRef with the same kind-aware metadata as `list_children`.

Going `list_descendants(module_id, kind_filter=["java.method"])` traverses the full hierarchy from the module down to all methods within it, returning each method with its metadata — all from the in-memory model.

For very large results, pagination is supported via the `cursor` parameter (see the pagination design document for details).

### Dependency analysis

These tools answer questions about dependencies between subtrees. Aggregated tools handle the pairwise rollup view; evidence tools handle the type-level and detail-level zoom into a specific pair.

#### `aggregated_dependencies`

```
aggregated_dependencies(
    source_ids: long[],     // required: 1 or more source subtree IDs
    target_ids: long[]      // required: 1 or more target subtree IDs
)
```

*(Replaces `aggregated_outgoing`, `aggregated_incoming`, and `dependency_between`.)*

Returns the aggregated edges from any source in `source_ids` to any target in `target_ids`. Each edge represents the total dependency from one specific source to one specific target, with weight, type_pair_count, and the structured attribute set (see *Type-level edge attributes* above).

Aggregation is always pairwise. Given two subtrees A and B, there is exactly one aggregated dependency from A to B. This tool computes that for every (source, target) pair in the cross product of the input sets.

**No `limit` parameter.** The result size is fully bounded by the input: at most `|source_ids| × |target_ids|` edges. The LLM controls result size by controlling input size; there's no hidden expansion that could produce surprisingly large responses. This is different from the evidence tools (`outgoing_dependencies`, `incoming_dependencies`) where the result is bounded by the underlying data and a limit is genuinely needed.

**Input validation.** The cross product `|source_ids| × |target_ids|` is capped at 2500. If the LLM passes inputs that exceed this product, the tool returns a clear error indicating that the input is too large and should be narrowed. This is a defensive cap against degenerate inputs, not a behavioral limit — realistic workflows are well below it.

Pairs with no dependency are omitted from the response (not returned with zero weight). The summary block reports the structural facts: total pairs requested, pairs with dependency, pairs without dependency — so the LLM has honest accounting of what it asked for vs. what it got.

**Response summary block:**

```json
{
  "edges": [ ... ],
  "summary": {
    "total_pairs_requested": 12,
    "pairs_with_dependency": 7,
    "pairs_without_dependency": 5
  }
}
```

**Use cases:**

- **"What does A depend on?"** → `aggregated_dependencies(source_ids=[A], target_ids=[X, Y, Z])` where the LLM picks the targets it wants to check. Typically `list_children(some_node)` to enumerate candidate targets, then pass those.
- **"What depends on A?"** → `aggregated_dependencies(source_ids=[X, Y, Z], target_ids=[A])`. Same pattern, reversed.
- **"How coupled are A and B?"** → `aggregated_dependencies(source_ids=[A], target_ids=[B])`. Single-result query.
- **"Among these N modules, what depends on what?"** → consider using `pairwise_dependencies` for matrix-shaped results instead.

The directional concept (outgoing vs. incoming) is implicit in how the LLM populates `source_ids` and `target_ids`. There's no parameter for direction; you just put the depender in `source_ids` and the depended-on in `target_ids`.

#### `pairwise_dependencies`

```
pairwise_dependencies(
    node_ids: long[],                  // required: 2-50 subtree IDs
    direction: "outgoing" | "incoming" | "both" = "both"
)
```

Returns the dependency matrix among the input set of subtrees. Structurally similar to `aggregated_dependencies(source_ids=node_ids, target_ids=node_ids)`, but the response is shaped for matrix consumption — better suited to the DSM-style architectural analysis question shape.

Validation differs from `aggregated_dependencies`: requires ≥2 nodes, capped at 50 for matrix usability. The response includes matrix-style indexing (row/column structure) for easier visualization-tool consumption.

**Use cases:**

- Design Structure Matrix (DSM) analysis
- "Show me the coupling among these N modules"
- Any architectural analysis where the matrix shape matters

When in doubt between `aggregated_dependencies` and `pairwise_dependencies`: use the former for one-directional or asymmetric queries; use the latter for symmetric matrix analysis.

#### `outgoing_dependencies` / `incoming_dependencies`

```
outgoing_dependencies(
    from_id: long,                              // required
    to_id: long,                                // required
    detail_level: "type" | "detail" = "type",   // optional, defaults to "type"
    relationship: string?,                      // only valid when detail_level == "detail"
    limit: int = 200
)

incoming_dependencies(
    from_id: long,
    to_id: long,
    detail_level: "type" | "detail" = "type",
    relationship: string?,
    limit: int = 200
)
```

*(Replace the previous `outgoing_core_dependencies`, `incoming_core_dependencies`, and the directional split of `detail_dependencies`.)*

Return the edges between two specific subtrees, at the requested zoom level. These are the *evidence* tools: given that you know A depends on B (from an aggregated tool result), these tell you what's actually between them.

The directional naming stays here because the question shape genuinely differs: `outgoing_dependencies` asks "what does the source side use of the target side?" while `incoming_dependencies` asks "what does the target side use of the source side?" Both are between the same pair, but the LLM's question shape determines which direction matters.

**At `detail_level: "type"` (the default):**

Returns type-to-type edges between the source subtree and the target subtree. Each edge has a source type NodeRef, a target type NodeRef, a weight (number of underlying detail edges), a type_pair_count of 1, and kind flags (extends, implements, annotated_by, etc.) indicating what kinds of detail-level relationships contribute to this type-level edge.

This uses the in-memory hierarchical model. Fast — microseconds to milliseconds.

The `relationship` parameter is not valid at this level; passing it returns a structured error directing the LLM to use `detail_level: "detail"` instead.

**At `detail_level: "detail"`:**

Returns method/field-level edges between entities in the source subtree and entities in the target subtree. Each edge has a source method/field NodeRef, a target method/field/type NodeRef, a relationship kind (throws, calls, reads_field, writes_field, annotated_by, etc.), and a source location (file + line number).

This uses Neo4j queries. Slower — milliseconds to a few seconds depending on subtree sizes.

The `relationship` parameter is valid and filters to a specific kind. The available relationship vocabulary is surfaced via `graph_overview`.

The response includes summary fields (`by_relationship`, `by_source_type`) that give structural digest even when results are truncated.

### Reachability and impact

These tools answer questions about transitive dependencies and blast radius. Both operate on the type-level dependency graph in the in-memory model — that's where reachability actually exists, since module-level and package-level edges are derived from type-level facts. The tools accept input at any subtree level (module, package, or type) and expand higher-level inputs to the contained types internally.

Both tools operate entirely on the in-memory model. No Neo4j queries at request time; the in-memory graph is optimized for exactly this kind of traversal.

#### `affected_by`

```
affected_by(
    node_id: long,                              // required: module, package, or type
    direction: "outgoing" | "incoming" = "incoming",
    max_depth: int?,                            // optional, default unbounded
    kind_filter: string[]?,                     // optional filter on result type kinds
    limit: int = 200
)
```

Returns the types transitively connected to the input via type-level dependencies. Each result is a type NodeRef with coupling distance and a representative path back to a source type.

**Direction:**

- `"incoming"` (default) — return types that depend on the input. This is the "what breaks if I change this?" use case, the most common refactoring question.
- `"outgoing"` — return types that the input depends on, transitively. The "what does this rely on?" use case.

**Input expansion:**

- If `node_id` is a type, the tool returns types transitively connected to that type.
- If `node_id` is a package, module, or other subtree, the tool expands to all types contained recursively in that subtree, and returns the union of affected types across all those sources.

This expansion is internal — the LLM doesn't need to enumerate types itself. The tool handles the natural question "what's affected by changes to this package?" directly.

**Response shape for non-type input:**

Each affected type has:

- `node` — NodeRef of the affected type
- `distance` — the *minimum* coupling distance from any type in the input subtree
- `source_count` — how many types in the input subtree reach this affected type (prioritization signal — higher means more coupled to the input subtree)
- `via` — one representative path from a source type to the affected type, with edge weights

For type input, `source_count` is always 1, and `distance`/`via` reflect the single source.

**Other details:**

The `kind_filter` filters the *results* (returned affected types), not the inputs. Useful for narrowing to "only classes" or similar.

`max_depth` caps traversal depth. Unbounded by default, but the LLM can set this to limit the result to "things within N steps."

If nothing is affected, returns an empty list with `total: 0`. Not an error — "no dependencies" is a meaningful answer.

**Use cases:**

- **"What breaks if I change this class?"** → `affected_by(class_id)` (direction=incoming, default)
- **"What breaks if I refactor this package?"** → `affected_by(package_id)` (same; tool expands internally)
- **"What does this class transitively rely on?"** → `affected_by(class_id, direction="outgoing")`
- **"Which modules are affected by changes to this module?"** → `affected_by(module_id)` returns affected types; the LLM can group results by their parent modules to roll up. Or use `affected_by(module_id)` with `kind_filter=["types"]` and post-process.

#### `find_dependency_path`

```
find_dependency_path(
    from_id: long,                              // required: module, package, or type
    to_id: long,                                // required: module, package, or type
    max_paths: int = 5,
    max_length: int?                            // optional cap on path length
)
```

Returns paths in the type-level dependency graph from the source to the target. Each path is a sequence of type NodeRefs connected by type-level edges, with weight per edge.

**Input expansion:**

If either `from_id` or `to_id` is a higher-level subtree (package, module), the tool expands to the contained types. It finds the shortest distinct paths between *any* type in the source subtree and *any* type in the target subtree.

Each returned path has explicit endpoints — specific types in each subtree — so the LLM knows which concrete types are involved, even when the question was asked at the subtree level.

**Result ordering:**

Paths are sorted by length (shortest first), then by total edge weight (heaviest first within the same length). The LLM gets the most direct connections first.

**Other details:**

`max_paths` limits how many distinct paths are returned. Most use cases want the few shortest paths, not an exhaustive enumeration.

`max_length` is an optional cap on path length. Useful when the LLM wants to find only short connections ("are these two types within 3 steps of each other?").

If no path exists, returns `paths: []`. Not an error — "no dependency relationship" is a meaningful answer.

**Use cases:**

- **"Why does class A end up depending on class B?"** → `find_dependency_path(A_id, B_id)`
- **"Is there any dependency from the API layer to the database layer?"** → `find_dependency_path(api_module_id, db_module_id)` (tool expands internally and returns paths between concrete types)
- **"Show me the shortest connections between these two packages."** → `find_dependency_path(pkg_A_id, pkg_B_id, max_paths=10)`

**Composition pattern:** Both reachability tools work at the type level but accept subtree inputs. To get a subtree-level result (e.g., "which modules are affected by this module?"), call the type-level tool and post-process — group results by their parent modules. This composition is trivial and keeps the API honest about where reachability actually lives.

### Entity-detail tools

These tools answer "tell me everything about this specific entity." They're single-ID-in, rich-response-out. The pattern is uniform across kinds: every leaf-ish entity (type, method, field) has a dedicated detail tool that returns the entity's full structural picture in one call.

#### `type_details`

```
type_details(type_id: long)
```

Full structural details for a single type: modifiers, inheritance (extends and implements), declared methods and fields, inner types, annotations, type parameters (for generic types), source location.

**Response includes:**

- `node` — the full NodeRef for the type (enriched, with all the kind-aware metadata)
- `parent_type` — the superclass as a NodeRef, or `null` for interfaces, root classes, and types without a meaningful superclass
- `interfaces` — list of NodeRefs for implemented interfaces (empty list if none)
- `methods` — list of method NodeRefs with per-method enriched metadata (modifiers, parameter_count, throws_count, annotation_count, is_constructor). Capped at 50; if more exist, `methods_total` and `methods_truncated: true` indicate the truncation, and the LLM can use `list_children` or `list_methods` for the full list.
- `fields` — list of field NodeRefs with per-field enriched metadata (modifiers, field_type_name, annotation_count, is_constant). Same capping behavior as methods.
- `inner_types` — list of NodeRefs for nested classes/interfaces/enums declared inside this type
- `annotations` — list of annotation wrappers (`{type: NodeRef}` per annotation, matching `method_details` and `field_details`)
- `type_parameters` — for generic types, the declared type parameters as NodeRefs or names (empty list for non-generic types)
- `location` — file path and line number of the type's declaration

**What it doesn't include:**

`type_details` covers the type's *own structure* — its inheritance, members, annotations, location. It deliberately does *not* include:

- Who depends on this type (use `aggregated_dependencies` or `incoming_dependencies`)
- What this type depends on (use `aggregated_dependencies` or `outgoing_dependencies`)
- Subtypes that extend this class (use `affected_by` with `direction: "incoming"` to find them)
- Implementations of this interface (same)

The principle parallels `method_details` and `field_details`: entity-detail tools describe what's *inside* the entity, not the entity's *external relationships*. External relationships have their own dedicated tools.

No `detail_level` parameter — this tool returns the type's structural detail, which is its own zoom level.

#### `method_details`

```
method_details(method_id: long)
```

Full structural details for a single method: modifiers, return type, parameters with their types and annotations, declared exceptions, method-level annotations, override target, source location.

No `detail_level` parameter — this tool is inherently at the detail level. A "type-level method details" would be a contradiction.

#### `field_details`

```
field_details(field_id: long)
```

Full structural details for a single field: modifiers, type, annotations, read/write access digest (with sample reader/writer methods and a `by_declaring_type` summary), source location.

No `detail_level` parameter — same reasoning as `method_details`.

## The complete tool list, after this proposal

**Discovery and navigation (4 tools):**
- `find_node`
- `graph_overview` (renamed from `describe_graph`)
- `list_children` (kind-aware metadata; returns methods and fields with rich metadata when called on a type)
- `list_descendants` (kind-aware metadata, same shape as `list_children`)

**Aggregated dependency analysis (2 tools):**
- `aggregated_dependencies` (consolidates outgoing/incoming/between into one)
- `pairwise_dependencies` (for matrix-shaped DSM analysis)

**Type-level and detail-level dependency evidence (2 tools):**
- `outgoing_dependencies` (with `detail_level` parameter)
- `incoming_dependencies` (with `detail_level` parameter)

**Reachability and impact (2 tools):**
- `affected_by`
- `find_dependency_path`

**Entity detail (3 tools):**
- `type_details`
- `method_details`
- `field_details`

**Total: 13 tools** — down from roughly 18 in the previous catalog.

## Tools eliminated in this proposal

For clarity, here's what the proposal removes compared to the current state:

**`outgoing_core_dependencies`** — replaced by `outgoing_dependencies(..., detail_level="type")`.

**`incoming_core_dependencies`** — replaced by `incoming_dependencies(..., detail_level="type")`.

**`detail_dependencies`** — replaced by `outgoing_dependencies(..., detail_level="detail")` and `incoming_dependencies(..., detail_level="detail")`. The directional split makes the API more symmetric and the question shape clearer.

**`aggregated_outgoing`** — folded into `aggregated_dependencies(source_ids=[from], target_ids=[...])`.

**`aggregated_incoming`** — folded into `aggregated_dependencies(source_ids=[...], target_ids=[to])`.

**`dependency_between`** — folded into `aggregated_dependencies(source_ids=[A], target_ids=[B])`. The single-target case.

**`describe_graph`** — renamed to `graph_overview`.

**`list_methods`** — folded into `list_children(type_id)`. When the input is a type, `list_children` now returns methods with per-method metadata (modifiers, parameter_count, throws_count, annotation_count, is_constructor) directly. The `name_pattern`, `modifier_filter`, and `include_inherited` parameters moved to `list_children`.

**`list_fields`** — folded into `list_children(type_id)`. When the input is a type, `list_children` now returns fields with per-field metadata (modifiers, field_type_name, annotation_count, is_constant) directly. Same parameter migration as `list_methods`.

The five old aggregated tools become two new ones. The four old type/detail dependency tools become two with a parameter. The two old member-listing tools fold into `list_children`. The total reduction is meaningful: the LLM has fewer similar-sounding tools to choose between, and `list_children` becomes a single uniform "what's inside this thing?" tool with kind-aware responses.

## How the LLM picks among them

The decision tree, roughly:

1. **"I have a name; what's the ID?"** → `find_node`

2. **"I'm new here; what's the codebase?"** → `graph_overview`

3. **"What's inside this thing?"** → `list_children` (one level) or `list_descendants` (all levels). The response includes kind-appropriate metadata when the children are methods or fields, so a single `list_children(type_id)` call returns the type's methods and fields with enough metadata to investigate them.

4. **"What dependencies exist between these sources and these targets?"** → `aggregated_dependencies(source_ids, target_ids)`

5. **"What's the dependency matrix among these N subtrees?"** → `pairwise_dependencies`

6. **"What specifically is between subtree A and subtree B?"** → `outgoing_dependencies` or `incoming_dependencies`
   - For type-level edges: `detail_level: "type"` (the default)
   - For method/field-level edges: `detail_level: "detail"`, optionally filtered by `relationship`

7. **"What breaks if I change this?"** → `affected_by`

8. **"Why does A end up depending on B?"** → `find_dependency_path`

9. **"Tell me everything about this specific type, method, or field"** → `type_details`, `method_details`, or `field_details` respectively

Each branch leads to one tool (or one tool plus a parameter). The LLM picks based on the question shape, not by remembering which of several similar tool names applies.

## A typical workflow

To make the design concrete, here's a representative workflow showing how the tools compose:

The user asks: *"I'm thinking of refactoring the cluster coordination module. What would I need to be careful about?"*

1. **Orient**: `graph_overview()` to learn the codebase shape.
2. **Find the target**: `find_node("cluster.coordination")` → returns the module's node ID.
3. **Understand blast radius**: `affected_by(coordination_id)` → expands the module to its contained types internally, returns the affected types across the codebase with `source_count` indicating how heavily each is coupled to the module. The LLM groups results by parent module to see which modules contain the most affected types.
4. **Drill into direct couplings**: `list_children(root_id)` to get the top-level modules, then `aggregated_dependencies(source_ids=[coordination_id], target_ids=[other_modules])` to see how the coordination module depends on each other module.
5. **Investigate the strongest coupling**: pick the heaviest edge from step 4, say to the `transport` module. Call `outgoing_dependencies(from_id=coordination_id, to_id=transport_id, detail_level="type")` to see which classes are involved.
6. **Get method/field evidence**: for a critical type pair, `outgoing_dependencies(from_id=specific_coordination_class, to_id=specific_transport_class, detail_level="detail")` to see actual call sites.
7. **Read source code**: use the locations from step 6 to read the implementation via Claude's file tools.

Five steps gets from "I want to refactor X" to "here are the specific lines of code I should look at." Each step uses the right tool for the question; the tools compose naturally because they share the NodeRef convention.

## What `graph_overview` should communicate

To make this whole system navigable, `graph_overview` should return — in addition to its current content — a brief description of the zoom-level model. Something like:

```json
{
  "stats": { ... },
  "kinds": { ... },
  "hierarchy": { ... },
  "relationships": [ ... ],
  "model": {
    "aggregation": "Aggregation is pairwise. Given any two subtrees, aggregated_dependencies computes one aggregated edge between them. Provide source_ids and target_ids as sets; the result includes one edge per (source, target) pair that has a dependency.",
    "levels": [
      {
        "name": "aggregated",
        "description": "Pairwise rollup of dependencies between subtrees, with weight and kinds",
        "tools": ["aggregated_dependencies", "pairwise_dependencies"]
      },
      {
        "name": "type",
        "description": "Type-to-type edges between two specific subtrees, fast (in-memory)",
        "tools": ["outgoing_dependencies", "incoming_dependencies"],
        "parameter": "detail_level=\"type\" (default)"
      },
      {
        "name": "detail",
        "description": "Method/field-level edges between two specific subtrees, queried from Neo4j",
        "tools": ["outgoing_dependencies", "incoming_dependencies"],
        "parameter": "detail_level=\"detail\""
      }
    ]
  }
}
```

The LLM reads this once at session start and learns the model. From then on, the tool descriptions and parameter names reinforce it.

## Migration considerations

For a pre-launch project, the migration cost is minimal — find-and-replace across the design documents, update the implementation, ship. The new API is the only API. Do it now.

## Summary of the change

The current API has tools that look similar but operate at different zoom levels (the core_dependencies vs. detail_dependencies confusion), tools that represent the same operation with different parameter shapes (aggregated_outgoing vs. aggregated_incoming vs. dependency_between), and a fuzzy "scope" concept that doesn't match what aggregation actually is.

This proposal:

- Unifies the lexical hierarchy in the user-facing model (modules through methods/fields)
- Frames the architecture as *full hierarchy in memory, type-level dependencies in memory, detail-level evidence in Neo4j on demand* — a cleaner split than the previous "type-level in memory, methods/fields in Neo4j" framing
- Defines two NodeRef shapes (enriched for browse tools, minimal for structural references) backed by the in-memory model, so browse responses can be rich without architectural cost
- Consolidates the aggregated dependency tools into `aggregated_dependencies` + `pairwise_dependencies`, replacing the previous five-tool group
- Consolidates the evidence tools into directional pairs (`outgoing_dependencies` / `incoming_dependencies`) with a `detail_level` parameter, replacing the previous four-tool group
- Folds `list_methods` and `list_fields` into `list_children`, which now returns kind-aware enriched NodeRefs across all input kinds
- Keeps specialized tools where they add value (the entity-detail tools for full per-method and per-field information)
- Makes `graph_overview` the place where the LLM learns the model

The result is a smaller, more coherent API where each tool's role is obvious from its name and parameters, where aggregation is honest about its pairwise nature, where the in-memory model serves the bulk of navigation and reasoning workflows, and where the LLM picks correctly based on question shape rather than memorizing which of several similar tools applies.
