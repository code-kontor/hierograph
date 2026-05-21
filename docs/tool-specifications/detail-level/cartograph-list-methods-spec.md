# Cartograph: `list_methods` Specification

This is the complete specification for `list_methods`, one of the detail-level tools introduced in v0.2. It serves the "what's inside this type?" question — enumerating the methods declared on a single type with enough metadata for the LLM to decide whether to drill deeper into any of them.

This specification builds on conventions established in `mcp-tools.md` (NodeRef, node IDs, limits, JSON precision) and the architectural framing in `detail-level-tools.md` (the hierarchical/detail/code layer model). Read those first if anything below seems to assume context that isn't here.

## Purpose

`list_methods` returns the methods declared on a specific type, along with lightweight per-method metadata. It serves the *composition* workflow: when the LLM has identified a type of interest (typically via `find_node` or a hierarchical drill-down) and wants to understand what methods it contains before deciding which to inspect further.

This is distinct from `detail_dependencies`, which answers "what's the method-level evidence for this aggregated edge?" `list_methods` doesn't take a target subtree — it just enumerates what's *in* the type. The LLM uses it to orient inside a type, then either calls `method_details` for one specific method or `detail_dependencies` to investigate the type's relationships at the method level.

## Parameters

```
list_methods(
    type_id: long,             // required
    name_pattern: string?,     // optional substring match against method name
    modifier_filter: string[]?, // optional: e.g. ["public"], ["static"], ["public", "static"]
    include_inherited: bool = false,
    limit: int = 50
)
```

### `type_id` (required)

The node ID of the type whose methods should be enumerated. Must be a type-kind node (`java.class`, `java.interface`, `java.enum`, `java.annotation`). Other kinds return an error.

If the node ID is unknown or stale, the tool returns a structured error rather than empty results — this prevents the LLM from misinterpreting "no methods found" as "this type has no methods" when the underlying issue is a bad ID.

### `name_pattern` (optional)

Case-insensitive substring match against the method name. Matches the conventions in `find_descendants` semantics: substring is the most natural form for natural-language-derived queries, glob and regex are deferred.

Useful for "show me the `init` methods" or "list any methods named like `handle`" without enumerating all methods. Does not match against parameters, return type, or qualified name — just the simple method name.

### `modifier_filter` (optional)

A list of Java modifiers, ANDed together. Each entry must be one of: `"public"`, `"protected"`, `"private"`, `"package-private"`, `"static"`, `"final"`, `"abstract"`, `"synchronized"`, `"native"`, `"default"` (for interface methods).

If supplied, only methods whose modifiers include *all* listed values are returned. Example: `modifier_filter: ["public", "static"]` returns only public-static methods.

`"package-private"` is the conventional name for methods with no explicit visibility modifier. Some jQAssistant schemas may encode this differently; the tool normalizes to the convention above.

### `include_inherited` (default `false`)

By default, only methods *declared on* the type are returned. Inherited methods (from superclasses or interfaces) are excluded.

When `true`, the response also includes methods inherited from ancestor types. Each inherited method's NodeRef points to its declaring type (so the `parent_id` reflects the declarer, not the requested type), and an additional field on the per-method object indicates the inheritance source. The `summary` block distinguishes declared and inherited counts.

The default is `false` because the common case is "what does *this* type itself define" — inherited methods inflate the result set and shift the LLM's attention away from the type the user actually asked about. Set to `true` when investigating overall API surface ("what can callers do with an instance of this type?").

### `limit` (default 50)

Maximum number of methods to return. Typical types have well under 50 methods; the default rarely truncates in practice. Server-side cap at 500.

When `total_matching` exceeds `limit`, the response truncates and sets `truncated: true` in the summary. The LLM sees the true total and can re-issue the call with a larger `limit` (up to the cap), or apply a tighter filter to narrow the result set.

## Response shape

