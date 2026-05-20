# Cartograph: `list_fields` Specification

This is the complete specification for `list_fields`, one of the detail-level tools introduced in v0.2. It serves the "what fields does this type have?" question — enumerating the fields declared on a single type with enough metadata for the LLM to decide whether to drill deeper into any of them.

This specification builds on conventions established in `mcp-tools.md` (NodeRef, node IDs, limits, JSON precision) and the architectural framing in `detail-level-tools.md` (the hierarchical/detail/code layer model). It also parallels `list_methods` closely; differences between the two are highlighted where they matter.

## Purpose

`list_fields` returns the fields declared on a specific type, along with lightweight per-field metadata. It serves the *composition* workflow: when the LLM has identified a type of interest and wants to understand its data members before deciding which to inspect further.

Fields are where the framework-wiring story really lives. Spring `@Autowired`, JPA `@Entity` mappings (`@Column`, `@JoinColumn`, `@OneToMany`), configuration properties, dependency injection — most of this lands on fields rather than methods. A type's fields, together with their annotations and types, often tell you more about how the class is integrated into the broader system than its methods do.

This is distinct from `detail_dependencies`, which answers "what's the method/field-level evidence for this aggregated edge?" `list_fields` doesn't take a target subtree — it enumerates what's *in* the type. The LLM uses it to orient inside a type, then either calls `field_details` for one specific field or `detail_dependencies` to investigate the type's relationships at the detail level.

## Parameters

```
list_fields(
    type_id: long,             // required
    name_pattern: string?,     // optional substring match against field name
    modifier_filter: string[]?, // optional: e.g. ["private"], ["static", "final"]
    include_inherited: bool = false,
    limit: int = 50
)
```

### `type_id` (required)

The node ID of the type whose fields should be enumerated. Must be a type-kind node (`java.class`, `java.interface`, `java.enum`, `java.annotation`). Other kinds return an error.

A note on interfaces and annotations: these can have fields too, though their semantics differ. Interface fields are implicitly `public static final`. Annotation "fields" are actually annotation members. The tool returns whatever fields jQAssistant records for the type; it doesn't filter by what's "typical" for a kind.

If the node ID is unknown or stale, the tool returns a structured error rather than empty results.

### `name_pattern` (optional)

Case-insensitive substring match against the field name. Useful for finding fields by partial name without enumerating all of them — for example, `"id"` to find ID-like fields, `"config"` to find configuration-related fields. Does not match against the field's type or qualified name — just the simple field name.

### `modifier_filter` (optional)

A list of Java modifiers, ANDed together. Each entry must be one of: `"public"`, `"protected"`, `"private"`, `"package-private"`, `"static"`, `"final"`, `"transient"`, `"volatile"`.

If supplied, only fields whose modifiers include *all* listed values are returned. Example: `modifier_filter: ["static", "final"]` returns only constants. `modifier_filter: ["private"]` returns only private fields.

The set of valid modifiers differs from `list_methods` — fields have `transient` and `volatile`, methods have `abstract`, `synchronized`, `native`, `default`. The tool validates against the field-appropriate set.

### `include_inherited` (default `false`)

By default, only fields *declared on* the type are returned. Inherited fields (from superclasses) are excluded.

When `true`, the response also includes fields inherited from ancestor types. Each inherited field's NodeRef points to its declaring type, and an additional field on the per-field object indicates the inheritance source. The `summary` block distinguishes declared and inherited counts.

Inherited fields are subject to Java's visibility rules — `private` fields of a superclass aren't accessible to subclasses, but jQAssistant records them in the inheritance chain. The tool returns them regardless; the LLM can filter by visibility if needed. This is more honest than silently hiding them, and matches the spirit of `list_methods`'s `include_inherited` behavior.

The default is `false` for the same reasons as `list_methods` — the common case is "what does *this* type itself define," and inherited fields add noise when investigating a specific type's structure.

### `limit` (default 50)

Maximum number of fields to return. Typical types have well under 50 fields; the default rarely truncates in practice. Server-side cap at 500.

When `total_matching` exceeds `limit`, the response truncates and sets `truncated: true` in the summary. The LLM sees the true total and can re-issue the call with a larger `limit` (up to the cap), or apply a tighter filter to narrow the result set.

## Response shape

