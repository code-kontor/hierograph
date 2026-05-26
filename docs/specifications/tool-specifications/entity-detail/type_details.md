# `type_details`

**Category:** Entity detail
**Result-size class:** Input-bounded (single entity, no pagination needed)

## Purpose

Returns the full structural details for a single type (class, interface, enum, record, or annotation type): modifiers, superclass, implemented interfaces, type-level annotations, member counts, inner type summary, and source location.

This complements the enriched NodeRef that browse tools (`list_children`, `list_descendants`, `find_node`) already return for types. The enriched NodeRef carries counts and flags suitable for scanning a list of types; `type_details` adds the *identity* of the superclass, each implemented interface, and each annotation — as navigable NodeRefs the LLM can follow.

The typical workflow: the LLM scans types via `list_children` or `list_descendants`, sees a type with `interface_count: 3` or `is_abstract: true`, and calls `type_details` to learn *which* interfaces, *which* annotations, and *what* superclass.

## Signature

```
type_details(type_id: long)
```

### Parameters

**`type_id`** (long, required)
The node ID of the type to inspect. Must be a type-kind node (`java.class`, `java.interface`, `java.enum`, `java.record`, `java.annotation`). Typically obtained from `find_node`, `list_children`, `list_descendants`, or from dependency results.

## Response shape

Uses **inline NodeRefs** (not slim encoding) — this is a single-entity response where each referenced type appears at most once.

```json
{
  "type": {
    "id": 47291,
    "name": "ClusterService",
    "qualified_name": "org.elasticsearch.cluster.ClusterService",
    "kind": "java.class",
    "parent_id": 12503,
    "parent_kind": "java.package"
  },
  "parent_container": {
    "id": 12503,
    "name": "org.elasticsearch.cluster",
    "qualified_name": "org.elasticsearch.cluster",
    "kind": "java.package"
  },
  "modifiers": ["public"],
  "is_abstract": false,
  "is_generic": false,
  "superclass": {
    "id": 47200,
    "name": "AbstractLifecycleComponent",
    "qualified_name": "org.elasticsearch.common.component.AbstractLifecycleComponent",
    "kind": "java.class"
  },
  "interfaces": [
    {
      "id": 48001,
      "name": "ClusterStateApplier",
      "qualified_name": "org.elasticsearch.cluster.ClusterStateApplier",
      "kind": "java.interface"
    },
    {
      "id": 48002,
      "name": "ClusterStateSupplier",
      "qualified_name": "org.elasticsearch.cluster.ClusterStateSupplier",
      "kind": "java.interface"
    }
  ],
  "annotations": [
    {
      "type": {
        "id": 88010,
        "name": "Singleton",
        "qualified_name": "javax.inject.Singleton",
        "kind": "java.annotation"
      }
    }
  ],
  "member_summary": {
    "method_count": 28,
    "field_count": 4,
    "constructor_count": 2
  },
  "inner_types": [
    {
      "id": 47350,
      "name": "ClusterService.ClusterStateStatus",
      "qualified_name": "org.elasticsearch.cluster.ClusterService.ClusterStateStatus",
      "kind": "java.enum"
    }
  ],
  "location": {
    "source_file": "org/elasticsearch/cluster/ClusterService.java",
    "line_number": 48
  }
}
```

### Response fields

**`type`** — minimal NodeRef of the type itself, including `parent_id` and `parent_kind`.

**`parent_container`** — minimal NodeRef of the containing package (or enclosing type, for inner types). Provides navigation context.

**`modifiers`** — list of Java modifier keywords (`public`, `abstract`, `final`, `static` for inner types, `sealed`, `non-sealed`).

**`is_abstract`** — `true` if the type is abstract.

**`is_generic`** — `true` if the type has generic type parameters. Individual type parameters are not surfaced.

**`superclass`** — minimal NodeRef of the direct superclass. `null` for interfaces, `java.lang.Object` subclasses where the superclass is implicit, enums (implicit `java.lang.Enum`), and records (implicit `java.lang.Record`). Present only when the type has an explicit, non-trivial superclass.

