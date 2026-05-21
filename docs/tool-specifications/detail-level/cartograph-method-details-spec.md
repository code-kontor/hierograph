# Cartograph: `method_details` Specification

This is the complete specification for `method_details`, one of the detail-level tools introduced in v0.2. It is the "open file" equivalent for a single method — given a method's node ID, return everything Cartograph can say about it structurally, in one call.

This specification builds on conventions established in `mcp-tools.md` (NodeRef, node IDs, JSON precision) and the architectural framing in `detail-level-tools.md` (the hierarchical/detail/code layer model). It parallels `field_details` closely, but with one important encoding difference: `method_details` keeps inline NodeRefs. A single-entity response with one declaring type does not benefit from a `nodes` wrapper map — the overhead exceeds the savings, since each referenced node appears at most once. `field_details`, by contrast, has reader/writer methods that can repeat across samples and types that repeat in `by_declaring_type`, so it does use slim encoding.

## Purpose

`method_details` returns the full structural information about a single method: its modifiers, return type, parameters, declared exceptions, annotations, and the method (if any) it overrides. Plus a source location so the LLM can navigate to the implementation via its own file-reading tools.

This is the natural follow-up tool after `list_methods` (which returns lightweight summaries) or `detail_dependencies` (which surfaces method IDs in its result edges). The LLM uses `method_details` when it has narrowed to a specific method and needs the complete picture before reasoning about it.

It's deliberately *not* paginated and doesn't take filter parameters — it's a single-entity inquiry. If the response includes lists (parameters, throws, annotations), those lists reflect the method's full structure, not a subset.

## Parameters

```
method_details(
    method_id: long           // required
)
```

### `method_id` (required)

The node ID of the method to inspect. Must be a method-kind node (`java.method`). Other kinds return an error.

Typically obtained from prior queries — `list_methods` returns method IDs on each entry, and `detail_dependencies` returns them as the `from` field on edges originating from methods (and as the `to` field for `calls` and `overrides` relationships).

If the node ID is unknown or stale, the tool returns a structured error rather than empty results.

## Response shape

This is a single-entity response with inline NodeRefs. No `nodes` wrapper map — see the rationale above. If a future version adds reader/caller digests (analogous to `field_details.read_access`), revisit the encoding then.

```json
{
  "method": {
    "id": 91204,
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
  "modifiers": ["public", "synchronized"],
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
      "name": "newState",
      "type": {
        "id": 38104,
        "name": "ClusterState",
        "qualified_name": "org.elasticsearch.cluster.ClusterState",
        "kind": "java.class"
      },
      "annotations": []
    },
    {
      "position": 1,
      "name": "listener",
      "type": {
        "id": 38201,
        "name": "ActionListener",
        "qualified_name": "org.elasticsearch.action.ActionListener",
        "kind": "java.interface"
      },
      "annotations": [
        {
          "type": {
            "id": 12001,
            "name": "Nullable",
            "qualified_name": "org.elasticsearch.common.Nullable",
            "kind": "java.annotation"
          }
        }
      ]
    }
  ],
  "throws": [
    {
      "id": 88301,
      "name": "IllegalStateException",
      "qualified_name": "java.lang.IllegalStateException",
      "kind": "java.class"
    }
  ],
  "annotations": [
    {
      "type": {
        "id": 12101,
        "name": "Override",
        "qualified_name": "java.lang.Override",
        "kind": "java.annotation"
      }
    },
    {
      "type": {
        "id": 12102,
        "name": "Transactional",
        "qualified_name": "org.springframework.transaction.annotation.Transactional",
        "kind": "java.annotation"
      }
    }
  ],
  "overrides": {
    "id": 91005,
    "name": "applyState",
    "qualified_name": "org.elasticsearch.cluster.AbstractClusterService.applyState",
    "kind": "java.method",
    "parent_id": 47200,
    "parent_kind": "java.class"
  },
  "location": {
    "absolute_path": "/Users/gerd/elasticsearch/server/src/main/java/org/elasticsearch/cluster/ClusterService.java",
    "relative_path": "server/src/main/java/org/elasticsearch/cluster/ClusterService.java",
    "workspace_root": "/Users/gerd/elasticsearch",
    "line_number": 247
  }
}
```

### Field-by-field

**`method`** — Full NodeRef for the method itself. Same shape as everywhere else in the API.

