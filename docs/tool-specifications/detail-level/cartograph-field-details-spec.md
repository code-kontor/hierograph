# Cartograph: `field_details` Specification

This is the complete specification for `field_details`, one of the detail-level tools introduced in v0.2. It is the "open file" equivalent for a single field — given a field's node ID, return everything Cartograph can say about it structurally, in one call.

This specification builds on conventions established in `mcp-tools.md` (NodeRef, node IDs, JSON precision) and the architectural framing in `detail-level-tools.md` (the hierarchical/detail/code layer model). It parallels `method_details` closely; differences between the two are highlighted where they matter.

## Purpose

`field_details` returns the full structural information about a single field: its modifiers, type, annotations, and a digest of which methods read or write it. Plus a source location so the LLM can navigate to the declaration via its own file-reading tools.

This is the natural follow-up tool after `list_fields` (which returns lightweight summaries) or `detail_dependencies` (which surfaces field IDs in its result edges). The LLM uses `field_details` when it has narrowed to a specific field and needs the complete picture before reasoning about it.

The read/write information is the most important thing this tool adds beyond what `list_fields` provides. For framework-wiring questions, mutable-state analysis, and dependency injection investigation, knowing *which methods touch this field* is often the critical piece of information.

## Parameters

```
field_details(
    field_id: long           // required
)
```

### `field_id` (required)

The node ID of the field to inspect. Must be a field-kind node (`java.field`). Other kinds return an error.

Typically obtained from prior queries — `list_fields` returns field IDs on each entry, and `detail_dependencies` returns them as the `from` field on edges originating from fields, or as the `to` field for `reads_field` and `writes_field` relationships.

If the node ID is unknown or stale, the tool returns a structured error rather than empty results.

## Response shape

```json
{
  "field": {
    "id": 88456,
    "name": "clusterState",
    "qualified_name": "org.elasticsearch.cluster.ClusterService.clusterState",
    "kind": "java.field",
    "parent_id": 47291,
    "parent_kind": "java.class"
  },
  "declaring_type": {
    "id": 47291,
    "name": "ClusterService",
    "qualified_name": "org.elasticsearch.cluster.ClusterService",
    "kind": "java.class"
  },
  "modifiers": ["private", "volatile"],
  "is_constant": false,
  "type": {
    "id": 38104,
    "name": "ClusterState",
    "qualified_name": "org.elasticsearch.cluster.ClusterState",
    "kind": "java.class"
  },
  "annotations": [
    {
      "type": {
        "id": 12201,
        "name": "Autowired",
        "qualified_name": "org.springframework.beans.factory.annotation.Autowired",
        "kind": "java.annotation"
      }
    }
  ],
  "read_access": {
    "method_count": 47,
    "methods_sample": [
      {
        "id": 91204,
        "name": "getState",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.getState",
        "kind": "java.method",
        "parent_id": 47291,
        "parent_kind": "java.class"
      },
      {
        "id": 91207,
        "name": "applyState",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState",
        "kind": "java.method",
        "parent_id": 47291,
        "parent_kind": "java.class"
      }
    ],
    "sample_truncated": true,
    "by_declaring_type": [
      { "type": { "id": 47291, "name": "ClusterService", "qualified_name": "...", "kind": "java.class" }, "count": 32 },
      { "type": { "id": 47305, "name": "ClusterMonitor", "qualified_name": "...", "kind": "java.class" }, "count": 11 },
      { "type": { "id": 47408, "name": "StateExporter", "qualified_name": "...", "kind": "java.class" }, "count": 4 }
    ]
  },
  "write_access": {
    "method_count": 3,
    "methods_sample": [
      {
        "id": 91207,
        "name": "applyState",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.applyState",
        "kind": "java.method",
        "parent_id": 47291,
        "parent_kind": "java.class"
      },
      {
        "id": 91208,
        "name": "resetState",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.resetState",
        "kind": "java.method",
        "parent_id": 47291,
        "parent_kind": "java.class"
      },
      {
        "id": 91209,
        "name": "initState",
        "qualified_name": "org.elasticsearch.cluster.ClusterService.initState",
        "kind": "java.method",
        "parent_id": 47291,
        "parent_kind": "java.class"
      }
    ],
    "sample_truncated": false,
    "by_declaring_type": [
      { "type": { "id": 47291, "name": "ClusterService", "qualified_name": "...", "kind": "java.class" }, "count": 3 }
    ]
  },
  "location": {
    "absolute_path": "/Users/gerd/elasticsearch/server/src/main/java/org/elasticsearch/cluster/ClusterService.java",
    "relative_path": "server/src/main/java/org/elasticsearch/cluster/ClusterService.java",
    "workspace_root": "/Users/gerd/elasticsearch",
    "line_number": 89
  }
}
```