```json
{
  "type": {
    "id": 47291,
    "name": "ClusterService",
    "qualified_name": "org.elasticsearch.cluster.ClusterService",
    "kind": "java.class"
  },
  "fields": [
    {
      "node": {
        "id": 88456,
        "name": "clusterState",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.clusterState",
        "kind": "java.field",
        "parent_id": 47291,
        "parent_kind": "java.class"
      },
      "modifiers": ["private", "volatile"],
      "field_type_name": "org.elasticsearch.cluster.ClusterState",
      "annotation_count": 0,
      "is_constant": false,
      "is_inherited": false,
      "declared_by": null
    },
    {
      "node": {
        "id": 88457,
        "name": "transportService",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.transportService",
        "kind": "java.field",
        "parent_id": 47291,
        "parent_kind": "java.class"
      },
      "modifiers": ["private", "final"],
      "field_type_name": "org.elasticsearch.transport.TransportService",
      "annotation_count": 1,
      "is_constant": false,
      "is_inherited": false,
      "declared_by": null
    },
    {
      "node": {
        "id": 88458,
        "name": "DEFAULT_TIMEOUT",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.DEFAULT_TIMEOUT",
        "kind": "java.field",
        "parent_id": 47291,
        "parent_kind": "java.class"
      },
      "modifiers": ["public", "static", "final"],
      "field_type_name": "long",
      "annotation_count": 0,
      "is_constant": true,
      "is_inherited": false,
      "declared_by": null
    }
  ],
  "summary": {
    "total_matching": 12,
    "returned": 12,
    "truncated": false,
    "declared_count": 12,
    "inherited_count": 0,
    "by_visibility": {
      "public": 2,
      "protected": 0,
      "private": 10,
      "package-private": 0
    },
    "annotated_count": 4,
    "static_count": 3,
    "final_count": 8,
    "constant_count": 2
  }
}
```

### Per-field fields

**`node`** — Full NodeRef for the field. Includes `parent_id` and `parent_kind`, which bridge back to the hierarchical model. The `parent_id` is the *declaring type's* ID, which equals `type_id` for declared fields and differs for inherited ones.

**`modifiers`** — List of Java modifier keywords, in canonical order: visibility first (`public`/`protected`/`private`/`package-private`), then storage modifiers (`static`, `final`, `transient`, `volatile`).

**`field_type_name`** — Human-readable type of the field as a string. For primitives, the keyword (`"int"`, `"boolean"`, `"long"`). For object types, the qualified name (e.g. `"java.util.List<java.lang.String>"`). This is for at-a-glance readability — the LLM uses `field_details` to get a structured NodeRef for the field's type, which is what's needed to navigate via dependency tools.

**`annotation_count`** — Number of annotations on the field. A non-zero value flags fields worth investigating for framework-wiring questions: Spring `@Autowired`, JPA `@Column`, `@Inject`, validation annotations, etc. This is the single most useful per-field metadata field for many real-world investigations.

**`is_constant`** — Boolean. `true` for fields that are both `static` and `final` (the conventional definition of a Java constant). Surfaced separately because constants are often interesting on their own — they're the named values a class exposes to the rest of the system.

**`is_inherited`** — Boolean. `true` only when `include_inherited` was set and this field is inherited from an ancestor.

**`declared_by`** — NodeRef of the type that declares this field. `null` for declared fields (the declarer is the queried type, already in `type.id`); a full NodeRef for inherited fields.

### Summary fields

**`total_matching`** — Count of fields matching the filter set, regardless of `limit`. Lets the LLM see how many fields are *really* there.

**`returned`** — Count of fields actually in the response array.

**`truncated`** — Boolean. `true` if `total_matching > returned`.

**`declared_count`** / **`inherited_count`** — Decomposition of `total_matching` by inheritance source. Always present; when `include_inherited` is `false`, `inherited_count` is `0`.

**`by_visibility`** — Distribution of fields across visibility modifiers. Useful for understanding the encapsulation pattern of the type.

**`annotated_count`** — Number of fields with at least one annotation. High annotated_count relative to total signals framework-heavy classes — Spring components, JPA entities, etc. This is the headline statistical signal for framework-wiring questions.

**`static_count`** — Number of static fields (whether final or not). Includes constants.

**`final_count`** — Number of final fields (whether static or not). Includes constants.

**`constant_count`** — Number of fields that are both static and final. Subset of both `static_count` and `final_count`.

The summary fields are designed to let the LLM understand the type's data structure at a glance. For a JPA entity, seeing `annotated_count: 12` of `total_matching: 15` is a strong signal of "this is a database-mapped class." For a service class, seeing `annotated_count: 3` of `total_matching: 5` with mostly `final` fields is a strong signal of "this is constructor-injected dependencies." The summary tells the framework story before the LLM even looks at individual fields.

