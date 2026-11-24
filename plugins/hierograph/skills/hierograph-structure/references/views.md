# Views — analysing in a view, and proposing one

A **view** is a named regrouping of the hierarchy that the server applies: groups of
modules (or packages, or types) that sit above their members as nodes of kind
`view.group`. Multi-modules, layers, bounded contexts, team ownership and target
architectures are all views. Every structural tool takes an optional `view` and then
answers in that view; dependencies are the same in every view, only where nodes sit
changes. So a group-level DSM is one `pairwise_dependencies` call over group ids, and
the boxes of a layered graph are a view's groups instead of hand-collected lists.

Contents: [Use an existing view](#use-an-existing-view) · [Propose a
view](#propose-a-view) · [The document](#the-document) · [Reading the
report](#reading-the-report) · [Things that bite](#things-that-bite)

## Use an existing view

1. `list_views()` — the views there are: `source` `project` (versioned with the code,
   in `.hierograph-views/`) or `state` (an internal draft defined at runtime with
   `define_view`, gone after the next server restart), `valid`, and counts of
   groups, errors and warnings. An invalid view lists its `problems`; fix it before
   using it. `default` beside them is the hierarchy as scanned — what a call without
   `view` answers in; it is not a name to pass.
2. `graph_overview(view = "<name>")` — the view's top level (`hierarchy`: its groups
   and the modules no group takes) and a `view` section with the report. A report
   with errors means part of the document was not applied; say so before
   interpreting anything.
3. Pass the **same** `view` to **every** call of the analysis. A group id is unknown
   outside its view (`NODE_NOT_FOUND`), and a cursor issued in a view is rejected
   anywhere else (`STALE_CURSOR_DATA`).

**Node sets in a view** (SKILL.md step 1):

| Node set | How |
|----------|-----|
| the view's top level | the ids in `graph_overview(view).hierarchy` — groups plus ungrouped modules; drop "External Types" as usual |
| the members of one group | `list_children(nodeId = <group id>, view = "<name>")` |
| all groups with their ids and paths | `get_view(name).groups` (`path`, `id`, `parent_id`, `child_count`) |

A **group-level DSM** is then the ordinary step 2:
`pairwise_dependencies(nodeIds = [<group ids>], view = "<name>")`. Its cells are the
sums over the members, so `has_cycles` answers "are the groups layered?". That is
its own question: grouping can create a cycle between groups whose members are
acyclic, and can hide a member cycle inside one group. When the group level and the
module level disagree, report both.

**Layered graph with the view's groups as boxes** (`references/layered.md`): take
the members as the node set and the groups as the boxes.

1. From `get_view(name).groups`, pick the groups to draw — usually the innermost
   ones, whose children are modules; one box per node.
2. For each, `list_children(nodeId = <group id>, view = "<name>")` gives the member
   ids.
3. `pairwise_dependencies(nodeIds = [<all member ids>])` for the edges. Real nodes
   have the same ids and dependencies in every view, so the call needs no `view`.
4. In the input file, map each group's path to its member ids under `view_groups`
   (`{"web": [<id>, <id>]}`). The script draws one spanning box per group.

## Propose a view

When the user wants to see the system "by layer", "by multi-module", "by context",
or to check a target architecture, draft a view, define it, and iterate on the
report. Sources for a first draft:

| Grouping | Where the membership comes from |
|----------|---------------------------------|
| multi-modules | the build: each Maven aggregator's `<modules>`, a Gradle parent's subprojects. Not name similarity |
| layers | the dependency order: `pairwise_dependencies` over the modules, `node_order` reversed; propose bands, let the user name and adjust them |
| bounded contexts | package or module name segments (`*.billing.*`, `*.shipping.*`), confirmed with the user |
| ownership, target architecture | the user; ask |

Then:

1. **Write the document** (below) and read the groups back to the user — like the
   node set, the grouping silently changes every later number.
2. **Save it as a project view**: `<build root>/.hierograph-views/<name>.json`, with
   your file tools, not with `define_view`. This is the standard: a view the user asked
   for is meant to be used again, by them and in later sessions, and lives with the
   code. The server reads the file with the next request; in Docker mode too (the
   container reads the mounted project, you write it on the host). Tell the user the
   file is there; committing it is theirs to decide.
