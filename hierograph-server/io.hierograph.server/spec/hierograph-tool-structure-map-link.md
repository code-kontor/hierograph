# Hierograph Tool `structure_map_link` — Specification

`structure_map_link` returns a link to the web UI's **levelized structure map** with a
given set of dependencies drawn on it. The agent computes the dependencies that matter
for the question at hand (the ones a planned extraction has to cut, the ones against an
intended layering, the uses of a type about to move) and hands the user one link that
shows them where they sit in the structure.

Concepts shared by all tools (node ids, errors, views) are defined in the
[MCP tools specification](hierograph-mcp-tools.md). The map itself, and how it reads the
link, is the web UI's structure screen (`hierograph-web/src/structure/`).

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

A list of dependencies in the conversation says *which* dependencies matter; the map
says *where* they are: between which modules and packages, on which level, inside or
across a tangle. The tool only builds the link: the URL alone carries the set, and
nothing is stored in the server, so the link works as long as its node ids name nodes.

## 2. Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `dependencies` | array of objects | yes | The dependencies, each `{"sourceId": <node id>, "targetId": <node id>}`: from anything in the source's subtree to anything in the target's. An end can be any node: module, package, type or member. Duplicates are dropped. At most 200. |
| `expandedIds` | array of integers | no | The containers to show expanded. Without it the map chooses: it opens just far enough that the two ends of every dependency lie in different boxes. Pass it only for a different picture. The map folds chains of lone packages ([MCP tools specification, §3.1](hierograph-mcp-tools.md#31-nodes-and-the-containment-tree)), so a skipped package's id is put in the link as its chain's end, the box that shows it. |
| `view` | string | no | The view to show the map in (shared parameter). |

## 3. Behaviour

1. **The link.** `<ui_url>structure?view=<view>&dependency_ids=<pairs>&expanded_ids=<ids>`,
   on the server's web UI URL (`server_info`'s `ui_url`, which follows an advertised
   `hierograph.server.url`). `view` only when the call names one, `expanded_ids` only
   when given.
2. **The pairs** are `<source id>-<target id>`, comma-separated; the targets of one
   source are grouped, `<source>-<target>.<target>`, in the order the sources first
   appear.
3. **Checks.** Every id is looked up in the graph (in the view, when one is named), and
   every pair's aggregated dependency, as `aggregated_dependencies` computes it. Ids
   that name no node and pairs without a dependency are reported in the summary, and
   stay in the link; the map doesn't draw them.
4. **What the map does with it.** It draws each dependency between the visible boxes
   its ends lie in (a member at its type), several between the same two boxes as one
   arrow with their count; a dependency against the levels is dashed. It doesn't draw
   a dependency with an id that names no node, one end inside the other, no
   dependency in the graph, or both ends in one collapsed box. It opens on the
   dependencies, and a switch draws only them and the boxes they touch.

## 4. Response

```json
{
  "url": "http://localhost:8765/ui/structure?view=layers&dependency_ids=4711-4802.4815,5020-4802",
  "view": "layers",
  "summary": {
    "dependencies": 3,
    "unknown_ids": [],
    "without_dependency": [{ "sourceId": 5020, "targetId": 4802 }]
  }
}
```

| Field | Description |
|-------|-------------|
| `url` | The link to hand to the user. |
| `view` | The view the map shows, or `null` for the hierarchy as scanned. |
| `summary.dependencies` | The number of dependencies in the link, without duplicates. |
| `summary.unknown_ids` | Ids that name no node; the map doesn't draw their pairs. |
| `summary.without_dependency` | Pairs whose ends resolve but with no dependency from the source's subtree into the target's. |

## 5. Errors

| Code | When | Recovery |
|------|------|----------|
| `INVALID_PARAMETER` | `dependencies` is empty, or an entry isn't `{"sourceId", "targetId"}` with numeric ids | Pass each dependency as an object with numeric node ids. |
| `TOO_MANY_DEPENDENCIES` | more than 200 dependencies | Pass the most relevant ones, or lift them to coarser ends (packages, modules). |
| `LINK_TOO_LONG` | the URL would exceed 7000 characters | Fewer dependencies or `expandedIds`. |
| `UI_UNAVAILABLE` | the server doesn't know its web UI's URL yet | Retry once the server is ready. |
| `VIEW_NOT_FOUND`, `VIEW_INVALID` | as for every tool that takes `view` | See the MCP tools specification. |

## 6. Working with the tool

- Compute the dependencies with the dependency tools first, e.g. `outgoing_dependencies`
  from the part to extract into the rest, or the feedback dependencies of
  `levelized_structure`, then pass their ends here.
- Coarser ends make a calmer map: a package-to-package dependency is one arrow, where its
  type-level dependencies would be many.
- Pass `view` when the question is asked in a view, e.g. a restructuring view that shows
  a planned extraction.

## 7. Guarantees

- Nothing is stored: the same arguments give the same link.
- The link names nodes by id; it shows the same dependencies after a rescan as long as
  the ids name the same nodes.
- At most 200 dependencies, and at most 7000 characters, so a browser request carries the
  link to the server.