## Result ordering

Fields are returned in document order — the order they appear in the source file. This is predictable and matches how a developer reads the file. Static fields appear in their natural source-file position rather than being grouped separately; constants are not pulled to the top of the list.

If the LLM needs sorted results (by name, by visibility, by annotation count), it sorts post-hoc. Adding a `sort_by` parameter is deferred until usage data shows it's necessary.

## Filter combination semantics

When multiple filters are supplied, they're combined with AND:

- `name_pattern + modifier_filter` → fields matching both
- `name_pattern + include_inherited=true` → matching fields, including inherited ones
- `modifier_filter + include_inherited=true` → modifier-matching fields, including inherited ones

Within `modifier_filter`, the listed modifiers are also ANDed (a field must have all listed modifiers). This matches the conventions established for `list_methods` and the broader Cartograph filter pattern.

## Error cases

The tool returns a structured error (not an empty response) in these cases:

**`NODE_NOT_FOUND`** — `type_id` doesn't exist in the graph. Suggests the LLM should re-resolve via `find_node`.

**`WRONG_NODE_KIND`** — `type_id` exists but isn't a type-kind node (e.g., it's a method ID, a field ID, or a package ID). The error includes the actual kind for context.

**`INVALID_MODIFIER`** — `modifier_filter` contains a value outside the allowed set. The error includes the offending value and the allowed list (which differs from `list_methods` — `transient` and `volatile` are valid here; `abstract`, `synchronized`, etc., are not).

Each error includes a human-readable message and a structured `code` field for programmatic handling.

## Performance characteristics

Like `list_methods`, `list_fields` queries Neo4j directly at request time rather than from the in-memory model. Expected behavior:

- **Typical types**: 5–15 fields. Response time well under 100ms.
- **Framework-heavy types**: 20–40 fields (DTOs, JPA entities, configuration classes). Response time still under 200ms.
- **Pathological types**: 100+ fields (rare, often generated code or table-row mappings). Response truncates at the limit.

The Cypher query is parameterized and pre-compiled. JSON serialization is the bottleneck only for unusually large result sets.

If response times become a problem in practice, the in-memory model could be extended to cache field lists per type. As with `list_methods`, defer this optimization until usage data demands it.

## Description for the tool registration

This is the text exposed to the LLM via MCP.

> Return the fields declared on a type, with lightweight metadata for each. Use this when you have identified a type and want to understand its data members — for example, *"what fields does `UserEntity` have?"* or *"list the autowired dependencies of this Spring component."*
>
> Returns each field as a NodeRef plus metadata: modifiers, field type name, annotation count, and flags like `is_constant`. The `annotation_count` is particularly valuable for framework-wiring questions — fields with annotations are often where Spring injection, JPA mappings, or validation rules live. The `summary` block surfaces aggregate signals like `annotated_count`, `constant_count`, and visibility distribution, which often tell the framework story before you even look at individual fields.
>
> Common parameter patterns:
>
> - Just `type_id`: enumerate all declared fields.
> - `type_id` + `modifier_filter: ["private", "final"]`: list constructor-injected dependencies (a common Spring pattern).
> - `type_id` + `modifier_filter: ["static", "final"]`: list the constants this type defines.
> - `type_id` + `name_pattern: "id"`: find ID-like fields (useful for entity/database investigation).
> - `type_id` + `include_inherited: true`: see all accessible fields, including inherited ones.
>
> When to use this vs. neighboring tools:
>
> - For deep information about one specific field (full type as a NodeRef, list of annotations, methods that read or write it), use `field_details`. `list_fields` returns lightweight summaries; `field_details` returns the full structural picture.
> - For "which methods read this field?" or "which classes have a field of type X?", use `detail_dependencies` — that's the dependency-driven view rather than the composition view.
> - For methods rather than fields, use `list_methods` (same shape, different entity).

## Integration with the broader workflow

`list_fields` typically appears in workflows like these:

**Workflow 1: investigate a Spring component's dependencies.**

1. `find_node("UserService")` → type ID
2. `list_fields(type_id, modifier_filter: ["private", "final"])` → see constructor-injected dependencies
3. For each field, note `annotation_count` and `field_type_name` — typically `@Autowired` (older style) or just final fields for constructor injection
4. `field_details(field_id)` on any specific dependency to see exact annotations and what reads/writes it

**Workflow 2: understand a JPA entity's data model.**

