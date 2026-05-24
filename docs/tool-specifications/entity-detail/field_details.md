# `field_details`

**Category:** Entity detail
**Result-size class:** Input-bounded (single entity, no pagination needed)

## Purpose

Returns the full structural details for a single field: modifiers, type, annotations, read/write access digest, and source location.

The read/write access digest is a distinguishing feature: it tells the LLM which methods read or write this field, grouped by declaring type, with a sample of method IDs — enough to understand the field's usage pattern without exhaustive enumeration.

This is the deepest zoom level for a field — after the LLM has identified a specific field of interest via `list_children`, `list_descendants`, or detail-level dependency results.

## Signature

```
field_details(field_id: long)
```

### Parameters

**`field_id`** (long, required)
The node ID of the field to inspect. Must be a field-kind node (`java.field`). Typically obtained from `list_children` on a type, `list_descendants` with `kind_filter: ["java.field"]`, or from detail-level dependency results.

## Response shape

Uses **slim payload encoding** — the read/write access digests reference multiple methods and their declaring types, so deduplication applies. The field itself, its declaring type, its field type, annotation types, and all accessor methods and their declaring types are registered in the `nodes` map.

```json
{
  "nodes": {
    "47310": { "name": "clusterName", "qualified_name": "org.elasticsearch.cluster.ClusterService.clusterName", "kind": "java.field" },
    "47291": { "name": "ClusterService", "qualified_name": "org.elasticsearch.cluster.ClusterService", "kind": "java.class" },
    "51001": { "name": "ClusterName", "qualified_name": "org.elasticsearch.cluster.ClusterName", "kind": "java.class" },
    "47305": { "name": "getClusterName", "qualified_name": "org.elasticsearch.cluster.ClusterService.getClusterName", "kind": "java.method" },
    "48102": { "name": "ClusterStateObserver", "qualified_name": "org.elasticsearch.cluster.ClusterStateObserver", "kind": "java.class" },
    "48110": { "name": "observe", "qualified_name": "org.elasticsearch.cluster.ClusterStateObserver.observe", "kind": "java.method" }
  },
  "field": 47310,
  "declaring_type": 47291,
  "modifiers": ["private", "final"],
  "is_constant": false,
  "type": 51001,
  "type_name": "org.elasticsearch.cluster.ClusterName",
  "annotations": [],
  "read_access": {
    "method_count": 3,
    "methods_sample": [47305, 48110],
    "sample_truncated": false,
    "by_declaring_type": [
      { "type": 47291, "count": 2 },
      { "type": 48102, "count": 1 }
    ]
  },
  "write_access": {
    "method_count": 1,
    "methods_sample": [47320],
    "sample_truncated": false,
    "by_declaring_type": [
      { "type": 47291, "count": 1 }
    ]
  },
  "location": {
    "line_number": 42
  }
}
```

### Response fields

**`field`** — field node ID (references the `nodes` map).

**`declaring_type`** — declaring type node ID (references the `nodes` map).

**`modifiers`** — list of Java modifier keywords (`public`, `private`, `static`, `final`, `transient`, `volatile`).

**`is_constant`** — `true` if both `static` and `final`.

**`type`** — field type node ID (references the `nodes` map). `null` for primitive field types.

**`type_name`** — always-present string: qualified name for reference types, keyword for primitives (e.g., `"int"`, `"boolean"`). The LLM can always read this field regardless of whether `type` is null.

**`annotations`** — list of field-level annotations, each as `{ type: ID }` referencing the `nodes` map. Empty if no annotations.

**`read_access`** — digest of methods that read this field:
- `method_count` — total number of reader methods across the codebase
- `methods_sample` — up to 10 method IDs (resolve via `nodes` map), sorted by qualified name for determinism
- `sample_truncated` — `true` if more readers exist than the sample shows
- `by_declaring_type` — list of `{ type: ID, count: N }`, sorted descending by count, capped at 10 entries. If more declaring types contributed than fit, an `others_count` field indicates how many were omitted.

**`write_access`** — same structure as `read_access`, for methods that write this field.

**`location`** — source location with `line_number`. `null` if unavailable.

### Primitive field types

When the field type is a primitive (`int`, `boolean`, etc.):
- `type` is `null`
- `type_name` contains the primitive keyword
- No entry in `nodes` for the type

The LLM should read `type_name` for display and not attempt to use `null` as input to other tools.

### Access digest design rationale

The read/write digests give the LLM the *structural story* of field usage without exhaustive enumeration:

- `method_count` tells the scale (is this field read by 2 methods or 200?)
- `by_declaring_type` tells the distribution (is access concentrated in the declaring class or spread across the codebase?)
- `methods_sample` gives concrete IDs for follow-up investigation

For fields with many accessors (loggers, common constants), the digest is far more useful than a flat list. For exhaustive enumeration, the LLM can use `incoming_dependencies(from_id: field_id, to_id: scope, detail_level: "detail", relationship: "reads_field")`.

## Input validation

**Wrong node kind.** If `field_id` refers to a non-field node:

```json
{
  "error": {
    "code": "WRONG_NODE_KIND",
    "message": "Node 47291 is a 'java.class', not a field. field_details requires a field-kind node.",
    "actual_kind": "java.class",
    "recovery": "To see the fields of this type, use list_children(node_id: 47291, kind_filter: ['java.field'])."
  }
}
```

**Unknown node ID.** Returns `NODE_NOT_FOUND` error with recovery pointing to `find_node`.

## Architecture

`field_details` queries **Neo4j via the provider layer**, same as `method_details`. The structural detail (field type, annotations, read/write access with declaring types) requires joining across multiple relationship types.

The query is issued through `DetailDependencyProvider.fieldDetailsQuery(fieldId)`. The provider encapsulates the Cypher; the tool layer receives domain-typed results and assembles the response with slim encoding.

The read/write access digests require aggregation across potentially many accessor methods. The provider query collects all readers/writers with their declaring types; the tool layer samples, groups by declaring type, and caps at 10 entries each. This server-side aggregation avoids the need for the LLM to paginate through accessor lists.

## Use cases

- **"Tell me everything about this field"** — `field_details(field_id: id)`
- **"What type is this field?"** — `field_details(field_id: id)`, read `type_name` and `type`
- **"Who reads this field?"** — `field_details(field_id: id)`, read `read_access`
- **"Is this field written from outside its class?"** — `field_details(field_id: id)`, check `write_access.by_declaring_type` for types other than `declaring_type`
- **"Is this a constant?"** — `field_details(field_id: id)`, check `is_constant`

## LLM tool description

The `@Tool` description should communicate:

1. Full structural details for a single field — the deepest zoom level
2. Input must be a field-kind node ID
3. Returns modifiers, type, annotations, read/write access digests, source location
4. The read/write digests show who accesses the field, grouped by declaring type — check these before enumerating accessors manually
5. Primitive types have `type: null`; read `type_name` for the primitive keyword
6. For the list of fields on a type, use `list_children`; for exhaustive accessor enumeration, use `incoming_dependencies` at detail level
7. Uses slim encoding because of the read/write digests
