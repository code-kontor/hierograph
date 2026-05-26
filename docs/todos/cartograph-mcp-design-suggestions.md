# Cartograph MCP — design suggestions

Notes captured after a session where Claude built a top-level DSM from the
DARE module graph the slow way (53 parallel `aggregated_outgoing` calls and
hand-stitched matrix output) before being told that `pairwise_dependencies`
exists and does the whole thing in one call.

Two distinct problem areas surfaced:

1. **Discoverability** — the right tool exists but is not chosen.
2. **Payload shape** — when the right tool is chosen, its response is
   ~3× larger than it needs to be, which forced a side-channel file read
   on a fairly small graph.

---

## 1. Discoverability fixes (tool-description engineering)

### 1.1 Put the canonical user-vocabulary term in the description

The failure mode: Claude was asked for a **DSM**. The string "DSM" does not
appear anywhere in the `pairwise_dependencies` description. The description
opens with *"Bundled pairwise dependency analysis over a node set"* — accurate
but no lexical hook for the user's term.

**Fix:** lead with the use case, in the words callers actually use.

> **Use this for: dependency structure matrix (DSM), module coupling matrix,
> cycle detection across a module set, layering analysis.** Bundled pairwise
> dependency analysis over a node set…

Other tools already do this well. `aggregated_incoming` opens with *"The
primary blast-radius tool"* — that's the right pattern. Apply it consistently.

Suggested term-to-tool mapping to bake into descriptions:

| User term | Tool |
|---|---|
| DSM, dependency matrix, coupling matrix, layering check | `pairwise_dependencies` |
| Blast radius, "what depends on X", fan-in | `aggregated_incoming` |
| Fan-out, "what does X depend on" | `aggregated_outgoing` |
| Find by name, locate, "where is X" | `find_node` |
| List all X in subtree | `list_descendants` |

### 1.2 Add explicit anti-patterns

`list_children` is the gold standard here:

> Do NOT use this tool recursively to enumerate descendants across multiple
> levels. If you find yourself wanting to call list_children more than once
> or twice to walk down a tree, you're using the wrong tool…

That kind of stop-sign actually works. Add equivalents to the other tools:

**`aggregated_outgoing`**

> **Do NOT loop this over many source nodes to build a matrix.** If you want
> pairwise dependencies among a module set (DSM, coupling analysis, cycle
> check), use `pairwise_dependencies` — one call, plus server-computed
> DAG/SCC analysis.

**`dependency_between`** (presumably exists as a pair query)

> **Do NOT call N² times for a module set.** Use `pairwise_dependencies` with
> a `nodeIds` list instead.

The anti-pattern sentence is more effective than the positive recommendation
in the destination tool. It catches the wrong-tool decision at the moment
it's being made.

### 1.3 Make `describe_graph` emit dynamic recipes

`describe_graph` is the first call for orientation. Its current response
covers what's in the graph but not what to do with it. Add a
`suggested_workflows` block to the *response payload* so the dispatch logic
arrives at the moment of decision, not buried in docs:

```jsonc
{
  "scope": { ... },
  "node_count_total": 17346,
  ...
  "suggested_workflows": {
    "build_dsm": {
      "tool": "pairwise_dependencies",
      "example": "nodeIds=[<top-level project ids from this response>]",
      "use_when": "DSM, coupling matrix, cycle/layer check across modules"
    },
    "blast_radius": {
      "tool": "aggregated_incoming",
      "use_when": "what depends on a module"
    },
    "find_by_name": {
      "tool": "find_node",
      "use_when": "first lookup when the user names a class/package"
    }
  }
}
```

Dynamic recipes in tool *output* beat static description text because they're
seen exactly at the point of decision, not at the point of tool registration.

### 1.4 Use the MCP `instructions` field

MCP servers can return an `instructions` string in their `initialize`
response; Claude Code surfaces it. A 5-line "common task → tool" table
there sits upstream of any individual tool description and is read on every
session that loads the server.

Example:

> Cartograph maps a dependency graph (Java/Maven projects). Common tasks:
> - DSM / coupling matrix → `pairwise_dependencies`
> - Blast radius → `aggregated_incoming`
> - First lookup by name → `find_node`
> - "All X under Y" → `list_descendants`
> - Orientation when graph is unfamiliar → `describe_graph` first

