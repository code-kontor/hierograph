# `method_details`

**Category:** Entity detail
**Result-size class:** Input-bounded (single entity, no pagination needed)

## Purpose

Returns the full structural details for a single method: modifiers, return type, parameters with their types and annotations, declared exceptions, method-level annotations, override target, and source location.

This is the deepest zoom level for a method — after the LLM has navigated the hierarchy (`list_children`, `list_descendants`) or followed dependency evidence (`outgoing_dependencies` at detail level) to identify a specific method of interest, `method_details` gives the complete picture in one call.

## Signature

```
method_details(method_id: long)
```

### Parameters

**`method_id`** (long, required)
The node ID of the method to inspect. Must be a method-kind node (`java.method`). Typically obtained from `list_children` on a type, `list_descendants` with `kind_filter: ["java.method"]`, or from detail-level dependency results.

## Response shape

Uses **inline NodeRefs** (not slim encoding) — this is a single-entity response where each referenced type appears at most once. The overhead of a `nodes` map would exceed the savings.

```json
{
  "method": {
    "id": 47305,
    "name": "applyState",
    "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState",
    "kind": "java.method",
    "parent_id": 47291,
    "parent_kind": "java.class"
  },
  "declaring_type": {
    "id": 47291,
    "name": "ClusterService",
    "qualified_name": "org.elasticsearch.cluster.ClusterService",
    "kind": "java.class"
  },
  "modifiers": ["private"],
  "is_constructor": false,
  "return_type": {
    "id": null,
    "name": "void",
    "qualified_name": "void",
    "kind": "java.primitive"
  },
  "parameters": [
    {
      "position": 0,
      "name": "source",
      "type": {
        "id": 48201,
        "name": "ClusterChangedEvent",
        "qualified_name": "org.elasticsearch.cluster.ClusterChangedEvent",
        "kind": "java.class"
      },
      "annotations": []
    },
    {
      "position": 1,
      "name": "taskId",
      "type": {
        "id": null,
        "name": "long",
        "qualified_name": "long",
        "kind": "java.primitive"
      },
      "annotations": []
    }
  ],
  "throws": [
    {
      "id": 99001,
      "name": "IOException",
      "qualified_name": "java.io.IOException",
      "kind": "java.class"
    }
  ],
  "annotations": [
    {
      "type": {
        "id": 88001,
        "name": "Override",
        "qualified_name": "java.lang.Override",
        "kind": "java.annotation"
      }
    }
  ],
  "overrides": {
    "id": 47400,
    "name": "applyState",
    "qualified_name": "org.elasticsearch.cluster.AbstractClusterService.applyState",
    "kind": "java.method",
    "parent_id": 47200,
    "parent_kind": "java.class"
  },
  "location": {
    "line_number": 247
  }
}
```

### Response fields

**`method`** — minimal NodeRef of the method itself.

**`declaring_type`** — minimal NodeRef of the type that declares this method.

**`modifiers`** — list of Java modifier keywords (`public`, `private`, `static`, `final`, `abstract`, `synchronized`, `native`, `default`).

**`is_constructor`** — `true` if this method is a constructor.

**`return_type`** — minimal NodeRef of the return type. For constructors, this is the declaring type. For `void` methods, this is a primitive NodeRef with `id: null` and `kind: "java.primitive"`.

**`parameters`** — ordered list of parameters, each with:
- `position` — zero-indexed parameter position
- `name` — parameter name (if available from bytecode; may be synthetic like `arg0`)
- `type` — minimal NodeRef of the parameter type
- `annotations` — list of annotations on this parameter, each as `{ type: NodeRef }`

**`throws`** — list of declared exception types as minimal NodeRefs. Empty if the method declares no checked exceptions.

**`annotations`** — list of method-level annotations, each as `{ type: NodeRef }`.

**`overrides`** — minimal NodeRef of the method this one overrides, or `null` if it doesn't override anything. Includes `parent_id` and `parent_kind` of the declaring type, so the LLM can navigate to the overridden method's context.

**`location`** — source location with `line_number`. Points to the method declaration; the body follows. `null` if source location is unavailable.

### Primitive types

Primitive types (`void`, `int`, `boolean`, `long`, etc.) appear as NodeRefs with `id: null` and `kind: "java.primitive"`. These are not first-class nodes in the graph — the LLM should not pass primitive NodeRef IDs to other tools.

For generic types like `List<String>`, the erased type (`java.util.List`) is reported; type parameters are not surfaced.

## Input validation

**Wrong node kind.** If `method_id` refers to a non-method node:

```json
{
  "error": {
    "code": "WRONG_NODE_KIND",
    "message": "Node 47291 is a 'java.class', not a method. method_details requires a method-kind node.",
    "actual_kind": "java.class",
    "recovery": "To see the methods of this type, use list_children(node_id: 47291, kind_filter: ['java.method'])."
  }
}
```

**Unknown node ID.** Returns `NODE_NOT_FOUND` error with recovery pointing to `find_node`.

## Architecture

`method_details` queries **Neo4j via the provider layer**. The structural detail this tool returns (parameter types as NodeRefs, full annotation lists with their types, override targets with declaring types) requires joining across multiple relationship types in the graph — this is what Neo4j is good at.

The query is issued through `DetailDependencyProvider.methodDetailsQuery(methodId)`. The provider translates to scanner-specific Cypher; the tool layer receives domain-typed results and assembles the response. No Cypher or scanner-specific labels leak into the tool layer.

This is consistent with the architecture principle: the in-memory model handles navigation and aggregation; Neo4j handles detail-level evidence and per-entity structural detail.

## Use cases

- **"Tell me everything about this method"** — `method_details(method_id: id)`
- **"What does this method's signature look like?"** — `method_details(method_id: id)`, read `parameters`, `return_type`, `throws`
- **"Does this method override something?"** — `method_details(method_id: id)`, check `overrides`
- **"What annotations are on this method?"** — `method_details(method_id: id)`, read `annotations`
- **"Where is this method declared?"** — `method_details(method_id: id)`, read `location` for line number, `declaring_type` for the class

## LLM tool description

The `@Tool` description should communicate:

1. Full structural details for a single method — the deepest zoom level
2. Input must be a method-kind node ID
3. Returns modifiers, return type, parameters (with types and annotations), throws, annotations, override target, source location
4. Primitive types appear with `id: null` — do not pass these to other tools
5. For the list of methods on a type, use `list_children`; for dependency evidence involving methods, use `outgoing_dependencies` / `incoming_dependencies` at detail level
6. Use the `location` field with file-reading tools to inspect the actual implementation