The response references multiple nodes — the queried type, every method, and (for inherited methods) one or more ancestor declaring types. To keep the response compact, display fields live once in a top-level `nodes` map; every other reference is an ID. The `parent` field on each method entry is a per-context ID (it varies between the queried type and the declaring type for inherited methods), so it stays at the appearance site rather than being folded into `nodes`.

```json
{
  "nodes": {
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" },
    "12": { "name": "Object", "qualified_name": "java.lang.Object", "kind": "java.class" },
    "91204": { "name": "applyState", "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState", "kind": "java.method" },
    "91205": { "name": "toString", "qualified_name": "java.lang.Object.toString", "kind": "java.method" }
  },
  "type": 47291,
  "methods": [
    {
      "node": 91204,
      "parent": 47291,
      "modifiers": ["public", "synchronized"],
      "return_type_name": "void",
      "parameter_count": 2,
      "throws_count": 1,
      "annotation_count": 0,
      "is_constructor": false,
      "is_inherited": false
    },
    {
      "node": 91205,
      "parent": 12,
      "modifiers": ["public"],
      "return_type_name": "java.lang.String",
      "parameter_count": 0,
      "throws_count": 0,
      "annotation_count": 0,
      "is_constructor": false,
      "is_inherited": true
    }
  ],
  "summary": {
    "total_matching": 23,
    "returned": 23,
    "truncated": false,
    "declared_count": 21,
    "inherited_count": 2,
    "by_visibility": {
      "public": 14,
      "protected": 2,
      "private": 5,
      "package-private": 0
    },
    "constructors": 2,
    "abstract_methods": 0
  }
}
```

### Top-level fields

**`nodes`** — Map from stringified node ID to display fields (`name`, `qualified_name`, `kind`). Contains exactly: the queried type, every method in the `methods` array, and every distinct declaring type referenced by an inherited method's `parent`. No other entries.

**`type`** — Node ID of the queried type. Display fields are in `nodes[type]`.

### Per-method fields

**`node`** — Node ID of the method. Resolve via `nodes[node]`.

**`parent`** — Node ID of the method's declaring type. Equals `type` for declared methods; for inherited methods, points to the ancestor type that actually declares the method. Always present. This is the slim-encoded form of what would otherwise be `parent_id` on a full NodeRef.

**`modifiers`** — List of Java modifier keywords, in canonical order: visibility first (`public`/`protected`/`private`/`package-private`), then other modifiers (`static`, `final`, `abstract`, `synchronized`, `native`, `default`).

**`return_type_name`** — Human-readable return type as a string. For primitives, the keyword (`"void"`, `"int"`, `"boolean"`). For object types, the simple name when unambiguous, qualified name when needed. For generics, the erased name with type parameters in angle brackets when present. This is for at-a-glance readability — the LLM uses `method_details` to get a structured NodeRef for the return type.

**`parameter_count`** — Number of declared parameters. Helps the LLM judge method shape ("no-arg" vs. "many-arg") without enumerating.

**`throws_count`** — Number of declared checked exceptions. A non-zero value flags methods worth investigating for error handling questions.

**`annotation_count`** — Number of annotations on the method itself (not on parameters). A non-zero value flags methods worth investigating for framework-wiring questions (Spring `@Transactional`, JUnit `@Test`, etc.).

**`is_constructor`** — Boolean. Constructors are technically methods in the bytecode model but are usually treated differently in reasoning.

**`is_inherited`** — Boolean. `true` only when `include_inherited` was set and this method is inherited from an ancestor. Equivalent to `parent != type` — surfaced as its own field so the LLM doesn't need to compare IDs.

### Summary fields

**`total_matching`** — Count of methods matching the filter set, regardless of `limit`. The truth-telling field: lets the LLM see how many methods are *really* there.

**`returned`** — Count of methods actually in the response array.

**`truncated`** — Boolean. `true` if `total_matching > returned`.

**`declared_count`** / **`inherited_count`** — Decomposition of `total_matching` by inheritance source. Always present; when `include_inherited` is `false`, `inherited_count` is `0`.