**`interfaces`** — list of minimal NodeRefs for directly implemented interfaces (for classes/enums/records) or extended interfaces (for interfaces). Empty if none.

**`annotations`** — list of type-level annotations, each as `{ type: NodeRef }`. Empty if none.

**`member_summary`** — counts of declared members:
- `method_count` — declared methods (excluding constructors)
- `field_count` — declared fields
- `constructor_count` — declared constructors

These counts match what `list_children(type_id)` would return. They're included here so the LLM has the full picture in one response without a follow-up call.

**`inner_types`** — list of minimal NodeRefs for types declared inside this type (inner classes, static nested classes, local enums, etc.). Empty if none. Only direct inner types, not nested inner types of inner types.

**`location`** — source location with `source_file` (relative path) and `line_number`. `null` if unavailable.

## Input validation

**Wrong node kind.** If `type_id` refers to a non-type node:

```json
{
  "error": {
    "code": "WRONG_NODE_KIND",
    "message": "Node 47305 is a 'java.method', not a type. type_details requires a type-kind node (java.class, java.interface, java.enum, java.record, java.annotation).",
    "actual_kind": "java.method",
    "declaring_type": {
      "id": 47291,
      "name": "ClusterService",
      "qualified_name": "org.elasticsearch.cluster.ClusterService",
      "kind": "java.class"
    },
    "recovery": "To inspect the declaring type, use type_details(type_id: 47291). To inspect the method itself, use method_details(method_id: 47305)."
  }
}
```

For method and field IDs, the error includes `declaring_type` for one-step recovery — consistent with the pattern used by dependency tools. For module and package IDs, `declaring_type` is omitted and the recovery message directs to `list_children` or `list_descendants`.

**Unknown node ID.** Returns `NODE_NOT_FOUND` error with recovery pointing to `find_node`.

## Architecture

`type_details` queries **Neo4j via the provider layer** for the superclass, interfaces, annotations, and inner types. These relationships (`:EXTENDS`, `:IMPLEMENTS`, `:ANNOTATED_BY`, `:DECLARES`) require graph traversal that the in-memory model doesn't currently store at the detail level.

The query is issued through `DetailDependencyProvider.typeDetailsQuery(typeId)`. The provider translates to scanner-specific Cypher; the tool layer receives domain-typed results and assembles the response.

Member counts (`method_count`, `field_count`, `constructor_count`) can be derived from the in-memory model's child list, avoiding a separate Cypher aggregation for these values.

### Why not purely in-memory?

The in-memory model stores the hierarchy (parent/child) and type-level dependency edges. It does *not* store the distinction between superclass vs. interface vs. annotation relationships at the type level — those are collapsed into the type-level edge kind flags (`extends`, `implements`, `annotated_by`). `type_details` needs the specific referenced types, not just the flags, so it queries Neo4j.

A future optimization could materialize superclass and interface references in the in-memory model at load time, making `type_details` purely in-memory. This is straightforward but not required for correctness.

## Use cases

- **"Tell me everything about this class"** — `type_details(type_id: id)`
- **"What does this class extend?"** — `type_details(type_id: id)`, read `superclass`
- **"Which interfaces does this class implement?"** — `type_details(type_id: id)`, read `interfaces`
- **"Is this class annotated with @Service?"** — `type_details(type_id: id)`, read `annotations`
- **"Does this class have inner types?"** — `type_details(type_id: id)`, read `inner_types`
- **"Where is this class defined?"** — `type_details(type_id: id)`, read `location`

## LLM tool description

The `@Tool` description should communicate:

1. Full structural details for a single type — superclass, interfaces, annotations, inner types, location
2. Input must be a type-kind node ID (class, interface, enum, record, annotation)
3. Complements the enriched NodeRef from browse tools: browse tools give counts and flags; this tool gives the actual referenced types as navigable NodeRefs
4. For the members of a type (methods, fields), use `list_children`; for full method or field detail, use `method_details` or `field_details`
5. For dependency analysis involving this type, use `aggregated_dependencies`, `outgoing_dependencies`, or `incoming_dependencies`
