# `list_descendants`

**Category:** Discovery and navigation
**Result-size class:** Data-bounded (cursor-based pagination)

## Purpose

Returns all descendants of a node matching the specified filters, across the entire subtree in a single call. This is the right tool for any "show me all X in subtree Y" question.

Each descendant is returned as an **enriched NodeRef** with the same kind-appropriate metadata as `list_children`. The response includes a `summary` block with `by_kind` and `by_parent` digests that are often more useful than the raw list — they answer "what's there?" without requiring the LLM to enumerate.

This tool replaces what would otherwise be repeated `list_children` calls. If a question can be expressed as "all the X under Y," this is the tool — do not walk the tree node-by-node.

## Signature

```
list_descendants(
    node_id: long,                       // required
    kind_filter: string[]?,              // optional
    name_pattern: string?,               // optional substring filter on name
    modifier_filter: string[]?,          // optional, only meaningful for methods/fields
    limit: int = 150,                    // optional
    cursor: string?                      // for pagination on large results
)
```

### Parameters

**`node_id`** (long, required)
Root of the subtree to traverse. Accepts any node kind. The root itself is not included in the results — only its descendants.

**`kind_filter`** (string[], optional)
Restricts results to specific kinds. Accepts specific kind values (`"java.class"`, `"java.method"`) and group aliases (`"types"`, `"members"`, `"packages"`). Mixing is allowed.

The filter applies to *results*, not to *traversal*. The full subtree is always traversed; only matching nodes appear in the result list. This means `kind_filter: ["java.method"]` on a module traverses through packages and types to reach methods, even though packages and types are filtered out of the results.

When omitted, all descendants are returned regardless of kind.

**`name_pattern`** (string, optional)
Case-insensitive substring match against descendant names. Applied after kind filtering.

**`modifier_filter`** (string[], optional)
Restricts to descendants whose modifiers include *all* listed values (AND logic). Only meaningful for methods and fields; non-member descendants that don't have modifiers are silently excluded when this filter is active.

**`limit`** (int, optional, default 150)
Maximum number of items per page. The default of 150 is calibrated to produce responses comfortably under the 10K-token warning threshold (~37 KB at ~250 bytes per enriched NodeRef).

Server-side cap: 500. Requesting more than 500 is silently capped.

**`cursor`** (string, optional)
Opaque cursor from a previous response's `next_cursor`. When present, returns the next page. When absent, starts from the beginning. See the Pagination section below for details.

## Response shape

```json
{
  "root": {
    "id": 12503,
    "name": "org.elasticsearch.cluster",
    "qualified_name": "org.elasticsearch.cluster",
    "kind": "java.package",
    "parent_id": 1001,
    "parent_kind": "java.module"
  },
  "results": [
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
      "parent_type": { "id": 9981, "name": "AbstractLifecycleComponent", ... }
    }
  ],
  "summary": {
    "total": 487,
    "returned": 150,
    "truncated": true,
    "by_kind": {
      "java.class": 42,
      "java.interface": 12,
      "java.enum": 3,
      "java.method": 380,
      "java.field": 50
    },
    "by_parent": [
      {
        "parent": { "id": 12503, "name": "org.elasticsearch.cluster", "qualified_name": "org.elasticsearch.cluster", "kind": "java.package" },
        "match_count": 87
      },
      {
        "parent": { "id": 12510, "name": "org.elasticsearch.cluster.coordination", "qualified_name": "org.elasticsearch.cluster.coordination", "kind": "java.package" },
        "match_count": 64
      }
    ]
  },
  "next_cursor": "eyJ2IjoxLCJ0b29sIjoibGlzdF9kZX..."
}
```

### Response fields

**`root`** — enriched NodeRef of the input node. Confirms the subtree root.

**`results`** — list of enriched NodeRefs for the current page of matching descendants. Kind-appropriate metadata is identical to `list_children` results:

- **Module/Package descendants:** `child_count`, `descendant_type_count`, etc.
- **Type descendants:** `modifiers`, `member_count`, `method_count`, `field_count`, `annotation_count`, `interface_count`, `is_abstract`, `is_generic`, `parent_type`
- **Method descendants:** `modifiers`, `parameter_count`, `throws_count`, `annotation_count`, `is_constructor`
- **Field descendants:** `modifiers`, `field_type_name`, `annotation_count`, `is_constant`

**`summary`** — structural digest of the *full* result set (not just the current page):

- `total` — true count of all matching descendants across all pages
- `returned` — number of items in this page
- `truncated` — `true` if more pages exist
- `by_kind` — count of all matching descendants per kind (across all pages)
- `by_parent` — top parents by match count (across all pages), each with a minimal NodeRef. Ordered by `match_count` descending. Limited to the top 10 parents to keep the summary compact.

The summary is computed over the full result set on *every* request, including paginated follow-ups. It is always complete and consistent, regardless of which page is being returned.