**`by_visibility`** — Distribution of methods across visibility modifiers. Useful for "what's the API surface?" questions.

**`constructors`** — Number of constructors among the returned set. Often interesting on its own (one constructor vs. many is a structural signal).

**`abstract_methods`** — Number of abstract methods. On interfaces, this approximates "method surface to implement."

The summary fields together let the LLM understand the type's composition without inspecting every method individually. For a type with 100 methods, the summary is often more useful than the enumeration.

## Result ordering

Methods are returned in document order — the order they appear in the source file (or equivalently, the order jQAssistant records them). This is predictable and matches "how a developer would read the file."

Constructors appear in their natural source-file position rather than being grouped separately. This may be surprising; an alternative is to group constructors first. Document-order was chosen for consistency with `list_descendants` and `list_children`, both of which use source-file order.

If the LLM needs sorted results (by name, by visibility, etc.), it sorts post-hoc. Adding a `sort_by` parameter is deferred — usage data should drive whether it's worth the complexity.

## Filter combination semantics

When multiple filters are supplied, they're combined with AND:

- `name_pattern + modifier_filter` → methods matching both
- `name_pattern + include_inherited=true` → matching methods, including inherited ones
- `modifier_filter + include_inherited=true` → modifier-matching methods, including inherited ones

Within `modifier_filter`, the listed modifiers are also ANDed (a method must have all listed modifiers). This matches the conventions established for other Cartograph filter tools.

## Error cases

The tool returns a structured error (not an empty response) in these cases:

**`NODE_NOT_FOUND`** — `type_id` doesn't exist in the graph. Suggests the LLM should re-resolve via `find_node`.

**`WRONG_NODE_KIND`** — `type_id` exists but isn't a type-kind node (e.g., it's a method ID or a package ID). The error includes the actual kind for context.

**`INVALID_MODIFIER`** — `modifier_filter` contains a value outside the allowed set. The error includes the offending value and the allowed list.

Each error includes a human-readable message and a structured `code` field for programmatic handling.

## Performance characteristics

Unlike the hierarchical tools, `list_methods` queries Neo4j directly at request time rather than from the in-memory model. Expected behavior:

- **Typical types**: 10-30 methods. Response time well under 100ms.
- **Large types**: 100+ methods (rare; usually a code smell). Response time may approach 500ms.
- **Pathological types**: 1000+ methods (extremely rare, typically generated code). Response truncates at the limit; pagination available.

The underlying Cypher query is parameterized and pre-compiled, so Neo4j caches its plan. The bottleneck for very large result sets is JSON serialization, not the database query.

If response times become a problem in practice, the in-memory model could be extended to cache method lists per type — but this trades the simplicity of the current design for marginal gains on a tool that's rarely called in tight loops. Defer unless usage data demands it.

## Description for the tool registration

This is the text exposed to the LLM via MCP. It should establish the tool's purpose, when to use it vs. alternatives, and the structural pattern of the response.

> Return the methods declared on a type, with lightweight metadata for each. Use this when you have identified a type and want to understand its method-level composition — for example, *"what does `ClusterService` contain?"* or *"list the public methods of this class."*
>
> Response shape: a top-level `nodes` map (each referenced node listed once with `name`, `qualified_name`, `kind`) plus a `methods` list whose entries reference nodes by ID. Each method entry carries `node` (the method ID), `parent` (declaring-type ID), and metadata: parameter count, throws count, annotation count, plus modifier flags. The counts let you decide which methods are worth investigating further (high `annotation_count` suggests framework wiring; high `throws_count` suggests error-handling complexity). The `summary` block gives a structural overview (visibility distribution, constructor count, declared vs. inherited) that's often more useful than enumerating every method.
>
> Common parameter patterns:
>
> - Just `type_id`: enumerate all declared methods.
> - `type_id` + `modifier_filter: ["public"]`: list the public API.
> - `type_id` + `name_pattern: "init"`: find initialization-style methods.
> - `type_id` + `include_inherited: true`: see the full callable surface, including methods from ancestors.
>
> When to use this vs. neighboring tools:
>
> - For deep information about one specific method (parameters, return type as a NodeRef, throws, annotations, location), use `method_details`. `list_methods` returns lightweight summaries; `method_details` returns the full structural picture.
> - For "which methods call this one?" or "which methods throw this exception?", use `detail_dependencies` — that's the dependency-driven view rather than the composition view.
> - For fields rather than methods, use `list_fields` (same shape, different entity).