1. `find_node("Order")` → type ID
2. `list_fields(type_id)` → see the field list with `annotation_count` per field
3. Notice high `annotated_count` in the summary — confirms this is a mapped entity
4. `field_details(field_id)` on annotated fields to see `@Column`, `@JoinColumn`, `@OneToMany`, etc.

**Workflow 3: explore the constants a class exposes.**

1. `find_node("HttpStatus")` → type ID
2. `list_fields(type_id, modifier_filter: ["public", "static", "final"])` → see the named constants
3. Use the field names directly; rarely need to drill further into individual `field_details`

**Workflow 4: investigate a class's mutable state.**

1. `find_node("ClusterStateManager")` → type ID
2. `list_fields(type_id)` → look for fields *without* `final` modifier
3. Note `volatile` markers (concurrency hint), `transient` markers (serialization hint)
4. Use `detail_dependencies` to find methods that write each mutable field — that's how the LLM understands "how does this class evolve over time?"

**Workflow 5: comparing types.**

1. Use `list_fields` on two candidate implementations of an interface
2. Compare the summary blocks (visibility distribution, annotation counts) to understand which is more framework-coupled vs. cleaner
3. Useful for "which implementation should I prefer?" or "are these really doing the same job?" questions

In each case, `list_fields` is the orientation step that follows hierarchical drilling or `find_node` and precedes deeper detail-level investigation. The summary block often answers the LLM's question without needing to enumerate every field; the per-field counts then guide which specific fields are worth investigating with `field_details`.

## Implementation notes

A few specifics worth flagging during implementation:

**Cypher query shape.** The underlying query matches the type by ID and finds its declared fields. With `include_inherited=true`, traversal includes superclass chains (interfaces typically don't contribute fields beyond constants, but the query handles them anyway). Pseudocode:

```cypher
// Base case (declared only)
MATCH (t:Type)-[:DECLARES]->(f:Field)
WHERE id(t) = $type_id
RETURN f, ...

// With include_inherited
MATCH (t:Type)-[:DECLARES|:EXTENDS*]-(f:Field)
WHERE id(t) = $type_id
RETURN f, ..., (declarer of f) AS declared_by
```

The exact graph patterns depend on jQAssistant's Java schema; verify the relationship types match (`DECLARES`, etc.) before committing. Annotation counts can typically be derived with an `OPTIONAL MATCH` and a count aggregation in the same query.

**Metadata extraction.** Modifiers, field type name, and annotation count should be derived in one query rather than per-field follow-ups. Neo4j handles this well when the query is structured as a single match with multiple optional matches.

**Field type name formatting.** jQAssistant typically records the field's type as a separate `Type` node linked by an `OF_TYPE` (or similar) relationship. The `field_type_name` in the response is a string formatted from that node's qualified name plus any generic type parameters jQAssistant captures. For unparameterized types this is just the FQN; for generics, format as `Container<Element>` etc. Some loss of fidelity is acceptable here since `field_details` provides the structured form for navigation.

**NodeRef construction.** The `parent_id` field on each field's NodeRef should be the declaring type's ID, not always `type_id`. For declared fields these are equal; for inherited fields they differ.

**Modifier normalization.** As with methods, jQAssistant may store modifiers as boolean properties or a string list. Normalize to the canonical list-of-strings form regardless.

**`is_constant` computation.** Derived: `modifiers.contains("static") && modifiers.contains("final")`. Compute server-side rather than asking the LLM to check both modifiers.

**Visibility for inherited private fields.** When `include_inherited=true`, jQAssistant returns private fields of superclasses that aren't actually accessible to the subclass. The tool returns them anyway — it's a faithful structural view, and hiding them would be misleading. The LLM can filter by visibility if it wants only accessible fields.

**Testing checklist:**

- Type with no fields (interface with no constants? marker class?)
- Type with one field
- Type with many fields (>50, triggers truncation)
- Inherited fields from a common superclass — common case for `include_inherited=true`
- Annotated fields (Spring `@Autowired`, JPA `@Column`, etc.) — verify `annotation_count` is correct
- Constants (static final) — verify `is_constant: true`
- Volatile / transient fields — verify these modifiers appear correctly
- Generic field types (`List<String>`, `Map<K, V>`) — verify `field_type_name` formatting
- Bad inputs: non-existent ID, package ID, method ID passed as `type_id`
- Invalid modifier filter values (e.g., `"abstract"`, which is method-only): should return `INVALID_MODIFIER`