### Field-by-field

**`field`** — Full NodeRef for the field itself. Same shape as everywhere else in the API.

**`declaring_type`** — Full NodeRef for the type that declares this field. Always present. Same as `field.parent_id` resolved to a full NodeRef — surfaced separately because the LLM commonly wants to navigate up to the type, and an explicit field beats requiring a separate `find_node` call.

**`modifiers`** — List of Java modifier keywords, in canonical order: visibility first (`public`/`protected`/`private`/`package-private`), then storage modifiers (`static`, `final`, `transient`, `volatile`). Same convention as `list_fields`.

**`is_constant`** — Boolean. `true` for fields that are both `static` and `final`. Surfaced explicitly because constants are reasoned about differently than regular fields.

**`type`** — NodeRef for the field's type. For primitives (`int`, `long`, `boolean`, etc.), the NodeRef has `id: null` and `kind: "java.primitive"` — same convention as `method_details`. For reference types, the full NodeRef is populated and the LLM can use it as input to other tools.

**`annotations`** — List of annotations on the field. Each entry has a `type` field with the annotation type's NodeRef. Empty list if none.

The wrapper-object structure (`{type: NodeRef}`) parallels `method_details` and leaves room for future expansion to include annotation values (e.g., `@Column(name = "user_id", nullable = false)` → `attributes: {name: "user_id", nullable: false}`). For v0.2, only the annotation type is captured.

**`read_access`** — Information about methods that read this field. Structure:

- `method_count` — total number of methods that read this field, across the entire codebase. The truth-telling field.
- `methods_sample` — an inline sample of up to 10 reader methods, as NodeRefs. For most fields this *is* the full list, since most fields are read by only a few methods.
- `sample_truncated` — boolean, `true` if `method_count > methods_sample.length`. Tells the LLM "there are more readers than this sample."
- `by_declaring_type` — a digest of which types contain the reading methods, with counts. Always present (computed from the full reader set, not just the sample). Sorted descending by count, capped at 10 entries.

The design choice here is critical: for fields with many readers (loggers, common services, framework-injected dependencies), we don't want to return hundreds of NodeRefs inline. The pattern is:

1. **Always report the true count** (`method_count`).
2. **Inline a small sample** for the common case where the field has few readers.
3. **Always provide structural summary** via `by_declaring_type`, so the LLM understands *where* the readers are even when truncated.
4. **For exhaustive enumeration**, the LLM uses `detail_dependencies(from=root, to=field_id, relationship="reads_field")`.

This gives rich responses for the typical case (10-20 readers — they all fit inline) while keeping the response bounded for the pathological case (hundreds of readers — the LLM gets a structural digest).

**`write_access`** — Same structure as `read_access`, but for methods that write the field. The structure is identical so the LLM doesn't have to remember two different shapes.

For most fields, the write set is much smaller than the read set — often just constructors and a few mutators. The summary fields handle the rare cases where this isn't true (e.g., publicly-modifiable static state).

**`location`** — File and line number for the field's declaration. Same shape as locations elsewhere: absolute path, relative path, workspace root, line number. The line number points to the field declaration line.

May be `null` for synthetic fields without source-level information. The field is always present in the response, but its value can be `null`.