## Integration with the broader workflow

`list_methods` typically appears in workflows like these:

**Workflow 1: orient inside a type, then drill into one method.**

1. `find_node("ClusterService")` → type ID
2. `list_methods(type_id)` → see the methods, notice `applyState` has annotations
3. `method_details(apply_state_id)` → see what's annotated, return type, throws, parameters

**Workflow 2: find a specific method by name pattern.**

1. `find_node("OrderRepository")` → type ID
2. `list_methods(type_id, name_pattern: "save")` → narrow to save-related methods
3. `method_details(...)` for the matching ones

**Workflow 3: understand the public API surface of a type.**

1. `list_methods(type_id, modifier_filter: ["public"], include_inherited: true)` → see all callable methods
2. Use the `summary.by_visibility` and `summary.constructors` for a one-glance picture
3. Drill into specific methods as questions arise

**Workflow 4: investigate framework wiring.**

1. `list_methods(type_id)` → look at `annotation_count` per method
2. For methods with annotations, `method_details(method_id)` → see which annotations
3. Possibly `detail_dependencies(type_id, annotation_type_id)` → find all methods anywhere using a particular annotation

In each case, `list_methods` is the orientation step that follows hierarchical drilling and precedes deeper detail-level investigation. Designed to be cheap to call and rich in summary signal.

## Implementation notes

A few specifics worth flagging during implementation:

**Cypher query shape.** The underlying query matches the type by ID and finds its declared methods. With `include_inherited=true`, traversal includes superclass and interface chains. Pseudocode:

```cypher
// Base case (declared only)
MATCH (t:Type)-[:DECLARES]->(m:Method)
WHERE id(t) = $type_id
RETURN m, ...

// With include_inherited
MATCH (t:Type)-[:DECLARES|:EXTENDS|:IMPLEMENTS*]-(m:Method)
WHERE id(t) = $type_id
RETURN m, ..., (declarer of m) AS declared_by
```

The exact graph patterns depend on jQAssistant's Java schema; verify the relationship types match (`DECLARES`, etc.) before committing.

**Metadata extraction.** Modifiers, parameter count, throws count, and annotation count can all be derived in one query via subquery patterns. Avoid making separate queries per method — Neo4j is good at this when the query is structured well, but degrades fast if you N+1 it.

**NodeRef construction.** Don't call `AbstractGraphMcpTools.toNodeRefShort(HGNode)` for method entries; emit IDs only and add display fields to the `nodes` map. The `parent` field on each method entry should be the declaring type's ID, not always `type_id`. For declared methods these are equal; for inherited methods they differ. The `nodes` map contains exactly the queried type, every method, and every distinct declaring type referenced by an inherited method's `parent` — collect these IDs while assembling the response, then resolve display fields in one pass.

**Modifier normalization.** jQAssistant may store modifiers as a set of boolean properties (`isPublic`, `isStatic`, etc.) or as a string list. The tool's response normalizes to the canonical list-of-strings form regardless of the underlying representation.

**Testing checklist:**

- Type with no methods (interface with default methods only? `Object`? marker interface?)
- Type with one method
- Type with many methods (>50, triggers pagination)
- Inherited methods from `Object` (`toString`, `hashCode`, etc.) — common case for `include_inherited=true`
- Anonymous inner classes (do they appear? are their methods queryable?)
- Methods with generics, varargs, and overloaded names (same name, different signatures)
- Constructor handling (correctly flagged as `is_constructor: true`)
- Bad inputs: non-existent ID, package ID, method ID passed as `type_id`