### 1.5 Tool naming

`pairwise_dependencies` is precise but does not ring the "matrix" bell.
Candidates if a rename is on the table:

- `dependency_matrix`
- `module_coupling_matrix`

If renaming would break callers, alias is fine — the lexical match is what
matters.

### 1.6 Worked example in every description

The single most reliable nudge for tool selection. Add one canonical
invocation example per tool:

> **Example:** `pairwise_dependencies(nodeIds=[ids of top-level projects
> from describe_graph])` → returns the DSM edge list, plus density,
> cycle status, and topological order.

Models pattern-match on examples more reliably than on prose.

---

## 2. Payload shape — slim node-id references

In the DARE session, `pairwise_dependencies` over 68 modules returned a
145,960-character response — over the inline limit, so Claude had to spill
to a file and slice it. The graph itself is small (68 nodes, 387 edges).
The size came from repeated serialization of node bodies.

### Where the bloat is

Every node currently appears in three places:

1. The top-level `nodes` array (correctly once).
2. The `summary.topological_order` array — as full node objects.
3. Both endpoints of every edge — as full node objects, on all 387 edges.

68 unique modules become ~840 serialized copies. Most of the response is
duplicate `qualified_name` strings.

### Proposed shape

```jsonc
{
  "nodes": {
    "5625164": { "name": "...", "qualified_name": "...", "kind": "Project" },
    "5625163": { ... }
  },
  "edges": [
    { "from": 5625164, "to": 5625163, "weight": 5 },
    ...
  ],
  "summary": {
    "node_count": 68,
    "edge_count": 387,
    "total_weight": 188760,
    "max_edge_weight": 20305,
    "density": 0.085,
    "has_cycles": false,
    "strongly_connected_components": [],
    "topological_order": [12747, 4, 5, ...]
  }
}
```

Rules:

- **Edges carry id references**, not embedded node bodies.
- **`nodes` is the single source of display info**, keyed by id so lookup
  is O(1) — array is fine too, but a map removes the "find the entry"
  step downstream.
- **`topological_order` is a list of ids**, not node objects.
- **`strongly_connected_components` is a list of id lists.**

Estimated size reduction: ~60–70%. The session's 145k response would have
dropped to roughly 45–55k — well inside any inlining budget.

### What this buys

- **Stays inline** for typical module-set sizes, no side-channel needed.
- **Standard graph-data idiom** — GraphML, Gephi, d3-force, NetworkX,
  JGraphT all use nodes-dict + id-referencing edges. No surprises for
  post-processors.
- **One place owns names.** No risk of one edge endpoint showing a stale
  `qualified_name` while another shows fresh data.

### Per-edge `in_cycle` — reconsider

Currently every edge has `"in_cycle": false | true`. When `has_cycles` is
false this is 387 redundant booleans. When `has_cycles` is true, what
callers usually want is *which nodes participate in a cycle* — that is
already conveyed by `strongly_connected_components`.

Options:

1. **Drop `in_cycle`.** SCC membership at node level is the answer to almost
   every cycle question.
2. **Move it to `summary`** as `cycle_edges: [[from_id, to_id], ...]` —
   only populated when cycles exist, only the edges that close cycles.
3. **Keep it** if there is a specific callsite that needs per-edge cycle
   marking. Worth checking before defending it.

### Do you need a separate name-hydration tool?

No. `nodes` already ships in the same response. The slim-edges change is
purely an *encoding* change inside one response. There is no extra call
required for callers to resolve names — they look them up in `nodes`.

### Caveat

If the audience for the raw JSON is a human grepping with `jq`, embedded
edge endpoints are slightly easier to eyeball. But:

- `jq` joins are one-liners.
- The dominant consumer is an LLM, where parser-cost and token-cost both
  favor the slim form.

The human-grep argument is weak. Slim wins.

---

## Priority

If only one change were made: **put "DSM" in `pairwise_dependencies` and
add the anti-pattern sentence to `aggregated_outgoing`.** Either alone
would have steered Claude correctly in this session.

If a second change: **slim the payload shape.** Even with the right tool
selected, the current response is large enough to force out-of-band
handling for graphs that are not actually large.

Everything else (dynamic recipes in `describe_graph`, MCP `instructions`,
tool renames) is incremental polish on top of those two.