## Semantics

A few details worth being explicit about:

### Primitive types

Primitives (`void`, `int`, `boolean`, `long`, `double`, `float`, `char`, `short`, `byte`) appear in the `type` position but aren't first-class nodes in the graph. The NodeRef uses `id: null` and `kind: "java.primitive"`. Same handling as `method_details`.

The LLM should not try to use a primitive NodeRef as input to other tools.

### Generic types

For fields of generic types (e.g., `List<String>`, `Map<K, V>`), the `type` field reports the erased type (`java.util.List` for `List<String>`). Type parameters aren't surfaced in v0.2. The LLM can infer generics from the source file via `location` + file reading.

A future version could add a `type_arguments` field; not in v0.2.

### Sample size and truncation strategy

The decision to inline up to 10 reader/writer methods is a deliberate tradeoff:

- **10 is enough for most real cases.** Field analysis on real codebases shows that the median field has 3-5 readers and 1-2 writers. The cap rarely truncates.
- **10 is small enough to never blow context.** Even a worst-case field response with 10 readers + 10 writers + their `by_declaring_type` summaries is under 5KB.
- **The `by_declaring_type` summary handles the truncated case gracefully.** "32 readers in `ClusterService`, 11 in `ClusterMonitor`, 4 in `StateExporter`" tells the LLM the structural story without enumerating each reader.

If usage data shows 10 is wrong (e.g., the LLM always asks for full enumeration on truncated responses), the cap can be raised. But starting conservative is the right call — better to find out the LLM wants more than to discover responses are bloated.

### The dependency-tool composition

When the LLM does want exhaustive reader/writer enumeration, it composes:

```
field_details(field_id) → see sample + counts + by_declaring_type
# decides it needs the full list of readers
detail_dependencies(from=root_id, to=field_id, relationship="reads_field", limit=200)
```

This composition is the right escape hatch. `field_details` gives the rich single-entity view; `detail_dependencies` gives the exhaustive list when needed. The two tools cover the use case together without bloating either.

The tool description should make this composition explicit so the LLM knows the escape hatch exists.

### Static vs. instance fields

The tool doesn't distinguish static and instance fields explicitly in its response shape — `modifiers` includes `"static"` when applicable, and `is_constant` is true for static-final. That's all.

For static fields, `read_access` and `write_access` reflect access to the static field across the codebase, including from outside the declaring type. For instance fields, the same — though most instance fields are accessed only from methods of the declaring type (and its subclasses), making the read/write sets typically much smaller.

### Inherited fields

`field_details` returns details for the field *as declared on its declaring type*. The `declaring_type` field reveals where the field was actually declared (which may differ from where the LLM saw it referenced).

The read/write counts cover all methods anywhere in the codebase that access this field, regardless of which type those methods are declared on. For a private field, this is typically just methods of the declaring type. For protected or public fields, broader.

### Final fields

For a `final` field, `write_access.method_count` is typically small — just the constructor(s) and possibly a single static initializer. If `write_access.method_count` is 0 on a non-final field, that's a code smell worth flagging (the field is set, but never written from anywhere visible in the bytecode — possibly reflection or framework injection).

If `write_access.method_count` is unexpectedly high for a `final` field (more than one or two), that's also a signal — possibly Java reflection setting `accessible(true)`, or jQAssistant capturing something unusual. The LLM can use the count combined with the modifiers to spot these patterns.

## Error cases

The tool returns a structured error in these cases:

**`NODE_NOT_FOUND`** — `field_id` doesn't exist in the graph. Suggests the LLM should re-resolve via `find_node` or a fresh `list_fields` call.

**`WRONG_NODE_KIND`** — `field_id` exists but isn't a field-kind node (e.g., the LLM accidentally passed a method ID or a type ID). The error includes the actual kind for context.

Each error includes a human-readable message and a structured `code` field for programmatic handling.

## Performance characteristics

`field_details` queries Neo4j directly at request time. Expected behavior:

