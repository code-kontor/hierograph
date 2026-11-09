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

**One call, no inputs.** The tool takes no parameters and always describes the whole
graph. There is nothing to get wrong, so it is a safe first call.

**Modules as entry points.** The `hierarchy` section lists every module as an enriched
node reference (see [§6.2 of the MCP tools specification](hierograph-mcp-tools.md#62-enriched-noderef))
with its id. Those ids feed `list_children`, `list_descendants` and
`pairwise_dependencies` directly, without a `find_node` lookup.

**Self-describing results.** The `kinds` and `relationships` sections define the values
that appear in other tools' responses, so a model can interpret `java.record` or
`reads_field` without prior knowledge.

**`graph_overview` and `list_children`.** Called with the root's id, `list_children`
also returns the modules. The two differ in what they add:

| | `graph_overview` | `list_children` on the root |
|---|---|---|
| Modules as enriched node references | yes | yes |
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

The tool has no parameters. It always describes the complete loaded graph.

**Why none.** An orientation call that needs arguments needs knowledge the caller does
not have yet. Everything that could be narrowed (a module, a kind) is the job of the
navigation tools that follow.

---

## 3. Behaviour

The tool walks the containment tree once for counts and once more for edges, below
each module. The synthetic root and the modules themselves are not visited, so:

- `total_nodes` and `nodes_by_kind` count packages, types, members and external types,
  but not modules. The module count is the length of `hierarchy`.
- `nodes_by_kind` includes `external.type` and the packages under `External Types`.
- `total_type_level_edges` counts every type-level dependency once, at its source.
  `type_level_edges_by_attribute` counts each edge once per attribute flag it carries,
  so the attribute counts can add up to more than the total.

The `hierarchy` section lists the root's children in the model's order, the
`External Types` module included. For each module:

- `outgoing_dep_count` is the number of type-level dependencies whose source lies
  anywhere in the module;
- `incoming_dep_count` is the number whose target lies anywhere in the module.

Both counts include dependencies that stay inside the module. They measure how much
dependency traffic a module takes part in, not how much crosses its boundary; the
crossing weight between two modules comes from `aggregated_dependencies` or
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

All seven have their source in `billing-core`, so its `outgoing_dep_count` is `7`. Five
have their target in `billing-core`, so its `incoming_dep_count` is `5`. `External Types`
has `0` outgoing and `2` incoming.

The `kinds`, `relationships` and `model` sections are fixed text: they are the same
for every graph.

---

## 4. Response

```json
{
  "stats": {
    "total_nodes": 26,
    "nodes_by_kind": {
      "java.package": 7, "external.type": 2, "java.class": 2, "java.method": 9,
      "java.field": 4, "java.interface": 1, "java.record": 1
    },
    "total_type_level_edges": 7,
    "type_level_edges_by_attribute": {
      "is_extends": 0, "is_implements": 0, "is_annotated_by": 0, "is_depends_on_other": 7
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
    "detail_level": [
      { "kind": "calls", "source": "method", "description": "Method invokes a method" },
      …
    ]
  },
  "hierarchy": [
    { "id": 4800, "name": "External Types", "qualified_name": "External Types",
      "kind": "java.module", "parent_id": 4600, "parent_kind": null,
      "child_count": 1, "descendant_type_count": 0, "descendant_method_count": 0,
      "outgoing_dep_count": 0, "incoming_dep_count": 2 },
    { "id": 4690, "name": "billing-core", "qualified_name": "com.example:billing-core:1.4.0",
      "kind": "java.module", "parent_id": 4600, "parent_kind": null,
      "child_count": 1, "descendant_type_count": 4, "descendant_method_count": 9,
      "outgoing_dep_count": 7, "incoming_dep_count": 5 }
  ],
  "model": {
    "aggregation": "Aggregation is pairwise. …",
    "levels": [
      { "name": "aggregated", "description": "…", "tools": ["aggregated_dependencies", "pairwise_dependencies"] },
      { "name": "type", "description": "…", "tools": ["outgoing_dependencies", "incoming_dependencies"], "parameter": "…" },
      { "name": "detail", "description": "…", "tools": ["outgoing_dependencies", "incoming_dependencies"], "parameter": "…" }
    ]
  },
  "scan_metadata": { "scanner": "hierograph" },
  "build_state": { "status": "current", "changed_files": [], "build_tool": "maven" }
}
```

The 26 nodes are the seven packages `com`, `example`, `billing`, `money`, `java`,
`math` and `util`; the types `Invoice`, `Invoice$Line`, `Ledger` and `Money`; nine
methods and four fields; and the external types `BigDecimal` and `List`. The synthetic
root has id `4600`.

| Field | Content | Why |
|-------|---------|-----|
| `stats.total_nodes` | nodes below the modules | the size of the codebase in one number |
| `stats.nodes_by_kind` | node count per kind, in order of first occurrence | shows the mix: a codebase of records reads differently from one of interfaces |
| `stats.total_type_level_edges` | number of type-level dependencies | the density of the graph |
| `stats.type_level_edges_by_attribute` | edge count per attribute flag, every flag present | how much coupling is inheritance or annotation rather than use |
| `kinds.structural` | the nine `java.*` node kinds with a description | defines the `kind` values of node references |
| `kinds.group_aliases` | each alias with its expansion | tells the caller what `types`, `members` and `packages` select in a kind filter |
| `relationships.type_level_attributes` | the four attribute flags with a description | defines the flags on type-level and aggregated edges |
| `relationships.detail_level` | the twelve detail-level relationships with their source kind | defines the `relationship` values of detail-level edges |
| `hierarchy` | every module as an enriched node reference plus `outgoing_dep_count` and `incoming_dep_count` | the ids and sizes needed to start navigating, and a first impression of coupling |
| `model` | how aggregation works and which tools serve each dependency level | lets the model choose the right dependency tool |
| `scan_metadata.scanner` | always `"hierograph"` | names the producer of the graph |
| `build_state` | present only when the server reads a build manifest | tells the caller whether the graph still matches the build files |

**`kinds.structural`** lists the nine kinds of [§3.2 of the MCP tools specification](hierograph-mcp-tools.md#32-kinds)
that start with `java.`. `external.type` appears in `nodes_by_kind` and in node
references but not in this list.

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
| No graph is loaded | `{"status": "no_graph_available", …}` (see [§8.3](hierograph-mcp-tools.md#83-no-graph-loaded)) | build the project, then call `reload_graph` |

The tool has no inputs to validate, so it has no other errors.

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
   a node id.
3. **Fixed vocabularies.** `kinds`, `relationships` and `model` are identical for every
   graph and every call.
4. **Consistent counts.** For one loaded graph, repeated calls return the same `stats`
   and the same `hierarchy` in the same order.
5. **Read-only.** The tool changes no server state.
