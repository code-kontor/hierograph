# Cartograph: Dependency Drill-Down Strategy

## Two Abstraction Levels

The cartograph tools operate at two distinct abstraction levels:

### Hierarchical level (Projects, Artifacts, Packages, Types)

Tools: `dependency_between`, `aggregated_outgoing`, `aggregated_incoming`, `pairwise_dependencies`, `outgoing_core_dependencies`, `incoming_core_dependencies`

- Work on any node pair from projects down to types
- Cheap to call, return weight + existence
- Use these for scanning, overview, and narrowing scope

### Detail level (Methods, Fields)

Tool: `detail_dependencies`

- Goes *below* types into methods and fields
- Returns individual edges with source locations (file + line number)
- Can produce hundreds or thousands of edges for broad scopes
- Expensive — must be used carefully

## The Narrowing Workflow

When analyzing dependencies between two modules, follow this top-down workflow:

### Step 1: Get the summary

Call `detail_dependencies(fromId, toId, limit=1)`.

Setting `limit=1` returns only 1 edge but includes the **full summary**: total edge count, breakdown by relationship kind, by source type, by source nodes, and by target nodes. This gives you the lay of the land without flooding context.

### Step 2: Assess the scope

- **If total_edges < ~50:** You can fetch the full detail in one call. Increase the limit and you're done.
- **If total_edges > ~100:** Do NOT fetch all edges. Proceed to narrowing.

### Step 3: Narrow using hierarchical tools

Use `list_children` or `list_descendants` on the source and/or target to discover sub-nodes (artifacts, packages, types).

Then use `dependency_between` to check which sub-node pairs actually carry weight. This is a cheap call that returns existence + weight for a single pair. Use it to scan candidates quickly.

Example:
```
list_children(sourceId)          -> [artifact_a, artifact_b]
dependency_between(artifact_a, targetId) -> weight: 360
dependency_between(artifact_b, targetId) -> weight: 24
```

Now you know `artifact_a` is where the coupling lives.

### Step 4: Repeat

Run `detail_dependencies(narrower_fromId, toId, limit=1)` on the narrower pair. Check the summary again. If still too many edges, narrow further — drill from artifact to package, from package to type.

### Step 5: Fetch full details

Once total_edges is manageable (< ~50), call `detail_dependencies` with a sufficient limit to retrieve all edges. These include:
- Source method/field and its declaring type
- Target method/field and its declaring type
- Relationship kind (calls, parameter_type, overrides, throws, reads_field, etc.)
- Source location (file path + line number)

## Additional Narrowing Options

Beyond narrowing source/target scope, you can also filter by:

- **Relationship kind** — pass the `relationship` parameter to `detail_dependencies` to see only `calls`, `parameter_type`, `overrides`, `throws`, `reads_field`, `writes_field`, `returns`, `has_type`, or `annotated_by`
- **Source type** — filter results by `from_parent` to focus on edges from a specific class

## Inherited Edges

The `total_edges` count from `detail_dependencies` includes **inherited edges** — edges from ancestor types (via EXTENDS/IMPLEMENTS) that are outside the from-subtree.

This means `total_edges` can be higher than the sum of `by_source_nodes` weights. The difference is inherited edges. Types in `by_source_type` whose qualified names belong to the *target* module's package are inherited, not physically in the source.

## Quick Reference

| Goal | Tool | Cost |
|---|---|---|
| Does A depend on B? How much? | `dependency_between` | Cheap |
| What does A depend on? (overview) | `aggregated_outgoing` | Cheap |
| What depends on B? (blast radius) | `aggregated_incoming` | Cheap |
| All-pairs coupling matrix | `pairwise_dependencies` | Cheap |
| Summary of method/field edges | `detail_dependencies(limit=1)` | Cheap |
| Full method/field edges | `detail_dependencies(limit=N)` | Expensive |
| Browse sub-nodes | `list_children` / `list_descendants` | Cheap |
| Find a node by name | `find_node` | Cheap |