- **Typical field** (few readers, few writers, simple type, no annotations): response under 50ms, payload a few hundred bytes.
- **Heavy field** (logger, framework-injected static): response under 200ms, payload a few KB. The cap on inline samples and `by_declaring_type` entries bounds the response size.
- **Cold cache**: first call after server startup slightly slower; subsequent calls fast.

The Cypher query is slightly more complex than `method_details` because of the read/write digest:

- Match the field
- Optional matches for declarer, type, annotations
- Count reads and writes, then aggregate by declaring type for the digest

The count and digest can be combined into single subqueries; avoid making three separate trips for "count reads," "sample reads," "group reads by type." Modern Neo4j handles this in one query with the right structuring.

## Description for the tool registration

This is the text exposed to the LLM via MCP.

> Return the full structural details of a single field, in one call. Use this when you've identified a field of interest (via `list_fields`, `detail_dependencies`, or another tool that surfaces field IDs) and need the complete picture: type, annotations, and information about which methods read or write it.
>
> The response includes a digest of read and write access — how many methods read or write this field, a sample of those methods (up to 10), and a `by_declaring_type` breakdown showing which types contain the accessing methods. For fields with many readers (loggers, common dependencies), the digest tells you the structural story without needing to enumerate every accessor.
>
> If you need the full list of readers or writers (beyond the inline sample), use `detail_dependencies(from=root_id, to=field_id, relationship="reads_field")` (or `writes_field`) for exhaustive enumeration.
>
> The field type and declaring type are NodeRefs — you can feed these into other tools to investigate, e.g., `find_node`, `aggregated_incoming`, or `list_fields` on the declaring type.
>
> Use the `location` field together with your file-reading tools when you need to inspect the field declaration in context.
>
> When to use this vs. neighboring tools:
>
> - For all the fields declared on a type (composition, not single-field detail), use `list_fields`.
> - For "which methods read this specific field?" with exhaustive enumeration or filters, use `detail_dependencies` with `relationship: "reads_field"`.
> - For methods rather than fields, use `method_details` (parallel tool, parallel shape).

## Integration with the broader workflow

`field_details` typically appears in workflows where understanding *where state lives and how it changes* is the question:

**Workflow 1: investigate a Spring-injected dependency.**

1. `list_fields(type_id)` → see fields with `annotation_count` per field
2. Notice a field with annotation count 1 — likely framework-wired
3. **`field_details(field_id)`** → confirm it's `@Autowired`, see the injected type, see which methods use it (read_access)
4. `aggregated_incoming(injected_type_id)` → see what else depends on this type

**Workflow 2: trace mutable state.**

1. `list_fields(type_id)` → look for non-final fields
2. **`field_details(field_id)`** → see write_access.method_count
3. If `method_count > 3`, this is shared mutable state — worth investigating concurrency
4. Use `detail_dependencies(from=root, to=field_id, relationship="writes_field")` for the full writer list

**Workflow 3: validate encapsulation.**

1. `list_fields(type_id)` → see visibility distribution
2. For a `private` field, **`field_details(field_id)`** → check `by_declaring_type` on read_access
3. If readers exist outside the declaring type, that's either reflection access or a bytecode-level escape — worth a closer look

**Workflow 4: understand a JPA mapped field.**

1. `list_fields(entity_type_id)` → see annotated fields
2. **`field_details(field_id)`** → see `@Column` or `@JoinColumn`, the mapped type
3. The mapped type's NodeRef can be used with hierarchical tools to see what else references it

**Workflow 5: investigate a static field.**

1. `find_node("SomeClass.DEFAULT_TIMEOUT")` → field ID
2. **`field_details(field_id)`** → see `is_constant: true`, the type, and which classes (`by_declaring_type` of read_access) consume the constant
3. Useful for "what's the blast radius of changing this constant?"

In each workflow, `field_details` is the resolution step that turns a structural relationship into a complete entity picture. The read/write digest is the differentiator from `method_details` — it captures the *bidirectional* relationship a field has with its accessors, which is typically the most interesting thing about a field.