**`declaring_type`** — Full NodeRef for the type that declares this method. Always present. Same as `method.parent_id` resolved to a full NodeRef — surfaced separately because the LLM commonly wants to navigate up to the type, and an explicit field beats requiring a separate `find_node` call.

**`modifiers`** — List of Java modifier keywords, in canonical order: visibility first (`public`/`protected`/`private`/`package-private`), then other modifiers (`static`, `final`, `abstract`, `synchronized`, `native`, `default`). Same convention as `list_methods`.

**`is_constructor`** — Boolean. Constructors are technically methods in the bytecode model but are usually reasoned about differently; surfacing this flag explicitly saves the LLM from inferring it from the method name.

**`return_type`** — NodeRef for the return type. For primitives (`int`, `boolean`, `void`, etc.), the NodeRef has `id: null` and `kind: "java.primitive"` — primitives aren't first-class entities in the graph but appear in this position because the LLM needs to know the return type. For reference types, the full NodeRef is populated and the LLM can use it as input to other tools (e.g., `find_node` on the qualified name, or `aggregated_incoming(return_type.id)`).

**`parameters`** — List of parameter objects, in declaration order. Each parameter has:

- `position` — zero-based index. Useful when the parameter list is long or when the LLM wants to reference "the second parameter."
- `name` — the parameter's declared name (`newState`, `listener`, etc.). Note: parameter names are sometimes lost in compilation; if jQAssistant captured `arg0`-style synthetic names instead, this field reflects that.
- `type` — NodeRef for the parameter's type. Same primitive-handling as `return_type`.
- `annotations` — list of annotations on this specific parameter (Spring `@RequestParam`, JPA `@Valid`, etc.). Each entry has a single `type` field with the annotation type's NodeRef. Empty list if no annotations.

Empty `parameters` array (not `null`) for no-argument methods.

**`throws`** — List of NodeRefs for the declared exception types. In declaration order. Empty list if no checked exceptions are declared. Note: this is the *declared* throws clause, not what the method might actually throw at runtime — that's a different question.

**`annotations`** — List of annotations on the method itself (not on parameters; those are in `parameters[].annotations`). Each entry has a `type` field with the annotation type's NodeRef. Empty list if none.

The reason `annotations` entries use `{type: NodeRef}` rather than just being NodeRefs directly: this leaves room for adding annotation values/attributes in a later version without changing the shape. For v0.2, only the annotation type is captured. For v0.3+, we might add an `attributes` field showing the actual values (e.g., `@RequestMapping(path = "/users", method = GET)` → `attributes: {path: "/users", method: "GET"}`). The wrapper makes that future addition non-breaking.

**`overrides`** — NodeRef for the method this method overrides, if any. `null` if the method doesn't override (constructors, static methods, methods declared on `Object` only, etc.). When present, this is a strong signal of structural commitment — the LLM should reason about polymorphism, contract changes, etc., when this field is non-null.

Note: only the *immediate* override is reported. For deeper override chains (this method overrides A, which overrides B), call `method_details` on the returned overrides node to traverse upward.

**`location`** — File and line number for the method's declaration. Same shape as locations in `detail_dependencies`: absolute path, relative path, workspace root, line number. The line number points to the method's declaration line (typically the line with the signature), not the body.

May be `null` for methods without source-level information — synthetic methods generated by the compiler, methods from class files without debug info, etc. The field is always present in the response, but its value can be `null`.

## Semantics

A few details worth being explicit about:

### Primitive types

Java primitives (`void`, `int`, `boolean`, `long`, `double`, `float`, `char`, `short`, `byte`) appear in `return_type` and `parameters[].type` positions but aren't first-class nodes in the graph. The NodeRef uses `id: null` and `kind: "java.primitive"` to signal this.

The LLM should not try to use a primitive NodeRef as input to other tools — those calls would fail because there's no node with `id: null`. The tool description should mention this so the LLM understands when navigation is meaningful and when it isn't.

If using `id: null` is problematic for the schema, an alternative is to use a sentinel ID (-1 or similar) with a clear `kind` marker. Decide based on what plays best with the rest of the implementation; the semantic intent is the same.

### Generic types

Java generics complicate the simple "parameter has a type" picture. A parameter of type `List<String>` has an erasure (`java.util.List`) and a type parameter (`java.lang.String`). jQAssistant captures both, but the API design needs to decide how to surface them.