3. **`get_view(name)`** returns the applied groups with their ids and the report.
   Read the report and fix what it names in the file; read it again. Repeat until it
   has no errors and the warnings are understood.
4. **Analyse** with the group ids: group-level DSM, layered graph.

**Internal drafts.** To try a variant quickly without touching the project, for
example several candidate layerings to compare, `define_view(document = {…})` stores
a runtime view and returns the groups and the report in one call. Runtime views are
internal and short-lived: the server deletes them when it restarts. Delete every
draft with `delete_view(name)` when the comparison is done, and save the one the user
picks as a project view (step 2). Never leave a runtime view as the result of a task.

## The document

```json
{
  "name": "layers",
  "description": "Target layering",
  "groups": [
    { "name": "api",    "members": ["com.acme:acme-*-api:*"] },
    { "name": "domain", "members": ["com.acme:acme-{billing,shipping}:*"] },
    { "name": "infra",  "groups": [
        { "name": "persistence", "members": [{ "match": "com.acme:acme-*-jpa:*", "kind": "java.module" }] },
        { "name": "messaging",   "members": ["com.acme:acme-kafka:*"] } ] }
  ]
}
```

- `name`: lower-case letters, digits, `.`, `_`, `-`.
- A **selector** is a pattern over qualified names: a module's is its GAV
  (`group:artifact:version`, a test artifact with a trailing `:test`), a package's or
  type's its fully qualified name. `*` matches anything except `:` — so
  `com.acme:acme-core:*` matches the main artifact but **not** the test artifact —
  `**` matches anything, `{a,b}` alternatives. `*` does cross `.`: `com.acme.*`
  matches sub-packages too.
- An object selector narrows with `kind` (`java.module`, `java.package`, `types`, …)
  and `within` (a pattern of an enclosing node), for names that exist in a main and a
  test artifact alike.
- A node is in at most one group; the direct members of a group must share one
  parent (modules: the root). Overlapping groupings are separate views.
- **`exclude`** leaves nodes out of the view: `"exclude": ["**:test"]` drops every
  test artifact, `{"match": "com.acme.generated", "kind": "packages"}` generated code.
  An excluded node goes with everything inside it and every dependency to and from it,
  so weights, levels and cycles in the view are computed without it — a cycle that only
  exists through test code is gone. Exclusions apply before groups (and moves), so a
  group never selects an excluded node. Use it to answer "how is the production code
  layered?" without restarting the server with other settings.
- Restructuring views (`"restructure": true`, `modules`, `moves`) move code; they
  belong to **hierograph-extract**.

## Reading the report

| Code | Meaning | Usual fix |
|------|---------|-----------|
| `SELECTOR_MATCHES_NOTHING` | a selector matches no node | check the GAV form and the version wildcard |
| `NODE_IN_TWO_GROUPS` | two groups select a node; the first keeps it | narrow one selector |
| `MEMBERS_WITH_DIFFERENT_PARENTS` | a group mixes levels (a module and a package) | split the group, or select at one level |
| `MEMBER_INSIDE_OTHER_GROUPS_MEMBER` | a group selects something inside another group's member | drop the inner selection |
| `EMPTY_GROUP` | nothing left to group | fix or remove the group |
| `NODES_EXCLUDED` (info) | what an exclusion left out: the nodes it selected, and how many nodes and types went | check the count; a pattern that takes too much shows here |
| `EXCLUDE_SELECTS_NOTHING` | an exclusion matches no node | check the pattern, as for a selector |

Warnings leave the rest of the view applied; errors drop exactly what they name. A
report with zero errors is the precondition for claiming anything about the
grouping.

## Things that bite

- **Forgetting `view` on one call.** The answer silently comes from the hierarchy as
  scanned. Pass it on every call, and name the view in the writeup.
- **A GAV pattern needs its version part.** `com.acme:acme-core` matches nothing;
  write `com.acme:acme-core:*` (and `com.acme:acme-core:**` to take the test
  artifact as well).
- **An excluded node is unknown in the view** (`NODE_NOT_FOUND`) and `find_node`
  doesn't find it there; its id still works without `view`. `get_view(name).excluded`
  lists what the view leaves out.
- **Stacking order between groups without edges is an artifact**, in the DSM and the
  layered graph alike; say so rather than reading a dependency into it.