## Implementation notes

A few specifics worth flagging during implementation:

**Cypher query shape.** A single match with optional matches and a couple of aggregations:

```cypher
MATCH (f:Field) WHERE id(f) = $field_id
OPTIONAL MATCH (declarer:Type)-[:DECLARES]->(f)
OPTIONAL MATCH (f)-[:OF_TYPE]->(fieldType)
OPTIONAL MATCH (f)-[:ANNOTATED_BY]->(annotation:Type)
WITH f, declarer, fieldType, collect(distinct annotation) AS annotations
OPTIONAL MATCH (reader:Method)-[:READS]->(f)
OPTIONAL MATCH (readerDecl:Type)-[:DECLARES]->(reader)
WITH f, declarer, fieldType, annotations,
     count(distinct reader) AS read_count,
     collect(distinct {method: reader, declarer: readerDecl})[..10] AS read_sample,
     (aggregate by readerDecl with count) AS read_by_type
OPTIONAL MATCH (writer:Method)-[:WRITES]->(f)
OPTIONAL MATCH (writerDecl:Type)-[:DECLARES]->(writer)
RETURN f, declarer, fieldType, annotations,
       read_count, read_sample, read_by_type,
       (similar aggregations for writes) AS write_data
```

(Pseudo-Cypher; the exact syntax for the aggregations needs care, and the schema details depend on jQAssistant's actual relationship names — `READS`/`WRITES`/`READS_FIELD`/`WRITES_FIELD` etc.)

The key insight: do everything in one query. Multiple round-trips per `field_details` call would be too expensive for an operation that's expected to be cheap.

**Sample selection.** The 10-method sample should be *representative*, not necessarily a specific subset. The simplest implementation is "first 10 by some stable order" (e.g., by method qualified name). This is fine for v0.2; if usage shows the LLM wants more sophisticated sampling (e.g., one method per declaring type), revisit.

**`by_declaring_type` computation.** This requires grouping reader/writer methods by their declaring type and counting. Cap at 10 entries (after sorting descending by count) to bound response size. For fields with more than 10 declaring types contributing, add an `others_count` field on the summary indicating how many more types exist.

**Distinguishing zero readers/writers.** When a field has zero readers (or zero writers), the response should have `method_count: 0`, `methods_sample: []`, `sample_truncated: false`, `by_declaring_type: []`. All fields present, all empty. Don't return `null` for the access objects themselves — that's a different shape, and the LLM has to handle it specially. Empty-but-present is cleaner.

**`is_constant` derivation.** Same as `list_fields`: `modifiers.contains("static") && modifiers.contains("final")`. Compute server-side.

**Annotation handling.** Same wrapper-object pattern as `method_details` (`{type: NodeRef}`) so future expansion to annotation values is non-breaking.

**Missing source location.** Synthetic fields (compiler-generated) may not have source positions. Return `location: null` rather than fabricating one.

**Testing checklist:**

- Field with no readers, no writers (orphaned field — code smell but valid)
- Field with one reader, one writer (typical private field)
- Field with many readers (logger, common service) — verify sample truncation works and `by_declaring_type` is correctly populated
- Field with many writers — verify same pattern
- Static final field (constant) — verify `is_constant: true`
- Volatile / transient fields — verify these modifiers appear correctly
- Field with primitive type (`int`, `boolean`) — verify `id: null` handling
- Field with generic type (`List<String>`) — verify erasure handling
- Annotated field (Spring `@Autowired`, JPA `@Column`) — verify `annotations` is populated
- Field inherited via include_inherited from `list_fields` — verify `field_details` on the returned ID returns the field's actual declaring type
- Bad inputs: non-existent ID, method ID passed as `field_id`, type ID passed as `field_id` — verify appropriate error codes
- Zero-read field where the field is actually used (reflection, framework injection) — document this as a known false-negative since jQAssistant only captures bytecode-visible access