For v0.2: the `type` field reports the erased type (`java.util.List` for `List<String>`). Type parameters aren't surfaced in `method_details`. This is a deliberate simplification — generics are important but complex enough to deserve their own design discussion, and the LLM can usually infer generics from the source file via `location` + file reading.

A future version (v0.3+) could add a `type_arguments` field on parameters: `type_arguments: [NodeRef]`. Not in v0.2.

### Overloaded methods

A class can have multiple methods with the same name (`add(int)`, `add(String)`, `add(Object)`). Each has its own unique node ID, and `method_details` returns the details for the specific ID provided. There's no ambiguity at the ID level.

If the LLM wants "all the overloads of `add`," it calls `list_methods` with `name_pattern: "add"` to get the list, then `method_details` on each.

### Inherited methods

`method_details` returns details for the method *as declared on its declaring type*. If the method ID was obtained via `list_methods(type_id, include_inherited: true)`, the `declaring_type` field reveals where the method was actually declared (which may differ from the type the LLM listed methods on).

There's no concept of "this method as inherited by class X" with different details — it's the same method, declared once, accessible via inheritance.

### Constructor handling

Constructors are methods with `is_constructor: true`. Their `return_type` is the declaring type itself (this is the JVM-level representation, though Java syntax doesn't write a return type for constructors). Their `name` is the simple type name (e.g., `ClusterService` for the constructor of `ClusterService`).

The LLM should recognize the constructor pattern; the `is_constructor` flag makes this explicit so it doesn't have to infer.

## Error cases

The tool returns a structured error in these cases:

**`NODE_NOT_FOUND`** — `method_id` doesn't exist in the graph. Suggests the LLM should re-resolve via `find_node` or a fresh `list_methods` call.

**`WRONG_NODE_KIND`** — `method_id` exists but isn't a method-kind node (e.g., the LLM accidentally passed a field ID or a type ID). The error includes the actual kind for context.

Each error includes a human-readable message and a structured `code` field for programmatic handling.

## Performance characteristics

Unlike the hierarchical tools, `method_details` queries Neo4j directly at request time. Expected behavior:

- **Typical method**: a few parameters, maybe one or two annotations, zero or one overrides. Response under 50ms, payload a few hundred bytes.
- **Heavy method** (many parameters, many annotations, generics): still fast — the single-entity query is small regardless. Response under 100ms.
- **Cold cache**: first call to `method_details` after server startup may be slower (a few hundred ms) as Neo4j caches the query plan; subsequent calls fast.

The underlying Cypher query is a single match-and-traverse: match the method node, then in optional matches collect its parameters, throws, annotations, and override target. One query, one round-trip.

There's no need for in-memory caching or precomputation. The query is cheap enough to run on every call.

## Description for the tool registration

This is the text exposed to the LLM via MCP.

> Return the full structural details of a single method, in one call. Use this when you've identified a method of interest (via `list_methods`, `detail_dependencies`, or another tool that surfaces method IDs) and need the complete picture: parameters with names and types, declared exceptions, annotations, the method it overrides (if any), and source location.
>
> The response includes the declaring type as a NodeRef, so you can navigate back to the hierarchical model. Parameter types and return type are also NodeRefs — you can feed these into other tools to investigate, e.g., `find_node` on the qualified name or `aggregated_incoming` to see what depends on a particular parameter type.
>
> Use the `location` field together with your file-reading tools when you need to inspect the actual method implementation (the line number points to the method declaration; the body follows from there).
>
> When to use this vs. neighboring tools:
>
> - For the methods declared on a type (composition, not single-method detail), use `list_methods`.
> - For "which methods call this one?" or "which methods throw this exception?", use `detail_dependencies` — that's the dependency-driven view rather than the entity-detail view.
> - For fields rather than methods, use `field_details` (parallel tool, parallel shape).

## Integration with the broader workflow

`method_details` typically appears at the *end* of an investigation, after the LLM has narrowed to a specific method:

**Workflow 1: drill down from an aggregated dependency.**

1. `aggregated_outgoing(some_module)` → see what the module depends on
2. `outgoing_core_dependencies(some_module, target_module)` → which type pairs contribute
3. `detail_dependencies(some_module, target_module)` → method/field-level edges
4. **`method_details(specific_method_id)`** → understand one specific method's full structure
5. Read the actual code via Claude's file tools, using `location`

**Workflow 2: investigate a specific method by name.**

1. `find_node("ClusterService.applyState")` → method ID
2. **`method_details(method_id)`** → see modifiers, parameters, throws, annotations
3. Note the `overrides` field: this overrides `AbstractClusterService.applyState` — go up the chain
4. `method_details(overrides_id)` → see the parent contract

**Workflow 3: understand a framework-annotated method.**

1. `list_methods(type_id)` → see method list with `annotation_count` per method
2. Notice one method has `annotation_count: 3` — interesting
3. **`method_details(method_id)`** → see the actual annotations (`@Transactional`, `@PreAuthorize`, etc.)
4. Read the method body to understand the framework behavior

**Workflow 4: trace an override chain.**

1. `method_details(method_id)` → see `overrides: someParentMethod`
2. `method_details(someParentMethod.id)` → see if *that* overrides something further up
3. Recurse until `overrides: null` — that's the root of the contract

In each workflow, `method_details` is the resolution step — the LLM has identified the entity, and now needs all the structural facts about it in one place. The `location` field then connects back to source-level reading when actual implementation details matter.

## Implementation notes

A few specifics worth flagging during implementation:

**Cypher query shape.** A single match with optional matches for the auxiliary data:

```cypher
MATCH (m:Method) WHERE id(m) = $method_id
OPTIONAL MATCH (m)-[:DECLARES]-(declarer:Type)
OPTIONAL MATCH (m)-[:RETURNS]->(returnType)
OPTIONAL MATCH (m)-[:HAS]->(p:Parameter)-[:OF_TYPE]->(paramType)
OPTIONAL MATCH (p)-[:ANNOTATED_BY]->(paramAnnotation:Type)
OPTIONAL MATCH (m)-[:THROWS]->(thrown:Type)
OPTIONAL MATCH (m)-[:ANNOTATED_BY]->(annotation:Type)
OPTIONAL MATCH (m)-[:OVERRIDES]->(overridden:Method)
RETURN m, declarer, returnType, p, paramType, paramAnnotation, thrown, annotation, overridden
```

The exact relationship names depend on jQAssistant's schema; verify before committing. The query should be structured so a single match-and-collect produces all the data — avoid N+1 queries per parameter or per annotation.

**Parameter ordering.** Parameters have a `position` attribute (zero-based) on the `Parameter` node in jQAssistant. Order results by this attribute, not by traversal order, which can be undefined.

**Primitive handling.** jQAssistant typically represents primitives as `Type` nodes with `fqn: "void"`, `fqn: "int"`, etc. The tool's response normalizes these to the `id: null, kind: "java.primitive"` form. Decide how to handle the missing ID — null is cleaner, but if your DTO framework hates nulls, use a sentinel.

**`is_constructor` derivation.** Likely a `Method` node property in jQAssistant (`isConstructor` boolean) or inferable from the method name matching the declaring type name. Use whichever is reliable.

**Missing source location.** Synthetic methods (compiler-generated `<clinit>`, `<init>`, default methods, etc.) may not have source positions. Return `location: null` rather than fabricating one.

**Override chain depth.** The query returns only the *immediate* override. If the LLM wants the chain, it makes multiple calls. Don't try to recurse server-side — that loses the "one round-trip per detail call" simplicity and produces unbounded responses on deep hierarchies.

**Annotation values.** v0.2 captures only the annotation *type*, not its parameter values. The wrapper-object structure (`{type: NodeRef}`) leaves room for future expansion to `{type: NodeRef, attributes: {...}}`. Don't add the attributes in v0.2; it requires significantly more jQAssistant query work and the v0.2 scope is "structural facts only."

**Testing checklist:**

- Method with no parameters, no annotations, no overrides (`Object.hashCode()` is a good test)
- Constructor (verify `is_constructor: true`, return type is the declaring type)
- Method with many parameters and parameter annotations (`@RequestMapping` controller method)
- Method that overrides a parent method (verify `overrides` is populated)
- Method with primitive return type (`void`, `int`) — verify `id: null` handling
- Method with generic parameters (`List<String>`) — verify erasure handling
- Synthetic method (no source position) — verify `location: null` returned cleanly
- Bad inputs: non-existent ID, field ID passed as `method_id`, type ID passed as `method_id` — verify appropriate error codes