**`next_cursor`** — present if and only if more results exist after this page. The LLM passes this as the `cursor` parameter on the next call to retrieve the next page. Absent (not `null`) when the last page is returned.

## Pagination

`list_descendants` is a data-bounded tool — the subtree size is determined by the codebase, not by the LLM's input. Cursor-based pagination prevents context-window overflow on large subtrees.

### Iteration order

Depth-first pre-order traversal of the hierarchy from the input node. Children at each level are visited in stable order: by qualified name (alphabetical). This produces a result sequence where a parent always appears before its children, and siblings appear in alphabetical order — a natural reading order for codebases.

The iteration order is deterministic: same data + same query parameters = same result sequence, regardless of when the query runs. This is what makes offset-based cursors valid.

### Cursor protocol

Cursors follow the standard Hierograph cursor protocol:

- Stateless — encoded as base64 JSON containing version, tool name, query hash, data hash, and offset
- Self-validating — wrong tool, changed parameters, or stale data produce clear structured errors with recovery paths
- The `limit` parameter is not covered by the query hash — different page sizes for different pages of the same query are allowed

### LLM pagination strategies

Two workflows apply:

1. **Paginating through** — the LLM needs exhaustive coverage. Loop: call without cursor, process results, call with `next_cursor`, repeat until `next_cursor` is absent.

2. **Narrowing the query** — the LLM sees `total: 2400` and realizes the result is too large. Instead of paginating through 16 pages, it reformulates with a tighter `kind_filter` or `name_pattern`. Ignore the cursor; issue a new query.

The `summary.by_kind` and `summary.by_parent` fields support the narrowing strategy: the LLM can see the distribution of results and decide how to filter without paginating.

## Input validation

**Unknown `node_id`:** returns a structured error:

```json
{
  "error": {
    "code": "NODE_NOT_FOUND",
    "message": "No node with id 99999 exists in the graph.",
    "recovery": "Use find_node to look up the correct node ID."
  }
}
```

**Invalid `kind_filter`:** structured error listing valid kinds and aliases.

**Invalid cursor:** structured error per the cursor protocol (see pagination specification). Possible codes: `INVALID_CURSOR_FORMAT`, `STALE_CURSOR_VERSION`, `WRONG_TOOL_CURSOR`, `STALE_CURSOR_QUERY`, `STALE_CURSOR_DATA`.

**Leaf node (method or field):** returns empty results with `total: 0`. Not an error.

## Architecture

`list_descendants` operates entirely on the **in-memory hierarchical model**. The full containment tree is in memory; the depth-first traversal walks in-memory child lists. Enriched metadata comes from the in-memory model via lazy property materialization.

No Neo4j queries at call time. The traversal and filtering are pure in-memory operations.

### Implementation approach

The tool computes the *full* filtered result set on every call (including paginated follow-ups), then slices for the requested page. This is necessary because:

- The `summary` must reflect the complete result set, not just the current page
- The traversal is fast (microseconds to low milliseconds on in-memory data)
- The alternative (maintaining server-side traversal state) would break the stateless cursor model

For the sizes Hierograph deals with (tens of thousands of descendants at worst, typically hundreds), full-traversal-then-slice is efficient and dramatically simpler than incremental traversal with continuation state.

### Filtering pipeline

Filters are applied during traversal but do not affect traversal itself:

1. **Traverse** — depth-first pre-order through all children of the root
2. **Kind filter** — skip descendants whose kind doesn't match
3. **Name pattern** — skip descendants whose name doesn't contain the substring
4. **Modifier filter** — skip descendants lacking required modifiers
5. **Collect** — all passing descendants are counted for the summary; the first `offset + limit` are retained for page extraction

## Use cases

- **"List all types in this module"** — `list_descendants(module_id, kind_filter: ["types"])`
- **"Find every class in this package"** — `list_descendants(package_id, kind_filter: ["java.class"])`
- **"Show all methods in this module"** — `list_descendants(module_id, kind_filter: ["java.method"])`
- **"Find all abstract classes"** — `list_descendants(module_id, kind_filter: ["java.class"], modifier_filter: ["abstract"])`
- **"Which nodes match 'Handler' in this subtree?"** — `list_descendants(root_id, name_pattern: "Handler")`
- **"How many types are in each package?"** — `list_descendants(module_id, kind_filter: ["types"])` and read `summary.by_parent`

## LLM tool description

The `@Tool` description should communicate:

1. Returns descendants across the entire subtree in a single call — this replaces recursive `list_children` usage
2. Supports filtering by kind, name pattern, and modifiers
3. The `summary` fields (`by_kind`, `by_parent`) often answer the question without needing to enumerate individual results
4. Results are paginated when large — use `next_cursor` to retrieve more, or narrow the query with tighter filters
5. If you find yourself calling `list_children` more than once or twice to walk a tree, use this tool instead
