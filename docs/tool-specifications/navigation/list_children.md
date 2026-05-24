# `list_children`

**Category:** Discovery and navigation
**Result-size class:** Input-bounded (no pagination needed)

## Purpose

Returns the immediate direct children of a node — one level deep only. This is the shallow exploration tool: "what's directly inside this thing?"

Each child is returned as an **enriched NodeRef** with kind-appropriate metadata, so the LLM can scan a module's packages with descendant counts, a package's types with method counts, or a class's methods with parameter counts — all in one call.

The children returned depend on the input node's kind:

- On a **module**: returns packages (and types declared directly in the module's default package)
- On a **package**: returns sub-packages and types
- On a **type**: returns methods and fields

For multi-level traversal, use `list_descendants`. For full detail on a specific method or field (parameter types, annotations, override target), use `method_details` or `field_details` after identifying the entity of interest from `list_children`.

## Signature

```
list_children(
    node_id: long,                       // required
    kind_filter: string[]?,              // optional
    name_pattern: string?,               // optional substring match on names
    modifier_filter: string[]?,          // optional, only meaningful for methods/fields
    limit: int = 200                     // optional
)
```

### Parameters

**`node_id`** (long, required)
The node whose direct children to return. Accepts any node kind in the hierarchy.

**`kind_filter`** (string[], optional)
Restricts results to specific kinds. Accepts specific kind values (`"java.class"`, `"java.method"`) and group aliases (`"types"`, `"members"`, `"packages"`). Mixing specific kinds and aliases is allowed: `["types", "java.method"]` returns all types and all methods.

When omitted, all children are returned regardless of kind.

**`name_pattern`** (string, optional)
Case-insensitive substring match against child names. Useful for finding members by partial name on a type with many methods.

Example: `name_pattern: "get"` on a type returns `getState`, `getTimeout`, `getClusterName`, etc.

**`modifier_filter`** (string[], optional)
Restricts to children whose modifiers include *all* listed values (AND logic). Only meaningful when the children are methods or fields; passing it when the input is a module or package is silently ignored.

Examples:
- `["public"]` — only public members
- `["static", "final"]` — only constants (effectively-immutable members)
- `["private"]` — only private members

**`limit`** (int, optional, default 200)
Caps the number of children returned. When the actual child count exceeds the limit, the response is honestly truncated: `summary.total` reports the true count and `summary.truncated` is `true`.

## Response shape

```json
{
  "parent": {
    "id": 47291,
    "name": "ClusterService",
    "qualified_name": "org.elasticsearch.cluster.ClusterService",
    "kind": "java.class",
    "parent_id": 12503,
    "parent_kind": "java.package"
  },
  "results": [
    {
      "id": 47305,
      "name": "applyState",
      "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState",
      "kind": "java.method",
      "parent_id": 47291,
      "parent_kind": "java.class",
      "modifiers": ["private"],
      "parameter_count": 2,
      "throws_count": 0,
      "annotation_count": 0,
      "is_constructor": false
    },
    {
      "id": 47310,
      "name": "clusterName",
      "qualified_name": "org.elasticsearch.cluster.ClusterService.clusterName",
      "kind": "java.field",
      "parent_id": 47291,
      "parent_kind": "java.class",
      "modifiers": ["private", "final"],
      "field_type_name": "org.elasticsearch.cluster.ClusterName",
      "annotation_count": 0,
      "is_constant": false
    }
  ],
  "summary": {
    "total": 32,
    "returned": 32,
    "truncated": false,
    "by_kind": {
      "java.method": 28,
      "java.field": 4
    }
  }
}
```

### Response fields

**`parent`** — enriched NodeRef of the input node. Gives the LLM confirmation of what it asked about and context for interpreting the children.

**`results`** — list of enriched NodeRefs for the matching children. Each carries kind-appropriate metadata:

- **Package children** (of a module): `child_count`, `descendant_type_count`, `direct_type_count`
- **Type children** (of a package): `modifiers`, `member_count`, `method_count`, `field_count`, `annotation_count`, `interface_count`, `is_abstract`, `is_generic`, `parent_type`
- **Method children** (of a type): `modifiers`, `parameter_count`, `throws_count`, `annotation_count`, `is_constructor`
- **Field children** (of a type): `modifiers`, `field_type_name`, `annotation_count`, `is_constant`

**`summary`** — structural digest of the result:
- `total` — true count of all matching children (before truncation)
- `returned` — number of items in this response
- `truncated` — `true` if `total > returned`
- `by_kind` — count of matching children per kind

The `by_kind` summary is computed over *all* matching children, not just the returned page. This gives the LLM an honest picture even when results are truncated.

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

**Invalid `kind_filter`:** same structured error as `find_node`, listing valid kinds and aliases.

**Leaf node (method or field):** returns an empty result with `total: 0`. Not an error — methods and fields have no children, and that's a valid answer.

## Architecture

`list_children` operates entirely on the **in-memory hierarchical model**. The full containment tree is in memory with parent/child references; retrieving children is a direct traversal of the in-memory node's child list. Enriched metadata for each child comes from the in-memory model (via lazy property materialization if needed).

No Neo4j queries at call time. Response assembly is microseconds for typical results.

### Filtering

All filtering (`kind_filter`, `name_pattern`, `modifier_filter`) is applied in the tool layer after retrieving the in-memory child list. The filters are evaluated in sequence:

1. Kind filter (if present) — drop children whose kind doesn't match
2. Name pattern (if present) — drop children whose name doesn't contain the substring
3. Modifier filter (if present) — drop children lacking any of the required modifiers

The summary counts (`total`, `by_kind`) reflect the post-filter, pre-truncation state — they count all children that passed the filters, not all children of the node.

## Use cases

- **"What's in this module?"** — `list_children(module_id)` returns packages with descendant counts
- **"Which classes in this package look framework-managed?"** — `list_children(package_id, kind_filter: ["types"])` and inspect `annotation_count` per type
- **"What does this class declare?"** — `list_children(type_id)` returns methods and fields with metadata
- **"What constants does this class expose?"** — `list_children(type_id, modifier_filter: ["static", "final"])`
- **"Show me only the public methods"** — `list_children(type_id, kind_filter: ["java.method"], modifier_filter: ["public"])`
- **"Find methods containing 'handle' in this class"** — `list_children(type_id, name_pattern: "handle", kind_filter: ["java.method"])`
## LLM tool description

The `@Tool` description should communicate:

1. Returns direct children only — one level deep
2. Do NOT use recursively to walk the tree; use `list_descendants` for multi-level traversal
3. Supports filtering by kind, name pattern, and modifiers
4. Each child carries kind-appropriate metadata (counts, modifiers, flags) — enough to decide what to investigate further
5. For full detail on a specific method or field, use `method_details` or `field_details` after identifying it here
