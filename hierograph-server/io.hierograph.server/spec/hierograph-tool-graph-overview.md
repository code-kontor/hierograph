# Hierograph Tool `graph_overview` — Specification

`graph_overview` is Hierograph's **orientation tool**: one call that tells a language
model what the loaded graph contains, which modules it has and how they are coupled,
and what the kinds and relationships returned by every other tool mean. A model that
starts a session without knowing the codebase calls it first.

This document explains why the tool exists, what it returns, and how to use the
answer. Concepts shared by all tools (node kinds, node references, errors) are defined
in the [MCP tools specification](hierograph-mcp-tools.md).

---

## Contents

1. [Purpose](#1-purpose)
2. [Parameters](#2-parameters)
3. [Behaviour](#3-behaviour)
4. [Response](#4-response)
5. [Errors](#5-errors)
6. [Working with the tool](#6-working-with-the-tool)
7. [Guarantees](#7-guarantees)

---

## 1. Purpose

A model that is asked about an unfamiliar codebase has neither ids to pass to the
navigation tools nor the vocabulary to read their answers. `graph_overview` supplies
both in one response: counts by kind, the list of modules with their ids and sizes,
the kind and relationship vocabularies, and a short description of the three levels at
which the dependency tools work.

**One call, no inputs.** The tool needs no arguments and always describes the whole
graph. There is nothing to get wrong, so it is a safe first call.

**Modules as entry points.** The `hierarchy` section lists every module with its id,
name, kind and counts (see §4). Those ids feed `list_children`, `list_descendants` and
`pairwise_dependencies` directly, without a `find_node` lookup.

**Within the response budget.** On a large codebase the module list is most of the
response. An entry therefore carries only what orientation needs, and on a very large
codebase `hierarchy` is paged like the paginated tools' results
([MCP tools specification, §7](hierograph-mcp-tools.md#7-pagination)).

**Self-describing results.** The `kinds` and `relationships` sections define the values
that appear in other tools' responses, so a model can interpret `java.record` or
`reads_field` without prior knowledge.

**`graph_overview` and `list_children`.** Called with the root's id, `list_children`
also returns the modules. The two differ in what they add:

| | `graph_overview` | `list_children` on the root |
|---|---|---|
| Modules with ids and sizes | yes (id, name, kind, counts) | yes (enriched node references) |
| Coupling counts per module | yes | no |
| Graph-wide counts by kind and attribute | yes | no |
| Kind and relationship vocabularies | yes | no |
| Filters and limit | no | yes |

1. `graph_overview` answers "what is this codebase?"; `list_children` answers "what
   is inside this node?".
2. Only `graph_overview` returns the vocabularies and the build state, which a model
   needs once per session.
3. Only `list_children` filters, which matters below the module level.

---

## 2. Parameters

| Parameter | Type | Required | Default | Meaning |
|-----------|------|----------|---------|---------|
| `view` | string | no | none | the view to answer in |
| `limit` | integer | no | `500` | hierarchy entries per page, clamped to 1–1,000 |
| `cursor` | string | no | first page | `next_cursor` of the previous page, for the rest of the hierarchy |

**`view`** is the parameter every tool that reads the graph takes
([MCP tools specification, §5](hierograph-mcp-tools.md#5-parameters)). Without
it the tool describes the complete loaded graph as scanned; with it, the same graph in
that [view](../../io.hierograph.server.views/spec/hierograph-views.md): `hierarchy` lists the view's top level — its groups and the nodes no
group takes — and the response gains a `view` section. The statistics count the view's
hierarchy: nodes a view excludes, and their dependencies, are not counted.

**`limit`** and **`cursor`** page the `hierarchy`. They are rarely needed: the default
of 500 entries holds the top level of almost any codebase, and a page holds fewer when
they would not fit the [response budget](hierograph-mcp-tools.md#73-the-response-budget).
The first page carries the whole overview; a later page carries only the rest of
`hierarchy` with its `summary`. A cursor is bound to the loaded graph and the view.

**Why nothing else.** An orientation call that needs arguments needs knowledge the caller does
not have yet. Everything that could be narrowed (a module, a kind) is the job of the
navigation tools that follow.

---

## 3. Behaviour

The tool walks the containment tree once for counts and once more for edges, from the
synthetic root. The root itself is not counted, so:

- `total_nodes` and `nodes_by_kind` count every node: modules (including the synthetic
  `External Types` module), packages, types, members and external types. The counts
  add up to `total_nodes`.
- `nodes_by_kind` includes `external.type` and the packages under `External Types`.
- `total_type_level_edges` counts every type-level dependency once, at its source.
  `type_level_edges_by_attribute` counts each edge once per attribute flag it carries,
  so the attribute counts can add up to more than the total.
- `type_level_weight_by_kind` sums the `kinds` of every type-level dependency: the
  number of references of each kind in the whole graph. Its values add up to the total
  weight of the type-level dependencies.

The `hierarchy` section lists the root's children in the model's order, the
`External Types` module included. For each module:

- `outgoing_dep_count` is the number of type-level dependencies whose source lies in
  the module and whose target lies outside it;
- `incoming_dep_count` is the number whose target lies in the module and whose source
  lies outside it.

Dependencies between two types of the same module count for neither: they are the
module's internal structure, not its coupling. The counts are numbers of dependencies;
their weights between two particular modules come from `aggregated_dependencies` or
`pairwise_dependencies`.

**Example.** The graph holds one module `billing-core` and the `External Types` module.
It has seven type-level dependencies:

| From | To |
|------|----|
| `Invoice` | `Ledger` |
| `Invoice` | `Invoice$Line` |
| `Invoice` | `Money` |
| `Invoice` | `java.util.List` |
| `Invoice$Line` | `Money` |
| `Ledger` | `Invoice` |
| `Money` | `java.math.BigDecimal` |

Five of them stay inside `billing-core`. The two into `java.util.List` and
`java.math.BigDecimal` leave it, so its `outgoing_dep_count` is `2`; nothing outside
depends on it, so its `incoming_dep_count` is `0`. `External Types` has `0` outgoing
and `2` incoming.

The `kinds`, `relationships` and `model` sections are fixed text: they are the same
for every graph.

---

## 4. Response

```json
{
  "stats": {
    "total_nodes": 28,
    "nodes_by_kind": {
      "java.module": 2, "java.package": 7, "external.type": 2, "java.class": 2, "java.method": 9,
      "java.field": 4, "java.interface": 1, "java.record": 1
    },
    "total_type_level_edges": 7,
    "type_level_edges_by_attribute": {
      "is_extends": 0, "is_implements": 0, "is_annotated_by": 0, "is_depends_on_other": 7
    },
    "type_level_weight_by_kind": {
      "has_type": 2, "parameter_type": 1, "returns": 2, "type_argument": 1, "calls": 9, "reads_field": 1, "instantiates": 1
    }
  },
  "kinds": {
    "structural": [
      { "kind": "java.module",  "description": "Build module (Maven/Gradle)" },
      { "kind": "java.package", "description": "Java package; contains sub-packages and types" },
      …
    ],
    "group_aliases": {
      "types":    ["java.class", "java.interface", "java.enum", "java.record", "java.annotation"],
      "members":  ["java.method", "java.field"],
      "packages": ["java.package"]
    }
  },
  "relationships": {
    "type_level_attributes": [
      { "attribute": "is_extends", "description": "At least one type in the source subtree extends a type in the target subtree" },
      …
    ],
    "type_level_kinds": [
      { "kind": "extends", "description": "extends facts of the type and its members" },
      …
      { "kind": "parameter_annotated_by", "description": "parameter_annotated_by facts of the type and its members" },
      …
    ],
    "detail_level": [
      { "kind": "extends", "source": "type", "description": "Type has this superclass" },
      …
      { "kind": "raises", "source": "method", "description": "Method body constructs and throws this exception type (throw new X(...)); not counted in weights", "counted": false },
      { "kind": "overrides", "source": "method", "description": "Method overrides another method (derived at load)", "derived": true },
      …
    ]
  },
  "hierarchy": [
    { "id": 4800, "name": "External Types", "kind": "java.module", "child_count": 1,
      "descendant_type_count": 0, "outgoing_dep_count": 0, "incoming_dep_count": 2 },
    { "id": 4690, "name": "billing-core", "kind": "java.module", "child_count": 1,
      "descendant_type_count": 4, "outgoing_dep_count": 2, "incoming_dep_count": 0 }
  ],
  "summary": { "total": 2, "returned": 2, "truncated": false },
  "model": {
    "aggregation": "Aggregation is pairwise. …",
    "levels": [
      { "name": "aggregated", "description": "…", "tools": ["aggregated_dependencies", "pairwise_dependencies", "levelized_structure"] },
      { "name": "type", "description": "…", "tools": ["outgoing_dependencies", "incoming_dependencies"], "parameter": "…" },
      { "name": "detail", "description": "…", "tools": ["outgoing_dependencies", "incoming_dependencies"], "parameter": "…" }
    ]
  },
  "scan_metadata": { "scanner": "hierograph" },
  "build_state": { "status": "current", "changed_files": [], "build_tool": "maven" }
}
```

The 28 nodes are the modules `billing-core` and `External Types`; the seven packages `com`, `example`, `billing`, `money`, `java`,
`math` and `util`; the types `Invoice`, `Invoice$Line`, `Ledger` and `Money`; nine
methods and four fields; and the external types `BigDecimal` and `List`. The synthetic
root has id `4600`.

| Field | Content | Why |
|-------|---------|-----|
| `stats.total_nodes` | every node except the synthetic root | the size of the codebase in one number |
| `stats.nodes_by_kind` | node count per kind, in order of first occurrence | shows the mix: a codebase of records reads differently from one of interfaces |
| `stats.total_type_level_edges` | number of type-level dependencies | the density of the graph |
| `stats.type_level_edges_by_attribute` | edge count per attribute flag, every flag present | how much coupling is inheritance or annotation rather than use |
| `stats.type_level_weight_by_kind` | number of references per kind over all type-level dependencies, kinds with a count only | what the coupling of the whole graph consists of: calls, signatures, casts, annotation values, … |
| `kinds.structural` | the nine `java.*` node kinds and `external.type`, each with a description | defines the `kind` values of node references |
| `kinds.group_aliases` | each alias with its expansion | tells the caller what `types`, `members` and `packages` select in a kind filter |
| `relationships.type_level_attributes` | the four attribute flags with a description | defines the flags on type-level and aggregated edges |
| `relationships.type_level_kinds` | the seventeen kinds of a type-level `kinds` breakdown, with a description | defines the keys of `kinds` and `by_kind` on type-level and aggregated edges; each is a counted detail-level relationship |
| `relationships.detail_level` | the twenty-one detail-level relationships with their source kind | defines the `relationship` values of detail-level edges and filters; eighteen are stored in the snapshot (`raises` with `"counted": false`, since it adds no weight), three are derived (`"derived": true`): `overrides` at load, `read_by` and `written_by` as reverse views |
| `hierarchy` | every module with `id`, `name`, `kind`, `child_count`, `descendant_type_count`, `outgoing_dep_count` and `incoming_dep_count`; a view group adds `descendant_module_count`, a virtual node `virtual`, a module of compiled tests `is_test` | the ids and sizes needed to start navigating, and a first impression of coupling |
| `summary` | `total`, `returned` and `truncated` for the hierarchy entries; `next_cursor` follows when more remain | whether the module list is complete |
| `model` | how aggregation works and which tools serve each dependency level | lets the model choose the right dependency tool |
| `scan_metadata.scanner` | always `"hierograph"` | names the producer of the graph |
| `build_state` | present only when the server reads a build manifest | tells the caller whether the graph still matches the build files |
| `graph_update` | present only while the server is updating the graph: `pending`, `rescanning`, `applying-manifest` or `reloading` | the graph is about to change, so the answer may be outdated within seconds; no `reload_graph` is needed |
| `view` | present only when called with `view`: the view's name, description, source, file, shadowing, `group_count`, `errors`, `warnings` and its `report`; a view with exclusions adds `excluded_count` and `excluded` (count, outermost nodes), a restructuring view its virtual modules and effect | tells the caller what the view regroups, what it leaves out and what could not be applied |

**A hierarchy entry is a part of an enriched node reference.** It leaves out what every
top-level entry shares — the parent, which is the root — and what orientation rarely
needs: a module's `qualified_name` (its build coordinate, the longest field) and
`descendant_method_count`. On a reactor of 154 modules this halves the list, from
52.6 KB to 25.8 KB (166 characters per entry). `find_node` and `list_children` report the full node
reference.

**`kinds.structural`** lists the ten node kinds of
[§3.2 of the MCP tools specification](hierograph-mcp-tools.md#32-kinds): the nine
`java.*` kinds and `external.type`, so every `kind` a node reference or
`nodes_by_kind` can carry is defined here. In a view it also lists `view.group`.

**`build_state`** has the shape and status values defined in the
[hierarchical graph service specification](../../io.hierograph.server.graphservice/spec/hierarchical-graph-service.md#build-files):
`status` (`current`, `stale`, `regenerating` or `regeneration-failed`), `changed_files`,
`build_tool` when the manifest names one, and `message` after a failed regeneration.
A server that loads a Parquet snapshot omits the key, because it has no build files to
compare.

---

## 5. Errors

| Situation | Response | Recovery |
|-----------|----------|----------|
| No graph is loaded | `{"status": "no_graph_available", …}` (see [§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)) | make the graph source available, then call `reload_graph` |
| `view` names no view | `VIEW_NOT_FOUND`, listing the known views | call `list_views` |
| the view's document does not parse | `VIEW_INVALID`, with its problems | fix the document, define it again |

A `cursor` that cannot be used fails with one of the cursor codes of
[§7.2 of the MCP tools specification](hierograph-mcp-tools.md#72-cursors); apart from
that and `view`, the tool has no inputs to validate.

---

## 6. Working with the tool

**Start a session.** Call `graph_overview` once. Pick the module of interest from
`hierarchy` and pass its `id` to `list_children` or `list_descendants`.

**Rank modules for a structure analysis.** Pass every module id from `hierarchy`,
except `External Types`, to `pairwise_dependencies` to get the dependency matrix
between them.

**Check that the graph is current.** In manifest mode, read `build_state.status`.
`stale` with `changed_files` means the build files changed after the manifest was
written; run the build that writes the manifest, then call `reload_graph`.

**Skip it.** A model that already knows the codebase and its ids can go straight to
`find_node`.

---

## 7. Guarantees

1. **Whole graph.** The response always describes the complete loaded graph.
2. **Every module listed.** `hierarchy` contains every child of the root, the
   `External Types` module included, each with an id accepted by every tool that takes
   a node id — on one page, or, for a very large top level, across the pages
   `next_cursor` leads to.
3. **Within the budget.** Every page fits the
   [response budget](hierograph-mcp-tools.md#73-the-response-budget).
4. **Fixed vocabularies.** `kinds`, `relationships` and `model` are identical for every
   graph and every call.
5. **Consistent counts.** For one loaded graph, repeated calls return the same `stats`
   and the same `hierarchy` in the same order.
6. **Read-only.** The tool changes no server state.
